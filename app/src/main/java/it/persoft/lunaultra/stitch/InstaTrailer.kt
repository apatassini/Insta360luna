package it.persoft.lunaultra.stitch

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Come era messa la camera nell'istante dello scatto, letta dalla foto stessa.
 *
 * [pitchDegrees] è l'inclinazione rispetto all'orizzontale vera, positiva verso l'alto, e non è
 * una stima: viene dalla gravità. [rollDegrees] è la rotazione attorno all'asse ottico, misurata
 * allo stesso modo. Il pan non c'è e non ci può essere — la gravità è simmetrica attorno alla
 * verticale, quindi di quanto la camera sia girata in orizzontale un accelerometro non lo sa.
 */
data class ShotAttitude(
    val pitchDegrees: Float,
    val rollDegrees: Float,
    /** Quanti campioni sono stati mediati: sotto un centinaio la misura vale poco. */
    val samples: Int,
    /** Il modulo del vettore misurato, in unità del sensore. Deve valere 1 g e restare costante. */
    val magnitude: Float,
)

/**
 * La coda che la Luna scrive dopo la fine del JPEG.
 *
 * Ogni foto della camera ha, dopo il marcatore di fine immagine, un blocco che i visori
 * ignorano e che finisce con la firma `8db42d694ccc418790edff439fe026bf`. Dentro c'è una traccia
 * di campioni inerziali a mille al secondo: venti byte l'uno, marca temporale a 64 bit in
 * microsecondi, tre interi per l'accelerometro e tre per la giroscopica, tutti a sedici bit in
 * binario con scostamento (il valore vero è il numero senza segno meno 32768).
 *
 * Quello che ci serve è l'accelerometro, e ci serve tantissimo. A camera ferma misura la
 * gravità, cioè indica dov'è il basso: da lì escono inclinazione e rollio **in assoluto**, senza
 * ipotesi ottiche, senza cercare l'orizzonte nell'immagine e senza dipendere dalla taratura del
 * gimbal. Sulle nove foto della spiaggia il modulo del vettore è costante entro il 4 per mille,
 * e tre scatti alla stessa inclinazione comandata ma a pan diversi danno lo stesso beccheggio
 * entro tre centesimi di grado.
 *
 * È il righello esterno che mancava. Con quello si è scoperto che la lente è di 77,07° e non
 * degli 81,74° dichiarati, e che a inclinazione comandata zero la camera guardava in su di
 * 6,86° — che è, misurato invece che dedotto, il «mare curvo».
 *
 * Gli assi, ricavati dai dati: 0 è l'asse ottico (avanti), 1 è trasversale, 2 è l'alto della
 * camera. A camera in bolla la gravità sta tutta sul 2, negativa.
 */
object InstaTrailer {

    private val MAGIC = "8db42d694ccc418790edff439fe026bf".toByteArray(Charsets.US_ASCII)

    /**
     * La coda è una catena di blocchi, letta all'indietro.
     *
     * A settantotto byte dalla fine c'è il primo piede: due byte di identificativo e quattro di
     * dimensione. I dati di quel blocco stanno `dimensione` byte più indietro, e sei byte prima
     * ancora comincia il piede del blocco precedente. Si va così fino a esaurire la catena.
     *
     * Su una foto vera della Luna i blocchi sono cinque:
     *
     *     0x0101    2839 byte   metadati (comincia col numero di serie della camera)
     *     0x0200 1179688 byte   anteprima compressa
     *     0x0300    8000 byte   la traccia inerziale: 400 campioni da venti byte
     *     0x0900     240 byte   esposizione: cinque voci da 48, con marca in millisecondi
     *     0x2a01      63 byte   parametri di scatto
     *
     * Cercare la traccia a tentoni funzionava, ma leggere la struttura è meglio per due ragioni:
     * si va dritti al blocco giusto invece di frugare, e soprattutto non si può sbagliare blocco
     * — dentro l'anteprima compressa, prima o poi, otto byte che sembrano una marca temporale
     * che avanza di un millesimo si trovano.
     */
    private const val CHAIN_START = 78
    private const val ID_IMU = 0x0300
    private const val ID_FOOTER = 6
    private const val MAX_BLOCKS = 16

    /** Coda: [dati][dimensione totale u32][versione u32][firma 32 byte]. */
    private const val FOOTER = 40
    private const val SAMPLE = 20
    private const val SEARCH_BYTES = 48 * 1024
    private const val MAX_PHASE = 4_000
    private const val MIN_SAMPLES = 50

    /** Campioni a mille al secondo: fra uno e l'altro passa un millesimo, con un po' di gioco. */
    private const val MIN_STEP_US = 900L
    private const val MAX_STEP_US = 1_100L

