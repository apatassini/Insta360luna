package it.persoft.lunaultra.data

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sign

@Serializable
data class GimbalCalibrationSample(
    val intensityPercent: Int,
    val axis: String,
    val command: Float,
    val pulseMs: Long,
    val shiftX: Float,
    val shiftY: Float,
    val inliers: Int,
    val confidence: Float,
    val commandOverheadMs: Long,
    val settleMs: Long,
) {
    val axisShift: Float get() = if (axis == AXIS_PAN) shiftX else shiftY
    /** Velocità dell'immagine prodotta dal comando positivo alla stessa intensità. */
    val signedPixelsPerSecond: Float
        get() = if (command == 0f || pulseMs <= 0L) 0f
        else axisShift / (pulseMs / 1000f) * sign(command)

    val usable: Boolean
        get() = intensityPercent in 1..100 && axis in setOf(AXIS_PAN, AXIS_TILT) &&
            abs(axisShift) >= 1.25f && inliers >= 5 && confidence >= 0.30f

    companion object {
        const val AXIS_PAN = "pan"
        const val AXIS_TILT = "tilt"
    }
}

@Serializable
data class GimbalResponsePoint(
    val intensityPercent: Int,
    val panImagePixelsPerSecond: Float,
    val tiltImagePixelsPerSecond: Float,
    val validPanSamples: Int,
    val validTiltSamples: Int,
)

/**
 * Curva hardware persistente. L/M/V non entrano nel modello: il log mostra che il relativo
 * comando può andare in timeout e le prove fisiche indicano la stessa velocità. La variabile
 * affidabile è l'intensità 1..100 inviata direttamente al comando gimbal 226.
 */
@Serializable
data class GimbalCalibrationProfile(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val calibratedAtMs: Long = 0L,
    val cameraModel: String = "",
    val firmware: String = "",
    val responseOverheadMs: Long = 0L,
    val settleMs: Long = DEFAULT_SETTLE_MS,
    val validSamples: Int = 0,
    val totalSamples: Int = 0,
    val responsePoints: List<GimbalResponsePoint> = emptyList(),
) {
    val isValid: Boolean
        get() = schemaVersion == CURRENT_SCHEMA && calibratedAtMs > 0L &&
            responsePoints.size >= MIN_VALID_POINTS &&
            responsePoints.any { it.intensityPercent <= 10 } &&
            responsePoints.any { it.intensityPercent == 100 } &&
            responsePoints.all {
                abs(it.panImagePixelsPerSecond) >= MIN_RATE &&
                    abs(it.tiltImagePixelsPerSecond) >= MIN_RATE &&
                    it.validPanSamples >= MIN_SAMPLES_PER_AXIS &&
                    it.validTiltSamples >= MIN_SAMPLES_PER_AXIS
            }

    val qualityPercent: Int
        get() = if (totalSamples <= 0) 0 else (validSamples * 100 / totalSamples).coerceIn(0, 100)

    /** Velocità immagine firmata del comando positivo, interpolata fra i punti misurati. */
    fun imageRateAt(intensityPercent: Float, panAxis: Boolean): Float {
        val points = responsePoints.sortedBy(GimbalResponsePoint::intensityPercent)
        if (points.isEmpty() || intensityPercent <= 0f) return 0f
        val requested = intensityPercent.coerceIn(0f, 100f)
        val upper = points.firstOrNull { it.intensityPercent >= requested }
        val lower = points.lastOrNull { it.intensityPercent <= requested }
        if (lower == null) {
            val first = points.first()
            return rate(first, panAxis) * requested / first.intensityPercent.coerceAtLeast(1)
        }
        if (upper == null || upper.intensityPercent == lower.intensityPercent) return rate(lower, panAxis)
        val t = (requested - lower.intensityPercent) / (upper.intensityPercent - lower.intensityPercent)
        return rate(lower, panAxis) + (rate(upper, panAxis) - rate(lower, panAxis)) * t
    }

    /** Frazione della velocità massima realmente prodotta da un comando [-1, 1]. */
    fun motionFraction(command: Float, panAxis: Boolean): Float {
        if (!isValid || command == 0f) return command
        val full = abs(imageRateAt(100f, panAxis))
        if (full < MIN_RATE) return command
        val measured = abs(imageRateAt(abs(command) * 100f, panAxis)) / full
        return measured.coerceIn(0f, 1f) * sign(command)
    }

    /** Inversa della curva: trasforma la velocità richiesta nell'intensità da inviare. */
    fun commandForMotionFraction(desiredFraction: Float, panAxis: Boolean): Float {
        if (!isValid || desiredFraction == 0f) return desiredFraction.coerceIn(-1f, 1f)
        val desired = abs(desiredFraction).coerceIn(0f, 1f)
        val full = abs(imageRateAt(100f, panAxis))
        if (full < MIN_RATE) return desiredFraction.coerceIn(-1f, 1f)
        val targetRate = desired * full
        val points = listOf(GimbalResponsePoint(0, 0f, 0f, 0, 0)) +
            responsePoints.sortedBy(GimbalResponsePoint::intensityPercent)
        val upperIndex = points.indexOfFirst { abs(rate(it, panAxis)) >= targetRate }
        if (upperIndex < 0) return sign(desiredFraction)
        if (upperIndex == 0) return 0f
        val upper = points[upperIndex]
        val lower = points[upperIndex - 1]
        val lowRate = abs(rate(lower, panAxis))
        val highRate = abs(rate(upper, panAxis))
        val t = if (highRate - lowRate < 0.001f) 1f else (targetRate - lowRate) / (highRate - lowRate)
        val percent = lower.intensityPercent + (upper.intensityPercent - lower.intensityPercent) * t
        return (percent / 100f).coerceIn(0.01f, 1f) * sign(desiredFraction)
    }

    private fun rate(point: GimbalResponsePoint, panAxis: Boolean): Float =
        if (panAxis) point.panImagePixelsPerSecond else point.tiltImagePixelsPerSecond

    companion object {
        const val CURRENT_SCHEMA = 2
        const val DEFAULT_SETTLE_MS = 260L
        const val MIN_SAMPLES_PER_AXIS = 2
        const val MIN_VALID_POINTS = 8
        const val MIN_RATE = 0.35f
    }
}

