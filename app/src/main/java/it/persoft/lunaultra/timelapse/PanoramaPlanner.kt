package it.persoft.lunaultra.timelapse

import it.persoft.lunaultra.data.GimbalAxisLimits
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.tan

@Serializable
enum class PhotoFrameAspect(val label: String, val width: Float, val height: Float) {
    FOUR_THREE("4:3", 4f, 3f),
    SIXTEEN_NINE("16:9", 16f, 9f),
    SQUARE("1:1", 1f, 1f),
}

data class LensFieldOfView(
    val zoomScale: Int,
    val equivalentFocalMm: Float,
    val horizontalDegrees: Float,
    val verticalDegrees: Float,
    val lensLabel: String,
    val qualityLabel: String,
)

/** Dati ottici pubblici della Luna Ultra, convertiti nel rapporto fotografico scelto. */
object LunaOptics {
    val zoomStops = listOf(1, 2, 3, 6, 12)

    /**
     * @param cropFactor quanta parte del fotogramma dichiarato la camera consegna davvero,
     *   misurata dall'unione e conservata nel profilo del gimbal. Uno significa tutta, ed è il
     *   valore finché nessuna panoramica l'ha ancora misurato.
     */
    fun fieldOfView(
        zoomScale: Int,
        aspect: PhotoFrameAspect,
        cropFactor: Float = 1f,
    ): LensFieldOfView {
        val zoom = zoomScale.takeIf { it in zoomStops } ?: 1
        val equivalentFocal = CATALOGUE_EQUIVALENT_FOCAL_MM * zoom
        // La focale equivalente è riferita alla diagonale full-frame (43,27 mm). Ricaviamo
        // larghezza e altezza equivalenti conservando la diagonale per il rapporto scelto.
        val ratio = aspect.width / aspect.height
        val equivalentHeight = FULL_FRAME_DIAGONAL_MM / sqrt(ratio * ratio + 1f)
        val equivalentWidth = equivalentHeight * ratio
        // Il ritaglio si applica in tangente, non in gradi: è una frazione di sensore, e in
        // gradi non sarebbe la stessa cosa a zoom diversi.
        val crop = cropFactor.coerceIn(0.5f, 1f)
        val horizontal = degrees(2.0 * atan(crop * equivalentWidth / (2.0 * equivalentFocal)))
        val vertical = degrees(2.0 * atan(crop * equivalentHeight / (2.0 * equivalentFocal)))
        return LensFieldOfView(
            zoomScale = zoom,
            equivalentFocalMm = equivalentFocal,
            horizontalDegrees = horizontal,
            verticalDegrees = vertical,
            lensLabel = if (zoom < 3) "Lente principale" else "Teleobiettivo 3×",
            qualityLabel = when (zoom) {
                1, 3 -> "ottico"
                2, 6 -> "senza perdita"
                else -> "digitale"
            },
        )
    }

    private fun degrees(radians: Double): Float = (radians * 180.0 / PI).toFloat()
    private const val FULL_FRAME_DIAGONAL_MM = 43.266f

    /**
     * La focale equivalente di catalogo: venti millimetri sulla lente principale.
     *
     * È il dato pubblicato da Insta360 per la Luna Ultra e non è in discussione — descrive la
     * **lente**. Quello che è in discussione è quanta di quella lente finisce nel file: il JPEG
     * da 37 MP (7040×5288) misura 77,1° di campo orizzontale invece degli 81,7° che questo
     * conto produce, come se la diagonale utile fosse 39,8 mm equivalenti invece di 43,27. Un
     * ritaglio dell'8,7%, che è il margine che un gimbal tiene per la stabilizzazione.
     *
     * Quindi questo resta il punto di partenza dichiarato, e il campo visivo **vero** lo misura
     * l'unione: con l'inclinazione data dalla gravità l'angolo fra due scatti è noto senza
     * ipotesi ottiche, e la focale che fa quadrare i dettagli abbinati si ricava da quello.
     * Un numero misurato batte un numero dedotto, ma nessuno dei due deve essere inventato.
     */
    const val CATALOGUE_EQUIVALENT_FOCAL_MM = 20f
}

