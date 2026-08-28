package com.example.poseottdemo.protocol

import android.util.Log
import com.example.poseottdemo.model.PoseFrameData
import com.example.poseottdemo.model.PosePerson
import com.example.poseottdemo.model.PosePoint
import org.json.JSONObject

object PoseJsonParser {
    private const val TAG = "PoseJsonParser"
    fun parse(jsonString: String): PoseFrameData? {
        return try {
            val root = JSONObject(jsonString)

            // 1. 检查消息类型
            val type = root.optString("type", "")
            if (type != "pose") {
                return null
            }

            // 2. 读取一帧的基本信息
            val frameId = root.optLong("frame_id", -1L)

            val timestampMs =
                root.optLong("timestamp_ms", -1L)

            val imageWidth =
                root.optInt("image_width", 0)

            val imageHeight =
                root.optInt("image_height", 0)

            // 3. 读取 persons 数组
            val personsJson =
                root.optJSONArray("persons")

            val persons =
                mutableListOf<PosePerson>()

            if (personsJson != null) {
                for (i in 0 until personsJson.length()) {
                    val personJson = personsJson.getJSONObject(i)

                    val personId = personJson.optInt("person_id", i)

                    val landmarksJson = personJson.optJSONArray("landmarks")

                    val landmarks = mutableListOf<PosePoint>()

                    if (landmarksJson != null) {
                        for (j in 0 until landmarksJson.length()) {
                            val pointJson = landmarksJson.getJSONObject(j)
                            val point = PosePoint(id = pointJson.optInt("id", j),

                                x = pointJson.optDouble("x", 0.0).toFloat(),

                                y = pointJson.optDouble("y", 0.0).toFloat(),

                                z = pointJson.optDouble("z", 0.0).toFloat(),

                                worldX = pointJson.optDouble("world_x", 0.0).toFloat(),

                                worldY = pointJson.optDouble("world_y", 0.0).toFloat(),

                                worldZ = pointJson.optDouble("world_z", 0.0).toFloat(),

                                visibility = pointJson.optDouble("visibility", 0.0).toFloat(),

                                presence = pointJson.optDouble("presence", 0.0).toFloat()
                            )
                            landmarks.add(point)
                        }
                    }
                    val person = PosePerson(personId = personId, landmarks = landmarks)
                    persons.add(person)
                }
            }

            // 4. 生成OTT端的PoseFrameData
            PoseFrameData(frameId = frameId, timestampMs = timestampMs, imageWidth = imageWidth, imageHeight = imageHeight, persons = persons)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pose JSON", e)
            null
        }
    }
}