object GimbalCalibrationBuilder {
    fun build(
        samples: List<GimbalCalibrationSample>,
        cameraModel: String,
        firmware: String,
        calibratedAtMs: Long = System.currentTimeMillis(),
    ): GimbalCalibrationProfile {
        val usable = samples.filter(GimbalCalibrationSample::usable)
        val points = samples.map(GimbalCalibrationSample::intensityPercent).distinct().sorted().map { intensity ->
            val pan = usable.filter { it.intensityPercent == intensity && it.axis == GimbalCalibrationSample.AXIS_PAN }
            val tilt = usable.filter { it.intensityPercent == intensity && it.axis == GimbalCalibrationSample.AXIS_TILT }
            GimbalResponsePoint(
                intensityPercent = intensity,
                panImagePixelsPerSecond = median(pan.map(GimbalCalibrationSample::signedPixelsPerSecond)),
                tiltImagePixelsPerSecond = median(tilt.map(GimbalCalibrationSample::signedPixelsPerSecond)),
                validPanSamples = pan.size,
                validTiltSamples = tilt.size,
            )
        }.filter { it.validPanSamples > 0 && it.validTiltSamples > 0 }

        return GimbalCalibrationProfile(
            calibratedAtMs = calibratedAtMs,
            cameraModel = cameraModel,
            firmware = firmware,
            responseOverheadMs = medianLong(usable.map { it.commandOverheadMs.coerceAtLeast(0L) }),
            settleMs = medianLong(usable.map { it.settleMs.coerceAtLeast(0L) })
                .coerceIn(120L, 900L)
                .takeIf { usable.isNotEmpty() } ?: GimbalCalibrationProfile.DEFAULT_SETTLE_MS,
            validSamples = usable.size,
            totalSamples = samples.size,
            responsePoints = points,
        )
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    private fun medianLong(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2L
    }
}
