package it.persoft.lunaultra.stitch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Dove sta un fotogramma sulla sfera, e come si torna indietro dai suoi pixel.
 *
 * Unire delle foto in una panoramica non è incollarle una accanto all'altra: ogni fotogramma è
 * una proiezione piana di un pezzo di sfera, e più il campo visivo è largo più i bordi sono
 * stirati rispetto al centro. Due fotogrammi affiancati senza correggere quella deformazione
 * combaciano al centro e divergono ai bordi, e la giunzione si vede sempre.
 *
 * La correzione è geometria, non ritocco. Ogni pixel della tela finita corrisponde a una
 * direzione — longitudine e latitudine — quella direzione si ruota nel sistema di riferimento
 * del fotogramma, e lì diventa un punto preciso sull'immagine originale attraverso la focale
 * dell'obiettivo. È il contrario della strada che fa la luce, e per questo si chiama proiezione
 * inversa: si parte dal risultato e si chiede a ogni foto cosa ci va.
 *
 * Il fatto che serve saperlo — la focale — l'app ce l'ha: il campo visivo dell'obiettivo è noto
 * per ogni livello di zoom. E dove puntava la camera a ogni scatto lo dice il piano della
 * panoramica, in gradi. Quindi la posizione di ogni fotogramma non va indovinata dalle immagini
 * come fa uno stitcher generico: si sa già, e alle immagini resta solo da correggere lo scarto.
 */

/** Un punto sull'immagine originale, in pixel, con l'indicazione se ci cade davvero dentro. */
data class SourcePoint(val x: Float, val y: Float, val inside: Boolean)

/**
 * La lente vista come proiezione: quanto vale un grado, e dove finisce un raggio.
 *
 * [focalPixels] è la distanza in pixel fra il centro di proiezione e il piano dell'immagine: è
 * il numero che lega gli angoli ai pixel in una lente rettilineare, e si ricava dal campo
 * visivo e dalla larghezza dell'immagine. Sotto i 90° di campo è ben definito; oltre, una lente
 * rettilineare non esisterebbe proprio, e infatti nessun obiettivo di questa camera ci arriva.
 */
class PinholeLens(
    val imageWidth: Int,
    val imageHeight: Int,
    val horizontalFovDegrees: Float,
) {
    val focalPixels: Float = (imageWidth / 2f) / tan(horizontalFovDegrees.toRadians() / 2f)

    /** Il campo verticale non si assume: discende dalla focale e dall'altezza vera del file. */
    val verticalFovDegrees: Float = 2f * atan2(imageHeight / 2f, focalPixels).toDegrees()

    /**
     * Da una direzione nel sistema del fotogramma al pixel che la guarda.
     *
     * Il vettore arriva già ruotato: qui resta solo la divisione prospettica, che è il punto in
     * cui nasce lo stiramento dei bordi. Un raggio dietro la camera (z ≤ 0) non ha nessun pixel
     * che lo guardi, e va scartato invece di produrre un punto specchiato.
     */
    fun project(x: Float, y: Float, z: Float): SourcePoint {
        if (z <= MIN_FORWARD) return SourcePoint(0f, 0f, inside = false)
        val px = imageWidth / 2f + focalPixels * x / z
        val py = imageHeight / 2f - focalPixels * y / z
        val inside = px >= 0f && py >= 0f && px <= imageWidth - 1f && py <= imageHeight - 1f
        return SourcePoint(px, py, inside)
    }

    private companion object {
        /** Sotto questo la divisione prospettica esplode: è il piano dietro l'obiettivo. */
        const val MIN_FORWARD = 1e-4f
    }
}

/**
 * Un fotogramma con il suo orientamento: dove guardava la camera quando l'ha scattato.
 *
 * [panDegrees] e [tiltDegrees] vengono dal piano della panoramica, non dalle immagini. È la
 * differenza fra questo e uno stitcher generico: quello deve dedurre le posizioni cercando
 * corrispondenze fra tutte le coppie, e su una parete uniforme o su un motivo ripetuto sbaglia.
 * Qui la posizione è nota in partenza, e il confronto fra immagini serve solo a correggere lo
 * scarto residuo — che la prova di andata e ritorno dice essere sotto i due gradi.
 */
data class FramePlacement(
    val panDegrees: Float,
    val tiltDegrees: Float,
    /** Correzioni trovate confrontando le immagini, sommate a quelle nominali. */
    val panCorrectionDegrees: Float = 0f,
    val tiltCorrectionDegrees: Float = 0f,
) {
    val effectivePan: Float get() = panDegrees + panCorrectionDegrees
    val effectiveTilt: Float get() = tiltDegrees + tiltCorrectionDegrees
}