data class PanoramaPlan(
    val waypoints: List<Waypoint>,
    val columns: Int,
    val rows: Int,
    val fieldOfView: LensFieldOfView,
    val horizontalCenterSpan: Float,
    val verticalCenterSpan: Float,
    /** Il centro che la griglia ha davvero, che non è per forza quello chiesto. */
    val centerPan: Float = 0f,
    val centerTilt: Float = 0f,
    /** Di quanto il centro si è spostato rispetto a dove guardava la camera. */
    val recenteredPanDegrees: Float = 0f,
    val recenteredTiltDegrees: Float = 0f,
    /** La copertura che si ottiene davvero: minore di quella chiesta se la corsa non basta. */
    val horizontalCoverage: Float = 0f,
    val verticalCoverage: Float = 0f,
    val warning: String? = null,
    /**
     * Quanti scatti ha ogni fila, dal basso verso l'alto.
     *
     * Non sono tutte uguali: più ci si alza e meno scatti servono per fare il giro. [columns] è
     * la fila più fitta, quella che decide la larghezza della griglia; il conto vero è la somma.
     */
    val columnsPerRow: List<Int> = List(rows) { columns },
) {
    val totalShots: Int get() = columnsPerRow.sum()

    /** Quanti scatti si sono risparmiati stringendo le file alte. */
    val shotsSavedAtPoles: Int get() = columns * rows - totalShots

    /**
     * Il centro si è dovuto spostare rispetto a dove guardava la camera.
     *
     * Non è un errore ed è la cosa normale su una sferica: serve solo a dirlo, perché il gimbal
     * si muove prima del primo scatto e chi guarda deve sapere perché.
     */
    val recentered: Boolean
        get() = abs(recenteredPanDegrees) > RECENTER_TOLERANCE || abs(recenteredTiltDegrees) > RECENTER_TOLERANCE

    private companion object {
        const val RECENTER_TOLERANCE = 0.5f
    }
}

