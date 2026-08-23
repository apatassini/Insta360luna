package it.persoft.lunaultra

import it.persoft.lunaultra.data.GimbalAxisLimits
import it.persoft.lunaultra.timelapse.LunaOptics
import it.persoft.lunaultra.timelapse.PanoramaPlanner
import it.persoft.lunaultra.timelapse.PhotoFrameAspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaPlannerTest {
    private val pan = limits(-57f, 235f, 48f)
    private val tilt = limits(-57f, 120f, 31f)

    @Test
    fun `the 3x lens is narrower than the main lens`() {
        val main = LunaOptics.fieldOfView(1, PhotoFrameAspect.FOUR_THREE)
        val tele = LunaOptics.fieldOfView(3, PhotoFrameAspect.FOUR_THREE)

        assertEquals(20f, main.equivalentFocalMm, 0.01f)
        assertEquals(60f, tele.equivalentFocalMm, 0.01f)
        assertTrue(main.horizontalDegrees > tele.horizontalDegrees)
        assertTrue(main.verticalDegrees > tele.verticalDegrees)
    }

    @Test
    fun `builds a calibrated serpentine grid for a 270 degree panorama`() {
        val plan = PanoramaPlanner.plan(
            centerPan = 60f,
            centerTilt = 0f,
            horizontalCoverage = 270f,
            verticalCoverage = 90f,
            overlapPercent = 30,
            zoomScale = 1,
            aspect = PhotoFrameAspect.FOUR_THREE,
            panLimits = pan,
            tiltLimits = tilt,
        ).getOrThrow()

        assertTrue(plan.columns >= 4)
        assertTrue(plan.rows >= 2)
        assertEquals(plan.columns * plan.rows, plan.waypoints.size)
        assertTrue(plan.waypoints.all { it.generatedByPanoramaPlanner })
        assertTrue(plan.waypoints.all { it.pan in pan.minimumDeg..pan.maximumDeg })
        assertTrue(plan.waypoints.all { it.tilt in tilt.minimumDeg..tilt.maximumDeg })
    }

    @Test
    fun `rejects a panorama that cannot be centered at the current position`() {
        val result = PanoramaPlanner.plan(
            centerPan = -50f,
            centerTilt = 0f,
            horizontalCoverage = 270f,
            verticalCoverage = 0f,
            overlapPercent = 30,
            zoomScale = 3,
            aspect = PhotoFrameAspect.FOUR_THREE,
            panLimits = pan,
            tiltLimits = tilt,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Da questa posizione"))
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
