package com.example.poseottdemo.scoring

import org.junit.Assert.assertEquals
import org.junit.Test

class JointCorrectionMapperTest {

    @Test
    fun mapsNineAnglesAndSelectsWorstCorrection() {
        val result = result(
            standardAngles = listOf(90.0, 90.0, 160.0, 160.0, 120.0, 120.0, 170.0, 170.0, 90.0),
            userAngles = listOf(90.0, 90.0, 120.0, 160.0, 120.0, 120.0, 170.0, 170.0, 90.0),
            angleScores = listOf(100, 100, 35, 100, 100, 100, 100, 100, 100)
        )

        val corrections = JointCorrectionMapper.from(result)

        assertEquals(9, corrections.size)
        assertEquals(CorrectionBodyPart.LEFT_ELBOW, corrections[2].bodyPart)
        assertEquals(CorrectionLevel.ERROR, corrections[2].level)
        assertEquals("肘部 再伸直一些", JointCorrectionMapper.primaryMessage(corrections, 70))
    }

    @Test
    fun reportsStandardActionWhenEveryJointIsGood() {
        val result = result(
            standardAngles = List(9) { 90.0 },
            userAngles = List(9) { 90.0 },
            angleScores = List(9) { 100 }
        )

        val corrections = JointCorrectionMapper.from(result)

        assertEquals("动作很标准", JointCorrectionMapper.primaryMessage(corrections, 100))
    }

    @Test
    fun ignoresMissingAnglesAndUsesUnifiedCoveragePrompt() {
        val result = PoseScoreResult(
            score = 72,
            videoFrameId = 100,
            matchedStandardFrameId = 92,
            standardAngles = List(9) { 90.0 },
            userAngles = listOf(Double.NaN, Double.NaN, 95.0, 85.0) + List(5) { Double.NaN },
            angleDiffs = listOf(Double.NaN, Double.NaN, 5.0, 5.0) + List(5) { Double.NaN },
            angleScores = listOf(-1, -1, 90, 90, -1, -1, -1, -1, -1),
            validAngleIndices = listOf(2, 3),
            angleCoverageRate = 22
        )

        val corrections = JointCorrectionMapper.from(result)

        assertEquals(2, corrections.size)
        assertEquals("请保持全身入镜", JointCorrectionMapper.coverageMessage(result))
    }

    private fun result(
        standardAngles: List<Double>,
        userAngles: List<Double>,
        angleScores: List<Int>
    ): PoseScoreResult {
        return PoseScoreResult(
            score = angleScores.average().toInt(),
            videoFrameId = 100,
            matchedStandardFrameId = 92,
            standardAngles = standardAngles,
            userAngles = userAngles,
            angleDiffs = standardAngles.zip(userAngles) { standard, user ->
                kotlin.math.abs(standard - user)
            },
            angleScores = angleScores
        )
    }
}
