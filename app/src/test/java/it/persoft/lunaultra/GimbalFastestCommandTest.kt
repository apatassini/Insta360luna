package it.persoft.lunaultra

import it.persoft.lunaultra.data.GimbalCalibrationBuilder
import it.persoft.lunaultra.data.GimbalCalibrationProfile
import it.persoft.lunaultra.data.GimbalAxisLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Il 100% non è il comando più veloce, e il profilo non deve fingere che lo sia.
 *
 * Numeri misurati sulla Luna Ultra il 23 agosto: il comando 100 muove circa 11 °/s su
 * entrambi gli assi, mentre il 90 ne fa 57 in orizzontale e 41 in verticale. Finché la curva
 * veniva "riparata" alzando il 100 al valore del 90, ogni spostamento fotografico partiva al
 * 100 credendo di andare a 41 °/s e ne faceva 11: si fermava dopo un quarto della strada, ed è
 * per questo che una panoramica sembrava non muovere la camera.
 */
class GimbalFastestCommandTest {

    /** La curva vera, come è uscita dal fine corsa. */
    private fun measuredProfile(): GimbalCalibrationProfile = GimbalCalibrationBuilder.buildFromDegrees(
        panCurve = listOf(
            1 to 0.86f, 5 to 3.45f, 10 to 7.31f, 20 to 13.57f, 30 to 19.00f, 40 to 25.91f,
            50 to 31.67f, 60 to 40.71f, 70 to 40.71f, 80 to 57.00f, 90 to 57.00f, 100 to 10.56f,
        ),
        tiltCurve = listOf(
            1 to 0.65f, 5 to 2.59f, 10 to 6.33f, 20 to 12.39f, 30 to 19.00f, 40 to 25.91f,
            50 to 31.67f, 60 to 31.67f, 70 to 40.71f, 80 to 40.71f, 90 to 40.71f, 100 to 11.40f,
        ),
        cameraModel = "Insta360 Luna Ultra",
        firmware = "v1.0.288",
        panLimits = limits(-57f, 235f, 10.7f),
        tiltLimits = limits(-57f, 120f, 7.5f),
    )

    @Test
    fun `il profilo tiene il cento per cento come e stato misurato, senza alzarlo`() {
        val profile = measuredProfile()
        val hundred = profile.responsePoints.first { it.intensityPercent == 100 }
        assertEquals(10.56f, hundred.panDegreesPerSecond, 0.001f)
        assertEquals(11.40f, hundred.tiltDegreesPerSecond, 0.001f)
    }

    @Test
    fun `il comando piu veloce e quello misurato piu veloce, non il cento`() {
        val profile = measuredProfile()
        assertEquals(80, profile.fastestCommandPercent(panAxis = true))
        assertEquals(70, profile.fastestCommandPercent(panAxis = false))
        assertEquals(57.00f, profile.maxAngularRate(panAxis = true), 0.01f)
        assertEquals(40.71f, profile.maxAngularRate(panAxis = false), 0.01f)
    }

    @Test
    fun `chiedere tutta la velocita manda il comando piu veloce, non il cento`() {
        val profile = measuredProfile()
        assertEquals(0.80f, profile.commandForMotionFraction(1f, panAxis = true), 0.001f)
        assertEquals(0.70f, profile.commandForMotionFraction(1f, panAxis = false), 0.001f)
        // Il segno passa: chiedere tutta la velocità all'indietro resta all'indietro.
        assertTrue(profile.commandForMotionFraction(-1f, panAxis = true) < 0f)
    }

    @Test
    fun `il dead reckoning integra la velocita vera del comando cento, non quella del novanta`() {
        val profile = measuredProfile()
        // motionFraction è la frazione del fondo scala che quel comando produce davvero.
        val fraction = abs(profile.motionFraction(1f, panAxis = false))
        assertEquals(11.40f / 40.71f, fraction, 0.001f)
        // Mezzo secondo di comando 100 sul verticale sono 5,7°, non 20,4°.
        assertEquals(5.70f, fraction * profile.maxAngularRate(false) * 0.5f, 0.01f)
    }

    @Test
    fun `le intensita dove chiedere di piu ottiene di meno vengono elencate`() {
        val profile = measuredProfile()
        val pan = profile.nonMonotonicPoints(panAxis = true)
        assertEquals(1, pan.size)
        assertEquals(100, pan.first().first)
        assertEquals(57.00f - 10.56f, pan.first().second, 0.01f)

        val tilt = profile.nonMonotonicPoints(panAxis = false)
        assertEquals(1, tilt.size)
        assertEquals(100, tilt.first().first)
    }

    @Test
    fun `una curva sana non segnala niente e il suo massimo resta il cento`() {
        val curve = listOf(
            1 to 0.6f, 5 to 3f, 10 to 6f, 20 to 12f, 30 to 18f, 40 to 25f,
            50 to 31f, 60 to 37f, 70 to 42f, 80 to 48f, 90 to 54f, 100 to 60f,
        )
        val profile = GimbalCalibrationBuilder.buildFromDegrees(
            panCurve = curve,
            tiltCurve = curve,
            cameraModel = "Insta360 Luna Ultra",
            firmware = "v1.0.288",
            panLimits = limits(-57f, 235f, 10.7f),
            tiltLimits = limits(-57f, 120f, 7.5f),
        )
        assertTrue(profile.isValid)
        assertEquals(100, profile.fastestCommandPercent(panAxis = true))
        assertEquals(60f, profile.maxAngularRate(panAxis = true), 0.01f)
        assertTrue(profile.nonMonotonicPoints(panAxis = true).isEmpty())
        assertEquals(1f, profile.commandForMotionFraction(1f, panAxis = true), 0.001f)
    }

    private fun limits(minimum: Float, maximum: Float, seconds: Float) = GimbalAxisLimits(
        minimumDeg = minimum,
        maximumDeg = maximum,
        sweepIntensityPercent = 40,
        travelSecondsAtSweepIntensity = seconds,
        movingPulses = 20,
        endpointConfidencePercent = 90,
    )
}
