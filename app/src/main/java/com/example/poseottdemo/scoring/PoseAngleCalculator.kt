package com.example.poseottdemo.scoring

import kotlin.math.acos
import kotlin.math.sqrt

object PoseAngleCalculator {

    /**
     * @param points 13个人体关键点
     * @param mirrorSwap 是否交换左右动作
     * @param calculate2D true只计算XY；false计算XYZ
     */
    fun calculate(
        points: Map<BodyPoint, Point3D>,
        mirrorSwap: Boolean,
        calculate2D: Boolean
    ): PoseAngles? {

        val values = calculatePartial(
            points = points,
            mirrorSwap = mirrorSwap,
            calculate2D = calculate2D
        )
        if (values.any { it == null }) { return null }
        return PoseAngles(
            theta1 = values[0]!!,
            theta2 = values[1]!!,
            theta3 = values[2]!!,
            theta4 = values[3]!!,
            theta5 = values[4]!!,
            theta6 = values[5]!!,
            theta7 = values[6]!!,
            theta8 = values[7]!!,
            theta9 = values[8]!!
        )
    }

    //按当前可用关键点分别计算角度。缺少下半身时仍可保留肩、肘等上半身角度。
    fun calculatePartial(
        points: Map<BodyPoint, Point3D>,
        mirrorSwap: Boolean,
        calculate2D: Boolean
    ): List<Double?> {

        val rightShoulder = points[BodyPoint.RIGHT_SHOULDER]
        val leftShoulder = points[BodyPoint.LEFT_SHOULDER]
        val rightElbow = points[BodyPoint.RIGHT_ELBOW]
        val leftElbow = points[BodyPoint.LEFT_ELBOW]
        val rightWrist = points[BodyPoint.RIGHT_WRIST]
        val leftWrist = points[BodyPoint.LEFT_WRIST]
        val rightHip = points[BodyPoint.RIGHT_HIP]
        val leftHip = points[BodyPoint.LEFT_HIP]
        val rightKnee = points[BodyPoint.RIGHT_KNEE]
        val leftKnee = points[BodyPoint.LEFT_KNEE]
        val rightAnkle = points[BodyPoint.RIGHT_ANKLE]
        val leftAnkle = points[BodyPoint.LEFT_ANKLE]
        val nose = points[BodyPoint.NOSE]

        fun angleOrNull(a: Point3D?, center: Point3D?, b: Point3D?): Double? {
            if (a == null || center == null || b == null) { return null }
            return angle(a = a, center = center, b = b, calculate2D = calculate2D)
        }

        //1. 左肘—左肩—左髋
        var leftShoulderAngle = angleOrNull(a = leftElbow, center = leftShoulder, b = leftHip)

        //2. 右肘—右肩—右髋
        var rightShoulderAngle = angleOrNull(a = rightElbow, center = rightShoulder, b = rightHip)

        //3. 左腕—左肘—左肩
        var leftElbowAngle = angleOrNull(a = leftWrist, center = leftElbow, b = leftShoulder)

        //4. 右腕—右肘—右肩
        var rightElbowAngle = angleOrNull(a = rightWrist, center = rightElbow, b = rightShoulder)

        //5. 左肩—左髋—左膝
        var leftHipAngle = angleOrNull(a = leftShoulder, center = leftHip, b = leftKnee)

        //6. 右肩—右髋—右膝
        var rightHipAngle = angleOrNull(a = rightShoulder, center = rightHip, b = rightKnee)

        //7. 左髋—左膝—左踝
        var leftKneeAngle = angleOrNull(a = leftHip, center = leftKnee, b = leftAnkle)

        //8. 右髋—右膝—右踝
        var rightKneeAngle = angleOrNull(a = rightHip, center = rightKnee, b = rightAnkle)

        //计算双肩中点。
        val middleShoulder = if (leftShoulder != null && rightShoulder != null) {
            Point3D(
                x = (leftShoulder.x + rightShoulder.x) / 2.0,
                y = (leftShoulder.y + rightShoulder.y) / 2.0,
                z = (leftShoulder.z + rightShoulder.z) / 2.0
            )
        } else { null }

        //9. 鼻子—双肩中点—右肩。
        val noseAngle = angleOrNull(
            a = nose,
            center = middleShoulder,
            b = if (mirrorSwap) { leftShoulder } else { rightShoulder }
        )

        //交换标准动作左右角度。
        if (mirrorSwap) {
            val shoulderTemp = leftShoulderAngle
            leftShoulderAngle = rightShoulderAngle
            rightShoulderAngle = shoulderTemp

            val elbowTemp = leftElbowAngle
            leftElbowAngle = rightElbowAngle
            rightElbowAngle = elbowTemp

            val hipTemp = leftHipAngle
            leftHipAngle = rightHipAngle
            rightHipAngle = hipTemp

            val kneeTemp = leftKneeAngle
            leftKneeAngle = rightKneeAngle
            rightKneeAngle = kneeTemp
        }

        return listOf(
            leftShoulderAngle,
            rightShoulderAngle,
            leftElbowAngle,
            rightElbowAngle,
            leftHipAngle,
            rightHipAngle,
            leftKneeAngle,
            rightKneeAngle,
            noseAngle
        )
    }

    //计算A—center—B形成的角度。
    private fun angle(
        a: Point3D,
        center: Point3D,
        b: Point3D,
        calculate2D: Boolean
    ): Double {

        val vectorAX = a.x - center.x
        val vectorAY = a.y - center.y
        val vectorAZ = if (calculate2D) { 0.0 } else { a.z - center.z }

        val vectorBX = b.x - center.x
        val vectorBY = b.y - center.y
        val vectorBZ = if (calculate2D) { 0.0 } else { b.z - center.z }

        val dot = vectorAX * vectorBX + vectorAY * vectorBY + vectorAZ * vectorBZ
        val lengthA = sqrt(vectorAX * vectorAX + vectorAY * vectorAY + vectorAZ * vectorAZ)
        val lengthB = sqrt(vectorBX * vectorBX + vectorBY * vectorBY + vectorBZ * vectorBZ)

        if (lengthA < 1e-9 || lengthB < 1e-9) { return 0.0 }
        val cosine = (dot / (lengthA * lengthB)).coerceIn(minimumValue = -1.0, maximumValue = 1.0)
        return Math.toDegrees(acos(cosine))
    }
}