object PanoramaPlanner {
    fun plan(
        centerPan: Float,
        centerTilt: Float,
        horizontalCoverage: Float,
        verticalCoverage: Float,
        overlapPercent: Int,
        zoomScale: Int,
        aspect: PhotoFrameAspect,
        panLimits: GimbalAxisLimits,
        tiltLimits: GimbalAxisLimits,
        /** Il ritaglio del fotogramma misurato dall'unione: 1 finché nessuna l'ha misurato. */
        frameCropFactor: Float = 1f,
        /**
         * Spingersi fino ai poli invece di fermarsi alla copertura richiesta.
         *
         * Serve allo scatto sferico, e cambia le cose. Per coprire 180° verticali bastano 114°
         * di centri, e il pianificatore si fermava lì: file da -25,5° a +88,5°, con il
         * fotogramma più basso che arrivava a -56° e tutto il resto — trentatré gradi di terreno
         * sotto i piedi — riempito estendendo l'ultimo anello, cioè inventato.
         *
         * Ma il gimbal arriva a -57°, non a -25,5°: quei gradi c'erano e non venivano usati.
         * Con tutta la corsa la fila più bassa scende a -57° e fotografa fin quasi al nadir, e
         * quella di cima cade **esattamente sulla verticale** invece che tre gradi prima.
         *
         * In alto ci si ferma comunque a 90°: oltre il polo non c'è niente di nuovo da
         * fotografare, si rifà l'altro lato a testa in giù.
         */
        fillPoles: Boolean = false,
    ): Result<PanoramaPlan> {
        require(panLimits.isValid && tiltLimits.isValid) { "Esegui prima la calibrazione completa dei fine corsa" }
        val fov = LunaOptics.fieldOfView(zoomScale, aspect, frameCropFactor)
        val overlap = overlapPercent.coerceIn(10, 60) / 100f
        val requestedHorizontal = horizontalCoverage.coerceIn(1f, 360f)
        val requestedVertical = verticalCoverage.coerceIn(0f, 180f)

        // Il centro non è un dato: è una conseguenza. Chi scatta inquadra quello che gli
        // interessa, non si mette a cercare a mano il punto da cui la griglia sta dentro i fine
        // corsa — e su una sferica quel punto non è nemmeno una cosa che si possa guardare. Qui
        // la posizione attuale è il *desiderio*, e la griglia scivola dentro la corsa restando
        // il più vicino possibile a quel desiderio.
        // Quando la copertura chiesta è il giro intero, la cucitura fra l'ultimo scatto e il
        // primo è una giunzione come le altre e va trattata come tale: se la corsa lo consente,
        // i centri si allargano fino a darle la stessa sovrapposizione.
        val closesTheCircle = requestedHorizontal >= FULL_TURN_DEGREES - CENTER_SPAN_TOLERANCE
        val seamSpan = if (closesTheCircle) {
            FULL_TURN_DEGREES - fov.horizontalDegrees * (1f - overlap)
        } else {
            0f
        }
        val pan = fitAxis(
            desiredCenter = centerPan,
            requestedCoverage = requestedHorizontal,
            fovDegrees = fov.horizontalDegrees,
            limits = panLimits,
            minimumSpan = seamSpan,
            maxCoverage = FULL_TURN_DEGREES,
        )
        val tilt = if (fillPoles) {
            poleToPole(tiltLimits, fov.verticalDegrees)
        } else {
            fitAxis(
                desiredCenter = centerTilt,
                requestedCoverage = requestedVertical,
                fovDegrees = fov.verticalDegrees,
                limits = tiltLimits,
                maxCoverage = HALF_TURN_DEGREES,
            )
        }

        val horizontalCenterSpan = pan.centerSpan
        val verticalCenterSpan = tilt.centerSpan
        val startPan = pan.center - horizontalCenterSpan / 2f
        val endPan = pan.center + horizontalCenterSpan / 2f
        val startTilt = tilt.center - verticalCenterSpan / 2f
        val endTilt = tilt.center + verticalCenterSpan / 2f

        val warning = buildList {
            if (pan.reduced) {
                add(
                    "orizzontale ridotta da ${requestedHorizontal.toInt()}° a ${pan.coverage.toInt()}°: " +
                        "la corsa del pan è ${panLimits.spanDeg.toInt()}°",
                )
            }
            if (tilt.reduced) {
                add(
                    "verticale ridotta da ${requestedVertical.toInt()}° a ${tilt.coverage.toInt()}°: " +
                        "la corsa del tilt è ${tiltLimits.spanDeg.toInt()}°",
                )
            }
        }.takeIf { it.isNotEmpty() }?.joinToString("; ")

        val columns = shotsOverSpan(horizontalCenterSpan, fov.horizontalDegrees, overlap, pan.coverage)
        val rows = when {
            tilt.coverage <= 0f -> 1
            // Fino ai poli le file tappezzano l'arco dei centri, che è tutta la corsa: il conto
            // sulla copertura darebbe una fila in meno e lascerebbe scoperta una fascia.
            fillPoles -> rowsUpToTheZenith(verticalCenterSpan, fov, overlap)
            else -> shotCount(tilt.coverage, fov.verticalDegrees, overlap)
        }
        val tilts = positions(startTilt, endTilt, rows)
        val columnsPerRow = worstLatitudes(tilts, fov.verticalDegrees).map { latitude ->
            columnsForRow(latitude, horizontalCenterSpan, fov.horizontalDegrees, overlap, pan.coverage)
                .coerceAtMost(columns)
        }
        val points = buildList {
            tilts.forEachIndexed { row, tilt ->
                val count = columnsPerRow[row]
                val rowPans = positions(startPan, endPan, count).let {
                    if (row % 2 == 0) it else it.asReversed()
                }
                rowPans.forEachIndexed { column, pan ->
                    add(
                        Waypoint(
                            name = "Panorama R${row + 1} C${column + 1}",
                            pan = pan,
                            tilt = tilt,
                            durationToNextSeconds = 1f,
                            positionModelVersion = Waypoint.CURRENT_POSITION_MODEL_VERSION,
                            generatedByPanoramaPlanner = true,
                        ),
                    )
                }
            }
        }
        return Result.success(
            PanoramaPlan(
                waypoints = points,
                columns = columns,
                rows = rows,
                fieldOfView = fov,
                horizontalCenterSpan = horizontalCenterSpan,
                verticalCenterSpan = verticalCenterSpan,
                centerPan = pan.center,
                centerTilt = tilt.center,
                recenteredPanDegrees = pan.center - centerPan,
                recenteredTiltDegrees = tilt.center - centerTilt,
                horizontalCoverage = pan.coverage,
                verticalCoverage = tilt.coverage,
                warning = warning,
                columnsPerRow = columnsPerRow,
            ),
        )
    }

