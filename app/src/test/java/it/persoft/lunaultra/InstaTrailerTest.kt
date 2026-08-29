package it.persoft.lunaultra

import it.persoft.lunaultra.protocol.ProtoWriter
import it.persoft.lunaultra.stitch.InstaTrailer
import java.io.ByteArrayOutputStream
import java.io.File
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

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.u32(value: Int) {
        repeat(4) { write((value shr (8 * it)) and 0xFF) }
    }

    private fun fileWithTrailer(imu: ByteArray): File {
        val metadata = ProtoWriter().message(65) { int32(2, 2_000) }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(metadata)
        out.u16(0x0101)
        out.u32(metadata.size)
        out.write(imu)
        out.u16(0x0300)
        out.u32(imu.size)
        // Fra l'ultimo piede della catena e la firma ci sono 72 byte. La lettura dei blocchi
        // parte infatti a 78 byte dalla fine: sei di piede più questi settantadue.
        repeat(32) { out.write(0) }
        out.u32(out.size() + 40)
        out.u32(3)
        out.write("8db42d694ccc418790edff439fe026bf".toByteArray(Charsets.US_ASCII))
        return File.createTempFile("luna-gyro-", ".mp4").apply { writeBytes(out.toByteArray()) }
    }

    /** Una coda finta con la stessa forma di quella vera: firma, dimensione, traccia a 1 kHz. */
    private fun trailer(
        forward: Int,
        side: Int,
        up: Int,
        samples: Int = 120,
        stepMicros: Long = 1_000L,
        junkBefore: Int = 137,
        gyro: (Int) -> Triple<Int, Int, Int> = { Triple(3, -4, 7) },
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Prima della traccia c'è dell'altro: nella coda vera sono i parametri di scatto.
        repeat(junkBefore) { out.write(0x2A) }
        var stamp = 132_316_213L
        repeat(samples) { indice ->
            for (i in 0 until 8) out.write(((stamp shr (8 * i)) and 0xFF).toInt())
            val movimento = gyro(indice)
            listOf(forward, side, up, movimento.first, movimento.second, movimento.third).forEach { value ->
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

    @Test
    fun `il giroscopio continuo misura il pan in entrambi i versi`() {
        val data = trailer(488, 1, -4062, samples = 2_200) { indice ->
            when (indice) {
                in 800 until 1_300 -> Triple(10, -5, 4 + 1_638)
                in 1_500 until 2_000 -> Triple(10, -5, 4 - 1_638)
                else -> Triple(10, -5, 4)
            }
        }
        val track = InstaTrailer.gyroTrackOf(data, fullScaleDegreesPerSecond = 2_000)
        assertNotNull(track)
        assertEquals(2_200, track!!.numeroCampioni)
        assertEquals(2.199, track.durataSecondi, 0.001)

        val bias = track.stimaBias(0.7)
        assertEquals(10.0, bias.asse0, 0.01)
        assertEquals(-5.0, bias.asse1, 0.01)
        assertEquals(4.0, bias.asse2, 0.01)

        val positivo = track.integra(0.8, 1.3, bias)
        assertEquals(0.0, positivo.asse0Gradi, 0.02)
        assertEquals(0.0, positivo.asse1Gradi, 0.02)
        assertEquals(50.0, positivo.asse2Gradi, 0.1)
        assertEquals(50.0, positivo.angoloGradi, 0.1)

        val negativo = track.integra(1.5, 2.0, bias)
        assertEquals(0.0, negativo.asse0Gradi, 0.02)
        assertEquals(0.0, negativo.asse1Gradi, 0.02)
        assertEquals(-50.0, negativo.asse2Gradi, 0.1)
        assertEquals(50.0, negativo.angoloGradi, 0.1)

        val movimenti = track.trovaDueMovimenti(0.7)
        assertEquals(0.8, movimenti.andata.inizioSecondi, 0.01)
        assertEquals(50.0, movimenti.andata.rotazione.angoloGradi, 0.1)
        assertEquals(1.5, movimenti.ritorno.inizioSecondi, 0.01)
        assertEquals(50.0, movimenti.ritorno.rotazione.angoloGradi, 0.1)
        assertTrue(movimenti.sogliaGradiSecondo < 1.0)
    }

    @Test
    fun `la traccia viene letta dalla catena completa del file`() {
        val file = fileWithTrailer(trailer(488, 1, -4062, samples = 1_000))
        try {
            val track = InstaTrailer.readGyroTrack(file)
            assertNotNull(track)
            assertEquals(1_000, track!!.numeroCampioni)
            assertEquals(2_000, track.fondoScalaGradiSecondo)
            assertEquals(0.999, track.durataSecondi, 0.001)
        } finally {
            file.delete()
        }
    }
}
