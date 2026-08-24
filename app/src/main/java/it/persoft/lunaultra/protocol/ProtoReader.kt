package it.persoft.lunaultra.protocol

/** Un campo protobuf decodificato dal wire format, senza conoscere lo schema. */
sealed class ProtoField {
    abstract val number: Int

    data class VarInt(override val number: Int, val value: Long) : ProtoField() {
        val asInt: Int get() = value.toInt()
        val asBool: Boolean get() = value != 0L
        /** Interpretazione zig-zag, per i campi dichiarati `sint32` nello schema originale. */
        val asSInt: Int get() = ((value ushr 1) xor -(value and 1L)).toInt()
    }

    data class Fixed32(override val number: Int, val value: Int) : ProtoField() {
        val asFloat: Float get() = java.lang.Float.intBitsToFloat(value)
    }

    data class Fixed64(override val number: Int, val value: Long) : ProtoField() {
        val asDouble: Double get() = java.lang.Double.longBitsToDouble(value)
    }

    data class LengthDelimited(override val number: Int, val value: ByteArray) : ProtoField() {
        val asString: String get() = String(value, Charsets.UTF_8)

        override fun equals(other: Any?): Boolean =
            other is LengthDelimited && other.number == number && other.value.contentEquals(value)

        override fun hashCode(): Int = 31 * number + value.contentHashCode()
    }
}

/**
 * Decoder tollerante del wire format protobuf: si ferma appena incontra byte non validi
 * invece di lanciare, così un payload sconosciuto resta comunque ispezionabile.
 */
class ProtoReader(private val buffer: ByteArray) {

    fun fields(): List<ProtoField> {
        val result = mutableListOf<ProtoField>()
        var pos = 0
        while (pos < buffer.size) {
            val tag = readVarint(pos) ?: break
            pos = tag.next
            val fieldNumber = (tag.value ushr 3).toInt()
            val wireType = (tag.value and 0x07L).toInt()
            if (fieldNumber <= 0) break
            when (wireType) {
                ProtoWriter.WIRE_VARINT -> {
                    val v = readVarint(pos) ?: break
                    result += ProtoField.VarInt(fieldNumber, v.value)
                    pos = v.next
                }

                ProtoWriter.WIRE_FIXED64 -> {
                    if (pos + 8 > buffer.size) break
                    var v = 0L
                    for (i in 0 until 8) v = v or ((buffer[pos + i].toLong() and 0xFF) shl (8 * i))
                    result += ProtoField.Fixed64(fieldNumber, v)
                    pos += 8
                }

                ProtoWriter.WIRE_LENGTH -> {
                    val len = readVarint(pos) ?: break
                    val start = len.next
                    val size = len.value.toInt()
                    if (size < 0 || start + size > buffer.size) break
                    result += ProtoField.LengthDelimited(fieldNumber, buffer.copyOfRange(start, start + size))
                    pos = start + size
                }

                ProtoWriter.WIRE_FIXED32 -> {
                    if (pos + 4 > buffer.size) break
                    var v = 0
                    for (i in 0 until 4) v = v or ((buffer[pos + i].toInt() and 0xFF) shl (8 * i))
                    result += ProtoField.Fixed32(fieldNumber, v)
                    pos += 4
                }

                else -> return result
            }
        }
        return result
    }

    /** Rappresentazione leggibile usata nella schermata Diagnostica. */
    fun describe(indent: String = ""): String {
        val fields = fields()
        if (fields.isEmpty()) return if (buffer.isEmpty()) "${indent}(payload vuoto)" else "$indent(non decodificabile) ${Hex.encode(buffer, limit = 32)}"
        val sb = StringBuilder()
        for (f in fields) {
            when (f) {
                is ProtoField.VarInt ->
                    sb.append(indent).append("#").append(f.number).append(" varint=").append(f.value)
                        .append(" (sint=").append(f.asSInt).append(")\n")

                is ProtoField.Fixed32 ->
                    sb.append(indent).append("#").append(f.number).append(" fixed32=").append(f.value)
                        .append(" (float=").append(f.asFloat).append(")\n")

                is ProtoField.Fixed64 ->
                    sb.append(indent).append("#").append(f.number).append(" fixed64=").append(f.value)
                        .append(" (double=").append(f.asDouble).append(")\n")

                is ProtoField.LengthDelimited -> {
                    val nested = ProtoReader(f.value).fields()
                    val printable = f.value.isNotEmpty() && f.value.all {
                        val c = it.toInt() and 0xFF
                        c in 0x20..0x7E
                    }
                    sb.append(indent).append("#").append(f.number)
                    when {
                        printable -> sb.append(" string=\"").append(f.asString).append("\"\n")
                        nested.isNotEmpty() -> {
                            sb.append(" message {\n")
                            sb.append(ProtoReader(f.value).describe("$indent  "))
                            sb.append('\n').append(indent).append("}\n")
                        }

                        else -> sb.append(" bytes=").append(Hex.encode(f.value, limit = 24)).append('\n')
                    }
                }
            }
        }
        return sb.toString().trimEnd('\n')
    }

    /** Cerca un campo per numero, opzionalmente dentro un messaggio annidato (`path` = 2,1). */
    fun find(vararg path: Int): ProtoField? {
        if (path.isEmpty()) return null
        var current: List<ProtoField> = fields()
        for ((index, number) in path.withIndex()) {
            val field = current.firstOrNull { it.number == number } ?: return null
            if (index == path.lastIndex) return field
            val nested = field as? ProtoField.LengthDelimited ?: return null
            current = ProtoReader(nested.value).fields()
        }
        return null
    }

    fun intOrNull(vararg path: Int): Int? = when (val f = find(*path)) {
        is ProtoField.VarInt -> f.asInt
        is ProtoField.Fixed32 -> f.value
        is ProtoField.Fixed64 -> f.value.toInt()
        else -> null
    }

    /**
     * Come [intOrNull] ma senza troncare: lo spazio di una scheda si misura in decine di
     * miliardi di byte, che in un Int non ci stanno.
     */
    fun longOrNull(vararg path: Int): Long? = when (val f = find(*path)) {
        is ProtoField.VarInt -> f.value
        is ProtoField.Fixed32 -> f.value.toLong()
        is ProtoField.Fixed64 -> f.value
        else -> null
    }

    fun floatOrNull(vararg path: Int): Float? = when (val f = find(*path)) {
        is ProtoField.Fixed32 -> f.asFloat
        is ProtoField.Fixed64 -> f.asDouble.toFloat()
        is ProtoField.VarInt -> f.asSInt.toFloat()
        else -> null
    }

    /**
     * Come [floatOrNull] ma senza perdere precisione: la durata della posa viaggia in `double`,
     * e un ottomillesimo di secondo in un `float` diventa un numero un po' diverso.
     */
    fun doubleOrNull(vararg path: Int): Double? = when (val f = find(*path)) {
        is ProtoField.Fixed64 -> f.asDouble
        is ProtoField.Fixed32 -> f.asFloat.toDouble()
        is ProtoField.VarInt -> f.asSInt.toDouble()
        else -> null
    }

    fun stringOrNull(vararg path: Int): String? =
        (find(*path) as? ProtoField.LengthDelimited)?.asString

    private data class VarintResult(val value: Long, val next: Int)

    private fun readVarint(start: Int): VarintResult? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < buffer.size && shift <= 63) {
            val b = buffer[pos].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            pos++
            if (b and 0x80 == 0) return VarintResult(result, pos)
            shift += 7
        }
        return null
    }
}
