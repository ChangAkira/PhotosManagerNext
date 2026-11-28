package com.example.photosmanager.ui.floating

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.photosmanager.R
import com.example.photosmanager.data.model.AlbumInfo
import com.example.photosmanager.data.model.MoveTarget
import com.example.photosmanager.data.model.PendingMove
import com.example.photosmanager.data.model.PhotoAction
import com.example.photosmanager.data.model.PhotoSelection
import com.example.photosmanager.data.repository.PhotoRepository
import com.example.photosmanager.data.repository.PreferencesRepository
import com.example.photosmanager.data.repository.ReviewRepository
import com.example.photosmanager.data.session.SessionStore
import com.example.photosmanager.ui.settings.SettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun FloatingWindowContent(
    onDismiss: () -> Unit,
    onFocusableChange: (Boolean) -> Unit = {},
    onComplete: (List<String>, List<PendingMove>) -> Unit
) {
    val context = LocalContext.current
    val photoRepository = remember { PhotoRepository(context) }
    val preferencesRepository = remember { PreferencesRepository(context) }
    val reviewRepository = remember { ReviewRepository(context) }
    val scope = rememberCoroutineScope()

    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenWidthPx = remember(screenWidthDp) {
        with(density) { screenWidthDp.dp.toPx() }
    }
    val swipeThreshold = remember(screenWidthPx) { screenWidthPx * 0.1f }

    var photos by remember { mutableStateOf<List<PhotoSelection>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }
    var enabledAlbums by remember { mutableStateOf<Set<String>>(emptySet()) }
    var photosPerSession by remember { mutableStateOf(5) }
    var nearbyCount by remember { mutableStateOf(10) }
    var isLoading by remember { mutableStateOf(true) }
    var allReviewed by remember { mutableStateOf(false) }
    var reviewedTotal by remember { mutableStateOf(0) }
    var reloadTick by remember { mutableStateOf(0) }

    var showMovePicker by remember { mutableStateOf(false) }
    var availableAlbums by remember { mutableStateOf<List<AlbumInfo>>(emptyList()) }
    var recentTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    var flaggedAlbums by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isAlbumsLoading by remember { mutableStateOf(false) }

    fun finishOrAdvance(position: Int) {
        val snapshotQueue = SessionStore.queue
        photos = snapshotQueue
        if (position < snapshotQueue.size - 1) {
            val next = position + 1
            currentIndex = next
            SessionStore.setIndex(context, next)
        } else {
            val toDelete = snapshotQueue
                .filter { it.action == PhotoAction.MARK_DELETE }
                .map { it.photoInfo.uri }
            val toMove = snapshotQueue.mapNotNull { sel ->
                val target = sel.moveTarget
                if (sel.action == PhotoAction.MARK_MOVE && target != null) {
                    PendingMove(
                        uri = sel.photoInfo.uri,
                        displayName = sel.photoInfo.displayName,
                        sourceAlbum = sel.photoInfo.albumName,
                        target = target
                    )
                } else {
                    null
                }
            }
            SessionStore.finish(context)
            if (toDelete.isEmpty() && toMove.isEmpty()) {
                onDismiss()
            } else {
                onComplete(toDelete, toMove)
            }
        }
    }

    LaunchedEffect(reloadTick) {
        isLoading = true
        allReviewed = false
        try {
            val albums = preferencesRepository.enabledAlbums.first()
            val sessionCount = preferencesRepository.photosPerSession.first()
            val nearby = preferencesRepository.nearbyPhotosCount.first()
            enabledAlbums = albums
            photosPerSession = sessionCount
            nearbyCount = nearby

            SessionStore.restoreIfNeeded(context)
            if (SessionStore.isActive) {
                photos = SessionStore.queue
                currentIndex = SessionStore.currentIndex
                isLoading = false
                return@LaunchedEffect
            }

            if (albums.isNotEmpty()) {
                val allPhotos = photoRepository.getAllPhotosFromAlbums(albums)
                val uniquePhotos = allPhotos.distinctBy { it.uri }
                val reviewed = reviewRepository.getReviewedUris()
                val eligible = uniquePhotos.filterNot { it.uri in reviewed }
                if (eligible.isEmpty() && uniquePhotos.isNotEmpty()) {
                    photos = emptyList()
                    allReviewed = true
                    reviewedTotal = uniquePhotos.size
                    isLoading = false
                    return@LaunchedEffect
                }
                val photosToUse = if (eligible.size < photosPerSession) {
                    eligible
                } else {
                    eligible.shuffled().take(photosPerSession)
                }
                val selections = photosToUse.map { PhotoSelection(it) }
                SessionStore.startRound(context, selections)
                photos = SessionStore.queue
                currentIndex = 0
                isLoading = false
            } else {
                isLoading = false
            }
        } catch (e: Exception) {
            android.util.Log.e("FloatingWindowContent", "初始化失败", e)
            isLoading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (allReviewed) {
            SessionCompleteContent(
                totalPhotos = reviewedTotal,
                onDismiss = onDismiss,
                onOpenSettings = {
                    context.startActivity(
                        android.content.Intent(context, SettingsActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    onDismiss()
                },
                onRestartCycle = {
                    scope.launch {
                        reviewRepository.startNewCycle()
                        reloadTick++
                    }
                }
            )
        } else if (photos.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close))
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.float_no_photo),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        } else if (currentIndex < photos.size) {
            val currentPhoto = photos[currentIndex]
            PhotoViewerContent(
                photoSelection = currentPhoto,
                albumName = currentPhoto.photoInfo.albumName,
                swipeThreshold = swipeThreshold,
                nearbyEnabled = !currentPhoto.fromNearby,
                onAction = { action ->
                    val position = currentIndex
                    when (action) {
                        PhotoAction.KEEP, PhotoAction.MARK_DELETE -> {
                            SessionStore.setAction(context, position, action)
                            reviewRepository.markReviewedAsync(photos[position].photoInfo.uri)
                            finishOrAdvance(position)
                        }
                        PhotoAction.MARK_MOVE -> {
                            showMovePicker = true
                            scope.launch {
                                isAlbumsLoading = true
                                try {
                                    val all = photoRepository.getAllAlbums()
                                    val recents = preferencesRepository.recentMoveTargets.first()
                                    val flagged = preferencesRepository.flaggedAlbums.first()
                                    recentTargets = recents
                                    flaggedAlbums = flagged
                                    availableAlbums = PhotoRepository.sortAlbumsForMove(
                                        albums = all,
                                        currentBucketId = currentPhoto.photoInfo.bucketId,
                                        currentAlbumName = currentPhoto.photoInfo.albumName,
                                        recentRelativePaths = recents
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.e("FloatingWindowContent", "读取目标相册列表失败", e)
                                    availableAlbums = emptyList()
                                } finally {
                                    isAlbumsLoading = false
                                }
                            }
                        }
                        PhotoAction.VIEW_NEARBY -> {
                            scope.launch {
                                val nearby = photoRepository.getNearbyPhotos(
                                    currentPhoto.photoInfo,
                                    nearbyCount
                                )
                                val existingUris = photos.map { it.photoInfo.uri }.toSet()
                                val uniqueNearby = nearby
                                    .filter { it.uri !in existingUris }
                                    .map { PhotoSelection(it, fromNearby = true) }
                                if (uniqueNearby.isNotEmpty()) {
                                    SessionStore.insertAfterCurrent(context, uniqueNearby)
                                    photos = SessionStore.queue
                                }
                            }
                        }
                    }
                },
                onDismiss = onDismiss,
                onSwipeLeft = {
                    if (currentIndex < photos.size - 1) {
                        val next = currentIndex + 1
                        currentIndex = next
                        SessionStore.setIndex(context, next)
                    }
                },
                onSwipeRight = {
                    if (currentIndex > 0) {
                        val previous = currentIndex - 1
                        currentIndex = previous
                        SessionStore.setIndex(context, previous)
                    }
                },
                currentIndex = currentIndex,
                totalCount = photos.size
            )

            if (showMovePicker) {
                MoveAlbumPickerOverlay(
                    albums = availableAlbums,
                    recentRelativePaths = recentTargets,
                    flaggedAlbums = flaggedAlbums,
                    isLoading = isAlbumsLoading,
                    onFocusableChange = onFocusableChange,
                    onSelectTarget = { target ->
                        val position = currentIndex
                        showMovePicker = false
                        SessionStore.setMove(context, position, target)
                        reviewRepository.markReviewedAsync(currentPhoto.photoInfo.uri)
                        scope.launch {
                            preferencesRepository.recordRecentMoveTarget(target.relativePath)
                        }
                        finishOrAdvance(position)
                    },
                    onClose = {
                        showMovePicker = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MoveAlbumPickerOverlay(
    albums: List<AlbumInfo>,
    recentRelativePaths: List<String>,
    flaggedAlbums: Set<String>,
    isLoading: Boolean,
    onFocusableChange: (Boolean) -> Unit,
    onSelectTarget: (MoveTarget) -> Unit,
    onClose: () -> Unit
) {
    var creatingNew by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }

    val invalidNameError = stringResource(R.string.move_new_album_invalid)
    val recentSet = remember(recentRelativePaths) {
        recentRelativePaths.map { PhotoRepository.normalizeRelativePath(it).lowercase() }.toSet()
    }
    val duplicateNames = remember(albums) {
        albums.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    }

    DisposableEffect(creatingNew) {
        onFocusableChange(creatingNew)
        onDispose {
            onFocusableChange(false)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.move_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            creatingNew = !creatingNew
                            nameError = null
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.move_new_album))
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close)
                        )
                    }
                }
            }

            if (creatingNew) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp)
                ) {
                    OutlinedTextField(
                        value = newAlbumName,
                        onValueChange = {
                            newAlbumName = it
                            nameError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.move_new_album_hint)) },
                        isError = nameError != null,
                        supportingText = {
                            val text = nameError ?: stringResource(R.string.move_new_album_path_note)
                            Text(text)
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                creatingNew = false
                                newAlbumName = ""
                                nameError = null
                            }
                        ) {
                            Text(stringResource(R.string.confirm_cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val sanitized = PhotoRepository.sanitizeAlbumName(newAlbumName)
                                if (sanitized == null) {
                                    nameError = invalidNameError
                                } else {
                                    val relPath = PhotoRepository.buildNewAlbumRelativePath(sanitized)
                                    val existing = albums.firstOrNull {
                                        PhotoRepository.normalizeRelativePath(it.relativePath)
                                            .equals(relPath, ignoreCase = true)
                                    }
                                    onSelectTarget(
                                        existing?.toMoveTarget() ?: MoveTarget(
                                            albumName = sanitized,
                                            relativePath = relPath
                                        )
                                    )
                                }
                            }
                        ) {
                            Text(stringResource(R.string.move_new_album_confirm))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    albums.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.move_picker_empty),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = albums,
                                key = { "${it.bucketId}_${it.relativePath}" }
                            ) { album ->
                                val isRecent = PhotoRepository.normalizeRelativePath(album.relativePath)
                                    .lowercase() in recentSet
                                val showPath = album.name in duplicateNames
                                val isFlagged = album.name in flaggedAlbums
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectTarget(album.toMoveTarget()) }
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = album.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (isFlagged) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_flag),
                                                    contentDescription = stringResource(R.string.cd_album_flagged),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            if (isRecent) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = stringResource(R.string.move_recent_tag),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        if (showPath) {
                                            Text(
                                                text = album.relativePath.trimEnd('/'),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(R.string.move_album_count, album.photoCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SessionCompleteContent(
    totalPhotos: Int,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onRestartCycle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close))
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = stringResource(R.string.float_done_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.float_done_body, totalPhotos),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.float_done_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onOpenSettings) {
                    Text(stringResource(R.string.float_go_settings))
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onRestartCycle) {
                    Text(stringResource(R.string.float_restart_round))
                }
            }
        }
    }
}