    /**
     * Quante file servono per chiudere il cielo con **un solo** scatto allo zenit.
     *
     * Lo scatto verticale è uno e deve restare uno: a novanta gradi il fotogramma guarda il
     * polo, e affiancargliene altri due significa fotografare tre volte lo stesso cielo. Ma uno
     * basta solo se la fila sotto arriva abbastanza in alto — precisamente fino alla latitudine
     * da cui un fotogramma abbraccia già tutto il giro, cioè dove `campo / cos(lat)` raggiunge i
     * 360°. Sopra i 77,6° con l'obiettivo a 1× succede; sotto no, e resta una fascia scoperta.
     *
     * Con quattro file la penultima si ferma a 72° e mancano cinque gradi: da qui la fila in
     * più, che è esattamente lo scatto che mancava guardando verso l'alto.
     */
    private fun rowsUpToTheZenith(centerSpan: Float, fov: LensFieldOfView, overlap: Float): Int {
        var rows = shotsOverSpan(centerSpan, fov.verticalDegrees, overlap, centerSpan + fov.verticalDegrees)
        if (centerSpan <= 0f) return rows
        val fullRing = degreesOfAcos((fov.horizontalDegrees / FULL_TURN_DEGREES).coerceIn(0f, 1f))
        // Il bordo alto della penultima fila, che è quella che deve consegnare il cielo allo
        // scatto verticale. Le file sono equidistanti sull'arco, quindi basta contarle.
        while (rows < MAX_ROWS) {
            val penultimateTop = if (rows < 2) {
                -HALF_TURN_DEGREES
            } else {
                ZENITH_DEGREES - centerSpan / (rows - 1) + fov.verticalDegrees / 2f
            }
            if (penultimateTop >= fullRing) break
            rows++
        }
        return rows
    }

    private fun degreesOfAcos(value: Float): Float =
        (kotlin.math.acos(value.toDouble()) * 180.0 / PI).toFloat()

    /** Oltre questo la griglia non è più una panoramica, è un guasto del conto. */
    private const val MAX_ROWS = 24

    /**
     * Il tilt che va dal fine corsa basso alla verticale esatta, usando tutta la corsa.
     *
     * L'ultima fila cade su 90° per costruzione, non per caso: è lo scatto che chiude il cielo,
     * ed è quello che fa anche l'app della camera. La prima cade sul fine corsa basso, che è
     * quanto di più vicino al nadir questo gimbal sappia guardare.
     */
    private fun poleToPole(limits: GimbalAxisLimits, fovDegrees: Float): AxisFit {
        val bottom = limits.minimumDeg
        val top = minOf(limits.maximumDeg, ZENITH_DEGREES)
        val span = (top - bottom).coerceAtLeast(0f)
        return AxisFit(
            center = (top + bottom) / 2f,
            centerSpan = span,
            coverage = (span + fovDegrees).coerceAtMost(HALF_TURN_DEGREES),
            reduced = false,
        )
    }

    /** Dove finisce il centro di un asse, e quanta copertura ci sta davvero. */
    private data class AxisFit(
        val center: Float,
        val centerSpan: Float,
        val coverage: Float,
        val reduced: Boolean,
    )

    /**
     * Fa entrare la copertura chiesta dentro la corsa misurata, spostando il centro il meno
     * possibile.
     *
     * La griglia occupa `copertura - campo visivo` gradi di **centri** (il primo scatto vede già
     * mezzo campo prima del suo centro, l'ultimo mezzo campo dopo), quindi il centro deve stare
     * nell'intervallo `[min + arco/2, max - arco/2]`. Finché quell'intervallo esiste, si prende
     * il punto più vicino a dove la camera sta guardando adesso: chi ha inquadrato una cosa
     * precisa se la ritrova più o meno lì, e chi non ha inquadrato niente non deve fare nulla.
     *
     * Quando l'intervallo non esiste — la corsa è più corta della copertura chiesta — non c'è
     * nessun centro che funzioni: si prende tutta la corsa, centro in mezzo, e la copertura
     * diventa quella che il gimbal può davvero fare. Meglio una panoramica un po' più stretta
     * di quella chiesta che un rifiuto con l'invito a spostarsi a mano.
     */
    private fun fitAxis(
        desiredCenter: Float,
        requestedCoverage: Float,
        fovDegrees: Float,
        limits: GimbalAxisLimits,
        /**
         * L'arco minimo da usare comunque, se la corsa lo consente.
         *
         * Serve al giro che si chiude. Con l'arco dei centri pari a `360° − campo visivo`, il
         * primo e l'ultimo scatto si sfiorano e basta: la cucitura del giro nasce con
         * sovrapposizione zero, e qualunque errore del gimbal la trasforma in una fessura — che
         * su un'equirettangolare si vede ai due bordi dell'immagine, come se mancassero due
         * pezzi opposti. Allargando i centri fin dove la corsa arriva, quella fessura diventa
         * una sovrapposizione come le altre.
         */
        minimumSpan: Float = 0f,
        maxCoverage: Float = FULL_TURN_DEGREES,
    ): AxisFit {
        val requestedSpan = (requestedCoverage - fovDegrees).coerceAtLeast(0f)
        val travel = (limits.maximumDeg - limits.minimumDeg).coerceAtLeast(0f)
        val span = maxOf(requestedSpan, minimumSpan).coerceAtMost(travel)
        val low = limits.minimumDeg + span / 2f
        val high = limits.maximumDeg - span / 2f
        val center = if (low > high) (limits.minimumDeg + limits.maximumDeg) / 2f else desiredCenter.coerceIn(low, high)
        return AxisFit(
            center = center,
            centerSpan = span,
            // Il giro non si copre due volte: l'arco allargato serve alla sovrapposizione della
            // cucitura, non ad annunciare 374° di panoramica.
            coverage = (span + fovDegrees).coerceAtMost(maxCoverage),
            reduced = requestedSpan - span > CENTER_SPAN_TOLERANCE,
        )
    }

