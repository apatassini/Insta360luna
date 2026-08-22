package it.persoft.lunaultra

import it.persoft.lunaultra.data.GimbalCalibrationBuilder
import it.persoft.lunaultra.data.GimbalCalibrationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GimbalCalibrationTest {
    @Test
    fun `builds signed response and L M V scales`() {
        val samples = buildList {
            val panRates = mapOf(1 to -30f, 2 to -60f, 3 to -120f)
            val tiltRates = mapOf(1 to 20f, 2 to 40f, 3 to 80f)
            for (level in 1..3) {
                repeat(3) {
                    listOf(0.2f, -0.2f).forEach { command ->
                        add(sample(level, GimbalCalibrationSample.AXIS_PAN, command, panRates.getValue(level)))
                        add(sample(level, GimbalCalibrationSample.AXIS_TILT, command, tiltRates.getValue(level)))
                    }
                }
            }
        }

        val profile = GimbalCalibrationBuilder.build(
            samples = samples,
            cameraModel = "Luna Ultra",
            firmware = "1.2.3",
            calibratedAtMs = 1234L,
        )

        assertTrue(profile.isValid)
        assertEquals(100, profile.qualityPercent)
        assertEquals(35L, profile.responseOverheadMs)
        assertEquals(300L, profile.settleMs)
        assertEquals(-120f, profile.level(3)!!.panImagePixelsPerSecond, 0.01f)
        assertEquals(80f, profile.level(3)!!.tiltImagePixelsPerSecond, 0.01f)
        assertEquals(0.25f, profile.level(1)!!.panSpeedScale, 0.01f)
        assertEquals(0.5f, profile.level(2)!!.tiltSpeedScale, 0.01f)
        assertEquals(1f, profile.level(3)!!.panSpeedScale, 0.01f)
    }

    @Test
    fun `does not validate an incomplete calibration`() {
        val profile = GimbalCalibrationBuilder.build(
            samples = listOf(sample(3, GimbalCalibrationSample.AXIS_PAN, 0.2f, -100f)),
            cameraModel = "Luna Ultra",
            firmware = "",
            calibratedAtMs = 1234L,
        )
        assertFalse(profile.isValid)
    }

    private fun sample(level: Int, axis: String, command: Float, signedRate: Float): GimbalCalibrationSample {
        val shift = signedRate * command * 0.5f
        return GimbalCalibrationSample(
            hardwareLevel = level,
            axis = axis,
            command = command,
            pulseMs = 500L,
            shiftX = if (axis == GimbalCalibrationSample.AXIS_PAN) shift else 0f,
            shiftY = if (axis == GimbalCalibrationSample.AXIS_TILT) shift else 0f,
            inliers = 12,
            confidence = 0.8f,
            commandOverheadMs = 35L,
            settleMs = 300L,
        )
    }
}
