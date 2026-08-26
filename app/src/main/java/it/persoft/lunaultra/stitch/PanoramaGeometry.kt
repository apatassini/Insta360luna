package it.persoft.lunaultra.stitch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
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
    /**
     * La rotazione attorno all'asse ottico, stimata dai punti di controllo: la camera su un
     * gimbal non è mai perfettamente in bolla, e una foto anche solo mezzo grado storta
     * combacia al centro e diverge ai bordi. È il parametro che gli stitcher seri (Hugin,
     * Autopano) ottimizzano insieme a pan e tilt, e che qui mancava.
     */
    val rollDegrees: Float = 0f,
    /**
     * Correzione moltiplicativa della focale per questo fotogramma: assorbe il piccolo
     * respiro dello zoom e gli errori del campo visivo dichiarato.
     */
    val focalScale: Float = 1f,
) {
    val effectivePan: Float get() = panDegrees + panCorrectionDegrees
    val effectiveTilt: Float get() = tiltDegrees + tiltCorrectionDegrees
}

/**
 * La forma in cui la sfera viene stesa sul rettangolo finito.
 *
 * Nessuna è «giusta»: una superficie curva non si appiattisce senza deformare qualcosa, e
 * ognuna sceglie cosa sacrificare. Quello che tutte e tre conservano è la verticale — una
 * linea verticale del mondo resta verticale — e l'orizzonte, che resta una riga dritta.
 */
enum class StitchProjection(val label: String, val limitDegrees: Float) {
    /**
     * Latitudine lineare sulle righe. È il formato dello standard sferico 2:1, l'unico che
     * arriva ai poli, e l'unico che un visualizzatore 360° sa leggere.
     */
    EQUIRECTANGULAR("Equirettangolare", 90f),

    /**
     * Il cilindro appoggiato attorno alla sfera: `y = R·tan(latitudine)`.
     *
     * È la proiezione delle panoramiche a fila singola, quella che usano le app dei telefoni.
     * Vicino all'orizzonte le altezze restano naturali invece di essere compresse come
     * nell'equirettangolare; in cambio verso l'alto e verso il basso si stira in fretta, e
     * ai poli non arriva affatto — motivo per cui una sferica non può usarla.
     */
    CYLINDRICAL("Cilindrica", 75f),

    /**
     * Mercatore: `y = R·ln(tan(45° + latitudine/2))`.
     *
     * La via di mezzo, e l'unica conforme delle tre: conserva le forme in piccolo, quindi
     * niente sembra schiacciato o stirato **localmente**, a costo di gonfiare le altezze
     * lontano dall'orizzonte. Buona per le file singole alte.
     */
    MERCATOR("Mercatore", 80f),
}

/**
 * La tela finita: quanti gradi copre, con quanti pixel per grado, e in che proiezione.
 *
 * Le colonne sono sempre longitudine lineare — è la parte facile, e tutte e tre le
 * proiezioni la trattano uguale. La differenza sta nelle righe: [pixelsPerDegree] è la scala
 * verticale **all'orizzonte**, e da lì in su e in giù ogni proiezione va per la sua strada.
 *
 * La risoluzione non è quella nominale delle foto messe in fila: si sceglie il numero di
 * pixel per grado che conserva il dettaglio del centro dei fotogrammi, e si mette un tetto al
 * lato lungo perché una tela da centinaia di megapixel non entra nella memoria di un telefono.
 */
