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

    fun fieldOfView(zoomScale: Int, aspect: PhotoFrameAspect): LensFieldOfView {
        val zoom = zoomScale.takeIf { it in zoomStops } ?: 1
        val equivalentFocal = CATALOGUE_EQUIVALENT_FOCAL_MM * zoom
        // La focale equivalente è riferita alla diagonale full-frame (43,27 mm). Ricaviamo
        // larghezza e altezza equivalenti conservando la diagonale per il rapporto scelto.
        val ratio = aspect.width / aspect.height
        val equivalentHeight = FULL_FRAME_DIAGONAL_MM / sqrt(ratio * ratio + 1f)
        val equivalentWidth = equivalentHeight * ratio
        val horizontal = degrees(2.0 * atan(equivalentWidth / (2.0 * equivalentFocal)))
        val vertical = degrees(2.0 * atan(equivalentHeight / (2.0 * equivalentFocal)))
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
    ): Result<PanoramaPlan> {
        require(panLimits.isValid && tiltLimits.isValid) { "Esegui prima la calibrazione completa dei fine corsa" }
        val fov = LunaOptics.fieldOfView(zoomScale, aspect)
        val overlap = overlapPercent.coerceIn(10, 60) / 100f
        val requestedHorizontal = horizontalCoverage.coerceIn(1f, 360f)
        val requestedVertical = verticalCoverage.coerceIn(0f, 180f)

        // Il centro non è un dato: è una conseguenza. Chi scatta inquadra quello che gli
        // interessa, non si mette a cercare a mano il punto da cui la griglia sta dentro i fine
        // corsa — e su una sferica quel punto non è nemmeno una cosa che si possa guardare. Qui
        // la posizione attuale è il *desiderio*, e la griglia scivola dentro la corsa restando
        // il più vicino possibile a quel desiderio.
        val pan = fitAxis(centerPan, requestedHorizontal, fov.horizontalDegrees, panLimits)
        val tilt = fitAxis(centerTilt, requestedVertical, fov.verticalDegrees, tiltLimits)

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

        val columns = shotCount(pan.coverage, fov.horizontalDegrees, overlap)
        val rows = if (tilt.coverage <= 0f) 1 else shotCount(tilt.coverage, fov.verticalDegrees, overlap)
        val tilts = positions(startTilt, endTilt, rows)
        val columnsPerRow = tilts.map { rowTilt ->
            columnsForRow(rowTilt, pan.coverage, fov.horizontalDegrees, overlap).coerceAtMost(columns)
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
    ): AxisFit {
        val requestedSpan = (requestedCoverage - fovDegrees).coerceAtLeast(0f)
        val travel = (limits.maximumDeg - limits.minimumDeg).coerceAtLeast(0f)
        val span = requestedSpan.coerceAtMost(travel)
        val low = limits.minimumDeg + span / 2f
        val high = limits.maximumDeg - span / 2f
        val center = if (low > high) (limits.minimumDeg + limits.maximumDeg) / 2f else desiredCenter.coerceIn(low, high)
        return AxisFit(
            center = center,
            centerSpan = span,
            coverage = span + fovDegrees,
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
     * Il coseno è limitato al minimo: allo zenit esatto varrebbe zero e la divisione non
     * esisterebbe, mentre uno scatto solo lì basta e avanza.
     */
    private fun columnsForRow(
        tiltDegrees: Float,
        coverage: Float,
        frameFov: Float,
        overlap: Float,
    ): Int {
        val cosine = cos(abs(tiltDegrees) * PI / 180.0).toFloat().coerceAtLeast(MIN_ROW_COSINE)
        val panWidth = (frameFov / cosine).coerceAtMost(FULL_TURN_DEGREES)
        return shotCount(coverage, panWidth, overlap)
    }

    /** Un resto sotto questa frazione di passo non vale uno scatto in più. */
    private const val RESIDUAL_TOLERANCE = 0.1f

    /** Sotto questo coseno un fotogramma copre comunque tutto il giro: è il polo. */
    private const val MIN_ROW_COSINE = 0.02f

    private const val FULL_TURN_DEGREES = 360f

    private fun positions(start: Float, end: Float, count: Int): List<Float> {
        if (count <= 1) return listOf((start + end) / 2f)
        return List(count) { index -> start + (end - start) * index / (count - 1) }
    }
}
