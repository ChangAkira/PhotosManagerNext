package com.example.photosmanager.data.repository

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.photosmanager.R
import com.example.photosmanager.data.database.AppDatabase
import com.example.photosmanager.data.model.AlbumInfo
import com.example.photosmanager.data.model.MoveTarget
import com.example.photosmanager.data.model.PhotoInfo
import com.example.photosmanager.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class PhotoRepository(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val unknownAlbum: String get() = context.getString(R.string.album_unknown)

    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.BUCKET_ID
    )

    sealed class MoveResult {
        data class Success(val newUri: String) : MoveResult()
        data class NeedsPermission(val intentSender: IntentSender) : MoveResult()
        data class Failure(val reason: String) : MoveResult()
    }

    companion object {
        private const val TAG = "PhotoRepository"
        private const val MAX_ALBUM_NAME_LENGTH = 60
        private val FORBIDDEN_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        fun sanitizeAlbumName(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_ALBUM_NAME_LENGTH) return null
            if (trimmed == "." || trimmed == "..") return null
            if (trimmed.any { it in FORBIDDEN_NAME_CHARS || it.code < 0x20 }) return null
            return trimmed
        }

        fun buildNewAlbumRelativePath(albumName: String): String {
            val clean = sanitizeAlbumName(albumName) ?: albumName.trim()
            return normalizeRelativePath("Pictures/$clean")
        }

        fun normalizeRelativePath(raw: String): String {
            val normalized = raw
                .replace('\\', '/')
                .split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("/")
            return if (normalized.isEmpty()) "" else "$normalized/"
        }

        fun deduplicateFileName(originalName: String, existingNames: Set<String>): String {
            val cleanOriginal = originalName.ifBlank { "IMG_${System.currentTimeMillis()}.jpg" }
            if (existingNames.none { it.equals(cleanOriginal, ignoreCase = true) }) {
                return cleanOriginal
            }
            val dotIndex = cleanOriginal.lastIndexOf('.')
            val (base, ext) = if (dotIndex > 0 && dotIndex < cleanOriginal.length - 1) {
                cleanOriginal.substring(0, dotIndex) to cleanOriginal.substring(dotIndex)
            } else {
                cleanOriginal to ""
            }
            var counter = 1
            while (true) {
                val candidate = "$base ($counter)$ext"
                if (existingNames.none { it.equals(candidate, ignoreCase = true) }) {
                    return candidate
                }
                counter++
            }
        }

        fun sortAlbumsForMove(
            albums: List<AlbumInfo>,
            currentBucketId: Long,
            currentAlbumName: String,
            recentRelativePaths: List<String>
        ): List<AlbumInfo> {
            val filtered = albums.filterNot { album ->
                if (currentBucketId > 0L && album.bucketId > 0L) {
                    album.bucketId == currentBucketId
                } else {
                    album.name == currentAlbumName
                }
            }
            val recentRank = recentRelativePaths
                .map { normalizeRelativePath(it).lowercase() }
                .withIndex()
                .associate { it.value to it.index }

            return filtered.sortedWith(
                compareBy<AlbumInfo>(
                    { album ->
                        recentRank[normalizeRelativePath(album.relativePath).lowercase()] ?: Int.MAX_VALUE
                    },
                    { -it.photoCount },
                    { it.name.lowercase() },
                    { it.relativePath.lowercase() }
                )
            )
        }
    }

    suspend fun getAllPhotosFromAlbums(albumNames: Set<String>): List<PhotoInfo> = withContext(Dispatchers.IO) {
        val selection = if (albumNames.isNotEmpty()) {
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IN (${albumNames.joinToString(",") { "?" }})"
        } else {
            null
        }
        queryPhotos(selection, albumNames.toTypedArray())
    }

    suspend fun getNearbyPhotos(
        currentPhoto: PhotoInfo,
        count: Int
    ): List<PhotoInfo> = withContext(Dispatchers.IO) {
        val (selection, selectionArgs) = if (currentPhoto.bucketId > 0L) {
            "${MediaStore.Images.Media.BUCKET_ID} = ?" to arrayOf(currentPhoto.bucketId.toString())
        } else {
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?" to arrayOf(currentPhoto.albumName)
        }
        val allPhotos = queryPhotos(selection, selectionArgs)
        val currentIndex = allPhotos.indexOfFirst { it.uri == currentPhoto.uri }
        if (currentIndex == -1) return@withContext emptyList()
        val startIndex = maxOf(0, currentIndex - count / 2)
        val endIndex = minOf(allPhotos.size, startIndex + count)
        allPhotos.subList(startIndex, endIndex)
    }

    private fun queryPhotos(selection: String?, selectionArgs: Array<String>?): List<PhotoInfo> {
        val photos = mutableListOf<PhotoInfo>()
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                ).toString()
                photos.add(
                    PhotoInfo(
                        uri = uri,
                        albumName = cursor.getString(bucketColumn) ?: unknownAlbum,
                        dateAdded = cursor.getLong(dateAddedColumn) * 1000,
                        dateModified = cursor.getLong(dateModifiedColumn) * 1000,
                        size = cursor.getLong(sizeColumn),
                        width = cursor.getInt(widthColumn),
                        height = cursor.getInt(heightColumn),
                        displayName = cursor.getString(nameColumn) ?: "",
                        bucketId = cursor.getLong(bucketIdColumn)
                    )
                )
            }
        }
        return photos
    }

    suspend fun getAllAlbumNames(): List<String> = withContext(Dispatchers.IO) {
        val albums = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                cursor.getString(bucketColumn)?.let { albums.add(it) }
            }
        }
        albums.toList().sorted()
    }

    suspend fun getAllAlbums(): List<AlbumInfo> = withContext(Dispatchers.IO) {
        data class MutableAlbum(
            val bucketId: Long,
            val name: String,
            val relativePath: String,
            val directoryPath: String?,
            var count: Int = 0
        )

        val albumsByKey = linkedMapOf<String, MutableAlbum>()
        val cols = mutableListOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cols.add(MediaStore.Images.Media.RELATIVE_PATH)
        }
        @Suppress("DEPRECATION")
        cols.add(MediaStore.Images.Media.DATA)

        val externalRoot = runCatching {
            Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        }.getOrNull()

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            cols.toTypedArray(),
            null,
            null,
            null
        )?.use { cursor ->
            val bucketIdCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                -1
            }
            @Suppress("DEPRECATION")
            val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)

            while (cursor.moveToNext()) {
                val bucketId = if (bucketIdCol >= 0) cursor.getLong(bucketIdCol) else -1L
                val rawName = if (bucketNameCol >= 0) cursor.getString(bucketNameCol) else null
                val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                val dirPath = dataPath?.let { runCatching { File(it).parent }.getOrNull() }

                val rawRelPath = if (relPathCol >= 0) cursor.getString(relPathCol) else null
                val relPath = when {
                    !rawRelPath.isNullOrBlank() -> normalizeRelativePath(rawRelPath)
                    dirPath != null && externalRoot != null && dirPath.startsWith("$externalRoot/") ->
                        normalizeRelativePath(dirPath.removePrefix("$externalRoot/"))
                    !rawName.isNullOrBlank() -> normalizeRelativePath("Pictures/$rawName")
                    else -> continue
                }
                val name = rawName?.takeIf { it.isNotBlank() }
                    ?: relPath.trimEnd('/').substringAfterLast('/').ifBlank { unknownAlbum }

                val key = if (bucketId > 0L) "b:$bucketId" else "p:${relPath.lowercase()}"
                val entry = albumsByKey.getOrPut(key) {
                    MutableAlbum(
                        bucketId = bucketId,
                        name = name,
                        relativePath = relPath,
                        directoryPath = dirPath,
                        count = 0
                    )
                }
                entry.count++
            }
        }

        albumsByKey.values.map {
            AlbumInfo(
                bucketId = it.bucketId,
                name = it.name,
                relativePath = it.relativePath,
                directoryPath = it.directoryPath,
                photoCount = it.count
            )
        }
    }

    suspend fun movePhoto(uri: String, target: MoveTarget): MoveResult = withContext(Dispatchers.IO) {
        try {
            val photoUri = Uri.parse(uri)
            val normalizedTargetRelPath = normalizeRelativePath(target.relativePath)
            if (normalizedTargetRelPath.isEmpty()) {
                return@withContext MoveResult.Failure("invalid_target_path")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                movePhotoApi29Plus(photoUri, uri, target, normalizedTargetRelPath)
            } else {
                movePhotoLegacy(photoUri, target, normalizedTargetRelPath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "移动照片异常: $uri -> ${target.relativePath}", e)
            MoveResult.Failure(e.message ?: "unknown_error")
        }
    }

    private fun movePhotoApi29Plus(
        photoUri: Uri,
        rawUri: String,
        target: MoveTarget,
        normalizedTargetRelPath: String
    ): MoveResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return MoveResult.Failure("unsupported_api")
        }
        val meta = querySourceMetadata(photoUri)
            ?: return MoveResult.Failure("source_not_found")

        val currentRelPath = normalizeRelativePath(meta.relativePath.orEmpty())
        if (currentRelPath.equals(normalizedTargetRelPath, ignoreCase = true)) {
            return MoveResult.Success(rawUri)
        }

        val existingNames = queryDisplayNamesInRelativePath(normalizedTargetRelPath)
        val finalDisplayName = deduplicateFileName(meta.displayName, existingNames)

        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.RELATIVE_PATH, normalizedTargetRelPath)
                if (finalDisplayName != meta.displayName) {
                    put(MediaStore.Images.Media.DISPLAY_NAME, finalDisplayName)
                }
            }
            val updated = context.contentResolver.update(photoUri, values, null, null)
            if (updated > 0) {
                Log.d(TAG, "照片移动成功 (MediaStore.update): $rawUri -> $normalizedTargetRelPath$finalDisplayName")
                return MoveResult.Success(rawUri)
            }
        } catch (e: RecoverableSecurityException) {
            Log.w(TAG, "Android 10 需要单张修改授权: $rawUri")
            return MoveResult.NeedsPermission(e.userAction.actionIntent.intentSender)
        } catch (e: SecurityException) {
            Log.w(TAG, "MediaStore.update 权限不足: ${e.message}")
            if (PermissionHelper.canDeleteWithoutConfirm(context)) {
                val direct = moveViaDirectFile(meta.dataPath, target, normalizedTargetRelPath, finalDisplayName)
                if (direct != null) return direct
            }
            return MoveResult.Failure("permission_denied")
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore.update 失败，尝试回退方案: ${e.message}")
        }

        if (PermissionHelper.canDeleteWithoutConfirm(context)) {
            val direct = moveViaDirectFile(meta.dataPath, target, normalizedTargetRelPath, finalDisplayName)
            if (direct != null) return direct
        }

        return moveViaCopyThenDeleteApi29Plus(photoUri, meta, normalizedTargetRelPath, finalDisplayName)
    }

    private fun moveViaCopyThenDeleteApi29Plus(
        sourceUri: Uri,
        meta: SourceMeta,
        normalizedTargetRelPath: String,
        finalDisplayName: String
    ): MoveResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return MoveResult.Failure("unsupported_api")
        }
        val insertValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, finalDisplayName)
            put(MediaStore.Images.Media.MIME_TYPE, meta.mimeType ?: "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, normalizedTargetRelPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val newUri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            insertValues
        ) ?: return MoveResult.Failure("insert_target_failed")

        try {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: throw IllegalStateException("open_source_stream_failed")
            input.use { src ->
                val output = context.contentResolver.openOutputStream(newUri)
                    ?: throw IllegalStateException("open_target_stream_failed")
                output.use { dst ->
                    src.copyTo(dst)
                }
            }
            val publishValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(newUri, publishValues, null, null)

            val deleted = try {
                context.contentResolver.delete(sourceUri, null, null)
            } catch (e: RecoverableSecurityException) {
                runCatching { context.contentResolver.delete(newUri, null, null) }
                return MoveResult.NeedsPermission(e.userAction.actionIntent.intentSender)
            } catch (e: SecurityException) {
                runCatching { context.contentResolver.delete(newUri, null, null) }
                return MoveResult.Failure("permission_denied")
            }

            if (deleted > 0) {
                Log.d(TAG, "照片移动成功 (copy+delete): $sourceUri -> $newUri")
                return MoveResult.Success(newUri.toString())
            } else {
                runCatching { context.contentResolver.delete(newUri, null, null) }
                return MoveResult.Failure("delete_source_failed")
            }
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(newUri, null, null) }
            Log.e(TAG, "copy+delete 移动失败", e)
            return MoveResult.Failure(e.message ?: "copy_failed")
        }
    }

    private fun movePhotoLegacy(
        photoUri: Uri,
        target: MoveTarget,
        normalizedTargetRelPath: String
    ): MoveResult {
        val meta = querySourceMetadata(photoUri)
            ?: return MoveResult.Failure("source_not_found")
        val targetDir = resolveTargetDirectory(target, normalizedTargetRelPath)
            ?: return MoveResult.Failure("invalid_target_dir")
        val existingNames = targetDir.list()?.toSet().orEmpty()
        val finalName = deduplicateFileName(meta.displayName, existingNames)
        return moveViaDirectFile(meta.dataPath, target, normalizedTargetRelPath, finalName)
            ?: MoveResult.Failure("legacy_move_failed")
    }

    private fun moveViaDirectFile(
        sourcePath: String?,
        target: MoveTarget,
        normalizedTargetRelPath: String,
        preferredFileName: String
    ): MoveResult? {
        if (sourcePath.isNullOrBlank()) return null
        val srcFile = File(sourcePath)
        if (!srcFile.exists() || !srcFile.isFile) return null

        val targetDir = resolveTargetDirectory(target, normalizedTargetRelPath) ?: return null
        if (!targetDir.exists() && !targetDir.mkdirs()) return null

        val existing = targetDir.list()?.toSet().orEmpty()
        val finalName = deduplicateFileName(preferredFileName.ifBlank { srcFile.name }, existing)
        val dstFile = File(targetDir, finalName)
        if (srcFile.absolutePath == dstFile.absolutePath) {
            return MoveResult.Success(Uri.fromFile(dstFile).toString())
        }

        val moved = try {
            if (srcFile.renameTo(dstFile)) {
                true
            } else {
                FileInputStream(srcFile).use { input ->
                    FileOutputStream(dstFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (srcFile.delete()) {
                    true
                } else {
                    dstFile.delete()
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "直接文件移动失败: ${srcFile.absolutePath} -> ${dstFile.absolutePath}", e)
            runCatching { if (dstFile.exists() && srcFile.exists()) dstFile.delete() }
            false
        }

        if (!moved) return null

        MediaScannerConnection.scanFile(
            context,
            arrayOf(srcFile.absolutePath, dstFile.absolutePath),
            null,
            null
        )
        Log.d(TAG, "照片移动成功 (DirectFile): ${srcFile.absolutePath} -> ${dstFile.absolutePath}")
        return MoveResult.Success(Uri.fromFile(dstFile).toString())
    }

    private fun resolveTargetDirectory(target: MoveTarget, normalizedTargetRelPath: String): File? {
        if (!target.directoryPath.isNullOrBlank()) {
            return File(target.directoryPath)
        }
        val externalRoot = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return null
        return File(externalRoot, normalizedTargetRelPath.trimEnd('/'))
    }

    private data class SourceMeta(
        val displayName: String,
        val mimeType: String?,
        val relativePath: String?,
        val dataPath: String?
    )

    private fun querySourceMetadata(photoUri: Uri): SourceMeta? {
        val cols = mutableListOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cols.add(MediaStore.Images.Media.RELATIVE_PATH)
        }
        @Suppress("DEPRECATION")
        cols.add(MediaStore.Images.Media.DATA)

        return context.contentResolver.query(photoUri, cols.toTypedArray(), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val relIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                -1
            }
            @Suppress("DEPRECATION")
            val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            SourceMeta(
                displayName = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else "",
                mimeType = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null,
                relativePath = if (relIdx >= 0) cursor.getString(relIdx) else null,
                dataPath = if (dataIdx >= 0) cursor.getString(dataIdx) else null
            )
        }
    }

    private fun queryDisplayNamesInRelativePath(normalizedRelativePath: String): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
        val names = mutableSetOf<String>()
        val trimmed = normalizedRelativePath.trimEnd('/')
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        val args = arrayOf(normalizedRelativePath, trimmed)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            selection,
            args,
            null
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            while (idx >= 0 && cursor.moveToNext()) {
                cursor.getString(idx)?.let { names.add(it) }
            }
        }
        return names
    }

    suspend fun deletePhoto(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val photoUri = Uri.parse(uri)
            try {
                val deleted = context.contentResolver.delete(photoUri, null, null)
                if (deleted > 0) {
                    database.deletedPhotoDao().insert(
                        com.example.photosmanager.data.model.DeletedPhotoRecord(uri = uri)
                    )
                    return@withContext true
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "直接删除权限异常: ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "直接删除异常: ${e.message}", e)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val id = ContentUris.parseId(photoUri)
                    val deleteUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val deleted = context.contentResolver.delete(deleteUri, null, null)
                    if (deleted > 0) {
                        database.deletedPhotoDao().insert(
                            com.example.photosmanager.data.model.DeletedPhotoRecord(uri = uri)
                        )
                        return@withContext true
                    } else {
                        val selection = "${MediaStore.Images.Media._ID} = ?"
                        val selectionArgs = arrayOf(id.toString())
                        val deleted2 = context.contentResolver.delete(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            selection,
                            selectionArgs
                        )
                        if (deleted2 > 0) {
                            database.deletedPhotoDao().insert(
                                com.example.photosmanager.data.model.DeletedPhotoRecord(uri = uri)
                            )
                            return@withContext true
                        }
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "MediaStore删除权限异常: ${e.message}", e)
                } catch (e: Exception) {
                    Log.e(TAG, "MediaStore删除异常: ${e.message}", e)
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "删除照片异常: ${e.message}", e)
            false
        }
    }

    suspend fun countGonePhotos(uris: List<String>): Int = withContext(Dispatchers.IO) {
        val ids = uris.mapNotNull { uri ->
            runCatching { ContentUris.parseId(Uri.parse(uri)) }.getOrNull()
        }
        if (ids.isEmpty()) return@withContext 0
        val selection = "${MediaStore.Images.Media._ID} IN (${ids.joinToString(",") { "?" }})"
        val stillThere = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            selection,
            ids.map { it.toString() }.toTypedArray(),
            null
        )?.use { it.count } ?: ids.size
        (ids.size - stillThere).coerceAtLeast(0)
    }

    suspend fun getTotalDeletedCount(): Long {
        return database.deletedPhotoDao().getTotalDeletedCount()
    }
}
