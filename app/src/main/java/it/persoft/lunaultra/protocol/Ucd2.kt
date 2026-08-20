package it.persoft.lunaultra.protocol

/**
 * Descrive il layout dell'header binario che precede il payload protobuf.
 *
 * ATTENZIONE: gli schemi ufficiali del protocollo Insta360 (UCD2) non sono pubblici.
 * Il layout di default qui sotto è l'ipotesi di lavoro documentata nel README; è tenuto
 * completamente parametrico proprio perché va confermato con una cattura reale, che si
 * ottiene dalla schermata Diagnostica dell'app. Un offset a [ABSENT] significa
 * "campo non presente in questo layout".
 */
data class Ucd2Layout(
    val headerSize: Int = 16,
    val lengthOffset: Int = 0,
    val lengthSize: Int = 4,
    val lengthIncludesHeader: Boolean = true,
    val versionOffset: Int = 4,
    val version: Int = 2,
    val typeOffset: Int = 5,
    val sequenceOffset: Int = 6,
    val sequenceSize: Int = 2,
    val commandOffset: Int = 8,
    val commandSize: Int = 4,
    val errorOffset: Int = 12,
    val errorSize: Int = 4,
    val littleEndian: Boolean = true,
    /**
     * Se true il byte di versione viene usato anche come marcatore di sincronizzazione:
     * un header il cui byte di versione non corrisponde viene scartato. È ciò che permette di
     * riagganciare il flusso dopo byte spuri, dato che una lunghezza sbagliata da sola può
     * risultare comunque "plausibile".
     */
    val validateVersion: Boolean = true,
) {
    init {
        require(headerSize in 4..64) { "headerSize fuori range" }
        require(lengthOffset + lengthSize <= headerSize) { "campo length fuori dall'header" }
    }

    companion object {
        const val ABSENT = -1

        /** Layout di default (16 byte). */
        val DEFAULT = Ucd2Layout()

        /**
         * Layout alternativo minimale: solo lunghezza a 4 byte + comando a 4 byte.
         * Utile come fallback se la camera risponde con header più corto.
         */
        val COMPACT = Ucd2Layout(
            headerSize = 8,
            lengthOffset = 0,
            lengthSize = 4,
            lengthIncludesHeader = true,
            versionOffset = ABSENT,
            validateVersion = false,
            typeOffset = ABSENT,
            sequenceOffset = ABSENT,
            sequenceSize = 0,
            commandOffset = 4,
            commandSize = 4,
            errorOffset = ABSENT,
            errorSize = 0,
        )
    }
}

/** Un messaggio completo: header decodificato + payload protobuf grezzo. */
data class Ucd2Frame(
    val commandId: Int,
    val sequence: Int,
    val type: Int = TYPE_REQUEST,
    val errorCode: Int = 0,
    val payload: ByteArray = ByteArray(0),
) {
    val isError: Boolean get() = errorCode != 0

    fun describePayload(): String = ProtoReader(payload).describe()

    override fun equals(other: Any?): Boolean =
        other is Ucd2Frame &&
            other.commandId == commandId &&
            other.sequence == sequence &&
            other.type == type &&
            other.errorCode == errorCode &&
            other.payload.contentEquals(payload)

    override fun hashCode(): Int {
        var result = commandId
        result = 31 * result + sequence
        result = 31 * result + type
        result = 31 * result + errorCode
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val TYPE_REQUEST = 0
        const val TYPE_RESPONSE = 1
        const val TYPE_NOTIFICATION = 2
    }
}

/** Serializza e deserializza i frame secondo un [Ucd2Layout]. */
class Ucd2Codec(val layout: Ucd2Layout = Ucd2Layout.DEFAULT) {

    fun encode(frame: Ucd2Frame): ByteArray {
        val total = layout.headerSize + frame.payload.size
        val out = ByteArray(total)
        val declaredLength = if (layout.lengthIncludesHeader) total else frame.payload.size
        putInt(out, layout.lengthOffset, layout.lengthSize, declaredLength)
        putInt(out, layout.versionOffset, 1, layout.version)
        putInt(out, layout.typeOffset, 1, frame.type)
        putInt(out, layout.sequenceOffset, layout.sequenceSize, frame.sequence)
        putInt(out, layout.commandOffset, layout.commandSize, frame.commandId)
        putInt(out, layout.errorOffset, layout.errorSize, frame.errorCode)
        frame.payload.copyInto(out, layout.headerSize)
        return out
    }