    fun readAttitude(file: File): ShotAttitude? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < CHAIN_START + SAMPLE) return null
            val magic = ByteArray(MAGIC.size)
            raf.seek(length - MAGIC.size)
            raf.readFully(magic)
            if (!magic.contentEquals(MAGIC)) return null
            imuBlock(raf, length)?.let { return@use attitudeOf(it) }
            // Ripiego: se la catena non si legge — formato diverso, file troncato — si torna a
            // riconoscere la traccia da come si comporta.
            fallbackScan(raf, length)?.let { attitudeOf(it) }
        }
    }.getOrNull()

    /** Percorre la catena all'indietro e restituisce i dati del blocco inerziale. */
    private fun imuBlock(raf: RandomAccessFile, length: Long): ByteArray? {
        var footerAt = length - CHAIN_START
        val head = ByteArray(ID_FOOTER)
        repeat(MAX_BLOCKS) {
            if (footerAt < ID_FOOTER) return null
            raf.seek(footerAt)
            raf.readFully(head)
            val id = (head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8)
            val size = u32(head, 2).toLong() and 0xFFFFFFFFL
            if (size <= 0L || size > footerAt) return null
            val start = footerAt - size
            if (id == ID_IMU) {
                if (size < SAMPLE * MIN_SAMPLES) return null
                val want = minOf(size, SEARCH_BYTES.toLong()).toInt()
                val data = ByteArray(want)
                raf.seek(start)
                raf.readFully(data)
                return data
            }
            footerAt = start - ID_FOOTER
        }
        return null
    }

    private fun fallbackScan(raf: RandomAccessFile, length: Long): ByteArray? {
        val footer = ByteArray(FOOTER)
        raf.seek(length - FOOTER)
        raf.readFully(footer)
        val total = u32(footer, 0)
        if (total <= FOOTER || total > length) return null
        val want = minOf(total.toLong() - FOOTER, SEARCH_BYTES.toLong()).toInt()
        if (want < SAMPLE * MIN_SAMPLES) return null
        val trailer = ByteArray(want)
        raf.seek(length - total)
        raf.readFully(trailer)
        return trailer
    }

    /** Separata dal file perché si possa provare su una coda costruita a mano. */
    internal fun attitudeOf(trailer: ByteArray): ShotAttitude? {
        val base = findTrack(trailer) ?: return null
        var sumForward = 0.0
        var sumSide = 0.0
        var sumUp = 0.0
        var count = 0
        var previous = -1L
        var offset = base
        while (offset + SAMPLE <= trailer.size) {
            val stamp = u64(trailer, offset)
            if (previous >= 0 && !plausibleStep(stamp - previous)) break
            previous = stamp
            sumForward += biased(trailer, offset + 8)
            sumSide += biased(trailer, offset + 10)
            sumUp += biased(trailer, offset + 12)
            count++
            offset += SAMPLE
        }
        if (count < MIN_SAMPLES) return null
        val forward = sumForward / count
        val side = sumSide / count
        val up = sumUp / count
        val magnitude = sqrt(forward * forward + side * side + up * up)
        if (magnitude < 1.0) return null
        val pitch = Math.toDegrees(asin((forward / magnitude).coerceIn(-1.0, 1.0)))
        val roll = Math.toDegrees(atan2(side, -up))
        return ShotAttitude(pitch.toFloat(), roll.toFloat(), count, magnitude.toFloat())
    }

    /**
     * Dove comincia la traccia.
     *
     * Non c'è un indice da leggere, quindi la si riconosce da come si comporta: una marca
     * temporale che cresce di un millesimo di secondo a ogni venti byte, per almeno cinquanta
     * campioni di fila. Nessun'altra cosa dentro quella coda — l'anteprima compressa, i
     * parametri di scatto — si comporta così, e cinquanta passi giusti di fila non capitano.
     */
    private fun findTrack(trailer: ByteArray): Int? {
        val limit = minOf(MAX_PHASE, trailer.size - SAMPLE * (MIN_SAMPLES + 1))
        for (offset in 0 until maxOf(limit, 0)) {
            if (!plausibleStep(u64(trailer, offset + SAMPLE) - u64(trailer, offset))) continue
            var previous = u64(trailer, offset + SAMPLE)
            var run = 1
            var k = 2
            while (offset + k * SAMPLE + 8 <= trailer.size && run < MIN_SAMPLES) {
                val stamp = u64(trailer, offset + k * SAMPLE)
                if (!plausibleStep(stamp - previous)) break
                previous = stamp
                run++
                k++
            }
            if (run >= MIN_SAMPLES) return offset
        }
        return null
    }

    private fun plausibleStep(delta: Long): Boolean = delta in MIN_STEP_US..MAX_STEP_US

    /** Intero a sedici bit in binario con scostamento: il valore vero è quello meno 32768. */
    private fun biased(b: ByteArray, at: Int): Double =
        (((b[at + 1].toInt() and 0xFF) shl 8 or (b[at].toInt() and 0xFF)) - 32768).toDouble()

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun u64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }
}
