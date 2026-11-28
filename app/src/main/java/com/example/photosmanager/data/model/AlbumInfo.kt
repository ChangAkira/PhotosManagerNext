package com.example.photosmanager.data.model

/**
 * 设备上已有相册的元信息，用于移动照片时选择目标相册。
 */
data class AlbumInfo(
    val bucketId: Long,
    val name: String,
    val relativePath: String,
    val directoryPath: String?,
    val photoCount: Int
) {
    fun toMoveTarget(): MoveTarget = MoveTarget(
        albumName = name,
        relativePath = relativePath,
        bucketId = bucketId,
        directoryPath = directoryPath
    )
}
