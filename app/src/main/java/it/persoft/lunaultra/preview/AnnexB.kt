package it.persoft.lunaultra.preview

/**
 * Aiuti sullo stream elementare Annex-B dell'anteprima.
 *
 * La camera manda video grezzo senza contenitore: quale codec sia, dove finisca un fotogramma
 * e dove inizi il successivo va letto dai byte. Tenere questa parte pura la rende verificabile
 * senza camera attaccata.
 */
object AnnexB {

    /** H.264: tipi di NAL che servono a riconoscere parametri e keyframe. */
    private const val H264_SPS = 7
    private const val H264_PPS = 8
    private const val H264_IDR = 5

    /** H.265: VPS, SPS, PPS e l'intervallo IRAP (BLA_W_LP..CRA_NUT), che apre un keyframe. */
    private const val H265_VPS = 32
    private const val H265_SPS = 33
    private const val H265_PPS = 34
    private const val H265_IRAP_MIN = 16
    private const val H265_IRAP_MAX = 23

    enum class Codec(val mime: String) {
        H264("video/avc"),
        H265("video/hevc"),
    }

    /**
     * Posizione di ogni start code (`00 00 01`, con o senza lo zero iniziale in più) nel
     * buffer. Restituisce le posizioni del primo byte dello start code.
     */
    fun startCodes(bytes: ByteArray, from: Int = 0, until: Int = bytes.size): List<Int> {
        val positions = mutableListOf<Int>()
        var i = from
        while (i + 2 < until) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte()) {
                // Uno start code a quattro byte inizia un byte prima.
                positions += if (i > from && bytes[i - 1] == 0.toByte()) i - 1 else i
                i += 3
            } else {
                i++
            }
        }
        return positions
    }

    /** Lunghezza dello start code che inizia a [at]: 3 o 4 byte. */
    fun startCodeLength(bytes: ByteArray, at: Int): Int =
        if (at + 3 < bytes.size && bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() &&
            bytes[at + 2] == 0.toByte() && bytes[at + 3] == 1.toByte()
        ) 4 else 3

    /** Tipo di NAL H.264: i 5 bit bassi del primo byte del payload. */
    fun h264NalType(firstByte: Byte): Int = firstByte.toInt() and 0x1F

    /** Tipo di NAL H.265: i bit 1..6 del primo byte del payload. */
    fun h265NalType(firstByte: Byte): Int = (firstByte.toInt() shr 1) and 0x3F

    /**
     * Riconosce il codec dai NAL presenti.
     *
     * La distinzione si basa sui set di parametri, non su un singolo byte: un NAL H.264 di tipo
     * 7 (SPS) e uno H.265 di tipo 33 (SPS) hanno primi byte diversi, e cercarli entrambi evita
     * di scambiare un tipo per l'altro su un frammento qualsiasi. Restituisce null finché non
     * ha visto abbastanza per decidere.
     */
    fun detectCodec(bytes: ByteArray): Codec? {
        for (start in startCodes(bytes)) {
            val payload = start + startCodeLength(bytes, start)
            if (payload >= bytes.size) continue
            val first = bytes[payload]
            when (h265NalType(first)) {
                H265_VPS, H265_SPS, H265_PPS -> return Codec.H265
            }
            when (h264NalType(first)) {
                H264_SPS, H264_PPS -> return Codec.H264
            }
        }
        return null
    }

    /** Vero se il buffer contiene un NAL che apre un keyframe: prima di uno, il decoder non parte. */
    fun containsKeyframe(bytes: ByteArray, codec: Codec): Boolean {
        for (start in startCodes(bytes)) {
            val payload = start + startCodeLength(bytes, start)
            if (payload >= bytes.size) continue
            val first = bytes[payload]
            val isKey = when (codec) {
                Codec.H264 -> h264NalType(first).let { it == H264_IDR || it == H264_SPS }
                Codec.H265 -> h265NalType(first).let {
                    it in H265_IRAP_MIN..H265_IRAP_MAX || it == H265_VPS || it == H265_SPS
                }
            }
            if (isKey) return true
        }
        return false
    }
}
