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

/**
 * Un punto della curva: quanti gradi al secondo produce un'intensità, su ogni asse.
 *
 * I gradi al secondo sono la misura buona, e vengono dal cronometro contro i fine corsa: la
 * camera annuncia il limite, la corsa fino a lì è nota, il tempo di comando si conta. Non
 * serve riconoscere niente nell'inquadratura — ed è il punto, perché intorno alla camera non
 * sempre c'è qualcosa di riconoscibile, e su una parete uniforme o a motivo ripetuto ogni
 * misura basata sulle immagini è una scommessa.
 *
 * I pixel al secondo restano perché servono alla correzione visiva dei waypoint, dove il
 * confronto ha senso: là si confronta la stessa posizione con sé stessa.
 */
@Serializable
data class GimbalResponsePoint(
    val intensityPercent: Int,
    val panImagePixelsPerSecond: Float,
    val tiltImagePixelsPerSecond: Float,
    val validPanSamples: Int,
    val validTiltSamples: Int,
    /** Gradi al secondo misurati col cronometro sul fine corsa. Zero = non misurato. */
    val panDegreesPerSecond: Float = 0f,
    val tiltDegreesPerSecond: Float = 0f,
) {
    fun degreesPerSecond(panAxis: Boolean): Float =
        if (panAxis) panDegreesPerSecond else tiltDegreesPerSecond

    val measuredInDegrees: Boolean get() = panDegreesPerSecond > 0f && tiltDegreesPerSecond > 0f
}

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
     * Di quanto la scala in gradi di questo profilo è sbagliata. 1 significa giusta.
     *
     * Questo campo esisteva già, ma valeva solo per i profili vecchi — quelli dedotti dalle
     * immagini — e sui profili misurati a cronometro veniva ignorato. Ignorarlo era un errore,
     * e qui sotto c'è perché.
     *
     * La calibrazione misura due cose oneste: **quanti secondi** e **quanti impulsi** ci vogliono
     * per attraversare un asse da un fine corsa all'altro. Poi divide per la corsa in gradi per
     * ottenere i gradi al secondo — e quella corsa in gradi non la misura nessuno: sono i −57°…
     * +235° del catalogo, scritti come costanti. Siccome il gimbal naviga a stima, integrando
     * velocità per tempo senza nessun ritorno di posizione, un errore su quel numero finisce
     * identico e proporzionale su **ogni** spostamento comandato.
     *
     * Su un esemplare vero è successo: le nove foto di una panoramica, misurate una contro
     * l'altra riconoscendo i dettagli, dicevano che a 32° chiesti ne corrispondevano 42. Il
     * verticale sbagliava del 31%, l'orizzontale del 22%. Il che implica una corsa reale di
     * circa 232° in verticale e 357° in orizzontale — e quel 357 a un giro intero ci somiglia
     * troppo per essere un caso.
     *
     * L'errore è puramente moltiplicativo, quindi si corregge senza rifare niente: si moltiplica
     * qui, e tutta la scala si rimette a posto. Il fattore lo misura [PanoramaStitcher] a ogni
     * unione, confrontando quanto le foto si sono spostate davvero con quanto era stato chiesto.
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
            responsePoints.all { it.measuredInDegrees || legacyPixelPoint(it) }

    /**
     * Perché il profilo non è valido, in una frase leggibile. Nullo quando è valido.
     *
     * Serve perché "Misure insufficienti (42/42)" non è una diagnosi: quel 42 su 42 dice che
     * tutti i campioni raccolti erano buoni, e nasconde che a mancare erano quelli mai
     * raccolti. Chi legge deve sapere quale intensità e quale asse, non un rapporto che torna.
     */
    val invalidReason: String?
        get() {
            if (schemaVersion != CURRENT_SCHEMA) return "profilo di una versione precedente"
            if (calibratedAtMs <= 0L) return "profilo mai calibrato"
            if (!panLimits.isValid) return "fine corsa orizzontali non affidabili"
            if (!tiltLimits.isValid) return "fine corsa verticali non affidabili"
            if (responsePoints.size < MIN_VALID_POINTS) {
                return "solo ${responsePoints.size} intensità misurate su almeno $MIN_VALID_POINTS"
            }
            if (responsePoints.none { it.intensityPercent <= 10 }) {
                return "nessuna intensità bassa (1–10%) misurata: senza quelle i movimenti lenti non sono calcolabili"
            }
            if (responsePoints.none { it.intensityPercent == 100 }) return "il 100% non è stato misurato"
            responsePoints.forEach { point ->
                if (point.measuredInDegrees) return@forEach
                if (point.validPanSamples < MIN_SAMPLES_PER_AXIS) {
                    return "al ${point.intensityPercent}% l'orizzontale ha ${point.validPanSamples} misure buone su $MIN_SAMPLES_PER_AXIS"
                }
                if (point.validTiltSamples < MIN_SAMPLES_PER_AXIS) {
                    return "al ${point.intensityPercent}% il verticale ha ${point.validTiltSamples} misure buone su $MIN_SAMPLES_PER_AXIS"
                }
                if (abs(point.panImagePixelsPerSecond) < MIN_RATE) {
                    return "al ${point.intensityPercent}% l'orizzontale non ha mosso abbastanza da essere misurato"
                }
                if (abs(point.tiltImagePixelsPerSecond) < MIN_RATE) {
                    return "al ${point.intensityPercent}% il verticale non ha mosso abbastanza da essere misurato"
                }
            }
            return null
        }

    /** Un punto della vecchia curva a pixel, valido secondo le regole di allora. */
    private fun legacyPixelPoint(point: GimbalResponsePoint): Boolean =
        abs(point.panImagePixelsPerSecond) >= MIN_RATE &&
            abs(point.tiltImagePixelsPerSecond) >= MIN_RATE &&
            point.validPanSamples >= MIN_SAMPLES_PER_AXIS &&
            point.validTiltSamples >= MIN_SAMPLES_PER_AXIS

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

    /**
     * Velocità misurata a una data intensità, in gradi se ci sono, in pixel per i profili vecchi.
     *
     * È il numero con cui si risponde a «quanto muove questo comando»: la curva in gradi viene
     * da un conteggio di impulsi contro un fine corsa, quella in pixel da come si spostava
     * l'immagine. La prima è una misura, la seconda una deduzione, quindi vince la prima.
     */
    fun responseRateAt(intensityPercent: Float, panAxis: Boolean): Float {
        val degrees = degreesRateAt(intensityPercent, panAxis)
        return if (degrees > 0f) degrees else abs(imageRateAt(intensityPercent, panAxis))
    }

    /**
     * Il comando più veloce della curva. Non è detto che sia il 100%.
     *
     * Misurato sulla Luna Ultra: il comando 100 muove circa 11 °/s su entrambi gli assi, mentre
     * il 90 ne fa 57 in orizzontale e 41 in verticale. Il 100 non è il massimo, è un valore che
     * il firmware tratta a modo suo — e chiedere «vai al massimo» mandando 100 vuol dire andare
     * quattro volte più piano credendo di andare al massimo.
     */
    fun fastestCommandPercent(panAxis: Boolean): Int =
        responsePoints
            .filter { responseRateAt(it.intensityPercent.toFloat(), panAxis) > 0f }
            .maxByOrNull { responseRateAt(it.intensityPercent.toFloat(), panAxis) }
            ?.intensityPercent
            ?: 100

    /** La velocità del comando più veloce: il fondo scala vero della curva. */
    fun fullScaleRate(panAxis: Boolean): Float =
        responseRateAt(fastestCommandPercent(panAxis).toFloat(), panAxis)

    /** Frazione della velocità massima realmente prodotta da un comando [-1, 1]. */
    fun motionFraction(command: Float, panAxis: Boolean): Float {
        if (!isValid || command == 0f) return command
        val full = fullScaleRate(panAxis)
        if (full < MIN_RATE) return command
        val measured = responseRateAt(abs(command) * 100f, panAxis) / full
        return measured.coerceIn(0f, 1f) * sign(command)
    }

    /**
     * Le intensità dove chiedere di più ottiene di meno, con quanto costa in gradi al secondo.
     *
     * Non è rumore da lisciare: è un fatto della camera, e va scritto invece che nascosto.
     * Finché veniva corretto in silenzio, il profilo dichiarava per il 100% la velocità del 90%
     * e ogni spostamento «al massimo» arrivava a poco più di un quarto della strada.
     */
    fun nonMonotonicPoints(panAxis: Boolean): List<Pair<Int, Float>> {
        val points = responsePoints.sortedBy(GimbalResponsePoint::intensityPercent)
        var best = 0f
        val found = mutableListOf<Pair<Int, Float>>()
        points.forEach { point ->
            val rate = responseRateAt(point.intensityPercent.toFloat(), panAxis)
            if (rate > 0f && best > 0f && rate < best) found += point.intensityPercent to (best - rate)
            if (rate > best) best = rate
        }
        return found
    }

    /** Velocità angolare assoluta ricavata dal tempo di attraversamento dei fine corsa. */
    fun angularRateAt(intensityPercent: Float, panAxis: Boolean): Float {
        if (!isValid || intensityPercent <= 0f) return 0f
        val limits = if (panAxis) panLimits else tiltLimits
        // Se la curva è stata misurata in gradi, si usa quella: è un cronometro contro un
        // fine corsa, cioè due fatti. La strada dei pixel resta per i profili vecchi.
        val measured = degreesRateAt(intensityPercent, panAxis)
        if (measured > 0f) return measured

        val scale = if (panAxis) panAngularScale else tiltAngularScale
        val sweepRate = limits.spanDeg / limits.travelSecondsAtSweepIntensity * scale
        val referenceImageRate = abs(imageRateAt(limits.sweepIntensityPercent.toFloat(), panAxis))
        if (referenceImageRate < MIN_RATE) return 0f
        return sweepRate * abs(imageRateAt(intensityPercent, panAxis)) / referenceImageRate
    }

    /**
     * Gradi al secondo interpolati fra i punti misurati col cronometro; 0 se non ce ne sono.
     *
     * La correzione di scala si applica **qui**, che è il punto in cui i gradi nascono. Prima
     * valeva solo per la strada vecchia, quella dedotta dalle immagini, e i profili nuovi — che
     * sono tutti quelli che contano — la saltavano.
     */
    fun degreesRateAt(intensityPercent: Float, panAxis: Boolean): Float {
        val scale = (if (panAxis) panAngularScale else tiltAngularScale)
            .coerceIn(MIN_ANGULAR_SCALE, MAX_ANGULAR_SCALE)
        return rawDegreesRateAt(intensityPercent, panAxis) * scale
    }

    private fun rawDegreesRateAt(intensityPercent: Float, panAxis: Boolean): Float {
        val points = responsePoints
            .filter { it.degreesPerSecond(panAxis) > 0f }
            .sortedBy(GimbalResponsePoint::intensityPercent)
        if (points.isEmpty()) return 0f
        val requested = intensityPercent.coerceIn(0f, 100f)
        val upper = points.firstOrNull { it.intensityPercent >= requested }
        val lower = points.lastOrNull { it.intensityPercent <= requested }
        if (lower == null) {
            val first = points.first()
            return first.degreesPerSecond(panAxis) * requested / first.intensityPercent.coerceAtLeast(1)
        }
        if (upper == null || upper.intensityPercent == lower.intensityPercent) {
            return lower.degreesPerSecond(panAxis)
        }
        val t = (requested - lower.intensityPercent) / (upper.intensityPercent - lower.intensityPercent)
        return lower.degreesPerSecond(panAxis) +
            (upper.degreesPerSecond(panAxis) - lower.degreesPerSecond(panAxis)) * t
    }

    /** La velocità massima è quella del comando più veloce misurato, non quella del 100%. */
    fun maxAngularRate(panAxis: Boolean): Float =
        angularRateAt(fastestCommandPercent(panAxis).toFloat(), panAxis)

    fun limitsFor(panAxis: Boolean): GimbalAxisLimits = if (panAxis) panLimits else tiltLimits

    /**
     * Applica una correzione di scala misurata, componendola con quella che c'è già.
     *
     * Si compone invece di sostituire perché il fattore arriva da una panoramica scattata **con**
     * la correzione corrente addosso: dice di quanto il comportamento di adesso è ancora
     * sbagliato, non di quanto lo era il profilo di fabbrica. Applicarlo due volte, o
     * sostituirlo, vorrebbe dire sbagliare la seconda correzione di tutta la prima.
     */
    fun withAngularScale(panFactor: Float, tiltFactor: Float): GimbalCalibrationProfile = copy(
        panAngularScale = (panAngularScale * panFactor).coerceIn(MIN_ANGULAR_SCALE, MAX_ANGULAR_SCALE),
        tiltAngularScale = (tiltAngularScale * tiltFactor).coerceIn(MIN_ANGULAR_SCALE, MAX_ANGULAR_SCALE),
    )

    /** Inversa della curva: trasforma la velocità richiesta nell'intensità da inviare. */
    fun commandForMotionFraction(desiredFraction: Float, panAxis: Boolean): Float {
        if (!isValid || desiredFraction == 0f) return desiredFraction.coerceIn(-1f, 1f)
        val desired = abs(desiredFraction).coerceIn(0f, 1f)
        val fastest = fastestCommandPercent(panAxis)
        val full = fullScaleRate(panAxis)
        if (full < MIN_RATE) return desiredFraction.coerceIn(-1f, 1f)
        val targetRate = desired * full
        val points = listOf(GimbalResponsePoint(0, 0f, 0f, 0, 0)) +
            responsePoints.sortedBy(GimbalResponsePoint::intensityPercent)
        // Il primo comando che raggiunge la velocità chiesta, che non è per forza il più alto:
        // se il 100 muove meno del 90, chiedere il massimo deve mandare il 90.
        val upperIndex = points.indexOfFirst { responseRateAt(it.intensityPercent.toFloat(), panAxis) >= targetRate }
        if (upperIndex < 0) return fastest / 100f * sign(desiredFraction)
        if (upperIndex == 0) return 0f
        val upper = points[upperIndex]
        val lower = points[upperIndex - 1]
        val lowRate = responseRateAt(lower.intensityPercent.toFloat(), panAxis)
        val highRate = responseRateAt(upper.intensityPercent.toFloat(), panAxis)
        val t = if (highRate - lowRate < 0.001f) 1f else (targetRate - lowRate) / (highRate - lowRate)
        val percent = lower.intensityPercent + (upper.intensityPercent - lower.intensityPercent) * t
        return (percent / 100f).coerceIn(0.01f, 1f) * sign(desiredFraction)
    }

    private fun rate(point: GimbalResponsePoint, panAxis: Boolean): Float =
        if (panAxis) point.panImagePixelsPerSecond else point.tiltImagePixelsPerSecond

    companion object {
        // 5: la curva è in gradi al secondo, misurata col cronometro contro i fine corsa
        // invece che dedotta dallo spostamento delle immagini. I profili precedenti nascevano
        // da una misura che dipendeva da cosa c'era davanti all'obiettivo.
        const val CURRENT_SCHEMA = 5

        /**
         * La correzione da applicare al profilo, resa ripetibile.
         *
         * [measured] è quanto il gimbal ha sbagliato **con la taratura in vigore al momento
         * dello scatto**. Se da allora la taratura è cambiata — perché una prima unione l'aveva
         * già corretta — riapplicare la misura intera correggerebbe due volte lo stesso errore:
         * riunendo tre volte le stesse foto, 1,31 diventerebbe 2,25 e il gimbal comincerebbe a
         * mancare i finecorsa.
         *
         * Il rapporto fra la taratura di allora e quella di adesso rimette le cose a posto. La
         * prima volta vale uno e passa la misura intera; la seconda volta la taratura di adesso
         * è già quella di allora moltiplicata per la misura, quindi il rapporto vale l'inverso
         * della misura e la correzione risultante è esattamente 1: nulla cambia.
         */
        fun repeatableCorrection(measured: Float, scaleAtShot: Float, scaleNow: Float): Float =
            if (scaleNow <= 0f) 1f else measured * scaleAtShot / scaleNow

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

    /**
     * Costruisce il profilo dalle misure a cronometro: intensità → gradi al secondo.
     *
     * Non c'è niente da mediare né da filtrare: ogni punto è un cronometro contro un fine
     * corsa, e un cronometro o c'è o non c'è. I pixel al secondo restano a zero — servono solo
     * alla correzione visiva dei waypoint, che li ricava per conto suo quando le miniature ci
     * sono; qui non si finge di averli misurati.
     */
    fun buildFromDegrees(
        panCurve: List<Pair<Int, Float>>,
        tiltCurve: List<Pair<Int, Float>>,
        cameraModel: String,
        firmware: String,
        calibratedAtMs: Long = System.currentTimeMillis(),
        panLimits: GimbalAxisLimits = GimbalAxisLimits(),
        tiltLimits: GimbalAxisLimits = GimbalAxisLimits(),
    ): GimbalCalibrationProfile {
        val pan = panCurve.toMap()
        val tilt = tiltCurve.toMap()
        val points = (pan.keys + tilt.keys).distinct().sorted().mapNotNull { intensity ->
            val panRate = pan[intensity] ?: return@mapNotNull null
            val tiltRate = tilt[intensity] ?: return@mapNotNull null
            GimbalResponsePoint(
                intensityPercent = intensity,
                panImagePixelsPerSecond = 0f,
                tiltImagePixelsPerSecond = 0f,
                validPanSamples = 1,
                validTiltSamples = 1,
                panDegreesPerSecond = panRate,
                tiltDegreesPerSecond = tiltRate,
            )
        }
        return GimbalCalibrationProfile(
            calibratedAtMs = calibratedAtMs,
            cameraModel = cameraModel,
            firmware = firmware,
            validSamples = points.size * 2,
            totalSamples = (panCurve.size + tiltCurve.size),
            responsePoints = points.sortedBy(GimbalResponsePoint::intensityPercent),
            panLimits = panLimits,
            tiltLimits = tiltLimits,
        )
    }

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
