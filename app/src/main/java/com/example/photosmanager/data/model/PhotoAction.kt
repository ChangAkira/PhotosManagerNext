package com.example.photosmanager.data.model

enum class PhotoAction {
    KEEP,           // 保留
    MARK_DELETE,    // 预备删除
    MARK_MOVE,      // 预备移动到其他相册
    VIEW_NEARBY     // 查看附近照片
}
