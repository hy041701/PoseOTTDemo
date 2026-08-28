package com.example.poseottdemo.ui

// OTT的页面状态
enum class BusinessState {
    // OTT应用主界面
    HOME,
    // 边看边练主界面 = 视频选择界面
    VIDEO_SELECTION,
    // 视频播放界面
    PLAYING
}

// 视频播放模式
enum class PlaybackMode {
    // 当前没有播放视频
    NONE,
    // 只播放视频
    VIDEO_ONLY,
    // 视频 + 手机Pose火柴人
    VIDEO_WITH_POSE
}

// 播放页面上的浮层状态
enum class OverlayState {
    // 没有浮层
    NONE,
    // 二维码扫码浮层
    PAIRING_QR,
    //等待用户完成准备动作。
    READY_CHECK,
    // 播放中确认是否退出训练
    EXIT_CONFIRMATION,
    // 手机断开连接提示浮层
    PHONE_DISCONNECTED
}