class PanoramaCanvas(
    val centerPanDegrees: Float,
    val centerTiltDegrees: Float,
    val horizontalDegrees: Float,
    val verticalDegrees: Float,
    val pixelsPerDegree: Float,
    val projection: StitchProjection = StitchProjection.EQUIRECTANGULAR,
) {
    private val topLatitude = centerTiltDegrees + verticalDegrees / 2f
    private val bottomLatitude = centerTiltDegrees - verticalDegrees / 2f

    /** Il raggio del cilindro in pixel: quello che rende [pixelsPerDegree] la scala a zero. */
    private val radius = pixelsPerDegree * DEGREES_PER_RADIAN

    private val topY = verticalPixel(topLatitude)

    /** La longitudine da cui partono le colonne: la scheda grafica ricalcola da qui. */
    val startLongitudeDegrees: Float get() = centerPanDegrees - horizontalDegrees / 2f

    /** Il raggio del cilindro e l'origine verticale, per chi rifà la mappa riga↔latitudine. */
    val verticalRadius: Float get() = radius
    val topPixel: Float get() = topY

    val width: Int = max(1, (horizontalDegrees * pixelsPerDegree).toInt())
    val height: Int = max(1, (topY - verticalPixel(bottomLatitude)).toInt())

    /** Longitudine della colonna, in gradi, riferita allo zero della camera. */
    fun longitudeAt(column: Int): Float =
        centerPanDegrees - horizontalDegrees / 2f + (column + 0.5f) / pixelsPerDegree

    /** Latitudine della riga: la prima riga è la più alta, come in ogni immagine. */
    fun latitudeAt(row: Int): Float = latitudeOfPixel(topY - (row + 0.5f))

    /** La riga su cui cade una latitudine: l'inverso esatto di [latitudeAt]. */
    fun rowOf(latitudeDegrees: Float): Float = topY - verticalPixel(latitudeDegrees)

    private fun verticalPixel(latitudeDegrees: Float): Float {
        val lat = latitudeDegrees.coerceIn(-projection.limitDegrees, projection.limitDegrees).toRadians()
        return when (projection) {
            StitchProjection.EQUIRECTANGULAR -> lat.toDegrees() * pixelsPerDegree
            StitchProjection.CYLINDRICAL -> radius * tan(lat)
            StitchProjection.MERCATOR -> radius * ln(tan(QUARTER_TURN_RADIANS + lat / 2f))
        }
    }

    private fun latitudeOfPixel(y: Float): Float = when (projection) {
        StitchProjection.EQUIRECTANGULAR -> y / pixelsPerDegree
        StitchProjection.CYLINDRICAL -> atan(y / radius).toDegrees()
        StitchProjection.MERCATOR -> (2f * atan(exp(y / radius)) - QUARTER_TURN_RADIANS * 2f).toDegrees()
    }

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
            projection: StitchProjection = StitchProjection.EQUIRECTANGULAR,
            /**
             * Fin dove salire e scendere, in gradi dall'orizzonte. Zero: fin dove arrivano le foto.
             *
             * È il ritaglio che in Autopano si fa trascinando il bordo, ed è quasi sempre la cosa
             * giusta da fare. Vicino allo zenit ogni proiezione piatta deforma — non è un difetto
             * dello stitcher, è che una sfera non sta su un foglio — e gli ultimi dieci gradi di
             * cielo costano tantissimi pixel per mostrare rami stirati. Tagliandoli si guadagna
             * due volte: sparisce la deformazione peggiore, e la larghezza cresce, perché la
             * densità della tela la decide l'area totale.
             */
            verticalLimitDegrees: Float = 0f,
        ): PanoramaCanvas {
            require(placements.isNotEmpty()) { "Nessun fotogramma da unire" }
            val halfH = lens.horizontalFovDegrees / 2f
            val halfV = lens.verticalFovDegrees / 2f
            val minPan = placements.minOf { it.effectivePan } - halfH
            val maxPan = placements.maxOf { it.effectivePan } + halfH
            val minTilt = placements.minOf { it.effectiveTilt } - halfV
            val maxTilt = placements.maxOf { it.effectiveTilt } + halfV
            // Oltre il giro completo la tela ricomincerebbe da capo: le colonne in più
            // rifarebbero longitudini già disegnate, e la panoramica avrebbe un pezzo doppio.
            // Succede davvero: a 1× i 292° di corsa più gli 81,7° di campo fanno 373,7.
            val spanH = (maxPan - minPan).coerceIn(1f, FULL_TURN_DEGREES)
            // Oltre il limite della proiezione non c'è niente da disegnare: una latitudine di
            // 153° non esiste, e per la cilindrica anche molto prima la scala esplode.
            val limit = if (verticalLimitDegrees > 0f) {
                min(projection.limitDegrees, verticalLimitDegrees)
            } else {
                projection.limitDegrees
            }
            val topTilt = min(maxTilt, limit)
            val bottomTilt = max(minTilt, -limit)
            val spanV = (topTilt - bottomTilt).coerceAtLeast(1f)
            // Il tetto vale sul lato più lungo: una panoramica bassa e larga e una alta e
            // stretta devono costare la stessa memoria. L'altezza in pixel non è più
            // «gradi × densità» — con la cilindrica cresce più in fretta — quindi si misura
            // a densità unitaria, che è lecito perché è lineare nella densità.
            val heightPerDensity = verticalExtentPerDensity(topTilt, bottomTilt, projection)
            val longestPerDensity = max(spanH, heightPerDensity)
            val capped = min(requestedPixelsPerDegree, maximumLongSide / longestPerDensity)
            return PanoramaCanvas(
                centerPanDegrees = (minPan + maxPan) / 2f,
                centerTiltDegrees = (bottomTilt + topTilt) / 2f,
                horizontalDegrees = spanH,
                verticalDegrees = spanV,
                pixelsPerDegree = capped.coerceAtLeast(MIN_PIXELS_PER_DEGREE),
                projection = projection,
            )
        }

        /** L'altezza della tela per unità di densità: serve a mettere il tetto prima di costruirla. */
        private fun verticalExtentPerDensity(
            topDegrees: Float,
            bottomDegrees: Float,
            projection: StitchProjection,
        ): Float {
            val top = topDegrees.coerceIn(-projection.limitDegrees, projection.limitDegrees).toRadians()
            val bottom = bottomDegrees.coerceIn(-projection.limitDegrees, projection.limitDegrees).toRadians()
            return when (projection) {
                StitchProjection.EQUIRECTANGULAR -> (top - bottom).toDegrees()
                StitchProjection.CYLINDRICAL -> DEGREES_PER_RADIAN * (tan(top) - tan(bottom))
                StitchProjection.MERCATOR -> DEGREES_PER_RADIAN *
                    (ln(tan(QUARTER_TURN_RADIANS + top / 2f)) - ln(tan(QUARTER_TURN_RADIANS + bottom / 2f)))
            }.coerceAtLeast(1f)
        }

        /** Sotto questo la panoramica non è più un'immagine, è una miniatura. */
        const val MIN_PIXELS_PER_DEGREE = 2f

        /** Il polo: oltre non c'è sfera, e una tela che ci va oltre si ribalta su sé stessa. */
        const val POLE_DEGREES = 90f

        /** Il giro completo: una tela più larga ridisegnerebbe longitudini già fatte. */
        const val FULL_TURN_DEGREES = 360f

        /** Quanti gradi vale un radiante: lega il raggio del cilindro alla densità. */
        const val DEGREES_PER_RADIAN = 57.29578f

        /** Un quarto di giro in radianti, per la formula di Mercatore. */
        const val QUARTER_TURN_RADIANS = 0.7853982f
    }
}


