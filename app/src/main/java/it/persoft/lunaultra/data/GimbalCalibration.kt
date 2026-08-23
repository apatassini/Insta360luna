package it.persoft.lunaultra.data

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
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
 * Estremi realmente raggiungibili di un asse e tempo misurato per attraversarli al 20%.
 *
 * I gradi seguono il sistema pubblicato da Insta360: lo zero è l'inquadratura frontale
 * dell'avvio, non il punto medio meccanico. Questo spiega perché la Luna ha poca corsa a
 * sinistra dello zero e molta più corsa a destra.
 */
@Serializable
data class GimbalAxisLimits(
    val minimumDeg: Float = 0f,
    val maximumDeg: Float = 0f,
    val sweepIntensityPercent: Int = 20,
    val travelSecondsAtSweepIntensity: Float = 0f,
    val movingPulses: Int = 0,
    val endpointConfidencePercent: Int = 0,
) {
    val spanDeg: Float get() = maximumDeg - minimumDeg
    val isValid: Boolean
        get() = minimumDeg < 0f && maximumDeg > 0f && spanDeg > 30f &&
            sweepIntensityPercent in 1..100 && travelSecondsAtSweepIntensity > 0.5f &&
            movingPulses > 0 && endpointConfidencePercent >= 50
}

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
    val panLimits: GimbalAxisLimits = GimbalAxisLimits(),
    val tiltLimits: GimbalAxisLimits = GimbalAxisLimits(),

    /**
     * Correzione di scala misurata contro i fine corsa, alla fine della calibrazione.
     *
     * Le immagini danno la **forma** della curva — quanto è più veloce il 100% del 10% — ma non
     * la sua scala in gradi: quella nasce dal tempo impiegato a percorrere la corsa, che è una
     * misura a cronometro e sbaglia di qualche punto percentuale. Un errore del 10% sulla scala
     * non si nota su un arco di 45°, ma su 235° diventano 23° e il gimbal manca il fine corsa.
     *
     * Il fine corsa invece è verità assoluta: la camera lo annuncia. Quindi lo si raggiunge, si
     * confronta quanto il modello *credeva* di aver percorso con quanto c'era davvero da
     * percorrere, e il rapporto finisce qui. 1 significa modello già giusto.
     */
    val panAngularScale: Float = 1f,
    val tiltAngularScale: Float = 1f,
) {
    val isValid: Boolean
        get() = schemaVersion == CURRENT_SCHEMA && calibratedAtMs > 0L &&
            responsePoints.size >= MIN_VALID_POINTS &&
            responsePoints.any { it.intensityPercent <= 10 } &&
            responsePoints.any { it.intensityPercent == 100 } &&
            panLimits.isValid && tiltLimits.isValid &&
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

    /** Velocità angolare assoluta ricavata dal tempo di attraversamento dei fine corsa. */
    fun angularRateAt(intensityPercent: Float, panAxis: Boolean): Float {
        if (!isValid || intensityPercent <= 0f) return 0f
        val limits = if (panAxis) panLimits else tiltLimits
        val scale = if (panAxis) panAngularScale else tiltAngularScale
        val sweepRate = limits.spanDeg / limits.travelSecondsAtSweepIntensity * scale
        val referenceImageRate = abs(imageRateAt(limits.sweepIntensityPercent.toFloat(), panAxis))
        if (referenceImageRate < MIN_RATE) return 0f
        return sweepRate * abs(imageRateAt(intensityPercent, panAxis)) / referenceImageRate
    }

    fun maxAngularRate(panAxis: Boolean): Float = angularRateAt(100f, panAxis)

    fun limitsFor(panAxis: Boolean): GimbalAxisLimits = if (panAxis) panLimits else tiltLimits

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
        // 4: la scala angolare non viene più dal solo cronometro della corsa, ma è corretta
        // contro i fine corsa. I profili 3 sono sistematicamente troppo veloci e vanno rifatti.
        const val CURRENT_SCHEMA = 4

        /** Oltre questi limiti la correzione non è una taratura, è un sintomo. */
        const val MIN_ANGULAR_SCALE = 0.4f
        const val MAX_ANGULAR_SCALE = 2.5f
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
        panLimits: GimbalAxisLimits = GimbalAxisLimits(),
        tiltLimits: GimbalAxisLimits = GimbalAxisLimits(),
    ): GimbalCalibrationProfile {
        val usable = samples.filter(GimbalCalibrationSample::usable)
        val rawPoints = samples.map(GimbalCalibrationSample::intensityPercent).distinct().sorted().map { intensity ->
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

        // Il rumore del matching non deve poter creare una curva che rallenta aumentando il
        // comando (il vecchio log riportava 70% più rapido del 100%). Una regressione monotona
        // cumulativa conserva il verso misurato e rimuove soltanto tali inversioni fisicamente
        // impossibili.
        var lastPanMagnitude = 0f
        var lastTiltMagnitude = 0f
        val panSign = signOrOne(rawPoints.firstOrNull {
            abs(it.panImagePixelsPerSecond) >= GimbalCalibrationProfile.MIN_RATE
        }
            ?.panImagePixelsPerSecond ?: 1f)
        val tiltSign = signOrOne(rawPoints.firstOrNull {
            abs(it.tiltImagePixelsPerSecond) >= GimbalCalibrationProfile.MIN_RATE
        }
            ?.tiltImagePixelsPerSecond ?: 1f)
        val points = rawPoints.map { point ->
            lastPanMagnitude = max(lastPanMagnitude, abs(point.panImagePixelsPerSecond))
            lastTiltMagnitude = max(lastTiltMagnitude, abs(point.tiltImagePixelsPerSecond))
            point.copy(
                panImagePixelsPerSecond = lastPanMagnitude * panSign,
                tiltImagePixelsPerSecond = lastTiltMagnitude * tiltSign,
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
            responsePoints = points,
            panLimits = panLimits,
            tiltLimits = tiltLimits,
        )
    }

    private fun signOrOne(value: Float): Float = if (value < 0f) -1f else 1f

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
