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
    fun `la modalita smooth parte e arriva sui waypoint`() {
        assertEquals(10f, Interpolation.position(10f, 90f, 0f, InterpolationMode.SMOOTH), 1e-4f)
        assertEquals(90f, Interpolation.position(10f, 90f, 1f, InterpolationMode.SMOOTH), 1e-4f)
    }
}
