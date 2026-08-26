package it.persoft.lunaultra

import it.persoft.lunaultra.stitch.InstaTrailer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La coda inerziale delle foto, che è il righello esterno di tutto il resto.
 *
 * I numeri di questi test non sono inventati: vengono dalle nove foto della spiaggia. A
 * inclinazione comandata zero l'accelerometro medio vale (488, 0.6, -4062) in unità del sensore,
 * cioè la camera guardava in su di 6,85° mentre diceva zero — il «mare curvo», misurato dalla
 * gravità invece che dedotto dal mare.
 */
class InstaTrailerTest {

    /** Una coda finta con la stessa forma di quella vera: firma, dimensione, traccia a 1 kHz. */
    private fun trailer(
        forward: Int,
        side: Int,
        up: Int,
        samples: Int = 120,
        stepMicros: Long = 1_000L,
        junkBefore: Int = 137,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Prima della traccia c'è dell'altro: nella coda vera sono i parametri di scatto.
        repeat(junkBefore) { out.write(0x2A) }
        var stamp = 132_316_213L
        repeat(samples) {
            for (i in 0 until 8) out.write(((stamp shr (8 * i)) and 0xFF).toInt())
            listOf(forward, side, up, 3, -4, 7).forEach { value ->
                val biased = value + 32768
                out.write(biased and 0xFF)
                out.write((biased shr 8) and 0xFF)
            }
            stamp += stepMicros
        }
        return out.toByteArray()
    }

    @Test
    fun `dalla gravita' escono beccheggio e rollio veri`() {
        val a = InstaTrailer.attitudeOf(trailer(forward = 488, side = 1, up = -4062))
        assertNotNull(a)
        assertEquals(6.85f, a!!.pitchDegrees, 0.02f)
        assertEquals(0.014f, a.rollDegrees, 0.02f)
        assertEquals(120, a.samples)
        assertEquals(4091f, a.magnitude, 2f)
    }

    /** Le tre file della panoramica vera: comandate -32, 0, +32; fatte tutt'altro. */
    @Test
    fun `le tre file della spiaggia escono come le ha misurate la camera`() {
        val bassa = InstaTrailer.attitudeOf(trailer(-2181, -2, -3470))!!
        val media = InstaTrailer.attitudeOf(trailer(488, 1, -4062))!!
        val alta = InstaTrailer.attitudeOf(trailer(2983, -4, -2788))!!
        assertEquals(-32.15f, bassa.pitchDegrees, 0.05f)
        assertEquals(6.85f, media.pitchDegrees, 0.05f)
        assertEquals(46.94f, alta.pitchDegrees, 0.05f)
        // Comandati 32,017 di passo; fatti quaranta abbondanti, e nemmeno uguali fra loro.
        assertTrue(media.pitchDegrees - bassa.pitchDegrees > 38f)
        assertTrue(alta.pitchDegrees - media.pitchDegrees > 39f)
    }

    /** Senza una traccia lunga abbastanza non si inventa una misura. */
    @Test
    fun `una coda senza traccia non da' nessun assetto`() {
        assertNull(InstaTrailer.attitudeOf(ByteArray(9000) { 0x2A }))
        assertNull(InstaTrailer.attitudeOf(trailer(488, 1, -4062, samples = 20)))
    }

    /** Una marca temporale che non scorre a mille al secondo non è la traccia inerziale. */
    @Test
    fun `una traccia con il passo sbagliato viene scartata`() {
        assertNull(InstaTrailer.attitudeOf(trailer(488, 1, -4062, stepMicros = 40_000L)))
    }
}
