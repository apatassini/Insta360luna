package it.persoft.lunaultra.stitch

import it.persoft.lunaultra.protocol.ProtoReader
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
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

/** Bias statico del giroscopio, in conteggi grezzi del sensore. */
data class BiasGiroscopio(
    val asse0: Double,
    val asse1: Double,
    val asse2: Double,
)

/** Rotazione accumulata integrando la velocità angolare tridimensionale. */
data class RotazioneGiroscopio(
    val asse0Gradi: Double,
    val asse1Gradi: Double,
    val asse2Gradi: Double,
    val angoloGradi: Double,
)

/** Un tratto di rotazione riconosciuto nella traccia continua. */
data class MovimentoGiroscopio(
    val inizioSecondi: Double,
    val fineSecondi: Double,
    val rotazione: RotazioneGiroscopio,
)

/** I due movimenti opposti registrati da una misura della curva del gimbal. */
data class CoppiaMovimentiGiroscopio(
    val andata: MovimentoGiroscopio,
    val ritorno: MovimentoGiroscopio,
    val sogliaGradiSecondo: Double,
)

/** Un campione già convertito dal valore grezzo a gradi al secondo. */
data class CampioneGiroscopio(
    val tempoSecondi: Double,
    val asse0GradiSecondo: Double,
    val asse1GradiSecondo: Double,
    val asse2GradiSecondo: Double,
)

/**
 * Traccia giroscopica continua contenuta nella coda di una foto o di un video Insta360.
 *
 * La Luna registra a 1 kHz. Nei video la traccia copre l'intera registrazione e consente di
 * misurare il pan: il fondo scala viene letto dai metadati del file, senza costanti inventate.
 */