    /** Sotto mezzo grado la copertura non è stata «ridotta»: è arrotondamento. */
    private const val CENTER_SPAN_TOLERANCE = 0.5f

    /**
     * Quanti scatti servono a coprire [coverage] con fotogrammi larghi [frameFov].
     *
     * Il primo fotogramma copre già il suo campo visivo; ogni scatto in più aggiunge il passo
     * utile, cioè il campo meno la sovrapposizione. Il resto oltre l'ultimo passo intero viene
     * assorbito se è una briciola: chiedere 67° con un fotogramma da 66° faceva due scatti
     * sovrapposti al 98% per un grado di differenza, e due scatti quasi identici complicano
     * l'unione invece di aiutarla.
     */
    private fun shotCount(coverage: Float, frameFov: Float, overlap: Float): Int {
        if (coverage <= frameFov || frameFov <= 0f) return 1
        val usefulStep = frameFov * (1f - overlap)
        if (usefulStep <= 0f) return 1
        val steps = ceil((coverage - frameFov) / usefulStep - RESIDUAL_TOLERANCE).toInt()
        return (steps + 1).coerceAtLeast(1)
    }

    /**
     * Quanti scatti servono alla fila inclinata di [tiltDegrees], che sono meno che in basso.
     *
     * Il pan è una rotazione attorno alla verticale, e più si guarda in alto meno serve girare
     * per spostare l'inquadratura: a 60° di inclinazione un fotogramma copre il doppio dei gradi
     * di pan che copre all'orizzonte, e vicino allo zenit ne copre tutto il giro. È la ragione
     * per cui i meridiani si stringono verso il polo, ed è geometria, non un'approssimazione:
     * la larghezza in pan di un fotogramma è il suo campo visivo diviso il coseno
     * dell'inclinazione.
     *
     * Senza questo conto una panoramica sferica scattava sei foto a 88° di inclinazione, e le sei
     * inquadravano quasi la stessa cosa: un minuto buttato per sei volte lo stesso cielo, con
     * l'aggravante che una sovrapposizione al 99% non aiuta l'unione, la confonde.
     *
     * **Il coseno da usare non è quello del centro della fila.** Un fotogramma non è una riga:
     * copre mezzo campo verticale sopra e mezzo sotto il proprio centro, e la sua larghezza in
     * longitudine è la più stretta dove il coseno è più grande — cioè al bordo rivolto verso
     * l'equatore. Prendere il coseno del centro fa credere il fotogramma più largo di quanto sia
     * proprio dove è più stretto, e lì restano i buchi: nella panoramica del 29 agosto la fila a
     * 50,5° ha avuto quattro scatti distanti 92,8° quando al bordo basso ne coprivano 85,7 —
     * sette gradi di niente, ripetuti tre volte. La fila di cima ne ha avuto uno solo dove ne
     * servivano tre, ed è il cielo che non si chiude.
     *
     * Se la fila scavalca l'equatore il bordo più stretto è l'equatore stesso, dove il coseno
     * vale uno e la larghezza è il campo visivo puro.
     */
    private fun columnsForRow(
        worstLatitude: Float,
        centerSpan: Float,
        frameFov: Float,
        overlap: Float,
        coverage: Float,
    ): Int {
        val cosine = cos(worstLatitude * PI / 180.0).toFloat().coerceAtLeast(MIN_ROW_COSINE)
        val panWidth = (frameFov / cosine).coerceAtMost(FULL_TURN_DEGREES)
        return shotsOverSpan(centerSpan, panWidth, overlap, coverage)
    }

