package com.example.poseottdemo.scoring

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PoseAngleCalculatorPartialTest {

    @Test
    fun keepsUpperBodyAnglesWhenLowerBodyIsOutsideFrame() {
        val points = mapOf(
            BodyPoint.NOSE to Point3D(0.5, 0.1, 0.0),
            BodyPoint.LEFT_SHOULDER to Point3D(0.4, 0.3, 0.0),
            BodyPoint.RIGHT_SHOULDER to Point3D(0.6, 0.3, 0.0),
            BodyPoint.LEFT_ELBOW to Point3D(0.3, 0.5, 0.0),
            BodyPoint.RIGHT_ELBOW to Point3D(0.7, 0.5, 0.0),
            BodyPoint.LEFT_WRIST to Point3D(0.2, 0.7, 0.0),
            BodyPoint.RIGHT_WRIST to Point3D(0.8, 0.7, 0.0)
        )

        val angles = PoseAngleCalculator.calculatePartial(
            points = points,
            mirrorSwap = false,
            calculate2D = true
        )

        assertNull(angles[0])
        assertNull(angles[1])
        assertNotNull(angles[2])
        assertNotNull(angles[3])
        assertNull(angles[4])
        assertNull(angles[5])
        assertNull(angles[6])
        assertNull(angles[7])
        assertNotNull(angles[8])
    }
}
