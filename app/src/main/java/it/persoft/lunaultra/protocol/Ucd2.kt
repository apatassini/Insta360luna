package it.persoft.lunaultra.protocol

/**
 * Framing UCD2, il protocollo di controllo della Insta360 Luna Ultra su TCP/6666.
 *
 * Il layout non è documentato da Insta360 ma è stato ricostruito da più progetti indipendenti
 * di reverse engineering e verificato sulla camera reale (vedi i crediti nel README).
 * Ogni frame ha questa forma:
 *
 * ```
 *  0   4   'U' 'C' 'D' '2'      magic
 *  4   1   0x01                 versione
 *  5   1   0x0c                 flag
 *  6   1   tipo                 0x01 media, 0x04 comando/risposta, 0x05 stream
 *  7   1   seq                  contatore a 8 bit, non correlato alle risposte
 *  8   4   len (LE)             lunghezza del corpo
 * 12   len corpo
 *      4   trailer (LE)         checksum del frame (magic..corpo compreso)
 * ```
 *
 * La dimensione totale è quindi sempre `12 + len + 4`.
 *
 * Nei frame di tipo [TYPE_FILE] il corpo si apre con 9 byte di intestazione di comando:
 *
 * ```
 *  0   2   code (LE)            codice messaggio (`PHONE_COMMAND_*`); la risposta lo rimanda indietro
 *  2   1   direzione            0x02 richiesta, 0x03 risposta
 *  3   2   requestId (LE)       è QUESTO che correla richiesta e risposta, non seq
 *  5   4   0x00008000 (LE)      costante
 *  9   ..  messaggio protobuf
 * ```
 */
object Ucd2 {

    val MAGIC = byteArrayOf(0x55, 0x43, 0x44, 0x32) // "UCD2"

    const val VERSION = 0x01
    const val FLAGS = 0x0c

    const val TYPE_MEDIA = 0x01
    const val TYPE_FILE = 0x04
    const val TYPE_STREAM = 0x05

    const val DIRECTION_REQUEST = 0x02
    const val DIRECTION_RESPONSE = 0x03

    /** magic + versione + flag + tipo + seq. */
    const val HEADER_SIZE = 8

    /** Campo lunghezza subito dopo l'header. */
    const val LENGTH_SIZE = 4

    /** Checksum in coda al frame. */
    const val TRAILER_SIZE = 4

    /** Intestazione di comando all'inizio del corpo di un frame [TYPE_FILE]. */
    const val COMMAND_HEADER_SIZE = 9

    /** Intestazione dei frame media; lo stream Annex-B inizia subito dopo. */
    const val MEDIA_HEADER_SIZE = 9

    /** Primo byte dell'header media: 0x20 video principale, 0x30 anteprima, 0x40 giroscopio. */
    const val MEDIA_VIDEO = 0x20

    const val COMMAND_CONSTANT = 0x8000

    /** Oltre questa soglia una lunghezza dichiarata è considerata corrotta. */
    const val MAX_DECLARED = 8 * 1024 * 1024

    /**
     * Token costante del frame di handshake: è ciò che autorizza la sessione.
     * Occupa la posizione del trailer di un frame a lunghezza zero.
     */
    val HELLO_TOKEN = byteArrayOf(0xf6.toByte(), 0xcc.toByte(), 0x4f, 0x09)