/**
 * La copertura di uno scatto sferico: tutta la corsa che il gimbal ha davvero.
 *
 * Quanto ne esce dipende dall'obiettivo, ed è un conto che vale la pena fare invece di
 * promettere una sfera. La corsa del pan è 292° su 360, ma un fotogramma non è una linea: il
 * primo scatto vede mezzo campo *prima* del suo centro e l'ultimo mezzo campo *dopo*. A 1×,
 * dove il campo è 81,7°, l'arco coperto è 292 + 81,7 = 373,7 — **il giro si chiude davvero**.
 * Da 2× in su il campo si stringe e non basta più: a 2× ne restano fuori 22, a 12× sessanta.
 *
 * In verticale la corsa è 177° su 180, e il fotogramma più basso, guardando in giù dal limite,
 * arriva quasi al nadir: quel poco che manca è il buco che va chiuso dopo l'unione.
 *
 * Il conto che conta è dove vanno i *centri* dei fotogrammi, non quanto copre il risultato. Il
 * pianificatore ragiona in copertura, e la copertura è i centri più un campo visivo — mezzo per
 * parte. Quindi per far arrivare i centri fino agli estremi della corsa si chiede una copertura
 * pari alla corsa più un campo intero. Chiedere invece «copri 177°» lascerebbe i centri
 * all'interno e la camera non guarderebbe mai davvero in basso, che è proprio il posto dove
 * serve arrivare.
 *
 * Il margine tiene i centri dentro i limiti anche quando l'aritmetica in virgola mobile li
 * porterebbe sul limite esatto: lì il pianificatore rifiuterebbe il piano per un millesimo.
 */
data class SphericalCoverage(
    val centerPanDegrees: Float,
    val centerTiltDegrees: Float,
    val horizontalDegrees: Float,
    val verticalDegrees: Float,
) {
    /** Quanto resta fuori dietro le spalle: zero quando il campo dell'obiettivo chiude il giro. */
    val missingHorizontalDegrees: Float
        get() = (PanoramaCanvas.FULL_TURN_DEGREES - horizontalDegrees).coerceAtLeast(0f)

    /** Vero quando l'arco coperto arriva o supera il giro: succede a 1×, non oltre. */
    val closesTheCircle: Boolean
        get() = horizontalDegrees >= PanoramaCanvas.FULL_TURN_DEGREES
}

