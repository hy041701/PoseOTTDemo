package com.example.poseottdemo.scoring

data class Point3D(
    val x: Double,
    val y: Double,
    val z: Double
)

enum class BodyPoint {
    RIGHT_SHOULDER,
    LEFT_SHOULDER,
    RIGHT_ELBOW,
    LEFT_ELBOW,
    RIGHT_WRIST,
    LEFT_WRIST,
    RIGHT_HIP,
    LEFT_HIP,
    RIGHT_KNEE,
    LEFT_KNEE,
    RIGHT_ANKLE,
    LEFT_ANKLE,
    NOSE
}

data class StandardPoseFrame(
    val frameId: Int,
    val points: Map<BodyPoint, Point3D>
)

//整个课程中评分节点的的全局帧号
data class PoseAngles(
    val theta1: Double,
    val theta2: Double,
    val theta3: Double,
    val theta4: Double,
    val theta5: Double,
    val theta6: Double,
    val theta7: Double,
    val theta8: Double,
    val theta9: Double
) {
    fun toList(): List<Double> {
        return listOf(
            theta1,
            theta2,
            theta3,
            theta4,
            theta5,
            theta6,
            theta7,
            theta8,
            theta9
        )
    }
}

//一次评分的详细结果。
data class PoseScoreResult(
    //最终0～100分。
    val score: Int,
    //当前视频对应的帧号。
    val videoFrameId: Int,
    //得到最高分的标准动作帧号。
    val matchedStandardFrameId: Int,
    //标准动作的9个角度。
    val standardAngles: List<Double>,
    //用户动作的9个角度。
    val userAngles: List<Double>,
    //9个角度的绝对差值。
    val angleDiffs: List<Double>,
    //9个角度各自的得分。
    val angleScores: List<Int>
)

data class ScoreNode(
    val frameId: Int,
    val difficulty: Int
)

data class WorkoutScoreConfig(
    val frameCount: Int,
    val restSeconds: Int,
    val weights: List<Double>,
    val scoreNodes: List<ScoreNode>,
    val frames: List<StandardPoseFrame>
)