package it.persoft.lunaultra

import it.persoft.lunaultra.data.GimbalCalibrationBuilder
import it.persoft.lunaultra.data.GimbalCalibrationSample
import it.persoft.lunaultra.data.GimbalAxisLimits
import it.persoft.lunaultra.gimbal.formatAxisLimitSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GimbalCalibrationTest {
    @Test
    fun `end stop summary accepts a literal intensity percent`() {
        val text = formatAxisLimitSummary(limits(-57f, 235f, 23.4f))

        assertTrue(text.contains("Limiti: -57.0°…+235.0°"))
        assertTrue(text.contains("Tempo al 20%: 23.4 s"))
        assertTrue(text.contains("Affidabilità fine corsa: 90%"))
    }

    @Test
    fun `builds and interpolates the 1 to 100 percent response curve`() {
        val intensities = listOf(1, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
        val samples = buildList {
            intensities.forEach { intensity ->
                repeat(2) {
                    listOf(1f, -1f).forEach { direction ->
                        val command = intensity / 100f * direction
                        // Curva intenzionalmente non lineare: il 50% produce il 40% del moto.
                        val motion = intensity / 100f * (0.6f + intensity / 250f)
                        add(sample(intensity, GimbalCalibrationSample.AXIS_PAN, command, -120f * motion))
                        add(sample(intensity, GimbalCalibrationSample.AXIS_TILT, command, 80f * motion))
                    }
                }
            }
        }

        val profile = GimbalCalibrationBuilder.build(
            samples,
            "Luna Ultra",
            "1.0.288",
            1234L,
            panLimits = limits(-57f, 235f, 48f),
            tiltLimits = limits(-57f, 120f, 31f),
        )

        assertTrue(profile.isValid)
        assertEquals(12, profile.responsePoints.size)
        assertEquals(100, profile.qualityPercent)
        assertTrue(profile.imageRateAt(50f, true) < 0f)
        assertTrue(profile.imageRateAt(50f, false) > 0f)
        assertEquals(0.4f, profile.motionFraction(0.5f, panAxis = true), 0.03f)
        assertEquals(0.5f, profile.commandForMotionFraction(0.4f, panAxis = true), 0.04f)
        assertEquals(35L, profile.responseOverheadMs)
        assertEquals(300L, profile.settleMs)
        assertTrue(profile.maxAngularRate(panAxis = true) > 0f)
    }

    @Test
    fun `rejects an incomplete curve without low speed and 100 percent`() {
        val profile = GimbalCalibrationBuilder.build(
            samples = listOf(
                sample(50, GimbalCalibrationSample.AXIS_PAN, 0.5f, -40f),
                sample(50, GimbalCalibrationSample.AXIS_TILT, 0.5f, 30f),
            ),
            cameraModel = "Luna Ultra",
            firmware = "",
            calibratedAtMs = 1234L,
        )
        assertFalse(profile.isValid)
    }

    private fun sample(intensity: Int, axis: String, command: Float, signedRateAtIntensity: Float): GimbalCalibrationSample {
        val pulseSeconds = if (intensity <= 1) 4f else 1f
        val shift = signedRateAtIntensity * pulseSeconds * kotlin.math.sign(command)
        return GimbalCalibrationSample(
            intensityPercent = intensity,
            axis = axis,
            command = command,
            pulseMs = (pulseSeconds * 1000).toLong(),
            shiftX = if (axis == GimbalCalibrationSample.AXIS_PAN) shift else 0f,
            shiftY = if (axis == GimbalCalibrationSample.AXIS_TILT) shift else 0f,
            inliers = 12,
            confidence = 0.8f,
            commandOverheadMs = 35L,
            settleMs = 300L,
        )
    }

    private fun limits(min: Float, max: Float, seconds: Float) = GimbalAxisLimits(
        minimumDeg = min,
        maximumDeg = max,
        sweepIntensityPercent = 20,
        travelSecondsAtSweepIntensity = seconds,
        movingPulses = 24,
        endpointConfidencePercent = 90,
    )
}
