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
        assertEquals(plan.columnsPerRow.sum(), plan.waypoints.size)
        assertTrue(plan.waypoints.all { it.generatedByPanoramaPlanner })
        assertTrue(plan.waypoints.all { it.pan in pan.minimumDeg..pan.maximumDeg })
        assertTrue(plan.waypoints.all { it.tilt in tilt.minimumDeg..tilt.maximumDeg })
    }

    @Test
    fun `un grado in piu del fotogramma non vale un secondo scatto`() {
        // Il 16:9 orizzontale chiede 120x67 gradi e il fotogramma 4:3 a 1x ne copre 81,7x66,0: in
        // verticale mancava un grado, e il piano faceva due righe distanti 1,5 gradi. Due
        // fotogrammi sovrapposti al 98% non sono una panoramica, sono lo stesso scatto due
        // volte — e in una griglia 2x2 significa quattro foto invece di due.
        val plan = PanoramaPlanner.plan(
            centerPan = 5f,
            centerTilt = 23f,
            horizontalCoverage = 120f,
            verticalCoverage = 67f,
            overlapPercent = 30,
            zoomScale = 1,
            aspect = PhotoFrameAspect.FOUR_THREE,
            panLimits = pan,
            tiltLimits = tilt,
        ).getOrThrow()

        assertEquals(1, plan.rows)
        assertEquals(2, plan.columns)
        assertEquals(2, plan.waypoints.size)
    }

    @Test
    fun `una copertura che serve davvero due righe le ottiene`() {
        val plan = PanoramaPlanner.plan(
            centerPan = 5f,
            centerTilt = 0f,
            horizontalCoverage = 120f,
            verticalCoverage = 110f,
            overlapPercent = 30,
            zoomScale = 1,
            aspect = PhotoFrameAspect.FOUR_THREE,
            panLimits = pan,
            tiltLimits = tilt,
        ).getOrThrow()

        assertEquals(2, plan.rows)
        assertEquals(plan.columns * plan.rows, plan.waypoints.size)
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

    /**
     * Più ci si allontana dall'orizzonte, meno scatti servono per fare il giro.
     *
     * È il difetto misurato su una sferica vera: quattro file da sei scatti, e la fila a 88° di
     * inclinazione inquadrava sei volte quasi lo stesso cielo. Un minuto di gimbal e sei file da
     * unire, per un'informazione che sta in uno scatto solo — e con una sovrapposizione al 99%
     * l'unione peggiora, non migliora.
     *
     * Il riferimento è l'orizzonte, non il basso della griglia: la corsa del tilt va da -57° a
     * +120°, quindi la fila più fitta è quella che guarda dritto, non la prima.
     */
    @Test
    fun `le file lontane dall'orizzonte hanno meno scatti`() {
        val plan = PanoramaPlanner.plan(
            centerPan = 89f,
            centerTilt = 31.5f,
            horizontalCoverage = 360f,
            verticalCoverage = 180f,
            overlapPercent = 20,
            zoomScale = 1,
            aspect = PhotoFrameAspect.FOUR_THREE,
            panLimits = limits(-57f, 235f, 48f),
            tiltLimits = limits(-57f, 120f, 31f),
        ).getOrThrow()

        val counts = plan.columnsPerRow
        assertEquals(plan.rows, counts.size)
        val tilts = plan.waypoints.map { it.tilt }.distinct().sorted()
        assertEquals(tilts.size, counts.size)
        // Ordinate per distanza dall'orizzonte, le file non possono che accorciarsi.
        val byDistance = tilts.zip(counts).sortedBy { (tilt, _) -> kotlin.math.abs(tilt) }
        assertTrue(
            "Allontanandosi dall'orizzonte le file dovrebbero stringersi, invece sono $byDistance",
            byDistance.map { it.second }.zipWithNext().all { (near, far) -> far <= near },
        )
        assertTrue("La fila più inclinata dovrebbe essere la più corta", counts.last() < counts.max())
        assertEquals(counts.sum(), plan.waypoints.size)
        assertTrue("Il piano dovrebbe risparmiare scatti", plan.shotsSavedAtPoles > 0)
        assertTrue(plan.totalShots < plan.columns * plan.rows)
    }

    /** Vicino allo zenit uno scatto copre tutto il giro: di più è tempo buttato. */
    @Test
    fun `alla verticale basta uno scatto per fila`() {
        val plan = PanoramaPlanner.plan(
            centerPan = 89f,
            centerTilt = 31.5f,
            horizontalCoverage = 360f,
            verticalCoverage = 180f,
            overlapPercent = 20,
            zoomScale = 1,
            aspect = PhotoFrameAspect.FOUR_THREE,
            panLimits = limits(-57f, 235f, 48f),
            tiltLimits = limits(-57f, 120f, 31f),
        ).getOrThrow()

        val topTilt = plan.waypoints.maxOf { it.tilt }
        assertTrue("La fila più alta è a $topTilt gradi", topTilt > 80f)
        assertEquals(1, plan.waypoints.count { it.tilt == topTilt })
    }

    /** All'orizzonte non si stringe niente: il conto resta quello della griglia piena. */
    @Test
    fun `una panoramica orizzontale non perde scatti`() {
        val plan = PanoramaPlanner.plan(
            centerPan = 60f,
            centerTilt = 0f,
            horizontalCoverage = 270f,
            verticalCoverage = 0f,
            overlapPercent = 30,
            zoomScale = 1,
            aspect = PhotoFrameAspect.FOUR_THREE,
            panLimits = pan,
            tiltLimits = tilt,
        ).getOrThrow()

        assertEquals(0, plan.shotsSavedAtPoles)
        assertEquals(listOf(plan.columns), plan.columnsPerRow)
    }

    /**
     * Il punto di partenza è la specifica: venti millimetri equivalenti, cioè 81,74° in 4:3.
     *
     * Non è il campo visivo vero — il file da 37 MP ne misura 77,1, perché la camera ne ritaglia
     * un pezzo per la stabilizzazione — ma quello lo misura l'unione contro la gravità, e non va
     * scritto qui. Qui ci va il dato pubblicato, e questo test serve a non vederlo sostituire da
     * una misura fatta su una camera sola.
     */
    @Test
    fun `il campo visivo di partenza e' quello di catalogo`() {
        val quattroTerzi = LunaOptics.fieldOfView(1, PhotoFrameAspect.FOUR_THREE)
        assertEquals(81.74f, quattroTerzi.horizontalDegrees, 0.05f)
        assertEquals(20f, quattroTerzi.equivalentFocalMm, 0.01f)
    }
}
