package it.persoft.lunaultra

import it.persoft.lunaultra.protocol.FrameAssembler
import it.persoft.lunaultra.protocol.ProtoWriter
import it.persoft.lunaultra.protocol.Ucd2Codec
import it.persoft.lunaultra.protocol.Ucd2Frame
import it.persoft.lunaultra.protocol.Ucd2Layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ucd2CodecTest {

    private val codec = Ucd2Codec()

    @Test
    fun `encode e decode sono simmetrici`() {
        val payload = ProtoWriter().int32(1, 90).toByteArray()
        val frame = Ucd2Frame(commandId = 0x1234, sequence = 7, type = Ucd2Frame.TYPE_REQUEST, payload = payload)
        val bytes = codec.encode(frame)

        assertEquals(codec.layout.headerSize + payload.size, bytes.size)
        val result = codec.decode(bytes, 0, bytes.size)
        assertTrue(result is Ucd2Codec.DecodeResult.Frame)
        val decoded = (result as Ucd2Codec.DecodeResult.Frame).frame
        assertEquals(frame, decoded)
    }

    @Test
    fun `un frame incompleto non viene consumato`() {
        val bytes = codec.encode(Ucd2Frame(commandId = 1, sequence = 1, payload = ByteArray(20)))
        assertEquals(null, codec.decode(bytes, 0, bytes.size - 4))
    }

    @Test
    fun `il layout compatto resta simmetrico`() {
        val compact = Ucd2Codec(Ucd2Layout.COMPACT)
        val frame = Ucd2Frame(commandId = 99, sequence = 0, type = 0, errorCode = 0, payload = byteArrayOf(1, 2, 3))
        val decoded = compact.decode(compact.encode(frame), 0, compact.layout.headerSize + 3)
        assertTrue(decoded is Ucd2Codec.DecodeResult.Frame)
        assertEquals(99, (decoded as Ucd2Codec.DecodeResult.Frame).frame.commandId)
    }

    @Test
    fun `l'assembler ricompone i frame spezzati fra due letture`() {
        val assembler = FrameAssembler(codec)
        val first = codec.encode(Ucd2Frame(commandId = 10, sequence = 1, payload = byteArrayOf(1, 2, 3)))
        val second = codec.encode(Ucd2Frame(commandId = 11, sequence = 2, payload = byteArrayOf(4)))
        val stream = first + second

        val head = stream.copyOfRange(0, first.size - 1)
        assembler.append(head, head.size)
        assertTrue(assembler.drain().isEmpty())

        val tail = stream.copyOfRange(first.size - 1, stream.size)
        assembler.append(tail, tail.size)
        val frames = assembler.drain()

        assertEquals(2, frames.size)
        assertEquals(10, frames[0].commandId)
        assertEquals(11, frames[1].commandId)
    }

    @Test
    fun `un header con versione sbagliata viene rifiutato`() {
        val bytes = codec.encode(Ucd2Frame(commandId = 3, sequence = 1, payload = byteArrayOf(9)))
        bytes[codec.layout.versionOffset] = 0x7F
        val result = codec.decode(bytes, 0, bytes.size)
        assertTrue(result is Ucd2Codec.DecodeResult.Invalid)
    }

    @Test
    fun `dopo byte spuri l'assembler si risincronizza`() {
        val assembler = FrameAssembler(codec)
        val garbage = ByteArray(3) { 0xFF.toByte() }
        val valid = codec.encode(Ucd2Frame(commandId = 42, sequence = 5, payload = byteArrayOf(7)))
        val stream = garbage + valid

        assembler.append(stream, stream.size)
        var invalidReports = 0
        val frames = assembler.drain { invalidReports++ }

        assertEquals(1, frames.size)
        assertEquals(42, frames[0].commandId)
        assertTrue(invalidReports > 0)
    }
}
