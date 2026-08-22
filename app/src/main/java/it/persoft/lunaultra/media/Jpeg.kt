package it.persoft.lunaultra.media

/**
 * Estrazione di un JPEG da un blocco di byte qualsiasi.
 *
 * Serve perché la risposta della miniatura non ha una forma documentata: può arrivare nuda o
 * dentro un campo protobuf, e in entrambi i casi l'immagine è delimitata dagli stessi due
 * marcatori. Cercarli è più robusto che indovinare il numero di campo.
 */
object Jpeg {

    private const val MARKER = 0xFF.toByte()
    private const val SOI = 0xD8.toByte()
    private const val EOI = 0xD9.toByte()

    /** Il primo JPEG completo contenuto in [data], oppure null se non ce n'è. */
    fun extract(data: ByteArray): ByteArray? {
        val start = indexOfMarker(data, SOI, from = 0) ?: return null
        val end = indexOfMarker(data, EOI, from = start + 2) ?: return null
        if (end + 2 <= start) return null
        return data.copyOfRange(start, end + 2)
    }

    private fun indexOfMarker(data: ByteArray, second: Byte, from: Int): Int? {
        var i = from
        while (i < data.size - 1) {
            if (data[i] == MARKER && data[i + 1] == second) return i
            i++
        }
        return null
    }
}