/**
 * La tela finita: quanti gradi copre, con quanti pixel per grado.
 *
 * La proiezione è equirettangolare — longitudine sulle colonne, latitudine sulle righe — perché
 * una panoramica di questa camera può prendere anche centotrenta gradi in verticale, e una
 * proiezione cilindrica a quelle latitudini stirerebbe i poli fino a renderli inutilizzabili.
 *
 * La risoluzione non è quella nominale delle foto messe in fila: si sceglie il numero di pixel
 * per grado che conserva il dettaglio del centro dei fotogrammi, e si mette un tetto al lato
 * lungo perché una tela da centinaia di megapixel non entra nella memoria di un telefono.
 */
class PanoramaCanvas(
    val centerPanDegrees: Float,
    val centerTiltDegrees: Float,
    val horizontalDegrees: Float,
    val verticalDegrees: Float,
    val pixelsPerDegree: Float,
) {
    val width: Int = max(1, (horizontalDegrees * pixelsPerDegree).toInt())
    val height: Int = max(1, (verticalDegrees * pixelsPerDegree).toInt())

    /** Longitudine della colonna, in gradi, riferita allo zero della camera. */
    fun longitudeAt(column: Int): Float =
        centerPanDegrees - horizontalDegrees / 2f + (column + 0.5f) / pixelsPerDegree

    /** Latitudine della riga: la prima riga è la più alta, come in ogni immagine. */
    fun latitudeAt(row: Int): Float =
        centerTiltDegrees + verticalDegrees / 2f - (row + 0.5f) / pixelsPerDegree

    companion object {
        /**
         * La tela che contiene tutti i fotogrammi, con il margine del loro campo visivo.
         *
         * Si parte dagli angoli in cui sono stati scattati e si allarga di mezzo campo per
         * parte: è l'estensione che la panoramica copre davvero, che non è quella chiesta
         * — la griglia arrotonda sempre per eccesso, e il risultato è un po' più largo.
         */
        fun covering(
            placements: List<FramePlacement>,
            lens: PinholeLens,
            requestedPixelsPerDegree: Float,
            maximumLongSide: Int,
        ): PanoramaCanvas {
            require(placements.isNotEmpty()) { "Nessun fotogramma da unire" }
            val halfH = lens.horizontalFovDegrees / 2f
            val halfV = lens.verticalFovDegrees / 2f
            val minPan = placements.minOf { it.effectivePan } - halfH
            val maxPan = placements.maxOf { it.effectivePan } + halfH
            val minTilt = placements.minOf { it.effectiveTilt } - halfV
            val maxTilt = placements.maxOf { it.effectiveTilt } + halfV
            val spanH = (maxPan - minPan).coerceAtLeast(1f)
            val spanV = (maxTilt - minTilt).coerceAtLeast(1f)
            // Il tetto vale sul lato più lungo: una panoramica bassa e larga e una alta e
            // stretta devono costare la stessa memoria.
            val longestSpan = max(spanH, spanV)
            val capped = min(requestedPixelsPerDegree, maximumLongSide / longestSpan)
            return PanoramaCanvas(
                centerPanDegrees = (minPan + maxPan) / 2f,
                centerTiltDegrees = (minTilt + maxTilt) / 2f,
                horizontalDegrees = spanH,
                verticalDegrees = spanV,
                pixelsPerDegree = capped.coerceAtLeast(MIN_PIXELS_PER_DEGREE),
            )
        }

        /** Sotto questo la panoramica non è più un'immagine, è una miniatura. */
        const val MIN_PIXELS_PER_DEGREE = 2f
    }
}

/**
 * Da una direzione della tela al pixel del fotogramma che la guarda.
 *
 * Il giro è: longitudine e latitudine diventano un versore nello spazio; il versore si ruota
 * all'indietro dell'orientamento del fotogramma — prima il pan attorno all'asse verticale, poi
 * il tilt attorno a quello orizzontale — e quello che resta è la direzione vista dalla camera,
 * che l'obiettivo trasforma in un punto sull'immagine.
 *
 * L'ordine delle due rotazioni non è indifferente: pan e tilt di un gimbal non commutano, e
 * invertirli produce un errore che cresce con la latitudine — invisibile all'orizzonte e
 * grossolano a sessanta gradi, cioè esattamente dove una panoramica alta va a finire.
 */