    /**
     * Prova a leggere un frame da [buffer] a partire da [offset].
     * Restituisce null se i byte disponibili non bastano ancora.
     */
    fun decode(buffer: ByteArray, offset: Int, available: Int): DecodeResult? {
        if (available < layout.headerSize) return null
        val declared = getInt(buffer, offset + layout.lengthOffset, layout.lengthSize)
        val totalSize = if (layout.lengthIncludesHeader) declared else declared + layout.headerSize
        if (totalSize < layout.headerSize || totalSize > MAX_FRAME_SIZE) {
            return DecodeResult.Invalid("Lunghezza dichiarata non plausibile: $declared")
        }
        if (layout.validateVersion && layout.versionOffset >= 0) {
            val version = buffer[offset + layout.versionOffset].toInt() and 0xFF
            if (version != (layout.version and 0xFF)) {
                return DecodeResult.Invalid("Byte di versione inatteso: $version")
            }
        }
        if (available < totalSize) return null
        val payload = buffer.copyOfRange(offset + layout.headerSize, offset + totalSize)
        val frame = Ucd2Frame(
            commandId = getInt(buffer, offset + layout.commandOffset, layout.commandSize),
            sequence = getInt(buffer, offset + layout.sequenceOffset, layout.sequenceSize),
            type = getInt(buffer, offset + layout.typeOffset, 1),
            errorCode = getInt(buffer, offset + layout.errorOffset, layout.errorSize),
            payload = payload,
        )
        return DecodeResult.Frame(frame, totalSize)
    }

    private fun putInt(dest: ByteArray, offset: Int, size: Int, value: Int) {
        if (offset == Ucd2Layout.ABSENT || size <= 0) return
        for (i in 0 until size) {
            val shift = if (layout.littleEndian) 8 * i else 8 * (size - 1 - i)
            dest[offset + i] = ((value ushr shift) and 0xFF).toByte()
        }
    }

    private fun getInt(src: ByteArray, offset: Int, size: Int): Int {
        if (offset < 0 || size <= 0 || offset + size > src.size) return 0
        var value = 0
        for (i in 0 until size) {
            val shift = if (layout.littleEndian) 8 * i else 8 * (size - 1 - i)
            value = value or ((src[offset + i].toInt() and 0xFF) shl shift)
        }
        return value
    }

    sealed class DecodeResult {
        data class Frame(val frame: Ucd2Frame, val consumed: Int) : DecodeResult()
        data class Invalid(val reason: String) : DecodeResult()
    }

    companion object {
        const val MAX_FRAME_SIZE = 4 * 1024 * 1024
    }
}

/** Accumula i byte letti dal socket e ne estrae i frame completi. */
class FrameAssembler(private val codec: Ucd2Codec) {

    private var buffer = ByteArray(8 * 1024)
    private var size = 0

    fun append(data: ByteArray, length: Int) {
        ensureCapacity(size + length)
        data.copyInto(buffer, size, 0, length)
        size += length
    }

    /**
     * Estrae tutti i frame completi disponibili. In caso di header non plausibile scarta un
     * byte e riprova: così una eventuale desincronizzazione non blocca la connessione.
     */
    fun drain(onInvalid: (String) -> Unit = {}): List<Ucd2Frame> {
        val frames = mutableListOf<Ucd2Frame>()
        var offset = 0
        while (offset < size) {
            when (val result = codec.decode(buffer, offset, size - offset)) {
                null -> break
                is Ucd2Codec.DecodeResult.Frame -> {
                    frames += result.frame
                    offset += result.consumed
                }

                is Ucd2Codec.DecodeResult.Invalid -> {
                    onInvalid(result.reason)
                    offset += 1
                }
            }
        }
        if (offset > 0) {
            buffer.copyInto(buffer, 0, offset, size)
            size -= offset
        }
        return frames
    }

    fun reset() {
        size = 0
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var newSize = buffer.size
        while (newSize < needed) newSize *= 2
        buffer = buffer.copyOf(newSize)
    }
}
