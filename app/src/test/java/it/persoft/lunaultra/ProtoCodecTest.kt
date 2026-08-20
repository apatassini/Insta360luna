package it.persoft.lunaultra

import it.persoft.lunaultra.protocol.Hex
import it.persoft.lunaultra.protocol.ProtoField
import it.persoft.lunaultra.protocol.ProtoReader
import it.persoft.lunaultra.protocol.ProtoWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoCodecTest {

    @Test
    fun `varint e string sopravvivono al roundtrip`() {
        val bytes = ProtoWriter()
            .int32(1, 42)
            .string(2, "Luna")
            .bool(3, true)
            .toByteArray()

        val reader = ProtoReader(bytes)
        assertEquals(42, reader.intOrNull(1))
        assertEquals("Luna", reader.stringOrNull(2))
        assertEquals(1, reader.intOrNull(3))
    }

    @Test
    fun `sint32 conserva i valori negativi`() {
        val bytes = ProtoWriter().sint32(1, -137).toByteArray()
        val field = ProtoReader(bytes).fields().first() as ProtoField.VarInt
        assertEquals(-137, field.asSInt)
    }

    @Test
    fun `i messaggi annidati sono raggiungibili per path`() {
        val bytes = ProtoWriter()
            .message(2) {
                int32(1, 7)
                float(3, 1.5f)
            }
            .toByteArray()

        val reader = ProtoReader(bytes)
        assertEquals(7, reader.intOrNull(2, 1))
        assertEquals(1.5f, reader.floatOrNull(2, 3)!!, 1e-6f)
        assertNull(reader.intOrNull(9))
    }

    @Test
    fun `un payload troncato non fa esplodere il reader`() {
        val bytes = ProtoWriter().string(1, "abcdefgh").toByteArray()
        val truncated = bytes.copyOf(bytes.size - 3)
        val fields = ProtoReader(truncated).fields()
        assertTrue(fields.isEmpty())
    }

    @Test
    fun `hex decodifica i formati piu comuni`() {
        val expected = byteArrayOf(0x0A, 0x1B, 0x2C)
        assertTrue(expected.contentEquals(Hex.decodeOrNull("0A1B2C")!!))
        assertTrue(expected.contentEquals(Hex.decodeOrNull("0a 1b 2c")!!))
        assertTrue(expected.contentEquals(Hex.decodeOrNull("0x0a,0x1b,0x2c")!!))
        assertNull(Hex.decodeOrNull("0A1"))
        assertNull(Hex.decodeOrNull("zz"))
    }
}
