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
        val equivalentFocal = MEASURED_EQUIVALENT_FOCAL_MM * zoom
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
     * La focale equivalente **misurata**, non quella di catalogo.
     *
     * Il catalogo dice 20 mm, che in rapporto 4:3 darebbero 81,74° di campo orizzontale. È il
     * numero che ho usato per mesi, ed è sbagliato di quasi il sei per cento.
     *
     * La misura viene dall'accelerometro che la camera scrive in coda a ogni JPEG, dopo la fine
     * del file. Fotografa la gravità, quindi dà l'inclinazione della camera in **assoluto**: fra
     * due scatti della stessa colonna l'angolo fra i due vettori gravità è l'angolo vero di cui
     * la camera si è girata, senza passare da nessuna ipotesi ottica. Su nove foto quei vettori
     * hanno modulo costante entro il 4‰ e, a parità di inclinazione, si somigliano entro 0,17°:
     * lo strumento è buono.
     *
     * Con quell'angolo per vero, si cerca la focale che fa quadrare i dettagli abbinati fra le
     * stesse due foto. Il minimo è netto e vale 77,07° di campo orizzontale su tre coppie
     * indipendenti, con scarto 0,28°; a 81,74° lo scarto sale a 3,1°.
     *
     * Perché era invisibile prima: focale e rotazione si compensano a vicenda. Con la lente
     * dichiarata larga il 6% in più, le foto combaciano lo stesso — basta credere che il gimbal
     * si sia mosso il 6% in più di quanto ha fatto. Le due bugie si tengono per mano e la
     * panoramica esce comunque giusta. A separarle serve un righello esterno, e la gravità è
     * l'unico che abbiamo.
     */
    const val MEASURED_EQUIVALENT_FOCAL_MM = 21.73f
}

data class PanoramaPlan(
    val waypoints: List<Waypoint>,
    val columns: Int,
    val rows: Int,
    val fieldOfView: LensFieldOfView,
    val horizontalCenterSpan: Float,
    val verticalCenterSpan: Float,
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
        val horizontalCenterSpan = (requestedHorizontal - fov.horizontalDegrees).coerceAtLeast(0f)
        val verticalCenterSpan = (requestedVertical - fov.verticalDegrees).coerceAtLeast(0f)
        val startPan = centerPan - horizontalCenterSpan / 2f
        val endPan = centerPan + horizontalCenterSpan / 2f
        val startTilt = centerTilt - verticalCenterSpan / 2f
        val endTilt = centerTilt + verticalCenterSpan / 2f

        if (startPan < panLimits.minimumDeg || endPan > panLimits.maximumDeg) {
            val available = fov.horizontalDegrees + 2f * minOf(
                centerPan - panLimits.minimumDeg,
                panLimits.maximumDeg - centerPan,
            ).coerceAtLeast(0f)
            return Result.failure(
                IllegalArgumentException(
                    "Da questa posizione il panorama da ${requestedHorizontal.toInt()}° non entra: " +
                        "massimo centrato circa ${available.toInt()}°. Sposta l'inquadratura iniziale.",
                ),
            )
        }
        if (startTilt < tiltLimits.minimumDeg || endTilt > tiltLimits.maximumDeg) {
            val available = fov.verticalDegrees + 2f * minOf(
                centerTilt - tiltLimits.minimumDeg,
                tiltLimits.maximumDeg - centerTilt,
            ).coerceAtLeast(0f)
            return Result.failure(
                IllegalArgumentException(
                    "Da questa inclinazione la copertura verticale da ${requestedVertical.toInt()}° non entra: " +
                        "massimo centrato circa ${available.toInt()}°.",
                ),
            )
        }

        val columns = shotCount(requestedHorizontal, fov.horizontalDegrees, overlap)
        val rows = if (requestedVertical <= 0f) 1 else shotCount(requestedVertical, fov.verticalDegrees, overlap)
        val tilts = positions(startTilt, endTilt, rows)
        val columnsPerRow = tilts.map { tilt ->
            columnsForRow(tilt, requestedHorizontal, fov.horizontalDegrees, overlap).coerceAtMost(columns)
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
                columnsPerRow = columnsPerRow,
            ),
        )
    }

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
