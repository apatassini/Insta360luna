package it.persoft.lunaultra

import it.persoft.lunaultra.preview.AnnexB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il riconoscimento dei NAL decide se l'anteprima parte o resta nera: alimentare il decoder
 * prima di un keyframe, o con il codec sbagliato, non dà errori — dà uno schermo vuoto.
 */
class AnnexBTest {

    private fun nal(type: Int, h265: Boolean = false, fourByte: Boolean = true): ByteArray {
        val start = if (fourByte) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)
        val header = if (h265) {
            byteArrayOf(((type shl 1) and 0x7E).toByte(), 0x01)
        } else {
            byteArrayOf((type and 0x1F).toByte())
        }
        return start + header + byteArrayOf(0x10, 0x20)
    }

    @Test
    fun `trova gli start code a tre e a quattro byte`() {
        val stream = nal(7, fourByte = false) + nal(5, fourByte = true)
        val positions = AnnexB.startCodes(stream)

        assertEquals(2, positions.size)
        assertEquals(0, positions[0])
        assertEquals(3, AnnexB.startCodeLength(stream, positions[0]))
        assertEquals(4, AnnexB.startCodeLength(stream, positions[1]))
    }

    @Test
    fun `riconosce H264 dai set di parametri`() {
        val stream = nal(7) + nal(8) + nal(5)
        assertEquals(AnnexB.Codec.H264, AnnexB.detectCodec(stream))
    }

    @Test
    fun `riconosce H265 dai set di parametri`() {
        val stream = nal(32, h265 = true) + nal(33, h265 = true)
        assertEquals(AnnexB.Codec.H265, AnnexB.detectCodec(stream))
    }

    @Test
    fun `un frammento senza set di parametri non decide il codec`() {
        // Solo un NAL di dati: non basta a distinguere i due formati.
        assertNull(AnnexB.detectCodec(nal(1)))
    }

    @Test
    fun `un IDR e un keyframe, un frame normale no`() {
        assertTrue(AnnexB.containsKeyframe(nal(5), AnnexB.Codec.H264))
        assertFalse(AnnexB.containsKeyframe(nal(1), AnnexB.Codec.H264))
    }

    @Test
    fun `un IRAP H265 e un keyframe`() {
        assertTrue(AnnexB.containsKeyframe(nal(19, h265 = true), AnnexB.Codec.H265))
        assertFalse(AnnexB.containsKeyframe(nal(1, h265 = true), AnnexB.Codec.H265))
    }

    @Test
    fun `un buffer senza start code non produce nulla`() {
        val noise = byteArrayOf(1, 2, 3, 4, 5)
        assertTrue(AnnexB.startCodes(noise).isEmpty())
        assertNull(AnnexB.detectCodec(noise))
    }
}
