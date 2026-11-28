package com.example.photosmanager.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 本轮整理中待确认移动的一张照片及其目标相册，用于跨 Activity 传递。
 */
data class PendingMove(
    val uri: String,
    val displayName: String,
    val sourceAlbum: String,
    val target: MoveTarget
) {
    companion object {
        fun toJson(items: List<PendingMove>): String {
            val array = JSONArray()
            for (item in items) {
                array.put(
                    JSONObject()
                        .put("uri", item.uri)
                        .put("displayName", item.displayName)
                        .put("sourceAlbum", item.sourceAlbum)
                        .put("targetAlbumName", item.target.albumName)
                        .put("targetRelativePath", item.target.relativePath)
                        .put("targetBucketId", item.target.bucketId)
                        .put("targetDirectoryPath", item.target.directoryPath ?: JSONObject.NULL)
                )
            }
            return array.toString()
        }

        fun fromJson(raw: String?): List<PendingMove> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                val result = ArrayList<PendingMove>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val dirPath = if (obj.isNull("targetDirectoryPath")) {
                        null
                    } else {
                        obj.optString("targetDirectoryPath", "").takeIf { it.isNotEmpty() }
                    }
                    result.add(
                        PendingMove(
                            uri = obj.getString("uri"),
                            displayName = obj.optString("displayName", ""),
                            sourceAlbum = obj.optString("sourceAlbum", ""),
                            target = MoveTarget(
                                albumName = obj.getString("targetAlbumName"),
                                relativePath = obj.getString("targetRelativePath"),
                                bucketId = obj.optLong("targetBucketId", -1L),
                                directoryPath = dirPath
                            )
                        )
                    )
                }
                result
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