fun sphericalCoverage(
    panMinimumDeg: Float,
    panMaximumDeg: Float,
    tiltMinimumDeg: Float,
    tiltMaximumDeg: Float,
    horizontalFovDegrees: Float,
    verticalFovDegrees: Float,
    marginDegrees: Float = SPHERICAL_MARGIN_DEG,
): SphericalCoverage {
    val panLow = panMinimumDeg + marginDegrees
    val panHigh = panMaximumDeg - marginDegrees
    val tiltLow = tiltMinimumDeg + marginDegrees
    val tiltHigh = tiltMaximumDeg - marginDegrees
    val panCenterSpan = (panHigh - panLow).coerceAtLeast(0f)
    val tiltCenterSpan = (tiltHigh - tiltLow).coerceAtLeast(0f)
    return SphericalCoverage(
        centerPanDegrees = (panLow + panHigh) / 2f,
        centerTiltDegrees = (tiltLow + tiltHigh) / 2f,
        horizontalDegrees = panCenterSpan + horizontalFovDegrees,
        verticalDegrees = tiltCenterSpan + verticalFovDegrees,
    )
}

/** Un paio di gradi di margine: i centri restano dentro la corsa senza sfiorarne il limite. */
const val SPHERICAL_MARGIN_DEG = 2f

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

    // Rotazione inversa del rollio attorno all'asse ottico, e la scala della focale:
    // moltiplicare le componenti trasversali equivale a scalare la focale stessa.
    val roll = placement.rollDegrees.toRadians()
    val cosR = cos(roll)
    val sinR = sin(roll)
    val xr = x * cosR + yr * sinR
    val yrr = -x * sinR + yr * cosR

    return lens.project(xr * placement.focalScale, yrr * placement.focalScale, zr)
}

/**
 * La stessa proiezione di [projectToFrame], preparata per essere chiamata milioni di volte.
 *
 * [projectToFrame] è la versione leggibile, ed è quella giusta per l'allineamento, dove le
 * chiamate sono migliaia. Per la cucitura le chiamate sono **centinaia di milioni**, e lì due
 * dettagli che a leggerli non si notano diventano il costo dominante.
 *
 * Il primo: delle otto funzioni trigonometriche che calcola, **nessuna dipende davvero dal
 * pixel**. Seno e coseno di inclinazione e rollio sono costanti per tutto il fotogramma;
 * quelli della latitudine cambiano solo passando di riga; quelli della longitudine solo
 * passando di colonna — e le colonne sono sempre le stesse, quindi si tabulano una volta.
 * Chiamate per pixel, sono otto calcoli buttati via su ognuno.
 *
 * Il secondo: [projectToFrame] restituisce un [SourcePoint], che è un oggetto. Un oggetto per
 * pixel sono centinaia di milioni di oggetti da raccogliere, e il netturbino ferma tutti i
 * fili mentre passa. Qui il risultato si posa in tre campi che vivono quanto il proiettore.
 *
 * Un proiettore appartiene a un filo solo — se ne costruisce uno per riga, che a fronte dei
 * pixel di quella riga non è niente — e non va condiviso, perché i suoi campi sono il
 * risultato dell'ultima chiamata.
 */
