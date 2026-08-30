package it.persoft.lunaultra

import it.persoft.lunaultra.timelapse.Interpolation
import it.persoft.lunaultra.timelapse.InterpolationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpolationTest {

    @Test
    fun `smoothStep rispetta gli estremi`() {
        assertEquals(0f, Interpolation.smoothStep(0f), 1e-6f)
        assertEquals(1f, Interpolation.smoothStep(1f), 1e-6f)
        assertEquals(0.5f, Interpolation.smoothStep(0.5f), 1e-6f)
    }

    @Test
    fun `smoothStep satura fuori dall'intervallo`() {
        assertEquals(0f, Interpolation.smoothStep(-3f), 1e-6f)
        assertEquals(1f, Interpolation.smoothStep(7f), 1e-6f)
    }

    @Test
    fun `smoothStep e monotona crescente`() {
        var previous = -1f
        for (i in 0..100) {
            val value = Interpolation.smoothStep(i / 100f)
            assertTrue("non monotona in $i", value >= previous)
            previous = value
        }
    }

    @Test
    fun `partenza e arresto sono piu lenti del centro`() {
        val start = Interpolation.speedFactor(InterpolationMode.SMOOTH, 0f)
        val middle = Interpolation.speedFactor(InterpolationMode.SMOOTH, 0.5f)
        val end = Interpolation.speedFactor(InterpolationMode.SMOOTH, 1f)
        assertEquals(0f, start, 1e-6f)
        assertEquals(0f, end, 1e-6f)
        assertEquals(1.5f, middle, 1e-6f)
    }

    @Test
    fun `la modalita lineare interpola in proporzione`() {
        val value = Interpolation.position(-30f, 30f, 0.25f, InterpolationMode.LINEAR)
        assertEquals(-15f, value, 1e-4f)
    }

    @Test
    fun `la punta al centro e' quella impostata, e la media resta uno`() {
        // Il tratto va percorso tutto nel tempo dato: qualunque sia la punta, l'area sotto la
        // curva di velocita' deve valere uno. E' il vincolo che lega punta e rampa.
        for (punta in listOf(1f, 1.2f, 1.5f, 2f)) {
            val centro = Interpolation.speedFactor(InterpolationMode.SMOOTH, 0.5f, punta)
            assertEquals("punta $punta", punta, centro, 1e-4f)
            assertEquals("estremo iniziale $punta", if (punta > 1f) 0f else 1f,
                Interpolation.speedFactor(InterpolationMode.SMOOTH, 0f, punta), 1e-4f)
            var area = 0.0
            val passi = 20_000
            for (i in 0 until passi) {
                area += Interpolation.speedFactor(InterpolationMode.SMOOTH, (i + 0.5f) / passi, punta) / passi
            }
            assertEquals("media $punta", 1.0, area, 1e-3)
        }
    }

    @Test
    fun `a punta uno la morbida coincide con la retta`() {
        for (t in listOf(0f, 0.13f, 0.5f, 0.87f, 1f)) {
            assertEquals(t, Interpolation.apply(InterpolationMode.SMOOTH, t, 1f), 1e-5f)
        }
    }

    @Test
    fun `la morbida arriva sempre a destinazione`() {
        for (punta in listOf(1f, 1.2f, 1.5f, 2f)) {
            assertEquals("fine $punta", 1f, Interpolation.apply(InterpolationMode.SMOOTH, 1f, punta), 1e-4f)
            assertEquals("inizio $punta", 0f, Interpolation.apply(InterpolationMode.SMOOTH, 0f, punta), 1e-4f)
        }
    }

    @Test
    fun `la modalita smooth parte e arriva sui waypoint`() {
        assertEquals(10f, Interpolation.position(10f, 90f, 0f, InterpolationMode.SMOOTH), 1e-4f)
        assertEquals(90f, Interpolation.position(10f, 90f, 1f, InterpolationMode.SMOOTH), 1e-4f)
    }
}
