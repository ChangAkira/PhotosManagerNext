package com.example.photosmanager.data.model

/**
 * 移动目标相册描述。
 *
 * Android 10+ 依赖 [relativePath]（形如 "Pictures/旅行/"）更新 MediaStore.RELATIVE_PATH；
 * Android 7–9 优先使用 [directoryPath]，新建相册时再根据 [relativePath] 拼出外部存储目录。
 */
data class MoveTarget(
    val albumName: String,
    val relativePath: String,
    val bucketId: Long = -1L,
    val directoryPath: String? = null
)
