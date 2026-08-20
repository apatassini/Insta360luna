package it.persoft.lunaultra.protocol

/** Utility di conversione esadecimale usate dal log diagnostico e dall'invio di frame grezzi. */
object Hex {

    private const val DIGITS = "0123456789ABCDEF"

    fun encode(bytes: ByteArray, separator: String = " ", limit: Int = Int.MAX_VALUE): String {
        val n = minOf(bytes.size, limit)
        val sb = StringBuilder(n * (2 + separator.length))
        for (i in 0 until n) {
            if (i > 0) sb.append(separator)
            val v = bytes[i].toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        if (bytes.size > n) sb.append(separator).append("… (+").append(bytes.size - n).append(" byte)")
        return sb.toString()
    }

    /** Accetta "0A1B2C", "0a 1b 2c", "0x0a,0x1b". Restituisce null se la stringa non è valida. */
    fun decodeOrNull(text: String): ByteArray? {
        val cleaned = text
            .replace("0x", "", ignoreCase = true)
            .filter { !it.isWhitespace() && it != ',' && it != ':' && it != '-' }
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(cleaned[i * 2], 16)
            val lo = Character.digit(cleaned[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Dump stile hexdump con colonna ASCII, utile per riconoscere l'header dei frame. */
    fun dump(bytes: ByteArray, bytesPerRow: Int = 16, maxRows: Int = 8): String {
        val sb = StringBuilder()
        var row = 0
        var offset = 0
        while (offset < bytes.size && row < maxRows) {
            val end = minOf(offset + bytesPerRow, bytes.size)
            sb.append(String.format("%04X  ", offset))
            for (i in offset until offset + bytesPerRow) {
                if (i < end) sb.append(String.format("%02X ", bytes[i])) else sb.append("   ")
            }
            sb.append(' ')
            for (i in offset until end) {
                val c = bytes[i].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append('\n')
            offset = end
            row++
        }
        if (offset < bytes.size) sb.append("… (+").append(bytes.size - offset).append(" byte)\n")
        return sb.toString().trimEnd('\n')
    }
}
