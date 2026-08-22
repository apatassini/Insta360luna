package it.persoft.lunaultra.data

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Misura elementare raccolta durante un impulso della calibrazione. */
@Serializable
data class GimbalCalibrationSample(
    val hardwareLevel: Int,
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
    val normalizedPixelsPerSecond: Float
        get() = if (command == 0f || pulseMs <= 0L) 0f
        else axisShift / (pulseMs / 1000f) / command

    val usable: Boolean
        get() = hardwareLevel in 1..3 && axis in setOf(AXIS_PAN, AXIS_TILT) &&
            abs(axisShift) >= 1.5f && inliers >= 5 && confidence >= 0.30f

    companion object {
        const val AXIS_PAN = "pan"
        const val AXIS_TILT = "tilt"
    }
}

/** Risposta visiva misurata per uno dei tre livelli fisici L/M/V della camera. */
@Serializable
data class GimbalLevelCalibration(
    val hardwareLevel: Int,
    /** Spostamento orizzontale dell'immagine prodotto da un comando pan logico positivo. */
    val panImagePixelsPerSecond: Float,
    /** Spostamento verticale dell'immagine prodotto da un comando tilt logico positivo. */
    val tiltImagePixelsPerSecond: Float,
    /** Rapporto rispetto al livello Veloce, usato dal dead reckoning. */
    val panSpeedScale: Float,
    val tiltSpeedScale: Float,
    val validPanSamples: Int,
    val validTiltSamples: Int,
)

/**
 * Profilo hardware persistente. Viene scritto in `gimbal_calibration.json` e resta valido fra
 * riavvii e aggiornamenti dell'app; una prova incompleta non sostituisce mai il profilo buono.
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
    val levels: List<GimbalLevelCalibration> = emptyList(),
) {
    val isValid: Boolean
        get() = calibratedAtMs > 0L && levels.size == 3 && levels.all {
            abs(it.panImagePixelsPerSecond) >= MIN_RATE &&
                abs(it.tiltImagePixelsPerSecond) >= MIN_RATE &&
                it.validPanSamples >= MIN_SAMPLES_PER_AXIS &&
                it.validTiltSamples >= MIN_SAMPLES_PER_AXIS
        }

    val qualityPercent: Int
        get() = if (totalSamples <= 0) 0 else (validSamples * 100 / totalSamples).coerceIn(0, 100)

    fun level(level: Int): GimbalLevelCalibration? = levels.firstOrNull { it.hardwareLevel == level }

    companion object {
        const val CURRENT_SCHEMA = 1
        const val DEFAULT_SETTLE_MS = 260L
        const val MIN_SAMPLES_PER_AXIS = 4
        const val MIN_RATE = 3f
    }
}

/** Aggregazione deterministica e testabile delle misure grezze. */
object GimbalCalibrationBuilder {
    fun build(
        samples: List<GimbalCalibrationSample>,
        cameraModel: String,
        firmware: String,
        calibratedAtMs: Long = System.currentTimeMillis(),
    ): GimbalCalibrationProfile {
        val usable = samples.filter(GimbalCalibrationSample::usable)
        val rawRates = (1..3).associateWith { level ->
            val pan = usable.filter { it.hardwareLevel == level && it.axis == GimbalCalibrationSample.AXIS_PAN }
            val tilt = usable.filter { it.hardwareLevel == level && it.axis == GimbalCalibrationSample.AXIS_TILT }
            RawLevel(
                panRate = median(pan.map { it.normalizedPixelsPerSecond }),
                tiltRate = median(tilt.map { it.normalizedPixelsPerSecond }),
                panCount = pan.size,
                tiltCount = tilt.size,
            )
        }
        val fast = rawRates.getValue(3)
        val levels = rawRates.map { (level, raw) ->
            GimbalLevelCalibration(
                hardwareLevel = level,
                panImagePixelsPerSecond = raw.panRate,
                tiltImagePixelsPerSecond = raw.tiltRate,
                panSpeedScale = ratio(raw.panRate, fast.panRate),
                tiltSpeedScale = ratio(raw.tiltRate, fast.tiltRate),
                validPanSamples = raw.panCount,
                validTiltSamples = raw.tiltCount,
            )
        }
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
            levels = levels,
        )
    }

    private data class RawLevel(val panRate: Float, val tiltRate: Float, val panCount: Int, val tiltCount: Int)

    private fun ratio(value: Float, reference: Float): Float =
        if (abs(value) < GimbalCalibrationProfile.MIN_RATE || abs(reference) < GimbalCalibrationProfile.MIN_RATE) 1f
        else (abs(value) / abs(reference)).coerceIn(0.1f, 1.5f)

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