class FrameProjector(
    placement: FramePlacement,
    private val lens: PinholeLens,
    private val warp: LocalWarp? = null,
) {
    /** Longitudine del centro del fotogramma: le colonne si tabulano rispetto a questa. */
    val panDegrees: Float = placement.effectivePan

    private val cosTilt: Float
    private val sinTilt: Float
    private val cosRoll: Float
    private val sinRoll: Float
    private val scale = placement.focalScale
    private val focal = lens.focalPixels
    private val halfWidth = lens.imageWidth / 2f
    private val halfHeight = lens.imageHeight / 2f
    private val maxX = lens.imageWidth - 1f
    private val maxY = lens.imageHeight - 1f

    /** Seno e coseno della longitudine del centro: servono a chi ha le direzioni tabulate. */
    private val sinPan: Float = panDegrees.toRadians().let { sin(it) }
    private val cosPan: Float = panDegrees.toRadians().let { cos(it) }

    private var cosLat = 1f
    private var sinLat = 0f

    /** Il pixel trovato dall'ultima chiamata a [project]: valido solo se [inside] è vero. */
    var x = 0f
        private set
    var y = 0f
        private set
    var inside = false
        private set

    init {
        val tilt = placement.effectiveTilt.toRadians()
        cosTilt = cos(tilt)
        sinTilt = sin(tilt)
        val roll = placement.rollDegrees.toRadians()
        cosRoll = cos(roll)
        sinRoll = sin(roll)
    }

    /** Cambia riga: da qui in poi le proiezioni sono a questa latitudine. */
    fun row(latitudeDegrees: Float) {
        val lat = latitudeDegrees.toRadians()
        cosLat = cos(lat)
        sinLat = sin(lat)
    }

    /**
     * Proietta la colonna il cui scarto di longitudine dal centro del fotogramma ha questo
     * seno e questo coseno — tabulati una volta per tutte dal chiamante.
     */
    fun project(sinLon: Float, cosLon: Float) = projectAt(sinLat, cosLat, sinLon, cosLon)

    /**
     * Proietta una direzione di cui si conoscono già seno e coseno di latitudine e
     * longitudine **assolute**, senza passare per [row].
     *
     * È la strada dell'allineamento, dove le direzioni campione sono fisse e i candidati
     * migliaia: tabularle una volta e togliere la longitudine del candidato con la formula
     * di sottrazione — `sin(a−b) = sin a·cos b − cos a·sin b` — costa due moltiplicazioni al
     * posto di due funzioni trigonometriche, per ognuna delle centinaia di milioni di volte
     * che il confronto le chiede.
     */
    fun projectDirection(sinLat: Float, cosLat: Float, sinLon: Float, cosLon: Float) {
        projectAt(
            sinLat,
            cosLat,
            sinLon * cosPan - cosLon * sinPan,
            cosLon * cosPan + sinLon * sinPan,
        )
    }

    private fun projectAt(sinLat: Float, cosLat: Float, sinLon: Float, cosLon: Float) {
        val worldX = cosLat * sinLon
        val worldY = sinLat
        val worldZ = cosLat * cosLon

        val tiltedY = worldY * cosTilt - worldZ * sinTilt
        val tiltedZ = worldY * sinTilt + worldZ * cosTilt
        if (tiltedZ <= MIN_FORWARD) {
            inside = false
            return
        }

        val rolledX = worldX * cosRoll + tiltedY * sinRoll
        val rolledY = -worldX * sinRoll + tiltedY * cosRoll

        var px = halfWidth + focal * (rolledX * scale) / tiltedZ
        var py = halfHeight - focal * (rolledY * scale) / tiltedZ
        if (warp != null && px >= 0f && py >= 0f && px <= maxX && py <= maxY) {
            val shiftedX = px + warp.shiftX(px, py)
            val shiftedY = py + warp.shiftY(px, py)
            px = shiftedX
            py = shiftedY
        }
        x = px
        y = py
        inside = px >= 0f && py >= 0f && px <= maxX && py <= maxY
    }

    private companion object {
        /** Lo stesso piano dietro l'obiettivo oltre cui [PinholeLens] si rifiuta di dividere. */
        const val MIN_FORWARD = 1e-4f
    }
}

/**
 * Da un pixel del fotogramma alla direzione nel mondo: l'inverso esatto di [projectToFrame].
 *
 * Serve ai punti di coerenza: un dettaglio trovato in un fotogramma diventa una direzione
 * (longitudine, latitudine), e la stessa direzione si va a cercare nell'altro fotogramma. La
 * differenza fra dove la geometria lo prevede e dove l'immagine lo trova è la correzione.
 */
fun frameToWorld(
    pixelX: Float,
    pixelY: Float,
    placement: FramePlacement,
    lens: PinholeLens,
): FloatArray {
    val u = (pixelX - lens.imageWidth / 2f) / (lens.focalPixels * placement.focalScale)
    val v = (lens.imageHeight / 2f - pixelY) / (lens.focalPixels * placement.focalScale)

    // Rotazione diretta del rollio: l'inversa di quella applicata nella proiezione.
    val roll = placement.rollDegrees.toRadians()
    val cosR = cos(roll)
    val sinR = sin(roll)
    val x = u * cosR - v * sinR
    val y = u * sinR + v * cosR

    val norm = sqrt(x * x + y * y + 1f)
    val xn = x / norm
    val yn = y / norm
    val zn = 1f / norm

    // Rotazione diretta del tilt: l'inversa di quella applicata nella proiezione.
    val tilt = placement.effectiveTilt.toRadians()
    val cosT = cos(tilt)
    val sinT = sin(tilt)
    val yw = yn * cosT + zn * sinT
    val zw = -yn * sinT + zn * cosT

    val latitude = asin(yw.coerceIn(-1f, 1f)).toDegrees()
    val longitude = atan2(xn, zw).toDegrees() + placement.effectivePan
    return floatArrayOf(longitude, latitude)
}

