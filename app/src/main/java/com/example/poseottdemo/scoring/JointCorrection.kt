package com.example.poseottdemo.scoring

enum class CorrectionBodyPart {
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_ELBOW,
    RIGHT_ELBOW,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
    HEAD
}

enum class CorrectionLevel {
    GOOD,
    WARNING,
    ERROR
}

data class JointCorrection(
    val bodyPart: CorrectionBodyPart,
    val level: CorrectionLevel,
    val score: Int,
    val angleDiff: Double,
    val message: String
)

object JointCorrectionMapper {
    private const val GOOD_SCORE = 85
    private const val WARNING_SCORE = 60
    private const val DIRECTION_DEAD_ZONE_DEGREES = 5.0

    private val angleBodyParts = listOf(
        CorrectionBodyPart.LEFT_SHOULDER,
        CorrectionBodyPart.RIGHT_SHOULDER,
        CorrectionBodyPart.LEFT_ELBOW,
        CorrectionBodyPart.RIGHT_ELBOW,
        CorrectionBodyPart.LEFT_HIP,
        CorrectionBodyPart.RIGHT_HIP,
        CorrectionBodyPart.LEFT_KNEE,
        CorrectionBodyPart.RIGHT_KNEE,
        CorrectionBodyPart.HEAD
    )

    fun from(result: PoseScoreResult): List<JointCorrection> {
        val count = minOf(
            angleBodyParts.size,
            result.standardAngles.size,
            result.userAngles.size,
            result.angleDiffs.size,
            result.angleScores.size
        )

        return (0 until count).mapNotNull { index ->
            if (result.angleScores[index] < 0
                || !result.standardAngles[index].isFinite()
                || !result.userAngles[index].isFinite()
                || !result.angleDiffs[index].isFinite()
            ) { return@mapNotNull null }
            val bodyPart = angleBodyParts[index]
            val score = result.angleScores[index].coerceIn(0, 100)
            val level = when {
                score >= GOOD_SCORE -> CorrectionLevel.GOOD
                score >= WARNING_SCORE -> CorrectionLevel.WARNING
                else -> CorrectionLevel.ERROR
            }
            JointCorrection(
                bodyPart = bodyPart,
                level = level,
                score = score,
                angleDiff = result.angleDiffs[index],
                message = messageFor(
                    bodyPart = bodyPart,
                    level = level,
                    standardAngle = result.standardAngles[index],
                    userAngle = result.userAngles[index]
                )
            )
        }
    }

    fun coverageMessage(result: PoseScoreResult): String? {
        if (result.angleCoverageRate >= 100) { return null }
        return "请保持全身入镜"
    }

    fun primaryMessage(corrections: List<JointCorrection>, totalScore: Int): String {
        if (corrections.isEmpty()) {
            return when {
                totalScore >= 90 -> "真棒!"
                totalScore >= 75 -> "很好~"
                totalScore >= 60 -> "继续加油"
                else -> "注意动作"
            }
        }

        val worst = corrections.minByOrNull { it.score } ?: return "动作识别完成"
        return if (worst.level == CorrectionLevel.GOOD) {
            "动作很标准"
        } else {
            worst.message
        }
    }

    private fun messageFor(
        bodyPart: CorrectionBodyPart,
        level: CorrectionLevel,
        standardAngle: Double,
        userAngle: Double
    ): String {
        val partName = bodyPart.displayName()
        if (level == CorrectionLevel.GOOD) { return "$partName 动作正确" }

        val signedDifference = standardAngle - userAngle
        if (kotlin.math.abs(signedDifference) < DIRECTION_DEAD_ZONE_DEGREES) {
            return "$partName 存在轻微偏差"
        }

        return when (bodyPart) {
            CorrectionBodyPart.LEFT_ELBOW,
            CorrectionBodyPart.RIGHT_ELBOW,
            CorrectionBodyPart.LEFT_KNEE,
            CorrectionBodyPart.RIGHT_KNEE -> {
                if (signedDifference > 0.0) {
                    "$partName 再伸直一些"
                } else {
                    "$partName 再弯曲一些"
                }
            }

            CorrectionBodyPart.LEFT_SHOULDER,
            CorrectionBodyPart.RIGHT_SHOULDER,
            CorrectionBodyPart.LEFT_HIP,
            CorrectionBodyPart.RIGHT_HIP -> {
                if (signedDifference > 0.0) {
                    "$partName 夹角偏小"
                } else {
                    "$partName 夹角偏大"
                }
            }

            CorrectionBodyPart.HEAD -> "注意身体朝向"
        }
    }

    private fun CorrectionBodyPart.displayName(): String {
        return when (this) {
            // 具体侧别由火柴人的着色直接指示，文字不使用可能受前置镜像影响的左右名称。
            CorrectionBodyPart.LEFT_SHOULDER,
            CorrectionBodyPart.RIGHT_SHOULDER -> "肩部"
            CorrectionBodyPart.LEFT_ELBOW,
            CorrectionBodyPart.RIGHT_ELBOW -> "肘部"
            CorrectionBodyPart.LEFT_HIP,
            CorrectionBodyPart.RIGHT_HIP -> "髋部"
            CorrectionBodyPart.LEFT_KNEE,
            CorrectionBodyPart.RIGHT_KNEE -> "膝盖"
            CorrectionBodyPart.HEAD -> "身体朝向"
        }
    }
}