    private val CRC_TABLE = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var value = i shl 24
            repeat(8) {
                value = if (value and 0x80000000.toInt() != 0) {
                    (value shl 1) xor 0x04c11db7
                } else {
                    value shl 1
                }
            }
            table[i] = value
        }
    }

    /**
     * Checksum dei frame UCD2: una variante non standard di CRC-32 (polinomio 0x04C11DB7)
     * in cui ogni byte viene messo in XOR con il byte BASSO e seguito da quattro round di
     * tabella sul byte alto. Non "correggerla" per farla combaciare con un CRC-32 standard:
     * la camera scarta i frame il cui trailer non corrisponde.
     */
    fun checksum(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
        var checksum = -1 // 0xffffffff
        for (i in from until until) {
            checksum = checksum xor (data[i].toInt() and 0xFF)
            repeat(4) {
                checksum = (checksum shl 8) xor CRC_TABLE[checksum ushr 24]
            }
        }
        return checksum
    }

    /** Costruisce un frame generico: header + lunghezza + corpo + checksum. */
    fun frame(type: Int, sequence: Int, body: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + LENGTH_SIZE + body.size + TRAILER_SIZE)
        MAGIC.copyInto(out, 0)
        out[4] = VERSION.toByte()
        out[5] = FLAGS.toByte()
        out[6] = type.toByte()
        out[7] = (sequence and 0xFF).toByte()
        putIntLe(out, 8, body.size)
        body.copyInto(out, HEADER_SIZE + LENGTH_SIZE)
        val checksum = checksum(out, 0, HEADER_SIZE + LENGTH_SIZE + body.size)
        putIntLe(out, HEADER_SIZE + LENGTH_SIZE + body.size, checksum)
        return out
    }

    /** Frame di handshake/keep-alive: lunghezza zero e token costante al posto del checksum. */
    fun hello(sequence: Int): ByteArray {
        val out = ByteArray(HEADER_SIZE + LENGTH_SIZE + TRAILER_SIZE)
        MAGIC.copyInto(out, 0)
        out[4] = VERSION.toByte()
        out[5] = FLAGS.toByte()
        out[6] = TYPE_STREAM.toByte()
        out[7] = (sequence and 0xFF).toByte()
        putIntLe(out, 8, 0)
        HELLO_TOKEN.copyInto(out, HEADER_SIZE + LENGTH_SIZE)
        return out
    }

    /** Frame di comando: intestazione a 9 byte + messaggio protobuf. */
    fun command(sequence: Int, code: Int, requestId: Int, body: ByteArray): ByteArray {
        val raw = ByteArray(COMMAND_HEADER_SIZE + body.size)
        putShortLe(raw, 0, code)
        raw[2] = DIRECTION_REQUEST.toByte()
        putShortLe(raw, 3, requestId)
        putIntLe(raw, 5, COMMAND_CONSTANT)
        body.copyInto(raw, COMMAND_HEADER_SIZE)
        return frame(TYPE_FILE, sequence, raw)
    }

    internal fun putIntLe(dest: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) dest[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    internal fun putShortLe(dest: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 2) dest[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    internal fun intLe(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xFF) or
            ((src[offset + 1].toInt() and 0xFF) shl 8) or
            ((src[offset + 2].toInt() and 0xFF) shl 16) or
            ((src[offset + 3].toInt() and 0xFF) shl 24)

    internal fun shortLe(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xFF) or ((src[offset + 1].toInt() and 0xFF) shl 8)
}

/** Un frame UCD2 decodificato. */
data class Ucd2Frame(
    val type: Int,
    val code: Int = 0,
    val requestId: Int = 0,
    val direction: Int = 0,
    val substream: Int = 0,
    val payload: ByteArray = ByteArray(0),
) {
    /** Risposta a un comando o notifica spontanea della camera. */
    val isCommandFrame: Boolean get() = type == Ucd2.TYPE_FILE

    /** I codici da 8192 in su sono `CAMERA_NOTIFICATION_*`: arrivano senza richiesta. */
    val isNotification: Boolean get() = isCommandFrame && code >= LunaProtocolCodes.NOTIFICATION_BEGIN

    fun describePayload(): String = ProtoReader(payload).describe()

    override fun equals(other: Any?): Boolean =
        other is Ucd2Frame &&
            other.type == type &&
            other.code == code &&
            other.requestId == requestId &&
            other.direction == direction &&
            other.substream == substream &&
            other.payload.contentEquals(payload)

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + code
        result = 31 * result + requestId
        result = 31 * result + direction
        result = 31 * result + substream
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Estrae i frame completi da un flusso di byte.
 *
 * La ricerca riparte sempre dal magic `UCD2`, così qualche byte spurio o un frame troncato
 * non desincronizzano la sessione: si riaggancia al magic successivo.
 */
class FrameAssembler {

    private var buffer = ByteArray(16 * 1024)
    private var size = 0

    fun append(data: ByteArray, length: Int) {
        ensureCapacity(size + length)
        data.copyInto(buffer, size, 0, length)
        size += length
    }

    fun drain(onInvalid: (String) -> Unit = {}): List<Ucd2Frame> {
        val frames = mutableListOf<Ucd2Frame>()
        var offset = 0
        while (true) {
            val start = indexOfMagic(offset)
            if (start < 0) {
                // Conserva gli ultimi byte: potrebbero essere un magic spezzato a metà.
                offset = maxOf(offset, size - (Ucd2.MAGIC.size - 1)).coerceAtLeast(0)
                break
            }
            if (start > offset) {
                onInvalid("Scartati ${start - offset} byte prima del magic UCD2")
                offset = start
            }
            if (size - offset < Ucd2.HEADER_SIZE + Ucd2.LENGTH_SIZE) break

            val type = buffer[offset + 6].toInt() and 0xFF
            val declared = Ucd2.intLe(buffer, offset + 8)
            if (declared < 0 || declared > Ucd2.MAX_DECLARED) {
                onInvalid("Lunghezza dichiarata non plausibile: $declared")
                offset += Ucd2.MAGIC.size
                continue
            }
            val total = Ucd2.HEADER_SIZE + Ucd2.LENGTH_SIZE + declared + Ucd2.TRAILER_SIZE
            if (size - offset < total) break

            val bodyStart = offset + Ucd2.HEADER_SIZE + Ucd2.LENGTH_SIZE
            frames += decodeBody(type, bodyStart, declared)
            offset += total
        }
        if (offset > 0) {
            buffer.copyInto(buffer, 0, offset, size)
            size -= offset
        }
        return frames
    }

    private fun decodeBody(type: Int, bodyStart: Int, declared: Int): Ucd2Frame = when {
        type == Ucd2.TYPE_FILE && declared >= Ucd2.COMMAND_HEADER_SIZE -> Ucd2Frame(
            type = type,
            code = Ucd2.shortLe(buffer, bodyStart),
            direction = buffer[bodyStart + 2].toInt() and 0xFF,
            requestId = Ucd2.shortLe(buffer, bodyStart + 3),
            payload = buffer.copyOfRange(bodyStart + Ucd2.COMMAND_HEADER_SIZE, bodyStart + declared),
        )

        type == Ucd2.TYPE_MEDIA && declared > Ucd2.MEDIA_HEADER_SIZE -> Ucd2Frame(
            type = type,
            substream = buffer[bodyStart].toInt() and 0xFF,
            payload = buffer.copyOfRange(bodyStart + Ucd2.MEDIA_HEADER_SIZE, bodyStart + declared),
        )

        else -> Ucd2Frame(
            type = type,
            payload = buffer.copyOfRange(bodyStart, bodyStart + declared),
        )
    }

    private fun indexOfMagic(from: Int): Int {
        outer@ for (i in from..size - Ucd2.MAGIC.size) {
            for (j in Ucd2.MAGIC.indices) {
                if (buffer[i + j] != Ucd2.MAGIC[j]) continue@outer
            }
            return i
        }
        return -1
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
