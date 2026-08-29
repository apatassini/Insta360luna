package it.persoft.lunaultra

import it.persoft.lunaultra.stitch.GPanoXmp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class GPanoXmpTest {

    private val xmpHeader = "http://ns.adobe.com/xap/1.0/" + Char(0)

    @Test
    fun `una sferica intera occupa tutta la sfera`() {
        val p = GPanoXmp.placement(
            imageWidth = 36000,
            imageHeight = 18000,
            centerTiltDegrees = 0f,
            verticalDegrees = 180f,
            pixelsPerDegree = 100f,
        )!!

        assertEquals(36000, p.fullWidth)
        assertEquals(18000, p.fullHeight)
        assertEquals(0, p.left)
        assertEquals(0, p.top)
    }

    @Test
    fun `una sferica parziale si colloca dove guarda davvero`() {
        // I numeri veri di una panoramica del 29 agosto: 24000x9958 a 66,7 px per grado, cioè
        // 360° in orizzontale e 149° in verticale. Il bordo alto sta a +60°.
        val ppd = 66.7f
        val p = GPanoXmp.placement(
            imageWidth = 24000,
            imageHeight = 9958,
            centerTiltDegrees = 60f - 149.3f / 2f,
            verticalDegrees = 149.3f,
            pixelsPerDegree = ppd,
        )!!

        assertEquals((180f * ppd).toDouble(), p.fullHeight.toDouble(), 1.0)
        // Dallo zenit al bordo alto ci sono 30°, che a questa scala sono 2001 pixel.
        assertEquals(2001.0, p.top.toDouble(), 2.0)
        assertTrue("la parte bassa resta dentro la sfera", p.top + p.croppedHeight <= p.fullHeight)
    }

    @Test
    fun `il pacchetto porta i campi che accendono il visore`() {
        val p = GPanoXmp.placement(24000, 9958, 0f, 149.3f, 66.7f)!!
        val packet = GPanoXmp.packet(p)

        assertTrue(packet.contains("GPano:UsePanoramaViewer=\"True\""))
        assertTrue(packet.contains("GPano:ProjectionType=\"equirectangular\""))
        assertTrue(packet.contains("GPano:FullPanoWidthPixels=\"${p.fullWidth}\""))
        assertTrue(packet.contains("GPano:CroppedAreaTopPixels=\"${p.top}\""))
        assertTrue(packet.contains("<?xpacket end=\"w\"?>"))
    }

    @Test
    fun `l'XMP entra dopo le intestazioni e prima del corpo`() {
        val jpeg = jpegWith(app0 = true, existingXmp = false)
        val out = ByteArrayOutputStream()

        val written = GPanoXmp.copyInserting(ByteArrayInputStream(jpeg), out, "<x>ciao</x>")
        val result = out.toByteArray()

        assertTrue("qualcosa è stato scritto", written > 0)
        // SOI, poi l'APP0 che c'era, poi il nostro APP1, poi il resto.
        assertEquals(0xFF.toByte(), result[0])
        assertEquals(0xD8.toByte(), result[1])
        assertEquals(0xE0.toByte(), result[3])
        val app1 = indexOfMarker(result, 0xE1)
        val dqt = indexOfMarker(result, 0xDB)
        assertTrue("l'APP1 c'è", app1 > 0)
        assertTrue("viene prima del corpo", app1 < dqt)
        assertTrue("porta l'intestazione XMP", String(result, Charsets.ISO_8859_1).contains(xmpHeader))
        // Il corpo non è stato toccato: finisce ancora con EOI.
        assertEquals(0xFF.toByte(), result[result.size - 2])
        assertEquals(0xD9.toByte(), result[result.size - 1])
    }

    @Test
    fun `un XMP gia presente viene sostituito, non affiancato`() {
        val jpeg = jpegWith(app0 = true, existingXmp = true)
        val out = ByteArrayOutputStream()

        GPanoXmp.copyInserting(ByteArrayInputStream(jpeg), out, "<x>nuovo</x>")
        val testo = String(out.toByteArray(), Charsets.ISO_8859_1)

        assertEquals("un solo pacchetto", 1, testo.split(xmpHeader).size - 1)
        assertTrue(testo.contains("<x>nuovo</x>"))
        assertTrue("il vecchio è sparito", !testo.contains("<x>vecchio</x>"))
    }

    @Test
    fun `quello che non e' un JPEG non viene toccato`() {
        val roba = byteArrayOf(1, 2, 3, 4, 5)
        val out = ByteArrayOutputStream()

        val written = GPanoXmp.copyInserting(ByteArrayInputStream(roba), out, "<x/>")

        assertEquals(0, written)
        assertEquals(roba.toList(), out.toByteArray().toList())
    }

    private fun indexOfMarker(data: ByteArray, marker: Int): Int {
        for (i in 0 until data.size - 1) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == marker.toByte()) return i
        }
        return -1
    }

    /** Un JPEG minimo ma valido nella struttura: SOI, intestazioni, un pezzo di corpo, EOI. */
    private fun jpegWith(app0: Boolean, existingXmp: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        if (app0) {
            val body = "JFIF".toByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0)
            segment(out, 0xE0, body)
        }
        if (existingXmp) {
            segment(out, 0xE1, (xmpHeader + "<x>vecchio</x>").toByteArray(Charsets.ISO_8859_1))
        }
        segment(out, 0xDB, ByteArray(8) { it.toByte() })
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    private fun segment(out: ByteArrayOutputStream, marker: Int, body: ByteArray) {
        val length = body.size + 2
        out.write(0xFF)
        out.write(marker)
        out.write((length shr 8) and 0xFF)
        out.write(length and 0xFF)
        out.write(body)
    }
}


