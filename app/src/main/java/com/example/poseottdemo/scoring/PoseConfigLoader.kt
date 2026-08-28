package com.example.poseottdemo.scoring

import android.content.Context
import android.util.Log
import java.util.zip.ZipInputStream

object PoseConfigLoader {
    private const val TAG = "PoseScore"
    fun load(
        context: Context,
        assetDirectory: String
    ): WorkoutScoreConfig {

        val assetFileName = "$assetDirectory/config.txt"
        val lines = context.assets.open(assetFileName).bufferedReader().use { it.readLines() }

        require(lines.size >= 4) { "config.txt内容不足" }

        val firstLine = lines[0].split(",").filter { it.isNotBlank() }

        val frameCount = firstLine.first().trim().toInt()

        val segmentFrameCounts = readSegmentFrameCounts(context, assetDirectory)
        require(segmentFrameCounts.sum() == frameCount) {
            "config总帧数为$frameCount，ZIP中实际读取到${segmentFrameCounts.sum()}张图片"
        }
        val scoreNodes = parseScoreNodes(tokens = firstLine.drop(1), segmentFrameCounts = segmentFrameCounts)

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

        val sortedFrames = frames.sortedBy { it.frameId }
        return WorkoutScoreConfig(
            frameCount = frameCount,
            restSeconds = restSeconds,
            weights = weights,
            scoreNodes = scoreNodes,
            frames = sortedFrames
        )
    }

    //按ZIP名称顺序读取每个压缩包中的标准图片数量。
    private fun readSegmentFrameCounts(
        context: Context,
        assetDirectory: String
    ): List<Int> {
        val zipFiles = context.assets.list(assetDirectory)
            ?.filter { it.endsWith(".zip", ignoreCase = true) }
            ?.sortedWith(compareBy<String>({ fileNumber(it) }, { it }))
            .orEmpty()

        require(zipFiles.isNotEmpty()) { "$assetDirectory 中没有找到标准动作ZIP" }

        return zipFiles.map { zipFile ->
            val zipPath = "$assetDirectory/$zipFile"
            var imageCount = 0

            ZipInputStream(context.assets.open(zipPath).buffered()).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && isImageFile(entry.name)) {
                        imageCount++
                    }
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
            require(imageCount > 0) { "$zipPath 中没有标准动作图片" }
            imageCount
        }
    }

    //提取ZIP文件名中的数字，用于按素材序号排序。
    private fun fileNumber(fileName: String): Int {
        return Regex("\\d+").find(fileName)?.value?.toIntOrNull() ?: Int.MAX_VALUE
    }

    //判断ZIP条目是否为标准动作图片。
    private fun isImageFile(fileName: String): Boolean {
        val lowerName = fileName.lowercase()
        return lowerName.endsWith(".jpg") ||
                lowerName.endsWith(".jpeg") ||
                lowerName.endsWith(".png")
    }

    private fun parseScoreNodes(
        tokens: List<String>,
        segmentFrameCounts: List<Int>
    ): List<ScoreNode> {
        val result = mutableListOf<ScoreNode>()
        var currentDifficulty = 60
        val parsedTokens = tokens.mapNotNull { token ->
            val parts = token.split("#").filter { it.isNotBlank() }
            if (parts.size < 2) null else parts
        }
        val zipNumberBase = if (parsedTokens.any { it[0].toIntOrNull() == 0 }) 0 else 1

        parsedTokens.forEach { parts ->
            val zipNumber = parts[0].toIntOrNull() ?: return@forEach

            val localFrame = parts[1].toIntOrNull() ?: return@forEach

            if (parts.size >= 3) {
                currentDifficulty = parts[2].toIntOrNull()?.coerceIn(0, 100) ?: currentDifficulty
            }

            val segmentIndex = zipNumber - zipNumberBase

            if (segmentIndex !in segmentFrameCounts.indices) { return@forEach }

            val segmentFrameCount = segmentFrameCounts[segmentIndex]

            if (localFrame !in 0 until segmentFrameCount) { return@forEach }

            val previousFrames = segmentFrameCounts.take(segmentIndex).sum()

            val globalFrameId = previousFrames + localFrame + 1

            result.add(ScoreNode(frameId = globalFrameId, difficulty = currentDifficulty)
            )
        }
        return result.sortedBy {
            it.frameId
        }
    }
}
