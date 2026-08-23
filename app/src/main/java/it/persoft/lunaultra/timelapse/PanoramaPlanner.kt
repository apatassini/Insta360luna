package it.persoft.lunaultra.timelapse

import it.persoft.lunaultra.data.GimbalAxisLimits
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
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
        val equivalentFocal = 20f * zoom
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
}

data class PanoramaPlan(
    val waypoints: List<Waypoint>,
    val columns: Int,
    val rows: Int,
    val fieldOfView: LensFieldOfView,
    val horizontalCenterSpan: Float,
    val verticalCenterSpan: Float,
    val warning: String? = null,
) {
    val totalShots: Int get() = columns * rows
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
        val pans = positions(startPan, endPan, columns)
        val tilts = positions(startTilt, endTilt, rows)
        val points = buildList {
            tilts.forEachIndexed { row, tilt ->
                val rowPans = if (row % 2 == 0) pans else pans.asReversed()
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

    /** Un resto sotto questa frazione di passo non vale uno scatto in più. */
    private const val RESIDUAL_TOLERANCE = 0.1f

    private fun positions(start: Float, end: Float, count: Int): List<Float> {
        if (count <= 1) return listOf((start + end) / 2f)
        return List(count) { index -> start + (end - start) * index / (count - 1) }
    }
}