class TracciaGiroscopio internal constructor(
    private val marcheMicrosecondi: LongArray,
    private val asse0: ShortArray,
    private val asse1: ShortArray,
    private val asse2: ShortArray,
    val fondoScalaGradiSecondo: Int,
) {
    val numeroCampioni: Int get() = marcheMicrosecondi.size
    val durataSecondi: Double
        get() = if (numeroCampioni < 2) 0.0 else
            (marcheMicrosecondi.last() - marcheMicrosecondi.first()) / 1_000_000.0

    private val conteggiPerGradoSecondo = 32768.0 / fondoScalaGradiSecondo

    fun campione(indice: Int): CampioneGiroscopio {
        require(indice in marcheMicrosecondi.indices) { "Campione fuori intervallo: $indice" }
        return CampioneGiroscopio(
            tempoSecondi = (marcheMicrosecondi[indice] - marcheMicrosecondi.first()) / 1_000_000.0,
            asse0GradiSecondo = asse0[indice] / conteggiPerGradoSecondo,
            asse1GradiSecondo = asse1[indice] / conteggiPerGradoSecondo,
            asse2GradiSecondo = asse2[indice] / conteggiPerGradoSecondo,
        )
    }

    /** Misura il bias nel tratto iniziale in cui la camera deve essere ferma. */
    fun stimaBias(durataRiposoSecondi: Double = 0.8): BiasGiroscopio {
        require(durataRiposoSecondi > 0.0) { "La durata di riposo deve essere positiva" }
        val limite = marcheMicrosecondi.first() + (durataRiposoSecondi * 1_000_000.0).toLong()
        var somma0 = 0.0
        var somma1 = 0.0
        var somma2 = 0.0
        var campioni = 0
        while (campioni < numeroCampioni && marcheMicrosecondi[campioni] < limite) {
            somma0 += asse0[campioni]
            somma1 += asse1[campioni]
            somma2 += asse2[campioni]
            campioni++
        }
        require(campioni >= 50) { "Servono almeno 50 campioni fermi per misurare il bias" }
        return BiasGiroscopio(somma0 / campioni, somma1 / campioni, somma2 / campioni)
    }

    /**
     * Integra il giroscopio come quaternione, quindi funziona anche quando pan e tilt si
     * sovrappongono. [daSecondi] e [aSecondi] sono relativi al primo campione della traccia.
     */
    fun integra(
        daSecondi: Double,
        aSecondi: Double,
        bias: BiasGiroscopio,
    ): RotazioneGiroscopio {
        require(daSecondi >= 0.0 && aSecondi > daSecondi && aSecondi <= durataSecondi + 0.001) {
            "Intervallo non valido: $daSecondi..$aSecondi su una traccia di $durataSecondi s"
        }
        val da = marcheMicrosecondi.first() + (daSecondi * 1_000_000.0).toLong()
        val a = marcheMicrosecondi.first() + (aSecondi * 1_000_000.0).toLong()
        var q0 = 1.0
        var q1 = 0.0
        var q2 = 0.0
        var q3 = 0.0

        // La velocità appartiene al corpo camera: il piccolo quaternione del campione va
        // moltiplicato a destra. La media fra due campioni evita un errore sistematico ai bordi.
        for (indice in 1 until numeroCampioni) {
            val inizio = marcheMicrosecondi[indice - 1]
            val fine = marcheMicrosecondi[indice]
            if (fine <= da) continue
            if (inizio >= a) break
            val dt = (minOf(fine, a) - maxOf(inizio, da)) / 1_000_000.0
            if (dt <= 0.0) continue
            val velocita0 = ((asse0[indice - 1] + asse0[indice]) / 2.0 - bias.asse0) /
                conteggiPerGradoSecondo * PI / 180.0
            val velocita1 = ((asse1[indice - 1] + asse1[indice]) / 2.0 - bias.asse1) /
                conteggiPerGradoSecondo * PI / 180.0
            val velocita2 = ((asse2[indice - 1] + asse2[indice]) / 2.0 - bias.asse2) /
                conteggiPerGradoSecondo * PI / 180.0
            val modulo = sqrt(velocita0 * velocita0 + velocita1 * velocita1 + velocita2 * velocita2)
            if (modulo == 0.0) continue
            val mezzoAngolo = modulo * dt / 2.0
            val fattore = sin(mezzoAngolo) / modulo
            val r0 = cos(mezzoAngolo)
            val r1 = velocita0 * fattore
            val r2 = velocita1 * fattore
            val r3 = velocita2 * fattore
            val nuovo0 = q0 * r0 - q1 * r1 - q2 * r2 - q3 * r3
            val nuovo1 = q0 * r1 + q1 * r0 + q2 * r3 - q3 * r2
            val nuovo2 = q0 * r2 - q1 * r3 + q2 * r0 + q3 * r1
            val nuovo3 = q0 * r3 + q1 * r2 - q2 * r1 + q3 * r0
            val norma = sqrt(nuovo0 * nuovo0 + nuovo1 * nuovo1 + nuovo2 * nuovo2 + nuovo3 * nuovo3)
            q0 = nuovo0 / norma
            q1 = nuovo1 / norma
            q2 = nuovo2 / norma
            q3 = nuovo3 / norma
        }

        // q e -q rappresentano la stessa posa: si sceglie quello con angolo breve e segni
        // leggibili. Le tre componenti sono il vettore asse-angolo espresso in gradi.
        if (q0 < 0.0) {
            q0 = -q0
            q1 = -q1
            q2 = -q2
            q3 = -q3
        }
        val senoMezzo = sqrt(q1 * q1 + q2 * q2 + q3 * q3)
        if (senoMezzo < 1e-12) return RotazioneGiroscopio(0.0, 0.0, 0.0, 0.0)
        val angoloGradi = 2.0 * atan2(senoMezzo, q0) * 180.0 / PI
        return RotazioneGiroscopio(
            asse0Gradi = q1 / senoMezzo * angoloGradi,
            asse1Gradi = q2 / senoMezzo * angoloGradi,
            asse2Gradi = q3 / senoMezzo * angoloGradi,
            angoloGradi = angoloGradi,
        )
    }

    /**
     * Riconosce i due impulsi separati dal tratto di riposo della misura del gimbal.
     *
     * La soglia nasce dal rumore misurato all'inizio della stessa registrazione: in questo
     * modo resta abbastanza bassa da vedere l'1%, ma non scambia il bias del sensore per un
     * movimento. Brevi buchi sotto soglia vengono uniti perché accelerazione e frenata non
     * sono rettangolari. Fra tutti i tratti si tengono i due con più rotazione e si rimettono
     * in ordine temporale: sono l'andata e il ritorno comandati dalla taratura.
     */
    fun trovaDueMovimenti(durataRiposoSecondi: Double = 0.7): CoppiaMovimentiGiroscopio {
        val bias = stimaBias(durataRiposoSecondi)
        var rumoreQuadratico = 0.0
        var campioniRumore = 0
        for (indice in 0 until numeroCampioni) {
            val campione = campione(indice)
            if (campione.tempoSecondi >= durataRiposoSecondi) break
            val velocita0 = campione.asse0GradiSecondo - bias.asse0 / conteggiPerGradoSecondo
            val velocita1 = campione.asse1GradiSecondo - bias.asse1 / conteggiPerGradoSecondo
            val velocita2 = campione.asse2GradiSecondo - bias.asse2 / conteggiPerGradoSecondo
            rumoreQuadratico += velocita0 * velocita0 + velocita1 * velocita1 + velocita2 * velocita2
            campioniRumore++
        }
        val soglia = maxOf(
            SOGLIA_MINIMA_GRADI_SECONDO,
            sqrt(rumoreQuadratico / campioniRumore.coerceAtLeast(1)) * MOLTIPLICATORE_RUMORE,
        )

        data class Intervallo(val inizio: Int, val ultimoAttivo: Int)

        val intervalli = mutableListOf<Intervallo>()
        var inizio = -1
        var ultimoAttivo = -1
        for (indice in 0 until numeroCampioni) {
            val campione = campione(indice)
            if (campione.tempoSecondi < durataRiposoSecondi) continue
            val velocita0 = campione.asse0GradiSecondo - bias.asse0 / conteggiPerGradoSecondo
            val velocita1 = campione.asse1GradiSecondo - bias.asse1 / conteggiPerGradoSecondo
            val velocita2 = campione.asse2GradiSecondo - bias.asse2 / conteggiPerGradoSecondo
            val inMovimento = sqrt(
                velocita0 * velocita0 + velocita1 * velocita1 + velocita2 * velocita2,
            ) >= soglia
            if (inMovimento) {
                if (inizio < 0) inizio = indice
                ultimoAttivo = indice
            } else if (
                inizio >= 0 &&
                campione.tempoSecondi - campione(ultimoAttivo).tempoSecondi >= PAUSA_CHIUSURA_SECONDI
            ) {
                if (campione(ultimoAttivo).tempoSecondi - campione(inizio).tempoSecondi >= DURATA_MINIMA_SECONDI) {
                    intervalli += Intervallo(inizio, ultimoAttivo)
                }
                inizio = -1
                ultimoAttivo = -1
            }
        }
        if (inizio >= 0 && ultimoAttivo > inizio) intervalli += Intervallo(inizio, ultimoAttivo)

        val movimenti = intervalli.map { intervallo ->
            val da = campione(intervallo.inizio).tempoSecondi
            val a = minOf(
                durataSecondi,
                campione(intervallo.ultimoAttivo).tempoSecondi + CODA_FRENATA_SECONDI,
            )
            MovimentoGiroscopio(da, a, integra(da, a, bias))
        }.sortedByDescending { it.rotazione.angoloGradi }
            .take(2)
            .sortedBy(MovimentoGiroscopio::inizioSecondi)
        require(movimenti.size == 2) {
            "Riconosciuti ${movimenti.size} movimenti giroscopici invece di due"
        }
        return CoppiaMovimentiGiroscopio(movimenti[0], movimenti[1], soglia)
    }

    private companion object {
        const val SOGLIA_MINIMA_GRADI_SECONDO = 0.18
        const val MOLTIPLICATORE_RUMORE = 6.0
        const val PAUSA_CHIUSURA_SECONDI = 0.12
        const val DURATA_MINIMA_SECONDI = 0.10
        const val CODA_FRENATA_SECONDI = 0.15
    }
}

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
    private const val ID_METADATA = 0x0101
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

    /**
     * Legge l'intera traccia giroscopica di una foto o di un video.
     *
     * Nei metadati della Luna il fondo scala è `2000`: significa ±2000 °/s, quindi un grado al
     * secondo vale 32768/2000 conteggi. Se il file non dichiara la scala non si inventa nulla.
     */
    fun readGyroTrack(file: File): TracciaGiroscopio? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < CHAIN_START + SAMPLE) return null
            val magic = ByteArray(MAGIC.size)
            raf.seek(length - MAGIC.size)
            raf.readFully(magic)
            if (!magic.contentEquals(MAGIC)) return null

            val metadataBlock = findBlock(raf, length, ID_METADATA) ?: return null
            if (metadataBlock.size > Int.MAX_VALUE) return null
            val metadata = ByteArray(metadataBlock.size.toInt())
            raf.seek(metadataBlock.start)
            raf.readFully(metadata)
            val fullScale = ProtoReader(metadata).intOrNull(65, 2)?.takeIf { it > 0 } ?: return null

            val imu = findBlock(raf, length, ID_IMU) ?: return null
            if (imu.size > Int.MAX_VALUE) return null
            val data = ByteArray(imu.size.toInt())
            raf.seek(imu.start)
            raf.readFully(data)
            gyroTrackOf(data, fullScale)
        }
    }.getOrNull()

    /** Percorre la catena all'indietro e restituisce i dati del blocco inerziale. */
    private fun imuBlock(raf: RandomAccessFile, length: Long): ByteArray? {
        val block = findBlock(raf, length, ID_IMU) ?: return null
        if (block.size < SAMPLE * MIN_SAMPLES) return null
        val want = minOf(block.size, SEARCH_BYTES.toLong()).toInt()
        val data = ByteArray(want)
        raf.seek(block.start)
        raf.readFully(data)
        return data
    }

    private data class TrailerBlock(val start: Long, val size: Long)

    /** Trova un blocco della catena senza caricarlo: per un video la traccia può essere lunga. */
    private fun findBlock(raf: RandomAccessFile, length: Long, wantedId: Int): TrailerBlock? {
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
            if (id == wantedId) return TrailerBlock(start, size)
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

    /** Separata dal file per provare conversione e integrazione con una traccia costruita a mano. */
    internal fun gyroTrackOf(trailer: ByteArray, fullScaleDegreesPerSecond: Int): TracciaGiroscopio? {
        if (fullScaleDegreesPerSecond <= 0) return null
        val base = findTrack(trailer) ?: return null
        val maximum = (trailer.size - base) / SAMPLE
        val timestamps = LongArray(maximum)
        val axis0 = ShortArray(maximum)
        val axis1 = ShortArray(maximum)
        val axis2 = ShortArray(maximum)
        var count = 0
        var previous = -1L
        var offset = base
        while (offset + SAMPLE <= trailer.size) {
            val stamp = u64(trailer, offset)
            if (previous >= 0 && !plausibleStep(stamp - previous)) break
            timestamps[count] = stamp
            axis0[count] = biasedInt(trailer, offset + 14).toShort()
            axis1[count] = biasedInt(trailer, offset + 16).toShort()
            axis2[count] = biasedInt(trailer, offset + 18).toShort()
            previous = stamp
            count++
            offset += SAMPLE
        }
        if (count < MIN_SAMPLES) return null
        return TracciaGiroscopio(
            timestamps.copyOf(count),
            axis0.copyOf(count),
            axis1.copyOf(count),
            axis2.copyOf(count),
            fullScaleDegreesPerSecond,
        )
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
        biasedInt(b, at).toDouble()

    private fun biasedInt(b: ByteArray, at: Int): Int =
        ((b[at + 1].toInt() and 0xFF) shl 8 or (b[at].toInt() and 0xFF)) - 32768

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun u64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }
}