/**
 * Il campo di spostamento locale di un fotogramma: quello che la rotazione non può sistemare.
 *
 * Un gimbal non ruota attorno al centro ottico dell'obiettivo, ma attorno a un asse che gli sta
 * qualche centimetro dietro. Fra uno scatto e l'altro la camera quindi non gira soltanto: si
 * **sposta**. E quando la camera si sposta, quello che è vicino scorre più di quello che è
 * lontano — è la parallasse, la stessa per cui il dito davanti al naso salta chiudendo un occhio
 * per volta. Nessuna rotazione, per quanto ben stimata, rimette d'accordo due profondità nello
 * stesso momento: se allinei il bambù in fondo, la tenda davanti resta fuori posto, e viceversa.
 *
 * Quello che si può fare è quello che fa Autopano: prendere i punti di controllo rimasti fuori
 * posto **dopo** l'allineamento globale e trasformarli in un campo di spostamento morbido, da
 * applicare al campionamento. Localmente le due foto tornano a coincidere; il campo è liscio, e
 * dove non ci sono punti va a zero, così non inventa niente.
 *
 * La griglia è piccola apposta — poche celle sul fotogramma — e ogni nodo è una media pesata dei
 * punti vicini con una campana: nessun punto singolo può creare uno strappo, e un punto sbagliato
 * pesa quanto i suoi vicini gli concedono.
 */
