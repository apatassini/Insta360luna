package it.persoft.lunaultra

import it.persoft.lunaultra.timelapse.TimelapseSequence
import it.persoft.lunaultra.timelapse.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelapseSequenceTest {

    private fun waypoints(count: Int) = List(count) { index ->
        Waypoint(id = "w$index", name = ('A' + index).toString(), pan = index * 30f, tilt = 0f, durationToNextSeconds = 10f)
    }

    @Test
    fun `servono almeno due punti`() {
        assertFalse(TimelapseSequence(waypoints = waypoints(1)).isRunnable)
        assertTrue(TimelapseSequence(waypoints = waypoints(2)).isRunnable)
    }

    @Test
    fun `la durata totale viene divisa fra i tratti`() {
        val sequence = TimelapseSequence(
            waypoints = waypoints(4),
            totalDurationSeconds = 90f,
            useTotalDuration = true,
        )
        val durations = sequence.legDurations()
        assertEquals(3, durations.size)
        durations.forEach { assertEquals(30f, it, 1e-4f) }
        assertEquals(90f, sequence.effectiveTotalSeconds(), 1e-4f)
    }

    @Test
    fun `le durate per tratto vengono rispettate`() {
        val sequence = TimelapseSequence(
            waypoints = waypoints(3),
            useTotalDuration = false,
        )
        assertEquals(listOf(10f, 10f), sequence.legDurations())
        assertEquals(20f, sequence.effectiveTotalSeconds(), 1e-4f)
    }

    @Test
    fun `gli scatti stimati seguono l'intervallo`() {
        val sequence = TimelapseSequence(
            waypoints = waypoints(2),
            totalDurationSeconds = 60f,
            intervalSeconds = 2f,
            useTotalDuration = true,
        )
        assertEquals(30, sequence.estimatedShots())
    }

    @Test
    fun `una sequenza senza tratti non ha durata`() {
        val sequence = TimelapseSequence(waypoints = waypoints(1))
        assertTrue(sequence.legDurations().isEmpty())
        assertEquals(0f, sequence.effectiveTotalSeconds(), 1e-4f)
    }
}