fun projectToFrame(
    longitudeDegrees: Float,
    latitudeDegrees: Float,
    placement: FramePlacement,
    lens: PinholeLens,
): SourcePoint {
    val lon = (longitudeDegrees - placement.effectivePan).toRadians()
    val lat = latitudeDegrees.toRadians()
    val tilt = placement.effectiveTilt.toRadians()

    // Versore della direzione, con z in avanti, x a destra e y in alto.
    val cosLat = cos(lat)
    val x = cosLat * sin(lon)
    val y = sin(lat)
    val z = cosLat * cos(lon)

    // Rotazione inversa del tilt attorno all'asse orizzontale.
    val cosT = cos(tilt)
    val sinT = sin(tilt)
    val yr = y * cosT - z * sinT
    val zr = y * sinT + z * cosT

    return lens.project(x, yr, zr)
}

/**
 * Quanto pesa un pixel del fotogramma nella fusione: pieno al centro, spento sul bordo.
 *
 * È la sfumatura che nasconde una giunzione imperfetta. Dove due fotogrammi si sovrappongono i
 * pesi si sommano e il risultato è una media che passa dolcemente dall'uno all'altro, invece di
 * uno scalino sul confine. Un allineamento perfetto non ne avrebbe bisogno; un allineamento
 * vero sì, sempre, perché la parallasse fra due scatti presi da centri diversi non si annulla
 * con nessuna rotazione.
 *
 * Il peso segue la distanza dal bordo più vicino, normalizzata sulla metà del lato corto: al
 * centro vale uno, e cala fino a zero esattamente sul bordo. Il quadrato rende la transizione
 * più morbida di una rampa lineare, che a volte lascia intravedere una banda.
 */
fun featherWeight(x: Float, y: Float, width: Int, height: Int): Float {
    if (width <= 1 || height <= 1) return 0f
    val dx = min(x, width - 1f - x)
    val dy = min(y, height - 1f - y)
    if (dx < 0f || dy < 0f) return 0f
    val scale = min(width, height) / 2f
    val edge = min(dx, dy) / scale
    val clamped = edge.coerceIn(0f, 1f)
    return clamped * clamped
}

/**
 * Il fattore di luminosità che rende due fotogrammi confrontabili sulla sovrapposizione.
 *
 * Due scatti dello stesso posto a esposizione automatica non hanno la stessa luminosità: la
 * camera rimisura fra uno e l'altro, e su una panoramica che va dal cielo all'ombra la
 * differenza è netta. Senza correzione la sfumatura non nasconde niente, perché non è il
 * confine a vedersi ma il salto di tono ai suoi due lati.
 *
 * Il rapporto fra le medie è la correzione più semplice che funziona, e si limita: oltre certi
 * valori non è più esposizione diversa, è che la sovrapposizione conteneva due cose diverse — e
 * in quel caso correggere peggiora.
 */
fun exposureGain(referenceMean: Float, frameMean: Float): Float {
    if (frameMean <= 1f || referenceMean <= 1f) return 1f
    return (referenceMean / frameMean).coerceIn(MIN_EXPOSURE_GAIN, MAX_EXPOSURE_GAIN)
}

const val MIN_EXPOSURE_GAIN = 0.6f
const val MAX_EXPOSURE_GAIN = 1.7f

/**
 * Lo scarto in gradi che corrisponde a uno spostamento in pixel sulla tela.
 *
 * La raffinatura confronta le immagini e trova di quanti pixel un fotogramma è fuori posto; per
 * rimetterlo a posto quel numero va riportato in gradi, che è l'unità in cui il fotogramma
 * viene collocato. Alla latitudine [latitudeDegrees] i meridiani si stringono, quindi un grado
 * di longitudine vale meno pixel: senza il coseno la correzione orizzontale sarebbe giusta
 * all'orizzonte e sbagliata in alto.
 */
fun pixelsToDegrees(pixels: Float, pixelsPerDegree: Float, latitudeDegrees: Float, horizontal: Boolean): Float {
    if (pixelsPerDegree <= 0f) return 0f
    val base = pixels / pixelsPerDegree
    if (!horizontal) return base
    val shrink = cos(latitudeDegrees.toRadians())
    return if (abs(shrink) < MIN_COS_LATITUDE) base else base / shrink
}

private const val MIN_COS_LATITUDE = 0.05f

/** Distanza angolare fra due direzioni, in gradi: serve a sapere quali fotogrammi si toccano. */
fun angularDistance(
    panA: Float,
    tiltA: Float,
    panB: Float,
    tiltB: Float,
): Float {
    val latA = tiltA.toRadians()
    val latB = tiltB.toRadians()
    val deltaLon = (panA - panB).toRadians()
    val cosine = sin(latA) * sin(latB) + cos(latA) * cos(latB) * cos(deltaLon)
    return atan2(sqrt((1f - cosine * cosine).coerceAtLeast(0f)), cosine).toDegrees()
}

fun Float.toRadians(): Float = (this * PI / 180.0).toFloat()

fun Float.toDegrees(): Float = (this * 180.0 / PI).toFloat()