    /**
     * Quanti scatti servono a percorrere un arco di **centri** lungo [centerSpan].
     *
     * È il conto che mancava. `shotCount` ragiona sulla copertura — quanto si vuole vedere — e
     * ricava l'arco dei centri sottraendo un fotogramma; ma le file inclinate hanno un
     * fotogramma più largo in longitudine e un arco di centri che resta quello dell'equatore,
     * perché tutte le file partono e arrivano agli stessi gradi di pan. Chiedere a `shotCount`
     * quanti scatti servono «per 360° con fotogrammi da 128°» dava una risposta giusta per una
     * fila che si allargasse, e sbagliata per una che non si allarga: quattro scatti distribuiti
     * su 278° stanno a 93° l'uno dall'altro, non a 102 come il conto supponeva.
     *
     * Qui il vincolo è diretto: il passo fra due centri non deve superare il fotogramma meno la
     * sovrapposizione voluta.
     */
    private fun shotsOverSpan(
        centerSpan: Float,
        frameWidth: Float,
        overlap: Float,
        coverage: Float,
    ): Int {
        // Vicino al polo un fotogramma solo abbraccia tutto il giro: gli altri sarebbero copie
        // dello stesso cielo, e una sovrapposizione al 99% l'unione la confonde, non la aiuta.
        if (frameWidth >= coverage) return 1
        if (centerSpan <= 0f) return 1
        val step = frameWidth * (1f - overlap)
        if (step <= 0f) return 1
        return 1 + ceil(centerSpan / step - RESIDUAL_TOLERANCE).toInt().coerceAtLeast(0)
    }

    /**
     * Fin dove ogni fila deve arrivare da sola, in latitudine.
     *
     * Le file non lavorano da sole: i fotogrammi si sovrappongono anche in verticale, quindi la
     * fila a 50° non deve coprire il giro fino al proprio bordo basso — lì sotto ci pensa già la
     * fila a 12°. Deve coprirlo **da dove la vicina smette**, cioè dal bordo alto del fotogramma
     * di quella. È il punto in cui i meridiani sono più larghi fra quelli di sua competenza, e
     * quindi quello che decide quanti scatti servono.
     *
     * Prendere il centro della fila, come si faceva, la fa credere più larga di quanto sia
     * proprio dove è più stretta. Prendere il proprio bordo basso è l'errore opposto: la fila di
     * cima ne uscirebbe con tre scatti invece di uno, e sarebbero due scatti dello stesso cielo.
     *
     * Le file che scavalcano l'equatore rispondono dell'equatore, dove il coseno vale uno.
     */
    private fun worstLatitudes(tilts: List<Float>, verticalFov: Float): List<Float> {
        val half = verticalFov / 2f
        return tilts.map { tilt ->
            if (abs(tilt) <= half) return@map 0f          // il fotogramma contiene l'equatore
            // La vicina verso l'equatore: stessa metà del cielo, inclinazione minore.
            val neighbour = tilts
                .filter { it != tilt && (it >= 0f) == (tilt >= 0f) && abs(it) < abs(tilt) }
                .maxByOrNull { abs(it) }
            val boundary = if (neighbour != null) abs(neighbour) + half else abs(tilt) - half
            boundary.coerceIn(abs(tilt) - half, abs(tilt) + half).coerceAtLeast(0f)
        }
    }

    /** Un resto sotto questa frazione di passo non vale uno scatto in più. */
    private const val RESIDUAL_TOLERANCE = 0.1f

    /** Sotto questo coseno un fotogramma copre comunque tutto il giro: è il polo. */
    private const val MIN_ROW_COSINE = 0.02f

    private const val FULL_TURN_DEGREES = 360f
    private const val HALF_TURN_DEGREES = 180f

    /** La verticale pura: oltre di lì si rifotografa l'altro lato a testa in giù. */
    private const val ZENITH_DEGREES = 90f

    private fun positions(start: Float, end: Float, count: Int): List<Float> {
        if (count <= 1) return listOf((start + end) / 2f)
        return List(count) { index -> start + (end - start) * index / (count - 1) }
    }
}
