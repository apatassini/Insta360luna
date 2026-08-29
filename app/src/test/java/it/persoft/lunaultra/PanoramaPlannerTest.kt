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
    fun `una panoramica che da qui non ci starebbe viene ricentrata invece che rifiutata`() {
        // Da -50° una copertura di 270° sborderebbe dal fine corsa: prima era un rifiuto con
        // l'invito a spostarsi a mano, che su una sferica non vuol dire niente.
        val plan = PanoramaPlanner.plan(
            centerPan = -50f,
            centerTilt = 0f,
            horizontalCoverage = 270f,
            verticalCoverage = 0f,
            overlapPercent = 30,
            zoomScale = 3,
            aspect = PhotoFrameAspect.FOUR_THREE,
            panLimits = pan,
            tiltLimits = tilt,
        ).getOrThrow()

        assertTrue("il centro si è spostato", plan.recentered)
        assertTrue("si è spostato verso l'interno della corsa", plan.centerPan > -50f)
        // La copertura chiesta resta intera: si è solo scelto da dove partire.
        assertEquals(270f, plan.horizontalCoverage, 0.5f)
        val pans = plan.waypoints.map { it.pan }
        assertTrue("nessuno scatto fuori dal fine corsa", pans.all { it >= pan.minimumDeg - 0.01f })
        assertTrue("nessuno scatto fuori dal fine corsa", pans.all { it <= pan.maximumDeg + 0.01f })
    }

    @Test
    fun `quando la corsa non basta la copertura si riduce e lo dice`() {
        val plan = PanoramaPlanner.plan(
            centerPan = 0f,
            centerTilt = 0f,
            // 360° con una corsa di 292°: nessun centro può bastare, e l'unica cosa onesta è
            // prendere tutta la corsa e dichiarare quanto si è ottenuto davvero.
            horizontalCoverage = 360f,
            verticalCoverage = 0f,
            overlapPercent = 30,
            zoomScale = 3,
            aspect = PhotoFrameAspect.FOUR_THREE,
            panLimits = pan,
            tiltLimits = tilt,
        ).getOrThrow()

        assertTrue("la copertura ottenuta è minore di quella chiesta", plan.horizontalCoverage < 360f)
        assertEquals("centro in mezzo alla corsa", 89f, plan.centerPan, 0.5f)
        assertTrue("lo dichiara", plan.warning.orEmpty().contains("orizzontale ridotta"))
        val pans = plan.waypoints.map { it.pan }
        assertTrue(pans.all { it >= pan.minimumDeg - 0.01f && it <= pan.maximumDeg + 0.01f })
    }

    /**
     * La prova che conta su una sferica: che la sfera sia coperta davvero.
     *
     * Non guarda come il pianificatore ha deciso — quello cambierebbe il test insieme al codice
     * — ma campiona la fascia coperta e chiede, per ogni punto, se almeno un fotogramma ci
     * arriva. Il modello del fotogramma e' quello del pianificatore stesso: copre le latitudini
     * del proprio campo verticale, e a ogni latitudine copre `campo / cos(lat)` gradi di
     * longitudine, perche' i meridiani si stringono verso il polo.
     *
     * Con i numeri veri del 29 agosto — corsa -57..235 e -57..120, zoom 1x, ritaglio misurato
     * 0,921 — questo campionamento trovava scoperti i meridiani appena sopra i 40 gradi: la
     * fila a 50 gradi aveva quattro scatti distanti 93 dove ne copriva 85.
     */
    @Test
    fun `la sfera risulta coperta davvero, campionandola`() {
        val crop = 0.9214f
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
            frameCropFactor = crop,
        ).getOrThrow()

        val fov = LunaOptics.fieldOfView(1, PhotoFrameAspect.FOUR_THREE, crop)
        val bottom = plan.centerTilt - plan.verticalCoverage / 2f
        val top = plan.centerTilt + plan.verticalCoverage / 2f
        val scoperti = mutableListOf<String>()

        var lat = bottom + 1f
        while (lat <= top - 1f) {
            var lon = 0f
            while (lon < 360f) {
                val coperto = plan.waypoints.any { w ->
                    val dLat = kotlin.math.abs(lat - w.tilt)
                    if (dLat > fov.verticalDegrees / 2f) return@any false
                    val cos = kotlin.math.cos(Math.toRadians(lat.toDouble())).toFloat()
                        .coerceAtLeast(0.02f)
                    val mezzaLarghezza = (fov.horizontalDegrees / 2f) / cos
                    val dLon = kotlin.math.abs(((lon - w.pan) % 360f + 540f) % 360f - 180f)
                    dLon <= mezzaLarghezza
                }
                if (!coperto) scoperti += "lat %.0f lon %.0f".format(lat, lon)
                lon += 2f
            }
            lat += 2f
        }

        assertTrue(
            "Restano ${scoperti.size} punti senza foto, per esempio ${scoperti.take(5)}",
            scoperti.isEmpty(),
        )
    }

    /**
     * La cucitura del giro e' una giunzione come le altre e vuole la sua sovrapposizione.
     *
     * Con l'arco dei centri pari a `360 - campo`, il primo e l'ultimo scatto si sfioravano: zero
     * sovrapposizione, e su un'equirettangolare la fessura si vede ai due bordi dell'immagine.
     */
    @Test
    fun `la cucitura del giro nasce con sovrapposizione, non a filo`() {
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

        val fov = LunaOptics.fieldOfView(1, PhotoFrameAspect.FOUR_THREE)
        val fessura = 360f - plan.horizontalCenterSpan
        assertTrue(
            "La cucitura resta larga %.1f° contro un fotogramma da %.1f°".format(fessura, fov.horizontalDegrees),
            fessura < fov.horizontalDegrees - 5f,
        )
        assertTrue("i centri restano dentro la corsa", plan.waypoints.all { it.pan in -57f..235f })
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
