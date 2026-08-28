package com.example.poseottdemo.model

//OTT端一帧姿态数据
data class PoseFrameData(
    val frameId: Long,
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val persons: List<PosePerson>
)

//一个人的姿态数据
data class PosePerson(
    val personId: Int,
    val landmarks: List<PosePoint>
)

// 一个 MediaPipe 关键点
data class PosePoint(
    val id: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val worldX: Float,
    val worldY: Float,
    val worldZ: Float,
    val visibility: Float,
    val presence: Float
)