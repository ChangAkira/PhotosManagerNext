package com.example.photosmanager.data.session

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.photosmanager.R
import com.example.photosmanager.data.model.MoveTarget
import com.example.photosmanager.data.model.PhotoAction
import com.example.photosmanager.data.model.PhotoInfo
import com.example.photosmanager.data.model.PhotoSelection
import com.example.photosmanager.data.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object SessionStore {
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var photos: MutableList<PhotoSelection> = mutableListOf()
    private var index: Int = 0
    private var restored = false

    val queue: List<PhotoSelection>
        get() = synchronized(lock) { photos.map { it.copy() } }

    val currentIndex: Int
        get() = synchronized(lock) { index }

    val isActive: Boolean
        get() = synchronized(lock) { photos.isNotEmpty() }

    suspend fun restoreIfNeeded(context: Context) {
        synchronized(lock) {
            if (restored) return
            restored = true
        }
        val appContext = context.applicationContext
        val json = PreferencesRepository(appContext).activeSessionJson.first() ?: return
        val parsed = deserialize(appContext, json) ?: return
        val (savedPhotos, savedIndex) = parsed
        if (savedPhotos.isEmpty()) return

        val alive = withContext(Dispatchers.IO) {
            savedPhotos.filter { existsInMediaStore(appContext, it.photoInfo.uri) }
        }
        if (alive.isEmpty()) {
            finish(appContext)
            return
        }

        val currentUri = savedPhotos.getOrNull(savedIndex)?.photoInfo?.uri
        val dropped = alive.size != savedPhotos.size
        synchronized(lock) {
            photos = alive.toMutableList()
            index = currentUri?.let { uri ->
                alive.indexOfFirst { it.photoInfo.uri == uri }
            }?.takeIf { it >= 0 } ?: 0
        }
        if (dropped) write(appContext, snapshot())
        Log.d("SessionStore", "恢复未完成的一轮：${alive.size} 张，进度 $currentIndex")
    }

    fun startRound(context: Context, selections: List<PhotoSelection>) {
        val appContext = context.applicationContext
        synchronized(lock) {
            photos = selections.toMutableList()
            index = 0
            restored = true
        }
        write(appContext, snapshot())
    }

    fun setIndex(context: Context, position: Int) {
        val appContext = context.applicationContext
        val json = synchronized(lock) {
            if (photos.isEmpty()) return
            index = position.coerceIn(0, photos.size - 1)
            snapshot()
        }
        write(appContext, json)
    }

    fun setAction(context: Context, position: Int, action: PhotoAction) {
        val appContext = context.applicationContext
        val json = synchronized(lock) {
            val target = photos.getOrNull(position) ?: return
            val clearMove = action != PhotoAction.MARK_MOVE && target.moveTarget != null
            if (target.action == action && !clearMove) return
            target.action = action
            if (action != PhotoAction.MARK_MOVE) {
                target.moveTarget = null
            }
            snapshot()
        }
        write(appContext, json)
    }

    fun setMove(context: Context, position: Int, moveTarget: MoveTarget) {
        val appContext = context.applicationContext
        val json = synchronized(lock) {
            val target = photos.getOrNull(position) ?: return
            if (target.action == PhotoAction.MARK_MOVE && target.moveTarget == moveTarget) return
            target.action = PhotoAction.MARK_MOVE
            target.moveTarget = moveTarget
            snapshot()
        }
        write(appContext, json)
    }

    fun insertAfterCurrent(context: Context, extra: List<PhotoSelection>) {
        if (extra.isEmpty()) return
        val appContext = context.applicationContext
        val json = synchronized(lock) {
            if (photos.isEmpty()) return
            val before = photos.take(index + 1)
            val after = photos.drop(index + 1)
            photos = (before + extra + after).toMutableList()
            snapshot()
        }
        write(appContext, json)
    }

    fun finish(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            photos = mutableListOf()
            index = 0
            restored = true
        }
        write(appContext, null)
    }

    private fun snapshot(): String {
        val array = JSONArray()
        for (selection in photos) {
            val info = selection.photoInfo
            val obj = JSONObject()
                .put("uri", info.uri)
                .put("albumName", info.albumName)
                .put("dateAdded", info.dateAdded)
                .put("dateModified", info.dateModified)
                .put("size", info.size)
                .put("width", info.width)
                .put("height", info.height)
                .put("displayName", info.displayName)
                .put("bucketId", info.bucketId)
                .put("action", selection.action.name)
                .put("fromNearby", selection.fromNearby)
            selection.moveTarget?.let { target ->
                obj.put("moveTargetAlbumName", target.albumName)
                obj.put("moveTargetRelativePath", target.relativePath)
                obj.put("moveTargetBucketId", target.bucketId)
                if (!target.directoryPath.isNullOrEmpty()) {
                    obj.put("moveTargetDirectoryPath", target.directoryPath)
                }
            }
            array.put(obj)
        }
        return JSONObject()
            .put("version", VERSION)
            .put("index", index)
            .put("photos", array)
            .toString()
    }

    private fun write(context: Context, json: String?) {
        ioScope.launch {
            try {
                val repository = PreferencesRepository(context)
                if (json == null) repository.clearActiveSession() else repository.setActiveSession(json)
            } catch (e: Exception) {
                Log.e("SessionStore", "写入会话状态失败", e)
            }
        }
    }

    private fun deserialize(
        context: Context,
        raw: String,
    ): Pair<List<PhotoSelection>, Int>? {
        return try {
            val root = JSONObject(raw)
            if (root.optInt("version", -1) != VERSION) return null
            val array = root.getJSONArray("photos")
            val selections = ArrayList<PhotoSelection>(array.length())
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val info = PhotoInfo(
                    uri = item.getString("uri"),
                    albumName = item.optString("albumName", context.getString(R.string.album_unknown)),
                    dateAdded = item.optLong("dateAdded"),
                    dateModified = item.optLong("dateModified"),
                    size = item.optLong("size"),
                    width = item.optInt("width"),
                    height = item.optInt("height"),
                    displayName = item.optString("displayName", ""),
                    bucketId = item.optLong("bucketId", -1L)
                )
                val parsedAction = runCatching { PhotoAction.valueOf(item.optString("action")) }
                    .getOrDefault(PhotoAction.KEEP)
                val targetName = item.optString("moveTargetAlbumName", "")
                val targetRelPath = item.optString("moveTargetRelativePath", "")
                val moveTarget = if (targetName.isNotEmpty() && targetRelPath.isNotEmpty()) {
                    MoveTarget(
                        albumName = targetName,
                        relativePath = targetRelPath,
                        bucketId = item.optLong("moveTargetBucketId", -1L),
                        directoryPath = item.optString("moveTargetDirectoryPath", "").takeIf { it.isNotEmpty() }
                    )
                } else {
                    null
                }
                val action = if (parsedAction == PhotoAction.MARK_MOVE && moveTarget == null) {
                    PhotoAction.KEEP
                } else {
                    parsedAction
                }
                selections.add(
                    PhotoSelection(
                        photoInfo = info,
                        action = action,
                        fromNearby = item.optBoolean("fromNearby", false),
                        moveTarget = if (action == PhotoAction.MARK_MOVE) moveTarget else null
                    )
                )
            }
            val position = root.optInt("index", 0).coerceIn(0, maxOf(0, selections.size - 1))
            selections to position
        } catch (e: Exception) {
            Log.e("SessionStore", "会话数据解析失败，丢弃本轮", e)
            null
        }
    }

    private fun existsInMediaStore(context: Context, uriString: String): Boolean {
        return try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            context.contentResolver.query(Uri.parse(uriString), projection, null, null, null)?.use {
                it.moveToFirst()
            } ?: true
        } catch (e: Exception) {
            Log.d("SessionStore", "存在性检查失败，保留该照片: $uriString")
            true
        }
    }

    private const val VERSION = 1
}