class LocalWarp private constructor(
    private val nodesX: Int,
    private val nodesY: Int,
    private val cellWidth: Float,
    private val cellHeight: Float,
    private val dx: FloatArray,
    private val dy: FloatArray,
    /** Lo spostamento più grande del campo, in pixel: serve solo al racconto nel log. */
    val worstShiftPixels: Float,
) {
    /**
     * I nodi in fila — dx, dy, dx, dy… — per chi deve passarli altrove, per esempio alla
     * scheda grafica, che il campo lo interpola per conto suo con la stessa formula.
     */
    fun interleaved(): FloatArray = FloatArray(dx.size * 2) { i ->
        if (i % 2 == 0) dx[i / 2] else dy[i / 2]
    }

    fun shiftX(x: Float, y: Float): Float = sample(dx, x, y)
    fun shiftY(x: Float, y: Float): Float = sample(dy, x, y)

    private fun sample(grid: FloatArray, x: Float, y: Float): Float {
        val gx = (x / cellWidth).coerceIn(0f, (nodesX - 1).toFloat())
        val gy = (y / cellHeight).coerceIn(0f, (nodesY - 1).toFloat())
        val x0 = gx.toInt().coerceAtMost(nodesX - 1)
        val y0 = gy.toInt().coerceAtMost(nodesY - 1)
        val x1 = min(x0 + 1, nodesX - 1)
        val y1 = min(y0 + 1, nodesY - 1)
        val fx = gx - x0
        val fy = gy - y0
        val top = grid[y0 * nodesX + x0] * (1f - fx) + grid[y0 * nodesX + x1] * fx
        val bottom = grid[y1 * nodesX + x0] * (1f - fx) + grid[y1 * nodesX + x1] * fx
        return top * (1f - fy) + bottom * fy
    }

    companion object {
        /**
         * Costruisce il campo dai punti rimasti fuori posto.
         *
         * Ogni voce di [points] è `[x nel fotogramma, y nel fotogramma, spostamento x,
         * spostamento y]`: dove la geometria prevedeva il dettaglio, e di quanto si è
         * sbagliata. Restituisce null quando i punti sono troppo pochi per fidarsi.
         */
        fun from(
            points: List<FloatArray>,
            frameWidth: Int,
            frameHeight: Int,
            maximumShiftPixels: Float,
            /**
             * Quanto è stretta la campana, in frazioni del lato lungo: 4 è morbida e
             * prudente, 10 segue il dettaglio locale. È la manopola che decide se la foto
             * viene «deformata di più»: una campana stretta può rappresentare un
             * allargamento progressivo — le stecche di bambù che vanno allargate man mano
             * che ci si sposta — mentre una larga lo media via.
             */
            sigmaDivisor: Float = 6f,
        ): LocalWarp? {
            if (points.size < MIN_POINTS || frameWidth <= 1 || frameHeight <= 1) return null
            val cellWidth = frameWidth.toFloat() / (NODES_X - 1)
            val cellHeight = frameHeight.toFloat() / (NODES_Y - 1)
            val dx = FloatArray(NODES_X * NODES_Y)
            val dy = FloatArray(NODES_X * NODES_Y)
            // Più stretta segue il singolo punto (e i suoi errori), più larga torna a essere
            // una traslazione globale — che l'allineamento ha già tolto.
            val sigma = max(frameWidth, frameHeight) / sigmaDivisor.coerceIn(2f, 16f)
            val twoSigmaSquared = 2f * sigma * sigma
            var worst = 0f
            for (ny in 0 until NODES_Y) {
                val nodeY = ny * cellHeight
                for (nx in 0 until NODES_X) {
                    val nodeX = nx * cellWidth
                    var sumWeight = 0f
                    var sumX = 0f
                    var sumY = 0f
                    for (point in points) {
                        val ex = point[0] - nodeX
                        val ey = point[1] - nodeY
                        val weight = exp(-(ex * ex + ey * ey) / twoSigmaSquared)
                        sumWeight += weight
                        sumX += weight * point[2]
                        sumY += weight * point[3]
                    }
                    // Sotto un minimo di sostegno il nodo resta fermo: meglio non correggere
                    // che correggere per sentito dire da un punto lontano.
                    val index = ny * NODES_X + nx
                    if (sumWeight < MIN_SUPPORT) continue
                    val vx = (sumX / sumWeight).coerceIn(-maximumShiftPixels, maximumShiftPixels)
                    val vy = (sumY / sumWeight).coerceIn(-maximumShiftPixels, maximumShiftPixels)
                    dx[index] = vx
                    dy[index] = vy
                    worst = max(worst, sqrt(vx * vx + vy * vy))
                }
            }
            if (worst < MIN_USEFUL_SHIFT) return null
            return LocalWarp(NODES_X, NODES_Y, cellWidth, cellHeight, dx, dy, worst)
        }

        /**
         * Nodi della griglia. Devono essere abbastanza fitti da rappresentare la campana più
         * stretta che si può chiedere: con dodici intervalli sul lato lungo il passo è un
         * dodicesimo, e una campana da un decimo ci sta comoda. La morbidezza la garantisce
         * la campana, non la scarsità dei nodi.
         */
        const val NODES_X = 13
        const val NODES_Y = 9

        /** Sotto questi punti il campo sarebbe il ritratto del rumore. */
        const val MIN_POINTS = 24

        /** Il peso complessivo sotto il quale un nodo non ha davvero nessuno che lo sostenga. */
        const val MIN_SUPPORT = 0.75f

        /** Un campo che sposta meno di così non vale il conto in più al campionamento. */
        const val MIN_USEFUL_SHIFT = 0.35f
    }
}

