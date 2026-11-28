package com.example.photosmanager.ui.confirm

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.example.photosmanager.R
import com.example.photosmanager.data.model.PendingMove
import com.example.photosmanager.data.repository.PhotoRepository
import com.example.photosmanager.data.repository.PreferencesRepository
import com.example.photosmanager.data.repository.ReviewRepository
import com.example.photosmanager.ui.components.ThumbnailSize
import com.example.photosmanager.ui.theme.PHOTOSMANAGERTheme
import com.example.photosmanager.util.NotificationHelper
import com.example.photosmanager.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConfirmDeleteActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PHOTOS_TO_DELETE = "photos_to_delete"
        const val EXTRA_PHOTOS_TO_MOVE_JSON = "photos_to_move_json"

        private const val TAG = "ConfirmDeleteActivity"
        private const val REQUEST_DELETE_BATCH = 1001
        private const val REQUEST_MOVE_WRITE_BATCH = 1002
        private const val REQUEST_MOVE_SINGLE_Q = 1003
    }

    private val requestedDeletes: ArrayList<String> by lazy {
        intent.getStringArrayListExtra(EXTRA_PHOTOS_TO_DELETE) ?: arrayListOf()
    }

    private val requestedMoves: List<PendingMove> by lazy {
        PendingMove.fromJson(intent.getStringExtra(EXTRA_PHOTOS_TO_MOVE_JSON))
    }

    private val photoRepository by lazy { PhotoRepository(this) }
    private val reviewRepository by lazy { ReviewRepository(this) }
    private val preferencesRepository by lazy { PreferencesRepository(this) }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var selectedForDelete: List<String> = emptyList()
    private var selectedForMove: List<PendingMove> = emptyList()
    private var nextMoveIndex: Int = 0
    private var movedSuccessCount: Int = 0
    private val moveFailedUris = mutableListOf<String>()
    private var deletedGoneCount: Int = 0
    private var deleteFailedCount: Int = 0
    private var isProcessingState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (requestedDeletes.isEmpty() && requestedMoves.isEmpty()) {
            finishAndRemoveTask()
            return
        }
        setContent {
            PHOTOSMANAGERTheme {
                ConfirmActionsScreen(
                    photosToDelete = requestedDeletes,
                    photosToMove = requestedMoves,
                    isProcessing = isProcessingState.value,
                    onConfirm = { selectedDeletes, selectedMoves ->
                        if (!isProcessingState.value) {
                            startExecution(selectedDeletes, selectedMoves)
                        }
                    },
                    onCancel = {
                        val allUris = requestedDeletes + requestedMoves.map { it.uri }
                        reviewRepository.releaseAsync(allUris)
                        finishAndRemoveTask()
                    }
                )
            }
        }
    }

    private fun startExecution(deletes: List<String>, moves: List<PendingMove>) {
        isProcessingState.value = true
        selectedForDelete = deletes
        selectedForMove = moves
        nextMoveIndex = 0
        movedSuccessCount = 0
        moveFailedUris.clear()
        deletedGoneCount = 0
        deleteFailedCount = 0

        val uncheckedDeletes = requestedDeletes - deletes.toSet()
        val selectedMoveUris = moves.map { it.uri }.toSet()
        val uncheckedMoves = requestedMoves.map { it.uri }.filterNot { it in selectedMoveUris }
        reviewRepository.releaseAsync(uncheckedDeletes + uncheckedMoves)

        if (moves.isEmpty() && deletes.isEmpty()) {
            finishAndRemoveTask()
            return
        }

        if (moves.isNotEmpty()) {
            beginMovePhase()
        } else {
            beginDeletePhase()
        }
    }

    private fun beginMovePhase() {
        val needsBatchWriteRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !PermissionHelper.canDeleteWithoutConfirm(this)
        if (needsBatchWriteRequest) {
            try {
                val uris = selectedForMove.map { Uri.parse(it.uri) }
                val writeRequest = MediaStore.createWriteRequest(contentResolver, uris)
                startIntentSenderForResult(
                    writeRequest.intentSender,
                    REQUEST_MOVE_WRITE_BATCH,
                    null,
                    0,
                    0,
                    0,
                    null
                )
                return
            } catch (e: Exception) {
                Log.e(TAG, "创建批量移动授权请求失败，回退直接尝试移动", e)
            }
        }
        processNextMove()
    }

    private fun processNextMove() {
        ioScope.launch {
            while (nextMoveIndex < selectedForMove.size) {
                val item = selectedForMove[nextMoveIndex]
                when (val result = photoRepository.movePhoto(item.uri, item.target)) {
                    is PhotoRepository.MoveResult.Success -> {
                        movedSuccessCount++
                        nextMoveIndex++
                    }
                    is PhotoRepository.MoveResult.NeedsPermission -> {
                        withContext(Dispatchers.Main) {
                            try {
                                startIntentSenderForResult(
                                    result.intentSender,
                                    REQUEST_MOVE_SINGLE_Q,
                                    null,
                                    0,
                                    0,
                                    0,
                                    null
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "发起 Android 10 移动授权失败", e)
                                moveFailedUris.add(item.uri)
                                nextMoveIndex++
                                processNextMove()
                            }
                        }
                        return@launch
                    }
                    is PhotoRepository.MoveResult.Failure -> {
                        Log.w(TAG, "移动失败: ${item.uri} -> ${item.target.relativePath}, reason=${result.reason}")
                        moveFailedUris.add(item.uri)
                        nextMoveIndex++
                    }
                }
            }

            if (moveFailedUris.isNotEmpty()) {
                reviewRepository.release(moveFailedUris)
            }

            withContext(Dispatchers.Main) {
                beginDeletePhase()
            }
        }
    }

    private fun beginDeletePhase() {
        if (selectedForDelete.isEmpty()) {
            finishWithSummary()
            return
        }
        val needsSystemConfirm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !PermissionHelper.canDeleteWithoutConfirm(this)
        if (needsSystemConfirm) {
            try {
                val photoUris = selectedForDelete.map { Uri.parse(it) }
                val deleteRequest = MediaStore.createDeleteRequest(contentResolver, photoUris)
                startIntentSenderForResult(
                    deleteRequest.intentSender,
                    REQUEST_DELETE_BATCH,
                    null,
                    0,
                    0,
                    0,
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "创建删除请求失败", e)
                performActualDelete(selectedForDelete)
            }
        } else {
            performActualDelete(selectedForDelete)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_MOVE_WRITE_BATCH -> {
                if (resultCode == RESULT_OK) {
                    processNextMove()
                } else {
                    Log.w(TAG, "用户拒绝移动授权")
                    val refused = selectedForMove.map { it.uri }
                    moveFailedUris.addAll(refused)
                    reviewRepository.releaseAsync(refused)
                    beginDeletePhase()
                }
            }
            REQUEST_MOVE_SINGLE_Q -> {
                if (resultCode == RESULT_OK) {
                    processNextMove()
                } else {
                    selectedForMove.getOrNull(nextMoveIndex)?.let {
                        moveFailedUris.add(it.uri)
                    }
                    nextMoveIndex++
                    processNextMove()
                }
            }
            REQUEST_DELETE_BATCH -> {
                if (resultCode == RESULT_OK) {
                    performActualDelete(selectedForDelete)
                } else {
                    Log.w(TAG, "用户拒绝删除权限")
                    deleteFailedCount = selectedForDelete.size
                    reviewRepository.releaseAsync(selectedForDelete)
                    finishWithSummary()
                }
            }
        }
    }

    private fun performActualDelete(photos: List<String>) {
        ioScope.launch {
            val failed = mutableListOf<String>()
            photos.forEach { uri ->
                if (!photoRepository.deletePhoto(uri)) {
                    failed.add(uri)
                }
            }
            val goneCount = photoRepository.countGonePhotos(photos)
            deletedGoneCount = goneCount
            deleteFailedCount = (photos.size - goneCount).coerceAtLeast(0)
            if (goneCount > 0) {
                val after = preferencesRepository.addDeletedPhotos(goneCount)
                PreferencesRepository.deletedMilestone(after - goneCount, after)?.let { milestone ->
                    NotificationHelper.showDeletedMilestone(this@ConfirmDeleteActivity, milestone)
                }
            }
            val stillThere = if (goneCount < photos.size) failed else emptyList()
            if (stillThere.isNotEmpty()) {
                reviewRepository.release(stillThere)
            }
            withContext(Dispatchers.Main) {
                finishWithSummary()
            }
        }
    }

    private fun finishWithSummary() {
        val message = when {
            movedSuccessCount > 0 && deletedGoneCount > 0 ->
                getString(R.string.confirm_toast_moved_and_deleted, movedSuccessCount, deletedGoneCount)
            movedSuccessCount > 0 && moveFailedUris.isNotEmpty() ->
                getString(R.string.confirm_toast_moved_partial, movedSuccessCount, moveFailedUris.size)
            movedSuccessCount > 0 ->
                getString(R.string.confirm_toast_moved_only, movedSuccessCount)
            moveFailedUris.isNotEmpty() && deletedGoneCount == 0 ->
                getString(R.string.confirm_toast_move_failed, moveFailedUris.size)
            else -> null
        }
        if (message != null) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
        finishAndRemoveTask()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmActionsScreen(
    photosToDelete: List<String>,
    photosToMove: List<PendingMove>,
    isProcessing: Boolean,
    onConfirm: (List<String>, List<PendingMove>) -> Unit,
    onCancel: () -> Unit
) {
    var selectedDeletes by remember { mutableStateOf(photosToDelete.toSet()) }
    var selectedMoveUris by remember { mutableStateOf(photosToMove.map { it.uri }.toSet()) }

    val hasDeletes = photosToDelete.isNotEmpty()
    val hasMoves = photosToMove.isNotEmpty()
    val totalSelected = selectedDeletes.size + selectedMoveUris.size

    val titleText = when {
        hasMoves && hasDeletes -> stringResource(R.string.confirm_title_mixed)
        hasMoves -> stringResource(R.string.confirm_title_move)
        else -> stringResource(R.string.confirm_title)
    }

    val confirmButtonText = when {
        selectedMoveUris.isNotEmpty() && selectedDeletes.isNotEmpty() ->
            stringResource(R.string.confirm_mixed_n, selectedMoveUris.size, selectedDeletes.size)
        selectedMoveUris.isNotEmpty() ->
            stringResource(R.string.confirm_move_n, selectedMoveUris.size)
        else ->
            stringResource(R.string.confirm_delete_n, selectedDeletes.size)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(titleText) })
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.confirm_cancel))
                    }
                    Button(
                        onClick = {
                            val chosenDeletes = photosToDelete.filter { it in selectedDeletes }
                            val chosenMoves = photosToMove.filter { it.uri in selectedMoveUris }
                            onConfirm(chosenDeletes, chosenMoves)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isProcessing && totalSelected > 0,
                        colors = if (selectedDeletes.isNotEmpty()) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        }
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(confirmButtonText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (hasMoves) {
                item(key = "section_moves") {
                    Text(
                        text = stringResource(R.string.confirm_section_move, selectedMoveUris.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                items(photosToMove, key = { "move_${it.uri}" }) { pendingMove ->
                    PhotoMoveItem(
                        pendingMove = pendingMove,
                        isSelected = pendingMove.uri in selectedMoveUris,
                        enabled = !isProcessing,
                        onToggle = {
                            selectedMoveUris = if (pendingMove.uri in selectedMoveUris) {
                                selectedMoveUris - pendingMove.uri
                            } else {
                                selectedMoveUris + pendingMove.uri
                            }
                        }
                    )
                }
            }

            if (hasDeletes) {
                if (hasMoves) {
                    item(key = "section_divider") {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                if (hasMoves) {
                    item(key = "section_deletes") {
                        Text(
                            text = stringResource(R.string.confirm_section_delete, selectedDeletes.size),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
                items(photosToDelete, key = { "delete_$it" }) { uri ->
                    PhotoDeleteItem(
                        uri = uri,
                        isSelected = selectedDeletes.contains(uri),
                        enabled = !isProcessing,
                        onToggle = {
                            selectedDeletes = if (selectedDeletes.contains(uri)) {
                                selectedDeletes - uri
                            } else {
                                selectedDeletes + uri
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PhotoMoveItem(
    pendingMove: PendingMove,
    isSelected: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .heightIn(min = ThumbnailSize + 16.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = rememberAsyncImagePainter(Uri.parse(pendingMove.uri)),
            contentDescription = stringResource(R.string.dialog_preview),
            modifier = Modifier
                .size(ThumbnailSize)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = pendingMove.displayName.ifBlank {
                    Uri.parse(pendingMove.uri).lastPathSegment ?: pendingMove.uri
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(
                    R.string.confirm_move_route,
                    pendingMove.sourceAlbum,
                    pendingMove.target.albumName
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Checkbox(
            checked = isSelected,
            enabled = enabled,
            onCheckedChange = { onToggle() }
        )
    }
}

@Composable
fun PhotoDeleteItem(
    uri: String,
    isSelected: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .heightIn(min = ThumbnailSize + 16.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = rememberAsyncImagePainter(Uri.parse(uri)),
            contentDescription = stringResource(R.string.dialog_preview),
            modifier = Modifier
                .size(ThumbnailSize)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        Text(
            text = Uri.parse(uri).lastPathSegment ?: uri,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Checkbox(
            checked = isSelected,
            enabled = enabled,
            onCheckedChange = { onToggle() }
        )
    }
}
