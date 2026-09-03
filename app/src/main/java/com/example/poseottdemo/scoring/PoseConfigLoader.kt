package com.example.poseottdemo.scoring

import android.content.Context
import android.util.Log

object PoseConfigLoader {
    private const val TAG = "PoseScore"
    // 原始标准图片每个完整压缩包固定包含120帧。
    private const val FRAMES_PER_SEGMENT = 120
    // config中的分包编号固定从1开始。
    private const val SEGMENT_NUMBER_BASE = 1
    fun load(
        context: Context,
        assetDirectory: String
    ): WorkoutScoreConfig {

        val assetFileName = "$assetDirectory/config.txt"
        val lines = context.assets.open(assetFileName).bufferedReader().use { it.readLines() }
        require(lines.size >= 4) { "config.txt内容不足" }

        val firstLine = lines[0].split(",").filter { it.isNotBlank() }
        val frameCount = firstLine.first().trim().toInt()
        require(frameCount > 0) { "config总帧数必须大于0" }

        // 根据每段固定120帧直接计算评分节点，不再读取ZIP。
        val scoreNodes = parseScoreNodes(tokens = firstLine.drop(1), frameCount = frameCount)

        val restSeconds = lines[1].split(",").first { it.isNotBlank() }.trim().toInt()
        val weights = lines[2].split(",").filter { it.isNotBlank() }.map { it.trim().toDouble() }
        require(weights.size == 9) { "评分权重必须为9个" }
        val frames = mutableListOf<StandardPoseFrame>()
        lines.drop(3).forEach { line ->
            if (line.isBlank()) { return@forEach }
            val values = line.split(",").filter { it.isNotBlank() }
            if (values.size < 40) {
                Log.w(TAG, "跳过异常标准帧")
                return@forEach
            }

            val frameId = values[0].toInt()
            val numbers = values.drop(1).take(39).map { it.toDouble() }
            fun point(index: Int): Point3D {
                val start = index * 3
                return Point3D(x = numbers[start], y = numbers[start + 1], z = numbers[start + 2])
            }

            val points = mapOf(
                    BodyPoint.RIGHT_SHOULDER to point(0),
                    BodyPoint.LEFT_SHOULDER to point(1),
                    BodyPoint.RIGHT_ELBOW to point(2),
                    BodyPoint.LEFT_ELBOW to point(3),
                    BodyPoint.RIGHT_WRIST to point(4),
                    BodyPoint.LEFT_WRIST to point(5),
                    BodyPoint.RIGHT_HIP to point(6),
                    BodyPoint.LEFT_HIP to point(7),
                    BodyPoint.RIGHT_KNEE to point(8),
                    BodyPoint.LEFT_KNEE to point(9),
                    BodyPoint.RIGHT_ANKLE to point(10),
                    BodyPoint.LEFT_ANKLE to point(11),
                    BodyPoint.NOSE to point(12)
                )
            frames.add(StandardPoseFrame(frameId = frameId, points = points)
            )
        }
        require(frames.isNotEmpty()) { "没有读取到标准姿态帧" }

        require(frames.size == frameCount) { "config声明总帧数为$frameCount，" + "实际读取到${frames.size}帧标准姿态" }

        val sortedFrames = frames.sortedBy { it.frameId }

        sortedFrames.forEachIndexed { index, frame ->
            val expectedFrameId = index + 1
            require(frame.frameId == expectedFrameId) { "标准姿态帧号不连续：" + "期望$expectedFrameId，" + "实际${frame.frameId}" }
        }
        return WorkoutScoreConfig(
            frameCount = frameCount,
            restSeconds = restSeconds,
            weights = weights,
            scoreNodes = scoreNodes,
            frames = sortedFrames
        )
    }

    /**
     * 将“分段编号 + 分段内帧号”转换为视频全局帧号。
     * 当前config格式：#分段编号#分段内帧号#难度
     * 例如：#1#0#75 表示第1段第0帧，难度75。
     */
    private fun parseScoreNodes(
        tokens: List<String>,
        frameCount: Int
    ): List<ScoreNode> {
        val result = mutableListOf<ScoreNode>()

        // 没有单独配置难度时沿用前一个难度。
        var currentDifficulty = 60

        val parsedTokens =
            tokens.mapNotNull { token ->
                val parts = token.split("#").filter { it.isNotBlank() }

                if (parts.size < 2) {
                    null
                } else {
                    parts
                }
            }

        parsedTokens.forEach { parts ->
            val segmentNumber = parts[0].toIntOrNull() ?: return@forEach
            val localFrame = parts[1].toIntOrNull() ?: return@forEach

            if (parts.size >= 3) {
                currentDifficulty = parts[2].toIntOrNull()?.coerceIn(0, 100) ?: currentDifficulty
            }

            val segmentIndex = segmentNumber - SEGMENT_NUMBER_BASE

            if (segmentIndex < 0) {
                Log.w(TAG, "跳过无效分段编号：$segmentNumber")
                return@forEach
            }

            // 当前分段在整段视频中的起始位置。
            val segmentStartFrame = segmentIndex * FRAMES_PER_SEGMENT

            //最后一段可能不足120帧。例如总帧数1523：第13段开始位置为1440，实际只剩83帧。
            val remainingFrameCount = frameCount - segmentStartFrame
            val currentSegmentFrameCount = minOf(FRAMES_PER_SEGMENT, remainingFrameCount)

            if (currentSegmentFrameCount <= 0) {
                Log.w(TAG, "跳过超出总帧数的分段：$segmentNumber")
                return@forEach
            }

            // 分段内帧号从0开始。
            if (localFrame !in 0 until currentSegmentFrameCount
            ) {
                Log.w(TAG, "跳过无效分段帧：" + "segment=$segmentNumber，" + "localFrame=$localFrame，" + "segmentFrameCount=$currentSegmentFrameCount")
                return@forEach
            }

            //转换成从1开始的全局帧号：第1段第0帧 -> 全局第1帧；第2段第0帧 -> 全局第121帧
            val globalFrameId = segmentStartFrame + localFrame + 1

            if (globalFrameId !in 1..frameCount) {
                Log.w(TAG, "跳过超出范围的全局帧：$globalFrameId")
                return@forEach
            }
            result.add(ScoreNode(frameId = globalFrameId, difficulty = currentDifficulty)
            )
        }
        return result.sortedBy { it.frameId }
    }
}


