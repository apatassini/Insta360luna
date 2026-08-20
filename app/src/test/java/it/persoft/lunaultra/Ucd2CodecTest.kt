package it.persoft.lunaultra

import it.persoft.lunaultra.protocol.FrameAssembler
import it.persoft.lunaultra.protocol.ProtoWriter
import it.persoft.lunaultra.protocol.Ucd2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ucd2CodecTest {

    /** Costruisce una risposta come la manda la camera: direzione 0x03 invece di 0x02. */
    private fun response(code: Int, requestId: Int, body: ByteArray): ByteArray {
        val raw = ByteArray(Ucd2.COMMAND_HEADER_SIZE + body.size)
        Ucd2.putShortLe(raw, 0, code)
        raw[2] = Ucd2.DIRECTION_RESPONSE.toByte()
        Ucd2.putShortLe(raw, 3, requestId)
        Ucd2.putIntLe(raw, 5, Ucd2.COMMAND_CONSTANT)
        body.copyInto(raw, Ucd2.COMMAND_HEADER_SIZE)
        return Ucd2.frame(Ucd2.TYPE_FILE, sequence = 9, body = raw)
    }

    @Test
    fun `un comando ha l'intestazione UCD2 attesa`() {
        val body = ProtoWriter().int32(1, 48).toByteArray()
        val bytes = Ucd2.command(sequence = 0x24, code = 8, requestId = 1, body = body)

        assertEquals(16 + Ucd2.COMMAND_HEADER_SIZE + body.size, bytes.size)
        assertEquals('U'.code.toByte(), bytes[0])
        assertEquals('C'.code.toByte(), bytes[1])
        assertEquals('D'.code.toByte(), bytes[2])
        assertEquals('2'.code.toByte(), bytes[3])
        assertEquals(Ucd2.VERSION.toByte(), bytes[4])
        assertEquals(Ucd2.FLAGS.toByte(), bytes[5])
        assertEquals(Ucd2.TYPE_FILE.toByte(), bytes[6])
        assertEquals(0x24.toByte(), bytes[7])
        // La lunghezza dichiarata copre solo il corpo, non l'header né il checksum.
        assertEquals(Ucd2.COMMAND_HEADER_SIZE + body.size, Ucd2.intLe(bytes, 8))
        assertEquals(8, Ucd2.shortLe(bytes, 12))
        assertEquals(Ucd2.DIRECTION_REQUEST.toByte(), bytes[14])
        assertEquals(1, Ucd2.shortLe(bytes, 15))
    }

    @Test
    fun `il checksum copre header e corpo e finisce in coda`() {
        val bytes = Ucd2.command(sequence = 1, code = 8, requestId = 2, body = byteArrayOf(1, 2, 3))
        val end = bytes.size - Ucd2.TRAILER_SIZE
        assertEquals(Ucd2.checksum(bytes, 0, end), Ucd2.intLe(bytes, end))
    }

    @Test
    fun `il frame di handshake ha lunghezza zero e il token costante`() {
        val hello = Ucd2.hello(sequence = 0x24)
        assertEquals(16, hello.size)
        assertEquals(Ucd2.TYPE_STREAM.toByte(), hello[6])
        assertEquals(0, Ucd2.intLe(hello, 8))
        assertEquals(0xf6.toByte(), hello[12])
        assertEquals(0xcc.toByte(), hello[13])
        assertEquals(0x4f.toByte(), hello[14])
        assertEquals(0x09.toByte(), hello[15])
    }

    @Test
    fun `una risposta viene decodificata in codice requestId e corpo`() {
        val body = ProtoWriter().int32(1, 77).toByteArray()
        val assembler = FrameAssembler()
        val bytes = response(code = 8, requestId = 42, body = body)

        assembler.append(bytes, bytes.size)
        val frames = assembler.drain()

        assertEquals(1, frames.size)
        assertEquals(8, frames[0].code)
        assertEquals(42, frames[0].requestId)
        assertEquals(Ucd2.DIRECTION_RESPONSE, frames[0].direction)
        assertTrue(body.contentEquals(frames[0].payload))
    }

    @Test
    fun `un frame incompleto non viene consumato`() {
        val assembler = FrameAssembler()
        val bytes = response(code = 8, requestId = 1, body = ByteArray(20))
        val head = bytes.copyOfRange(0, bytes.size - 4)

        assembler.append(head, head.size)
        assertTrue(assembler.drain().isEmpty())
    }

    @Test
    fun `l'assembler ricompone i frame spezzati fra due letture`() {
        val assembler = FrameAssembler()
        val first = response(code = 10, requestId = 1, body = byteArrayOf(1, 2, 3))
        val second = response(code = 11, requestId = 2, body = byteArrayOf(4))
        val stream = first + second

        val head = stream.copyOfRange(0, first.size - 1)
        assembler.append(head, head.size)
        assertTrue(assembler.drain().isEmpty())

        val tail = stream.copyOfRange(first.size - 1, stream.size)
        assembler.append(tail, tail.size)
        val frames = assembler.drain()

        assertEquals(2, frames.size)
        assertEquals(10, frames[0].code)
        assertEquals(11, frames[1].code)
    }

    @Test
    fun `dopo byte spuri l'assembler si riaggancia al magic`() {
        val assembler = FrameAssembler()
        val garbage = ByteArray(3) { 0xFF.toByte() }
        val valid = response(code = 42, requestId = 5, body = byteArrayOf(7))
        val stream = garbage + valid

        assembler.append(stream, stream.size)
        var invalidReports = 0
        val frames = assembler.drain { invalidReports++ }

        assertEquals(1, frames.size)
        assertEquals(42, frames[0].code)
        assertTrue(invalidReports > 0)
    }

    @Test
    fun `i frame media espongono il sottoflusso senza la loro intestazione`() {
        val annexB = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x40, 0x01)
        val mediaBody = ByteArray(Ucd2.MEDIA_HEADER_SIZE) + annexB
        mediaBody[0] = Ucd2.MEDIA_VIDEO.toByte()
        val bytes = Ucd2.frame(Ucd2.TYPE_MEDIA, sequence = 7, body = mediaBody)

        val assembler = FrameAssembler()
        assembler.append(bytes, bytes.size)
        val frames = assembler.drain()

        assertEquals(1, frames.size)
        assertEquals(Ucd2.MEDIA_VIDEO, frames[0].substream)
        assertTrue(annexB.contentEquals(frames[0].payload))
    }

    @Test
    fun `i codici delle notifiche sono riconosciuti come tali`() {
        val notification = response(code = 8208, requestId = 0, body = byteArrayOf(0x08, 0x01))
        val assembler = FrameAssembler()
        assembler.append(notification, notification.size)

        val frame = assembler.drain().single()
        assertTrue(frame.isNotification)
    }
}