@Composable
fun PhotoViewerContent(
    photoSelection: PhotoSelection,
    albumName: String,
    swipeThreshold: Float,
    nearbyEnabled: Boolean,
    onAction: (PhotoAction) -> Unit,
    onDismiss: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    currentIndex: Int,
    totalCount: Int
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.float_album_from, albumName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val statusHint = when (photoSelection.action) {
                    PhotoAction.MARK_MOVE -> photoSelection.moveTarget?.let {
                        stringResource(R.string.float_status_move_to, it.albumName)
                    }
                    PhotoAction.MARK_DELETE -> stringResource(R.string.float_status_marked_delete)
                    else -> null
                }
                if (statusHint != null) {
                    Text(
                        text = statusHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (photoSelection.action == PhotoAction.MARK_DELETE) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(swipeThreshold) {
                    var totalDragX = 0f
                    var hasTriggeredSwipe = false
                    val threshold = swipeThreshold

                    detectDragGestures(
                        onDragEnd = {
                            if (kotlin.math.abs(totalDragX) > threshold && !hasTriggeredSwipe) {
                                if (totalDragX > 0) {
                                    onSwipeRight()
                                } else {
                                    onSwipeLeft()
                                }
                            }
                            totalDragX = 0f
                            hasTriggeredSwipe = false
                        }
                    ) { _: PointerInputChange, dragAmount: Offset ->
                        totalDragX += dragAmount.x
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                contentAlignment = Alignment.Center
            ) {
                val imageUri = try {
                    Uri.parse(photoSelection.photoInfo.uri)
                } catch (e: Exception) {
                    null
                }

                if (imageUri != null) {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = stringResource(R.string.float_load_failed),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (scrollState.maxValue > 0) {
                val scrollProgress = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(4.dp)
                        .padding(vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .background(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(scrollProgress.coerceIn(0f, 1f))
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(2.dp)
                            )
                            .align(Alignment.TopCenter)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val compactPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { onAction(PhotoAction.KEEP) },
                modifier = Modifier.weight(1f),
                contentPadding = compactPadding
            ) {
                Text(stringResource(R.string.float_keep), maxLines = 1)
            }
            Button(
                onClick = { onAction(PhotoAction.MARK_MOVE) },
                modifier = Modifier.weight(1f),
                contentPadding = compactPadding
            ) {
                Text(stringResource(R.string.float_move), maxLines = 1)
            }
            Button(
                onClick = { onAction(PhotoAction.VIEW_NEARBY) },
                enabled = nearbyEnabled,
                modifier = Modifier.weight(1f),
                contentPadding = compactPadding
            ) {
                Text(stringResource(R.string.float_nearby), maxLines = 1)
            }
            Button(
                onClick = { onAction(PhotoAction.MARK_DELETE) },
                modifier = Modifier.weight(1f),
                contentPadding = compactPadding
            ) {
                Text(stringResource(R.string.float_delete), maxLines = 1)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.float_progress, currentIndex + 1, totalCount),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
