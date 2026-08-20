package it.persoft.lunaultra.protocol

import java.io.ByteArrayOutputStream

/**
 * Encoder minimale del wire format protobuf.
 *
 * Il protocollo della camera usa protobuf ma gli schemi `.proto` ufficiali non sono pubblici:
 * comporre i messaggi campo per campo permette di adattare i payload durante il reverse
 * engineering senza rigenerare classi.
 */
class ProtoWriter {

    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, WIRE_VARINT)
        writeVarint(value)
        return this
    }

    fun int32(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = varint(field, if (value) 1L else 0L)

    /** Zig-zag (`sint32`): indispensabile per gli angoli negativi di pan/tilt. */
    fun sint32(field: Int, value: Int): ProtoWriter {
        val zigzag = ((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL
        return varint(field, zigzag)
    }

    fun float(field: Int, value: Float): ProtoWriter {
        tag(field, WIRE_FIXED32)
        val bits = java.lang.Float.floatToIntBits(value)
        for (i in 0 until 4) out.write((bits ushr (8 * i)) and 0xFF)
        return this
    }

    fun double(field: Int, value: Double): ProtoWriter {
        tag(field, WIRE_FIXED64)
        val bits = java.lang.Double.doubleToLongBits(value)
        for (i in 0 until 8) out.write(((bits ushr (8 * i)) and 0xFF).toInt())
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_LENGTH)
        writeVarint(value.size.toLong())
        out.write(value, 0, value.size)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter {
        val nested = ProtoWriter()
        nested.block()
        return bytes(field, nested.toByteArray())
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) {
        require(field > 0) { "Il numero di campo protobuf deve essere > 0" }
        writeVarint(((field shl 3) or wireType).toLong())
    }

    private fun writeVarint(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5

        fun empty(): ByteArray = ByteArray(0)
    }
}