/** La differenza fra due longitudini, riportata nel giro corto: 350° − 10° fa −20, non 340. */
fun wrapDegrees(delta: Float): Float {
    var d = delta % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
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

/**
 * Da che parte si guarda la panoramica: il punto che finisce al centro della tela.
 *
 * Una panoramica non ha un «diritto» e un «rovescio». I fotogrammi stanno su una sfera, e
 * stenderla su un rettangolo deforma: quanto e dove dipende da quale punto della sfera si
 * mette al centro. Lo stesso ramo in cima, che nell'equirettangolare centrata sull'orizzonte
 * si allarga cinque volte, portato al centro non si allarga affatto — e a pagare diventa
 * qualcos'altro, il mare, che al centro non c'è più.
 *
 * Chi cuce a mano questa scelta la fa a occhio, guardando: e` per questo che serve vederla.
 *
 * [panDegrees] gira l'orizzonte, [tiltDegrees] alza o abbassa il centro, [rollDegrees]
 * inclina tutto. Zero su tutti e tre e` la panoramica come l'ha vista il gimbal.
 */
data class PanoramaView(
    val panDegrees: Float = 0f,
    val tiltDegrees: Float = 0f,
    val rollDegrees: Float = 0f,
    /** La proiezione voluta, o null per lasciar decidere alla copertura. */
    val projection: StitchProjection? = null,
    /** Fin dove sale la tela, in gradi dall'orizzonte. Zero = nessun limite. */
    val verticalLimitDegrees: Float = 0f,
) {
    val turned: Boolean
        get() = panDegrees != 0f || tiltDegrees != 0f || rollDegrees != 0f
}

/**
 * Lo stesso fotogramma, visto da un altro punto di vista.
 *
 * Il pan da solo sarebbe una sottrazione: girare l'orizzonte sposta tutte le longitudini della
 * stessa quantita`. L'inclinazione no. Pan, tilt e rollio non sono tre numeri indipendenti da
 * sommare: sono tre rotazioni in fila — attorno alla verticale, poi all'asse orizzontale, poi
 * all'asse ottico — e alzare il centro di venti gradi cambia anche il pan e il rollio apparenti
 * di ogni fotogramma, tanto piu` quanto piu` sta lontano dal centro. Chi somma e basta ottiene
 * una panoramica che al centro sembra giusta e ai bordi si apre a ventaglio.
 *
 * Quindi si fa il conto vero: si compone la rotazione del fotogramma con l'inversa di quella
 * dello sguardo, e dalla matrice che ne esce si rileggono i tre angoli. Le correzioni
 * dell'allineamento sono gia` dentro — [FramePlacement.effectivePan] e [effectiveTilt] — e nel
 * risultato tornano come angoli nominali, perche` dopo una rotazione «nominale» e «corretto»
 * non hanno piu` due strade separate.
 */
fun FramePlacement.seenFrom(view: PanoramaView): FramePlacement {
    if (!view.turned) return this
    val frame = rotationYxz(effectivePan, effectiveTilt, rollDegrees)
    val eye = rotationYxz(view.panDegrees, view.tiltDegrees, view.rollDegrees)
    val turned = multiply(transpose(eye), frame)
    val angles = yxzAngles(turned)
    return FramePlacement(
        panDegrees = angles[0],
        tiltDegrees = angles[1],
        panCorrectionDegrees = 0f,
        tiltCorrectionDegrees = 0f,
        rollDegrees = angles[2],
        focalScale = focalScale,
    )
}

/**
 * La rotazione `Ry(pan) · Rx(tilt) · Rz(rollio)`, per righe.
 *
 * E` la stessa catena che [frameToWorld] applica passo per passo: rollio attorno all'asse
 * ottico, poi inclinazione, poi rotazione attorno alla verticale. Scritta come matrice serve
 * solo a poterla comporre con un'altra.
 */
internal fun rotationYxz(panDegrees: Float, tiltDegrees: Float, rollDegrees: Float): FloatArray {
    val a = panDegrees.toRadians()
    val b = tiltDegrees.toRadians()
    val c = rollDegrees.toRadians()
    val ca = cos(a); val sa = sin(a)
    val cb = cos(b); val sb = sin(b)
    val cc = cos(c); val sc = sin(c)
    return floatArrayOf(
        ca * cc - sa * sb * sc, -ca * sc - sa * sb * cc, sa * cb,
        cb * sc, cb * cc, sb,
        -sa * cc - ca * sb * sc, sa * sc - ca * sb * cc, ca * cb,
    )
}

/** I tre angoli — pan, inclinazione, rollio — che rifanno questa rotazione. */
internal fun yxzAngles(m: FloatArray): FloatArray {
    val sinTilt = m[5].coerceIn(-1f, 1f)
    val tilt = asin(sinTilt)
    val cosTilt = cos(tilt)
    // Allo zenit e al nadir pan e rollio diventano la stessa rotazione e non si distinguono
    // piu`: si tiene il rollio a zero e si mette tutto sul pan, che e` la scelta che non
    // sposta niente di visibile.
    if (abs(cosTilt) < GIMBAL_LOCK_COSINE) {
        return floatArrayOf(atan2(-m[6], m[0]).toDegrees(), tilt.toDegrees(), 0f)
    }
    return floatArrayOf(
        atan2(m[2], m[8]).toDegrees(),
        tilt.toDegrees(),
        atan2(m[3], m[4]).toDegrees(),
    )
}

private fun transpose(m: FloatArray): FloatArray = floatArrayOf(
    m[0], m[3], m[6],
    m[1], m[4], m[7],
    m[2], m[5], m[8],
)

private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
    val out = FloatArray(9)
    for (row in 0..2) {
        for (col in 0..2) {
            var sum = 0f
            for (k in 0..2) sum += a[row * 3 + k] * b[k * 3 + col]
            out[row * 3 + col] = sum
        }
    }
    return out
}

/** Sotto questo coseno dell'inclinazione, pan e rollio non si distinguono piu`. */
private const val GIMBAL_LOCK_COSINE = 1e-4f
