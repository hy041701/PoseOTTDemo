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

        val rightShoulder = points[BodyPoint.RIGHT_SHOULDER] ?: return null
        val leftShoulder = points[BodyPoint.LEFT_SHOULDER] ?: return null
        val rightElbow = points[BodyPoint.RIGHT_ELBOW] ?: return null
        val leftElbow = points[BodyPoint.LEFT_ELBOW] ?: return null
        val rightWrist = points[BodyPoint.RIGHT_WRIST] ?: return null
        val leftWrist = points[BodyPoint.LEFT_WRIST] ?: return null
        val rightHip = points[BodyPoint.RIGHT_HIP] ?: return null
        val leftHip = points[BodyPoint.LEFT_HIP] ?: return null
        val rightKnee = points[BodyPoint.RIGHT_KNEE] ?: return null
        val leftKnee = points[BodyPoint.LEFT_KNEE] ?: return null
        val rightAnkle = points[BodyPoint.RIGHT_ANKLE] ?: return null
        val leftAnkle = points[BodyPoint.LEFT_ANKLE] ?: return null
        val nose = points[BodyPoint.NOSE] ?: return null

        //1. 左肘—左肩—左髋
        var leftShoulderAngle = angle(a = leftElbow, center = leftShoulder, b = leftHip, calculate2D = calculate2D)

        //2. 右肘—右肩—右髋
        var rightShoulderAngle = angle(a = rightElbow, center = rightShoulder, b = rightHip, calculate2D = calculate2D)

        //3. 左腕—左肘—左肩
        var leftElbowAngle = angle(a = leftWrist, center = leftElbow, b = leftShoulder, calculate2D = calculate2D)

        //4. 右腕—右肘—右肩
        var rightElbowAngle = angle(a = rightWrist, center = rightElbow, b = rightShoulder, calculate2D = calculate2D)

        //5. 左肩—左髋—左膝
        var leftHipAngle = angle(a = leftShoulder, center = leftHip, b = leftKnee, calculate2D = calculate2D)

        //6. 右肩—右髋—右膝
        var rightHipAngle = angle(a = rightShoulder, center = rightHip, b = rightKnee, calculate2D = calculate2D)

        //7. 左髋—左膝—左踝
        var leftKneeAngle = angle(a = leftHip, center = leftKnee, b = leftAnkle, calculate2D = calculate2D)

        //8. 右髋—右膝—右踝
        var rightKneeAngle = angle(a = rightHip, center = rightKnee, b = rightAnkle, calculate2D = calculate2D)

        //计算双肩中点。
        val middleShoulder = Point3D(
                x = (leftShoulder.x + rightShoulder.x) / 2.0,
                y = (leftShoulder.y + rightShoulder.y) / 2.0,
                z = (leftShoulder.z + rightShoulder.z) / 2.0
            )

        //9. 鼻子—双肩中点—右肩。
        val noseAngle = angle(a = nose, center = middleShoulder, b = if (mirrorSwap) { leftShoulder } else { rightShoulder }, calculate2D = calculate2D)

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

        return PoseAngles(
            theta1 = leftShoulderAngle,
            theta2 = rightShoulderAngle,
            theta3 = leftElbowAngle,
            theta4 = rightElbowAngle,
            theta5 = leftHipAngle,
            theta6 = rightHipAngle,
            theta7 = leftKneeAngle,
            theta8 = rightKneeAngle,
            theta9 = noseAngle
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