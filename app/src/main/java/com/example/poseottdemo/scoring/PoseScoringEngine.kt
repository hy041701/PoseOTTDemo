package com.example.poseottdemo.scoring

import com.example.poseottdemo.model.PoseFrameData
import com.example.poseottdemo.model.PosePoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import android.os.SystemClock

//Kotlin版本姿态评分引擎。
class PoseScoringEngine(
    private val config: WorkoutScoreConfig,
) {
    companion object {
        //缓存最近5帧用户动作。
        private const val MAX_USER_POSES = 5
        //参考代码固定延迟8帧。
        private const val CONSTANT_DELAY = 8
        //向前搜索20个标准动作帧。
        private const val DYNAMIC_DELAY = 20
        //关键点最低可信度。
        private const val MIN_VISIBILITY = 0.50f
        private const val MIN_PRESENCE = 0.50f
        //用户姿态超过800毫秒后不能继续评分。
        private const val MAX_POSE_AGE_MS = 800L

    }

    //用户最近5帧动作缓存。
    private data class CachedUserFrame(val frame: PoseFrameData, val receivedAtMs: Long)
    private val recentUserFrames = ArrayDeque<CachedUserFrame>()

    //保存用户动作及其到达OTT的时间。
    fun addUserFrame(frame: PoseFrameData) {
        recentUserFrames.addLast(CachedUserFrame(frame = frame, receivedAtMs = SystemClock.elapsedRealtime()))
        while (recentUserFrames.size > MAX_USER_POSES) {
            recentUserFrames.removeFirst()
        }
    }

    //当前没有检测到人体时，清除之前保存的用户动作。
    fun clearUserFrames() { recentUserFrames.clear() }
    //清空评分状态。
    fun reset() { clearUserFrames() }
    //根据标准视频帧号和评分节点难度评分。
    fun score(
        currentVideoFrame: Int,
        difficulty: Int
    ): PoseScoreResult? {
        val nowMs = SystemClock.elapsedRealtime()
        while (recentUserFrames.isNotEmpty() && nowMs - recentUserFrames.first().receivedAtMs > MAX_POSE_AGE_MS) { recentUserFrames.removeFirst() }
        if (recentUserFrames.isEmpty() || config.frames.isEmpty()) { return null }

        val nearestIndex = findLastFrameBefore(currentVideoFrame)
        if (nearestIndex < 0) { return null }
        val maxStandardIndex = nearestIndex - CONSTANT_DELAY
        if (maxStandardIndex < 0) { return null }
        val minStandardIndex = max(0, maxStandardIndex - DYNAMIC_DELAY + 1)

        var bestResult: PoseScoreResult? = null

        for (cachedFrame in recentUserFrames) {
            val userPoints = convertUserPose(cachedFrame.frame) ?: continue
            val userAngles = PoseAngleCalculator.calculate(points = userPoints, mirrorSwap = false, calculate2D = true) ?: continue

            for (standardIndex in maxStandardIndex downTo minStandardIndex) {
                val standardFrame = config.frames[standardIndex]

                val standardAngles = PoseAngleCalculator.calculate(
                    points = standardFrame.points,
                    mirrorSwap = true,
                    calculate2D = true
                ) ?: continue

                val result = scoreOnePair(
                    standardFrame = standardFrame,
                    currentVideoFrame = currentVideoFrame,
                    difficulty = difficulty,
                    standardAngles = standardAngles,
                    userAngles = userAngles
                )
                if (bestResult == null || result.score > bestResult.score) { bestResult = result }
            }
        }
        return bestResult
    }

    //单个标准动作和单个用户动作评分。
    private fun scoreOnePair(
        standardFrame: StandardPoseFrame,
        currentVideoFrame: Int,
        difficulty: Int,
        standardAngles: PoseAngles,
        userAngles: PoseAngles
    ): PoseScoreResult {
        val standardList = standardAngles.toList()
        val userList = userAngles.toList()

        //根据难度得到满分角度容错。
        val angleLimit = difficultyToAngleLimit(difficulty)
        val angleDiffs = ArrayList<Double>(9)
        val angleScores = ArrayList<Int>(9)
        var weightedScore = 0.0
        var weightSum = 0.0

        for (index in 0 until 9) {
            val diff = abs(standardList[index] - userList[index])
            val angleScore = calculateAngleScore(angleDiff = diff, angleLimit = angleLimit)
            val weight = config.weights[index]
            angleDiffs.add(diff)
            angleScores.add(angleScore)
            weightedScore += angleScore * weight
            weightSum += weight
        }

        if (weightSum <= 0.0) {
            return PoseScoreResult(
                score = 0,
                videoFrameId = currentVideoFrame,
                matchedStandardFrameId = standardFrame.frameId,
                standardAngles = standardList,
                userAngles = userList,
                angleDiffs = angleDiffs,
                angleScores = angleScores
            )
        }

        //先得到加权百分制分数。
        var finalScore = weightedScore / weightSum
        // score = score² / 100 用于压低中间段分数。
        finalScore = finalScore * finalScore / 100.0

        return PoseScoreResult(score = finalScore.roundToInt().coerceIn(0, 100),
            videoFrameId = currentVideoFrame,
            matchedStandardFrameId = standardFrame.frameId,
            standardAngles = standardList,
            userAngles = userList,
            angleDiffs = angleDiffs,
            angleScores = angleScores
        )
    }

    //单角度分数。
    private fun calculateAngleScore(
        angleDiff: Double,
        angleLimit: Double
    ): Int {
        val descentAngle = 15.0
        val score = when {angleDiff <= angleLimit -> { 100.0 }
                angleDiff < angleLimit + descentAngle -> {
                    100.0 - 100.0 / descentAngle * (angleDiff - angleLimit)
                }
                else -> { 0.0 }
            }
        return score.roundToInt().coerceIn(0, 100)
    }

    //难度转换为角度容错。
    private fun difficultyToAngleLimit(
        difficulty: Int
    ): Double {
        val safeDifficulty = difficulty.coerceIn(0, 100)
        return if (safeDifficulty >= 50
        ) {
            50.0 - 0.5 * safeDifficulty
        } else {
            150.0 - 2.5 * safeDifficulty
        }
    }

    //找到最后一个frameId小于当前视频帧的标准帧下标。
    private fun findLastFrameBefore(
        targetFrameId: Int
    ): Int {
        var left = 0
        var right = config.frames.lastIndex
        var result = -1
        while (left <= right) {
            val middle = left + (right - left) / 2
            if (config.frames[middle].frameId < targetFrameId
            ) {
                result = middle
                left = middle + 1
            } else {
                right = middle - 1
            }
        }
        return result
    }

    //将手机33点转换成评分需要的13点。
    private fun convertUserPose(
        frame: PoseFrameData
    ): Map<BodyPoint, Point3D>? {
        val person = frame.persons.firstOrNull() ?: return null
        if (frame.imageWidth <= 0 || frame.imageHeight <= 0) { return null }
        val pointsById = arrayOfNulls<PosePoint>(33)

        for (point in person.landmarks) {
            if (point.id in 0..32) { pointsById[point.id] = point }
        }
        val requiredIds = intArrayOf(
                12, 11,
                14, 13,
                16, 15,
                24, 23,
                26, 25,
                28, 27,
                0
            )

        //判断评分点必须且可信。
        for (id in requiredIds) {
            val point = pointsById[id] ?: return null
            if (!point.x.isFinite() || !point.y.isFinite() || point.visibility < MIN_VISIBILITY || point.presence < MIN_PRESENCE) { return null }
        }

        //将归一化坐标恢复成图像坐标。目前评分使用2D，所以Z暂时保存但不参与计算。
        fun point(id: Int): Point3D {
            val source = pointsById[id]!!
            return Point3D(
                x = source.x.toDouble() * frame.imageWidth,
                y = source.y.toDouble() * frame.imageHeight,
                z = source.z.toDouble() * frame.imageWidth
            )
        }

        return mapOf(
            BodyPoint.RIGHT_SHOULDER to point(12),
            BodyPoint.LEFT_SHOULDER to point(11),
            BodyPoint.RIGHT_ELBOW to point(14),
            BodyPoint.LEFT_ELBOW to point(13),
            BodyPoint.RIGHT_WRIST to point(16),
            BodyPoint.LEFT_WRIST to point(15),
            BodyPoint.RIGHT_HIP to point(24),
            BodyPoint.LEFT_HIP to point(23),
            BodyPoint.RIGHT_KNEE to point(26),
            BodyPoint.LEFT_KNEE to point(25),
            BodyPoint.RIGHT_ANKLE to point(28),
            BodyPoint.LEFT_ANKLE to point(27),
            BodyPoint.NOSE to point(0)
        )
    }
}
