package it.persoft.lunaultra.gimbal

import it.persoft.lunaultra.data.GimbalCalibrationBuilder
import it.persoft.lunaultra.data.GimbalAxisLimits
import it.persoft.lunaultra.data.GimbalCalibrationProfile
import it.persoft.lunaultra.data.GimbalCalibrationSample
import it.persoft.lunaultra.data.JsonFileStore
import it.persoft.lunaultra.diagnostics.ImageVerification
import it.persoft.lunaultra.diagnostics.WaypointImageVerifier
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.preview.PreviewController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class GimbalCalibrationState(
    val running: Boolean = false,
    val pausedForPreview: Boolean = false,
    val completedSteps: Int = 0,
    val totalSteps: Int = TOTAL_STEPS,
    val overallPercent: Int = 0,
    val phaseLabel: String = "Preparazione",
    val message: String = "",
    val error: String? = null,
    val axisLabel: String = "—",
    val directionLabel: String = "—",
    val intensityPercent: Int = 0,
    val pulseMs: Long = 0L,
    /** Coordinate prodotte dal modello di movimento dell'app, non encoder assoluti. */
    val theoreticalPan: Float = 0f,
    val theoreticalTilt: Float = 0f,
    val shiftX: Float = 0f,
    val shiftY: Float = 0f,
    val referenceFeatures: Int = 0,
    val currentFeatures: Int = 0,
    val candidateMatches: Int = 0,
    val inlierMatches: Int = 0,
    val controlPointsPercent: Int = 0,
    val positioningPercent: Int = 0,
    val validSamples: Int = 0,
    val verificationLabel: String = "In attesa della prima misura",
    val annotatedJpeg: ByteArray? = null,
) {
    val progress: Float get() = overallPercent.coerceIn(0, 100) / 100f

    companion object {
        const val TOTAL_STEPS = 12 * 2 * 2
    }
}

/**
 * Riepilogo dei fine corsa isolato dalla procedura hardware, così anche i caratteri `%`
 * possono essere verificati dai test senza dover muovere davvero la camera.
 */
internal fun formatAxisLimitSummary(limits: GimbalAxisLimits): String = buildString {
    appendLine(
        String.format(
            Locale.US,
            "Limiti: %.1f°…%+.1f° · ampiezza %.1f°",
            limits.minimumDeg,
            limits.maximumDeg,
            limits.spanDeg,
        ),
    )
    val seconds = String.format(Locale.US, "%.1f", limits.travelSecondsAtSweepIntensity)
    appendLine(
        "Tempo al ${limits.sweepIntensityPercent}%: $seconds s · ${limits.movingPulses} impulsi utili",
    )
    append("Affidabilità fine corsa: ${limits.endpointConfidencePercent}%")
}

/**
 * Trova prima i quattro fine corsa con avvicinamento rapido e rifinitura lenta, quindi misura la risposta
 * reale del comando dall'1% al 100%. Il riferimento zero è l'inquadratura frontale di avvio:
 * non viene confuso con il centro aritmetico della corsa asimmetrica.
 */
class GimbalCalibrator(
    private val gimbal: GimbalController,
    private val limitMonitor: GimbalLimitMonitor,
    private val preview: PreviewController,
    private val store: JsonFileStore<GimbalCalibrationProfile>,
    private val log: EventLog,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(GimbalCalibrationState())
    val state: StateFlow<GimbalCalibrationState> = _state

    private var job: Job? = null

    fun start(cameraModel: String, firmware: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            runCalibration(cameraModel, firmware)
        }
    }

    fun cancel() {
        job?.cancel(CancellationException("Calibrazione interrotta dall'utente"))
    }

    private suspend fun runCalibration(cameraModel: String, firmware: String) {
        val samples = mutableListOf<GimbalCalibrationSample>()
        _state.value = GimbalCalibrationState(
            running = true,
            message = "Avvio anteprima e controllo immagine…",
            phaseLabel = "Controllo scena",
        )
        log.info("CALIBRAZIONE GIMBAL · AVVIO", "Profilo precedente conservato fino al completamento della nuova misura.")
        try {
            recenterHardware("Ricentraggio iniziale", 0, 2)
            val firstFrame = awaitFrame("Avvio: attendo un fotogramma verificabile…")
            val firstCheck = WaypointImageVerifier.verify(firstFrame, firstFrame)
            if (firstCheck == null || firstCheck.confidence < MIN_SCENE_CONFIDENCE) {
                throw IllegalStateException("La scena e completamente uniforme: inquadra almeno una nube, un bordo o un oggetto fermo")
            }
            _state.value = _state.value.copy(
                overallPercent = 2,
                verificationLabel = "SCENA RICONOSCIUTA",
                referenceFeatures = firstCheck.referenceFeatures,
                currentFeatures = firstCheck.currentFeatures,
                candidateMatches = firstCheck.candidateMatches,
                inlierMatches = firstCheck.inlierMatches,
                controlPointsPercent = controlPointPercent(firstCheck),
                positioningPercent = 100,
                annotatedJpeg = WaypointImageVerifier.annotatedCurrentJpeg(firstFrame, firstCheck),
            )

            log.info(
                "CALIBRAZIONE · RICERCA FINE CORSA",
                "Aggancio rapido al ${ENDSTOP_INTENSITY_PERCENT}%, arretramento e secondo aggancio al ${FINE_ENDSTOP_INTENSITY_PERCENT}%. " +
                    "Il criterio principale e la notifica hardware 8302; l'immagine resta una verifica.",
            )
            val panLimits = calibrateAxisLimits(
                axis = GimbalCalibrationSample.AXIS_PAN,
                minimumDeg = OFFICIAL_PAN_MIN_DEG,
                maximumDeg = OFFICIAL_PAN_MAX_DEG,
                phaseStartPercent = 2,
                phaseEndPercent = 14,
            )
            recenterHardware("Zero hardware dopo il pan", 14, 17)
            val tiltLimits = calibrateAxisLimits(
                axis = GimbalCalibrationSample.AXIS_TILT,
                minimumDeg = OFFICIAL_TILT_MIN_DEG,
                maximumDeg = OFFICIAL_TILT_MAX_DEG,
                phaseStartPercent = 17,
                phaseEndPercent = 27,
            )
            recenterHardware("Zero hardware dopo il tilt", 27, 30)

            _state.value = _state.value.copy(
                overallPercent = 30,
                phaseLabel = "Curva velocità 1–100%",
                message = "Fine corsa trovati · misuro ora la curva dei comandi",
            )

            for (intensityPercent in INTENSITY_PERCENTAGES) {
                for (axis in listOf(GimbalCalibrationSample.AXIS_PAN, GimbalCalibrationSample.AXIS_TILT)) {
                    repeat(REPETITIONS) {
                        var pairOrigin: ByteArray? = null
                        for (direction in listOf(1f, -1f)) {
                            val limits = if (axis == GimbalCalibrationSample.AXIS_PAN) panLimits else tiltLimits
                            val sweep = measureResponseSweep(axis, direction, intensityPercent, limits)
                            if (direction > 0f) pairOrigin = sweep.origin
                            val sample = sweep.sample
                            val positioningPercent = sample?.let { responseScorePercent(it, samples) } ?: 0
                            if (sample != null) samples += sample

                                val displayVerification = if (direction < 0f && pairOrigin != null) {
                                    WaypointImageVerifier.verify(pairOrigin, sweep.finalFrame)
                                } else sweep.lastStepVerification
                                val displayPositionPercent = if (direction < 0f && displayVerification != null) {
                                    returnPositionPercent(displayVerification)
                                } else positioningPercent
                                val afterPosition = gimbal.position.value
                                val done = _state.value.completedSteps + 1
                                _state.value = _state.value.copy(
                                    pausedForPreview = false,
                                    completedSteps = done,
                                    overallPercent = 30 + (done * 60 / GimbalCalibrationState.TOTAL_STEPS),
                                    phaseLabel = "Curva velocità 1–100%",
                                    message = "$intensityPercent% · ${axisLabel(axis)} · ${sweep.substeps} step · prova $done/${GimbalCalibrationState.TOTAL_STEPS}",
                                    theoreticalPan = afterPosition.pan,
                                    theoreticalTilt = afterPosition.tilt,
                                    shiftX = displayVerification?.shiftX ?: 0f,
                                    shiftY = displayVerification?.shiftY ?: 0f,
                                    referenceFeatures = displayVerification?.referenceFeatures ?: 0,
                                    currentFeatures = displayVerification?.currentFeatures ?: 0,
                                    candidateMatches = displayVerification?.candidateMatches ?: 0,
                                    inlierMatches = displayVerification?.inlierMatches ?: 0,
                                    controlPointsPercent = displayVerification?.let(::controlPointPercent) ?: 0,
                                    positioningPercent = displayPositionPercent,
                                    validSamples = samples.count(GimbalCalibrationSample::usable),
                                    verificationLabel = when {
                                        direction < 0f && displayVerification != null ->
                                            "RITORNO · ${displayVerification.verdict.label}"
                                        sample?.usable == true -> "MOVIMENTO MISURATO CORRETTAMENTE"
                                        else -> "MISURA INCERTA"
                                    },
                                    annotatedJpeg = WaypointImageVerifier.annotatedCurrentJpeg(
                                        sweep.finalFrame,
                                        displayVerification,
                                    ),
                                )
                                log.info(
                                    message = "CALIBRAZIONE · $intensityPercent% · ${axisLabel(axis)} · ${directionLabel(axis, direction).uppercase()}",
                                    detail = buildString {
                                        appendLine("Arco obiettivo: ~${sweep.targetDegrees.toInt()}° · ${sweep.substeps} step · ${sweep.commandDurationMs} ms totali")
                                        appendLine("Coordinate teoriche: pan %.3f° · tilt %.3f°".format(afterPosition.pan, afterPosition.tilt))
                                        appendLine("Spostamento: Δx %+.1f px · Δy %+.1f px".format(
                                            sample?.shiftX ?: 0f,
                                            sample?.shiftY ?: 0f,
                                        ))
                                        appendLine("Punti coerenti: ${displayVerification?.inlierMatches ?: 0}/${displayVerification?.candidateMatches ?: 0} · ${displayVerification?.let(::controlPointPercent) ?: 0}%")
                                        append("Misura: ${if (sample?.usable == true) "valida" else "incerta"} · posizionamento $displayPositionPercent%")
                                    },
                                    imageJpeg = WaypointImageVerifier.annotatedCurrentJpeg(
                                        sweep.finalFrame,
                                        displayVerification,
                                    ),
                                )
                                if (done % LOG_EVERY_STEPS == 0) {
                                    log.info(
                                        "CALIBRAZIONE GIMBAL · $done/${GimbalCalibrationState.TOTAL_STEPS}",
                                        "Campioni leggibili: ${samples.count(GimbalCalibrationSample::usable)}",
                                    )
                                }
                                delay(BETWEEN_SAMPLES_MS)
                            }
                        }
                    }
                    // Ogni intensita riparte dallo zero vero: eventuali piccoli errori della
                    // coppia andata/ritorno non possono accumularsi sulle prove successive.
                    gimbal.recenter().getOrElse {
                        throw IllegalStateException("Ricentraggio fra le intensita non riuscito: ${it.message}", it)
                    }
                    waitForHardwareCenter()
                    gimbal.setEstimated(0f, 0f)
                }

            val profile = GimbalCalibrationBuilder.build(
                samples = samples,
                cameraModel = cameraModel,
                firmware = firmware,
                panLimits = panLimits,
                tiltLimits = tiltLimits,
            )
            if (!profile.isValid) {
                throw IllegalStateException(
                    "Misure insufficienti (${profile.validSamples}/${profile.totalSamples}): " +
                        "usa una scena ferma, più luminosa e con più dettagli",
                )
            }
            validateMotionModel(profile, firstFrame)
            store.update { profile }
            log.info(
                "CALIBRAZIONE GIMBAL · COMPLETATA",
                buildString {
                    appendLine("Qualità: ${profile.qualityPercent}% · ${profile.validSamples}/${profile.totalSamples} campioni")
                    appendLine("Ritardo comando: ${profile.responseOverheadMs} ms · assestamento: ${profile.settleMs} ms")
                    appendLine(
                        "Fine corsa pan: %.1f°…%+.1f° · %.1f s al %d%%".format(
                            profile.panLimits.minimumDeg,
                            profile.panLimits.maximumDeg,
                            profile.panLimits.travelSecondsAtSweepIntensity,
                            profile.panLimits.sweepIntensityPercent,
                        ),
                    )
                    appendLine(
                        "Fine corsa tilt: %.1f°…%+.1f° · %.1f s al %d%%".format(
                            profile.tiltLimits.minimumDeg,
                            profile.tiltLimits.maximumDeg,
                            profile.tiltLimits.travelSecondsAtSweepIntensity,
                            profile.tiltLimits.sweepIntensityPercent,
                        ),
                    )
                    profile.responsePoints.forEach { point ->
                        appendLine(
                            "%3d%%: pan %+.1f px/s · tilt %+.1f px/s".format(
                                point.intensityPercent,
                                point.panImagePixelsPerSecond,
                                point.tiltImagePixelsPerSecond,
                            ),
                        )
                    }
                    append("Profilo salvato in gimbal_calibration.json e attivo da ora.")
                },
            )
            _state.value = _state.value.copy(
                running = false,
                overallPercent = 100,
                phaseLabel = "Completata",
                message = "Calibrazione salvata e attiva",
                error = null,
            )
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.copy(running = false, message = "Calibrazione interrotta", error = null)
            log.warn("CALIBRAZIONE GIMBAL · INTERROTTA", "Il profilo precedente non è stato modificato.")
        } catch (error: Exception) {
            _state.value = _state.value.copy(running = false, message = "Calibrazione non salvata", error = error.message)
            log.error("CALIBRAZIONE GIMBAL · ERRORE", "${error.message}\nIl profilo precedente non è stato modificato.")
        } finally {
            withContext(NonCancellable) {
                runCatching { gimbal.stop() }
            }
            job = null
        }
    }

    /**
     * Ogni punto della curva e un arco composto da piu step con fotogrammi sovrapposti.
     * Sopra il 10% punta a circa 45 gradi; 1% e 5% hanno un limite temporale di sicurezza,
     * utile a misurare l'avvio lento senza trasformare la calibrazione in un test di ore.
     */
    private suspend fun measureResponseSweep(
        axis: String,
        direction: Float,
        intensityPercent: Int,
        limits: GimbalAxisLimits,
    ): ResponseSweep {
        val referenceArcMs = limits.travelSecondsAtSweepIntensity * 1000f *
            (TARGET_RESPONSE_ARC_DEG / limits.spanDeg.coerceAtLeast(TARGET_RESPONSE_ARC_DEG))
        val rawDurationMs = referenceArcMs * limits.sweepIntensityPercent / intensityPercent.coerceAtLeast(1)
        val totalDurationMs = rawDurationMs.toLong().coerceIn(MIN_RESPONSE_SWEEP_MS, MAX_RESPONSE_SWEEP_MS)
        val estimatedArc = (TARGET_RESPONSE_ARC_DEG * totalDurationMs / rawDurationMs.coerceAtLeast(1f))
            .coerceIn(1f, TARGET_RESPONSE_ARC_DEG)
        val substeps = max(MIN_RESPONSE_SUBSTEPS, ceil(totalDurationMs / MAX_RESPONSE_STEP_MS.toDouble()).toInt())
        val origin = awaitFrame("Anteprima temporaneamente assente · calibrazione in pausa")
        var previous = origin
        var finalFrame = origin
        var lastVerification: ImageVerification? = null
        var shiftX = 0f
        var shiftY = 0f
        var confidenceSum = 0f
        var inliersSum = 0
        var measuredDurationMs = 0L
        var overheadSumMs = 0L
        var settleSumMs = 0L
        var validSegments = 0
        val command = direction * intensityPercent / 100f

        repeat(substeps) { index ->
            val consumed = totalDurationMs * index / substeps
            val next = totalDurationMs * (index + 1) / substeps
            val stepMs = (next - consumed).coerceAtLeast(60L)
            val beforePosition = gimbal.position.value
            _state.value = _state.value.copy(
                pausedForPreview = false,
                axisLabel = axisLabel(axis),
                directionLabel = directionLabel(axis, direction),
                intensityPercent = intensityPercent,
                pulseMs = stepMs,
                theoreticalPan = beforePosition.pan,
                theoreticalTilt = beforePosition.tilt,
                message = "$intensityPercent% · ${axisLabel(axis)} · step ${index + 1}/$substeps · arco ~${estimatedArc.toInt()}°",
                verificationLabel = "MOVIMENTO MULTI-STEP IN CORSO",
            )
            val started = System.nanoTime()
            gimbal.calibrationPulse(
                panPercent = if (axis == GimbalCalibrationSample.AXIS_PAN) command else 0f,
                tiltPercent = if (axis == GimbalCalibrationSample.AXIS_TILT) command else 0f,
                durationMs = stepMs,
            ).getOrElse { throw IllegalStateException("Movimento di calibrazione non riuscito: ${it.message}", it) }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            val settled = captureAfterSettling()
            val verification = WaypointImageVerifier.verify(previous, settled.jpeg)
            lastVerification = verification
            finalFrame = settled.jpeg
            if (verification != null && verification.inlierMatches >= MIN_RESPONSE_INLIERS &&
                verification.confidence >= MIN_RESPONSE_CONFIDENCE
            ) {
                shiftX += verification.shiftX
                shiftY += verification.shiftY
                confidenceSum += verification.confidence
                inliersSum += verification.inlierMatches
                measuredDurationMs += stepMs
                overheadSumMs += (elapsedMs - stepMs).coerceAtLeast(0L)
                settleSumMs += settled.elapsedMs
                validSegments++
            }
            val afterPosition = gimbal.position.value
            _state.value = _state.value.copy(
                theoreticalPan = afterPosition.pan,
                theoreticalTilt = afterPosition.tilt,
                shiftX = verification?.shiftX ?: 0f,
                shiftY = verification?.shiftY ?: 0f,
                referenceFeatures = verification?.referenceFeatures ?: 0,
                currentFeatures = verification?.currentFeatures ?: 0,
                candidateMatches = verification?.candidateMatches ?: 0,
                inlierMatches = verification?.inlierMatches ?: 0,
                controlPointsPercent = verification?.let(::controlPointPercent) ?: 0,
                positioningPercent = ((index + 1) * 100 / substeps),
                verificationLabel = when {
                    verification == null -> "NESSUN FOTOGRAMMA CONFRONTABILE"
                    verification.confidence >= MIN_RESPONSE_CONFIDENCE ->
                        "STEP ${index + 1}/$substeps MISURATO · ${verification.verdict.label}"
                    else -> "STEP ${index + 1}/$substeps · CORRELAZIONE DEBOLE"
                },
                annotatedJpeg = WaypointImageVerifier.annotatedCurrentJpeg(settled.jpeg, verification),
            )
            previous = settled.jpeg
        }

        val sample = if (validSegments > 0 && measuredDurationMs > 0L) {
            GimbalCalibrationSample(
                intensityPercent = intensityPercent,
                axis = axis,
                command = command,
                pulseMs = measuredDurationMs,
                shiftX = shiftX,
                shiftY = shiftY,
                inliers = inliersSum / validSegments,
                confidence = confidenceSum / validSegments,
                commandOverheadMs = overheadSumMs / validSegments,
                settleMs = settleSumMs / validSegments,
            )
        } else null
        return ResponseSweep(
            sample = sample,
            origin = origin,
            finalFrame = finalFrame,
            lastStepVerification = lastVerification,
            targetDegrees = estimatedArc,
            substeps = substeps,
            commandDurationMs = totalDurationMs,
        )
    }

    private data class ResponseSweep(
        val sample: GimbalCalibrationSample?,
        val origin: ByteArray,
        val finalFrame: ByteArray,
        val lastStepVerification: ImageVerification?,
        val targetDegrees: Float,
        val substeps: Int,
        val commandDurationMs: Long,
    )

    /**
     * Collaudo del modello prima del salvataggio. Per il pan destro esegue esplicitamente
     * il caso richiesto: 0° -> 200° teorici -> ultimi 35° circa -> notifica di fine corsa.
     *
     * Il riferimento visivo dello zero viene ripreso qui, non ereditato dall'avvio: fra la
     * prima inquadratura e questo punto passano otto minuti, e in otto minuti la scena reale
     * cambia da sola — le persone si spostano, la luce gira, il vento muove le foglie. Con un
     * riferimento vecchio la ripetibilità dello zero misura il mondo, non il gimbal.
     */
    private suspend fun validateMotionModel(profile: GimbalCalibrationProfile, startReference: ByteArray) {
        data class Target(val axis: String, val endpoint: Float, val checkpoint: Float)
        val targets = listOf(
            Target(GimbalCalibrationSample.AXIS_PAN, profile.panLimits.maximumDeg, 200f),
            Target(GimbalCalibrationSample.AXIS_PAN, profile.panLimits.minimumDeg, -45f),
            Target(GimbalCalibrationSample.AXIS_TILT, profile.tiltLimits.maximumDeg, 90f),
            Target(GimbalCalibrationSample.AXIS_TILT, profile.tiltLimits.minimumDeg, -45f),
        )
        _state.value = _state.value.copy(
            phaseLabel = "Validazione coordinate",
            overallPercent = 90,
            message = "Verifico che gli impulsi previsti coincidano coi quattro fine corsa",
        )

        recenterHardware("Zero di riferimento della validazione", 90, 90)
        val zeroReference = awaitFrame("Attendo l'immagine dello zero di riferimento")
        describeSceneDrift(startReference, zeroReference)

        val zeroChecks = mutableListOf<ZeroCheck>()
        targets.forEachIndexed { index, target ->
            recenterHardware("Zero prima della verifica ${directionLabel(target.axis, target.endpoint)}", 90 + index * 2, 91 + index * 2)
            zeroChecks += verifyRepeatedZero(zeroReference, index + 1, targets.size + 1)
            val checkpoint = target.checkpoint.coerceIn(
                min(0f, target.endpoint),
                max(0f, target.endpoint),
            )
            val first = movePredictedDegrees(profile, target.axis, checkpoint, VALIDATION_FAST_INTENSITY_PERCENT, false)
            if (first.limitReached) {
                throw IllegalStateException(
                    "Il fine corsa ${directionLabel(target.axis, target.endpoint)} e arrivato prima dei ${abs(checkpoint).toInt()}° previsti",
                )
            }

            val expectedRemainder = target.endpoint - checkpoint
            val finalApproach = movePredictedDegrees(
                profile,
                target.axis,
                expectedRemainder,
                VALIDATION_FINE_INTENSITY_PERCENT,
                true,
            )
            var reached = finalApproach.limitReached
            var actualRemainder = finalApproach.predictedDegrees
            while (!reached && abs(actualRemainder - expectedRemainder) < VALIDATION_EXTRA_SEARCH_DEG) {
                val extra = movePredictedDegrees(
                    profile,
                    target.axis,
                    VALIDATION_EXTRA_STEP_DEG * kotlin.math.sign(target.endpoint),
                    VALIDATION_FINE_INTENSITY_PERCENT,
                    true,
                )
                actualRemainder += extra.predictedDegrees
                reached = extra.limitReached
            }
            if (!reached) {
                throw IllegalStateException(
                    "Il fine corsa ${directionLabel(target.axis, target.endpoint)} non coincide col modello entro ${VALIDATION_EXTRA_SEARCH_DEG.toInt()}°",
                )
            }
            val endpointError = abs(actualRemainder - expectedRemainder)
            if (endpointError > VALIDATION_ENDPOINT_TOLERANCE_DEG) {
                throw IllegalStateException(
                    "Errore ${directionLabel(target.axis, target.endpoint)} %.1f°: il profilo non e abbastanza preciso".format(endpointError),
                )
            }
            _state.value = _state.value.copy(
                overallPercent = 92 + index * 2,
                axisLabel = axisLabel(target.axis),
                directionLabel = directionLabel(target.axis, target.endpoint),
                theoreticalPan = if (target.axis == GimbalCalibrationSample.AXIS_PAN) target.endpoint else 0f,
                theoreticalTilt = if (target.axis == GimbalCalibrationSample.AXIS_TILT) target.endpoint else 0f,
                positioningPercent = ((1f - endpointError / VALIDATION_ENDPOINT_TOLERANCE_DEG)
                    .coerceIn(0f, 1f) * 100f).toInt(),
                verificationLabel = "FINE CORSA COERENTE · ERRORE %.1f°".format(endpointError),
                message = "${directionLabel(target.axis, target.endpoint)}: punto previsto ${checkpoint.toInt()}°, residuo ${expectedRemainder.toInt()}°",
            )
            log.info(
                "CALIBRAZIONE · VALIDAZIONE ${directionLabel(target.axis, target.endpoint).uppercase()}",
                buildString {
                    appendLine("Zero hardware -> checkpoint previsto: ${checkpoint.toInt()}°")
                    appendLine("Checkpoint -> fine corsa previsto: ${expectedRemainder.toInt()}°")
                    appendLine("Movimento residuo osservato: %.1f°".format(actualRemainder))
                    append("Errore del modello: %.1f° · segnale hardware 8302 ricevuto".format(endpointError))
                },
                imageJpeg = preview.captureThumbnailJpeg(),
            )
        }

        recenterHardware("Verifica finale dello zero", 98, 99)
        zeroChecks += verifyRepeatedZero(zeroReference, targets.size + 1, targets.size + 1)

        val judged = zeroChecks.filter(ZeroCheck::comparable)
        val mismatches = judged.count(ZeroCheck::mismatch)
        val worst = zeroChecks.maxOfOrNull(ZeroCheck::displacementPixels) ?: 0f
        _state.value = _state.value.copy(
            overallPercent = 99,
            verificationLabel = when {
                judged.isEmpty() -> "ZERO HARDWARE · SCENA NON CONFRONTABILE"
                mismatches == 0 -> "ZERO HARDWARE RIPETIBILE"
                else -> "ZERO HARDWARE SPOSTATO IN $mismatches PROVE SU ${judged.size}"
            },
        )
        val summary = buildString {
            appendLine("Prove confrontabili: ${judged.size}/${zeroChecks.size} · scarto massimo %.1f px".format(worst))
            append(
                when {
                    judged.isEmpty() ->
                        "Nessuna prova era confrontabile: durante la calibrazione la scena è " +
                            "cambiata. La curva è stata comunque misurata e viene salvata; per " +
                            "avere anche la ripetibilità dello zero servono otto minuti di scena ferma."
                    mismatches == 0 ->
                        "Lo zero hardware è tornato ogni volta nello stesso punto."
                    else ->
                        "Lo zero hardware si è spostato in $mismatches prove su ${judged.size}. " +
                            "La curva di velocità resta valida — è misurata sugli spostamenti, non " +
                            "sul punto di partenza — ma i waypoint memorizzati prima di questa " +
                            "calibrazione possono essere sfalsati: rifalli."
                },
            )
        }
        if (mismatches > 0 || judged.isEmpty()) {
            log.warn("CALIBRAZIONE · RIPETIBILITÀ DELLO ZERO", summary)
        } else {
            log.info("CALIBRAZIONE · RIPETIBILITÀ DELLO ZERO", summary)
        }
    }

    /**
     * Ogni prova parte dal medesimo zero ottico, non soltanto da coordinate azzerate in memoria.
     *
     * Lo scarto viene creduto solo se il confronto ha un consenso forte: con qualcuno che si
     * muove davanti alla camera RANSAC trova comunque una traslazione, e quella traslazione
     * non è il gimbal. Quando il consenso è debole si registra "scena cambiata" e si va avanti.
     * In nessun caso la ripetibilità dello zero butta via la curva già misurata: dice dove
     * guarda lo zero, non quanto è veloce il gimbal — sono due misure diverse.
     */
    private suspend fun verifyRepeatedZero(zeroReference: ByteArray, attempt: Int, attempts: Int): ZeroCheck {
        val current = awaitFrame("Verifico che il centro hardware sia sempre lo stesso")
        val verification = WaypointImageVerifier.verify(zeroReference, current)
        val check = classifyZero(verification)
        val annotated = WaypointImageVerifier.annotatedCurrentJpeg(current, verification)
        val detail = buildString {
            appendLine(verification?.describe() ?: "Comando BACK_CENTER eseguito; scena non confrontabile.")
            append(check.explanation)
        }
        if (check.mismatch) {
            log.warn("CALIBRAZIONE · ZERO PRIMA DELLA PROVA $attempt", detail, imageJpeg = annotated)
        } else {
            log.info("CALIBRAZIONE · ZERO PRIMA DELLA PROVA $attempt", detail, imageJpeg = annotated)
        }
        _state.value = _state.value.copy(
            shiftX = verification?.shiftX ?: 0f,
            shiftY = verification?.shiftY ?: 0f,
            positioningPercent = verification?.let(::returnPositionPercent) ?: 0,
            verificationLabel = when {
                check.mismatch -> "ZERO SPOSTATO · PROVA $attempt/$attempts"
                check.comparable -> "ZERO RIPETIBILE · PROVA $attempt/$attempts"
                else -> "SCENA CAMBIATA · PROVA $attempt/$attempts"
            },
            annotatedJpeg = annotated,
        )
        return check
    }

    /**
     * Esito di un confronto con lo zero ottico.
     *
     * [comparable] vuol dire che il confronto ha davvero misurato qualcosa; [mismatch] che il
     * gimbal non è tornato dove era. Un confronto non comparabile non è un fallimento del
     * gimbal: è una scena che nel frattempo si è mossa.
     */
    private data class ZeroCheck(
        val comparable: Boolean,
        val mismatch: Boolean,
        val displacementPixels: Float,
        val explanation: String,
    )

    private fun classifyZero(verification: ImageVerification?): ZeroCheck {
        if (verification == null) {
            return ZeroCheck(false, false, 0f, "Nessun fotogramma confrontabile: verifica solo informativa.")
        }
        val inlierPercent = controlPointPercent(verification)
        val trustworthy = verification.confidence >= ZERO_MIN_CONFIDENCE &&
            verification.inlierMatches >= ZERO_MIN_INLIERS &&
            inlierPercent >= ZERO_MIN_INLIER_PERCENT
        if (!trustworthy) {
            return ZeroCheck(
                comparable = false,
                mismatch = false,
                displacementPixels = verification.displacementPixels,
                explanation = "Consenso troppo debole per giudicare lo zero: " +
                    "${verification.inlierMatches} punti coerenti, il $inlierPercent% delle " +
                    "corrispondenze, confidenza %.0f%%. ".format(verification.confidence * 100f) +
                    "A cambiare è stata la scena durante la calibrazione, non il gimbal.",
            )
        }
        val mismatch = verification.displacementPixels > ZERO_REPEATABILITY_RADIUS_PX
        return ZeroCheck(
            comparable = true,
            mismatch = mismatch,
            displacementPixels = verification.displacementPixels,
            explanation = if (mismatch) {
                "Lo zero hardware non è tornato dov'era: scarto %.1f px, limite %.0f px. "
                    .format(verification.displacementPixels, ZERO_REPEATABILITY_RADIUS_PX) +
                    "La curva misurata resta valida; a spostarsi è il punto di partenza."
            } else {
                "Zero ripetibile entro %.0f px.".format(ZERO_REPEATABILITY_RADIUS_PX)
            },
        )
    }

    /**
     * Confronto informativo fra la prima inquadratura e lo zero della validazione.
     *
     * Non giudica niente: serve a rispondere, leggendo il log, alla domanda "ma la camera
     * guardava ancora dalla stessa parte?" senza doverla dedurre dalle miniature.
     */
    private fun describeSceneDrift(startReference: ByteArray, validationReference: ByteArray) {
        val verification = WaypointImageVerifier.verify(startReference, validationReference)
        val detail = buildString {
            appendLine("Confronto fra la prima inquadratura e lo zero di adesso, solo informativo.")
            appendLine(verification?.describe() ?: "Le due immagini non sono confrontabili.")
            append(
                if (verification == null || controlPointPercent(verification) < ZERO_MIN_INLIER_PERCENT) {
                    "Scena diversa da quella di partenza: qualcosa si è mosso, oppure lo zero " +
                        "hardware non è dove era all'avvio. La validazione userà questa immagine."
                } else {
                    "Stessa scena della partenza: lo zero hardware guarda ancora là."
                },
            )
        }
        log.info("CALIBRAZIONE · ZERO DI RIFERIMENTO DELLA VALIDAZIONE", detail, imageJpeg = validationReference)
    }

    private suspend fun movePredictedDegrees(
        profile: GimbalCalibrationProfile,
        axis: String,
        degrees: Float,
        intensityPercent: Int,
        stopAtLimit: Boolean,
    ): PredictedMove {
        if (degrees == 0f) return PredictedMove(false, 0f)
        val panAxis = axis == GimbalCalibrationSample.AXIS_PAN
        val rate = profile.angularRateAt(intensityPercent.toFloat(), panAxis)
        if (rate <= 0f) throw IllegalStateException("Velocita ${axisLabel(axis)} non calcolabile al $intensityPercent%")
        val direction = kotlin.math.sign(degrees)
        val origin = gimbal.position.value
        var remainingMs = abs(degrees) / rate * 1000f
        var predicted = 0f
        while (remainingMs > 0f) {
            val stepMs = min(remainingMs, if (stopAtLimit) VALIDATION_FINE_STEP_MS else VALIDATION_FAST_STEP_MS)
                .toLong().coerceAtLeast(60L)
            val mark = limitMonitor.mark()
            gimbal.calibrationPulse(
                panPercent = if (panAxis) direction * intensityPercent / 100f else 0f,
                tiltPercent = if (!panAxis) direction * intensityPercent / 100f else 0f,
                durationMs = stepMs,
            ).getOrElse { throw IllegalStateException("Movimento di validazione non riuscito: ${it.message}", it) }
            val stepDegrees = rate * stepMs / 1000f * direction
            predicted += stepDegrees
            remainingMs -= stepMs
            if (limitMonitor.reached(axis, mark)) {
                gimbal.setEstimated(
                    pan = if (panAxis) (origin.pan + predicted) else origin.pan,
                    tilt = if (!panAxis) (origin.tilt + predicted) else origin.tilt,
                )
                return PredictedMove(true, predicted)
            }
        }
        gimbal.setEstimated(
            pan = if (panAxis) (origin.pan + predicted) else origin.pan,
            tilt = if (!panAxis) (origin.tilt + predicted) else origin.tilt,
        )
        return PredictedMove(false, predicted)
    }

    private data class PredictedMove(val limitReached: Boolean, val predictedDegrees: Float)

    /** Trova i due estremi: aggancio rapido, arretramento e riaggancio lento di precisione. */
    private suspend fun calibrateAxisLimits(
        axis: String,
        minimumDeg: Float,
        maximumDeg: Float,
        phaseStartPercent: Int,
        phaseEndPercent: Int,
    ): GimbalAxisLimits {
        val negative = findPreciseEndStop(
            axis = axis,
            direction = -1f,
            phaseLabel = "Fine corsa ${directionLabel(axis, -1f)}",
            phaseStartPercent = phaseStartPercent,
            phaseEndPercent = phaseStartPercent + (phaseEndPercent - phaseStartPercent) / 3,
            requireTravel = false,
        )
        val positionAtNegative = gimbal.position.value
        gimbal.setEstimated(
            pan = if (axis == GimbalCalibrationSample.AXIS_PAN) minimumDeg else positionAtNegative.pan,
            tilt = if (axis == GimbalCalibrationSample.AXIS_TILT) minimumDeg else positionAtNegative.tilt,
        )
        log.info(
            "CALIBRAZIONE · FINE CORSA ${directionLabel(axis, -1f).uppercase()}",
            "Rilevato dopo ${negative.totalPulses} impulsi · coordinate fissate a %.1f°".format(minimumDeg),
            imageJpeg = negative.annotatedJpeg,
        )

        val positive = findPreciseEndStop(
            axis = axis,
            direction = 1f,
            phaseLabel = "Corsa completa verso ${directionLabel(axis, 1f)}",
            phaseStartPercent = phaseStartPercent + (phaseEndPercent - phaseStartPercent) / 3,
            phaseEndPercent = phaseEndPercent,
            requireTravel = true,
        )
        val positionAtPositive = gimbal.position.value
        gimbal.setEstimated(
            pan = if (axis == GimbalCalibrationSample.AXIS_PAN) maximumDeg else positionAtPositive.pan,
            tilt = if (axis == GimbalCalibrationSample.AXIS_TILT) maximumDeg else positionAtPositive.tilt,
        )
        val limits = GimbalAxisLimits(
            minimumDeg = minimumDeg,
            maximumDeg = maximumDeg,
            sweepIntensityPercent = ENDSTOP_INTENSITY_PERCENT,
            travelSecondsAtSweepIntensity = positive.movingDurationMs / 1000f,
            movingPulses = positive.movingPulses,
            endpointConfidencePercent = positive.confidencePercent,
        )
        if (!limits.isValid) {
            throw IllegalStateException("Fine corsa ${axisLabel(axis)} non affidabili: libera il movimento della camera")
        }
        log.info(
            "CALIBRAZIONE · CORSA ${axisLabel(axis).uppercase()} COMPLETA",
            formatAxisLimitSummary(limits),
            imageJpeg = positive.annotatedJpeg,
        )
        return limits
    }

    private suspend fun findPreciseEndStop(
        axis: String,
        direction: Float,
        phaseLabel: String,
        phaseStartPercent: Int,
        phaseEndPercent: Int,
        requireTravel: Boolean,
    ): EndStopSearch {
        val split = phaseStartPercent + (phaseEndPercent - phaseStartPercent) * 2 / 3
        val coarse = seekEndStop(
            axis = axis,
            direction = direction,
            phaseLabel = "$phaseLabel · avvicinamento rapido",
            phaseStartPercent = phaseStartPercent,
            phaseEndPercent = split,
            requireTravel = requireTravel,
            intensityPercent = ENDSTOP_INTENSITY_PERCENT,
            pulseMs = ENDSTOP_PULSE_MS,
            maxPulses = MAX_ENDSTOP_PULSES,
        )

        _state.value = _state.value.copy(
            phaseLabel = "$phaseLabel · arretramento",
            overallPercent = split,
            directionLabel = directionLabel(axis, -direction),
            intensityPercent = ENDSTOP_BACKOFF_INTENSITY_PERCENT,
            pulseMs = ENDSTOP_BACKOFF_MS,
            message = "Mi allontano dal limite prima della verifica lenta",
            verificationLabel = "FINE CORSA AGGANCIATO · PREPARO LA RIFINITURA",
        )
        gimbal.calibrationPulse(
            panPercent = if (axis == GimbalCalibrationSample.AXIS_PAN) -direction * ENDSTOP_BACKOFF_INTENSITY else 0f,
            tiltPercent = if (axis == GimbalCalibrationSample.AXIS_TILT) -direction * ENDSTOP_BACKOFF_INTENSITY else 0f,
            durationMs = ENDSTOP_BACKOFF_MS,
        ).getOrElse { throw IllegalStateException("Arretramento dal fine corsa non riuscito: ${it.message}", it) }
        delay(ENDSTOP_SETTLE_MS)

        val fine = seekEndStop(
            axis = axis,
            direction = direction,
            phaseLabel = "$phaseLabel · aggancio lento",
            phaseStartPercent = split,
            phaseEndPercent = phaseEndPercent,
            requireTravel = false,
            intensityPercent = FINE_ENDSTOP_INTENSITY_PERCENT,
            pulseMs = FINE_ENDSTOP_PULSE_MS,
            maxPulses = MAX_FINE_ENDSTOP_PULSES,
        )
        log.info(
            "CALIBRAZIONE · $phaseLabel · PRECISIONE",
            "Primo aggancio: ${coarse.totalPulses} impulsi al ${ENDSTOP_INTENSITY_PERCENT}% · " +
                "secondo aggancio: ${fine.totalPulses} impulsi al ${FINE_ENDSTOP_INTENSITY_PERCENT}% · " +
                "segnale hardware: ${if (fine.hardwareSignal) "SI" else "fallback visivo"}",
            imageJpeg = fine.annotatedJpeg,
        )
        return fine.copy(
            movingPulses = coarse.movingPulses,
            movingDurationMs = coarse.movingDurationMs,
        )
    }

    private suspend fun seekEndStop(
        axis: String,
        direction: Float,
        phaseLabel: String,
        phaseStartPercent: Int,
        phaseEndPercent: Int,
        requireTravel: Boolean,
        intensityPercent: Int,
        pulseMs: Long,
        maxPulses: Int,
    ): EndStopSearch {
        var consecutiveStill = 0
        var movingPulses = 0
        var lastAnnotated: ByteArray? = null
        var endpointConfidenceSum = 0f

        var movingDurationMs = 0L

        for (pulse in 1..maxPulses) {
            val before = preview.captureThumbnailJpeg()
            val beforePosition = gimbal.position.value
            _state.value = _state.value.copy(
                phaseLabel = phaseLabel,
                overallPercent = phaseStartPercent +
                    ((phaseEndPercent - phaseStartPercent) * pulse / maxPulses),
                axisLabel = axisLabel(axis),
                directionLabel = directionLabel(axis, direction),
                intensityPercent = intensityPercent,
                pulseMs = pulseMs,
                theoreticalPan = beforePosition.pan,
                theoreticalTilt = beforePosition.tilt,
                message = "$phaseLabel · impulso $pulse",
                verificationLabel = "ATTENDO IL SEGNALE HARDWARE DI FINE CORSA",
            )
            val signalMark = limitMonitor.mark()
            gimbal.calibrationPulse(
                panPercent = if (axis == GimbalCalibrationSample.AXIS_PAN) direction * intensityPercent / 100f else 0f,
                tiltPercent = if (axis == GimbalCalibrationSample.AXIS_TILT) direction * intensityPercent / 100f else 0f,
                durationMs = pulseMs,
            ).getOrElse { throw IllegalStateException("Ricerca fine corsa non riuscita: ${it.message}", it) }
            delay(ENDSTOP_SETTLE_MS)
            val hardwareLimit = limitMonitor.reached(axis, signalMark)
            val after = preview.captureThumbnailJpeg()
            val verification = WaypointImageVerifier.verify(before, after)
            val still = verification != null && verification.inlierMatches >= MIN_ENDSTOP_INLIERS &&
                verification.displacementPixels <= ENDSTOP_STILL_RADIUS_PX
            // Attraversando tutta la corsa si parte ancora appoggiati al limite opposto. Una
            // notifica 8302 residua dei primissimi impulsi non deve essere scambiata per il
            // nuovo estremo: viene accettata solo dopo un movimento reale minimo.
            val acceptedHardwareLimit = hardwareLimit &&
                (!requireTravel || movingPulses >= MIN_ENDSTOP_TRAVEL_PULSES)
            val acceptedVisualStill = still &&
                (!requireTravel || movingPulses >= MIN_ENDSTOP_TRAVEL_PULSES)
            if (acceptedHardwareLimit) {
                consecutiveStill = ENDSTOP_CONFIRMATIONS
                endpointConfidenceSum = ENDSTOP_CONFIRMATIONS.toFloat()
            } else if (acceptedVisualStill) {
                consecutiveStill++
                endpointConfidenceSum += verification.confidence
            } else {
                consecutiveStill = 0
                endpointConfidenceSum = 0f
                movingPulses++
                movingDurationMs += pulseMs
            }
            lastAnnotated = WaypointImageVerifier.annotatedCurrentJpeg(after, verification)
            val afterPosition = gimbal.position.value
            val endpointPercent = (consecutiveStill * 100 / ENDSTOP_CONFIRMATIONS).coerceIn(0, 100)
            _state.value = _state.value.copy(
                theoreticalPan = afterPosition.pan,
                theoreticalTilt = afterPosition.tilt,
                shiftX = verification?.shiftX ?: 0f,
                shiftY = verification?.shiftY ?: 0f,
                referenceFeatures = verification?.referenceFeatures ?: 0,
                currentFeatures = verification?.currentFeatures ?: 0,
                candidateMatches = verification?.candidateMatches ?: 0,
                inlierMatches = verification?.inlierMatches ?: 0,
                controlPointsPercent = verification?.let(::controlPointPercent) ?: 0,
                positioningPercent = endpointPercent,
                verificationLabel = when {
                    acceptedHardwareLimit -> "FINE CORSA CONFERMATO DALLA CAMERA (8302)"
                    hardwareLimit -> "SEGNALE DEL LIMITE PRECEDENTE · VERIFICO IL MOVIMENTO"
                    acceptedVisualStill ->
                    "NESSUN MOVIMENTO · CONFERMA $consecutiveStill/$ENDSTOP_CONFIRMATIONS"
                    else ->
                    "MOVIMENTO RILEVATO · IL FINE CORSA NON È ANCORA QUI"
                },
                annotatedJpeg = lastAnnotated,
            )
            if (pulse % ENDSTOP_LOG_EVERY_PULSES == 0 || consecutiveStill > 0 || hardwareLimit) {
                log.info(
                    "CALIBRAZIONE · $phaseLabel · IMPULSO $pulse",
                    buildString {
                        appendLine("Comando: $intensityPercent% · ${directionLabel(axis, direction)} · $pulseMs ms")
                        appendLine("Δx %+.1f px · Δy %+.1f px".format(verification?.shiftX ?: 0f, verification?.shiftY ?: 0f))
                        appendLine("Punti coerenti: ${verification?.inlierMatches ?: 0}/${verification?.candidateMatches ?: 0}")
                        append("Segnale 8302: ${if (acceptedHardwareLimit) "FINE CORSA" else if (hardwareLimit) "residuo ignorato" else "—"} · conferme visive $consecutiveStill/$ENDSTOP_CONFIRMATIONS")
                    },
                    imageJpeg = lastAnnotated,
                )
            }
            if (consecutiveStill >= ENDSTOP_CONFIRMATIONS) {
                if (requireTravel && movingPulses < MIN_ENDSTOP_TRAVEL_PULSES) {
                    throw IllegalStateException(
                        "Il gimbal non ha attraversato la corsa ${axisLabel(axis)}: controlla il verso o eventuali ostacoli",
                    )
                }
                val confidence = ((endpointConfidenceSum / ENDSTOP_CONFIRMATIONS) * 100f)
                    .toInt().coerceIn(0, 100)
                return EndStopSearch(
                    totalPulses = pulse,
                    movingPulses = movingPulses,
                    movingDurationMs = movingDurationMs,
                    confidencePercent = confidence,
                    annotatedJpeg = lastAnnotated,
                    hardwareSignal = acceptedHardwareLimit,
                )
            }
            delay(BETWEEN_ENDSTOP_PULSES_MS)
        }
        throw IllegalStateException(
            "Fine corsa ${directionLabel(axis, direction)} non trovato entro il limite di sicurezza",
        )
    }

    /** Richiama `GIMBAL_ACTION_BACK_CENTER`: lo zero e deciso dal firmware, non dal cronometro. */
    private suspend fun recenterHardware(
        label: String,
        phaseStartPercent: Int,
        phaseEndPercent: Int,
    ) {
        _state.value = _state.value.copy(
            phaseLabel = label,
            overallPercent = phaseStartPercent,
            axisLabel = "entrambi",
            directionLabel = "centro",
            intensityPercent = 0,
            pulseMs = 0,
            message = "Comando hardware di ritorno a 0°/0°",
            verificationLabel = "RICENTRAGGIO HARDWARE IN CORSO",
        )
        gimbal.recenter().getOrElse { throw IllegalStateException("Ricentraggio hardware non riuscito: ${it.message}", it) }
        waitForHardwareCenter()
        gimbal.setEstimated(0f, 0f)
        _state.value = _state.value.copy(
            theoreticalPan = 0f,
            theoreticalTilt = 0f,
            overallPercent = phaseEndPercent,
            verificationLabel = "ZERO HARDWARE 0°/0° RIPRISTINATO",
        )
        log.info("CALIBRAZIONE · $label", "Eseguito GIMBAL_ACTION_BACK_CENTER (payload 08 02). Coordinate teoriche azzerate.")
    }

    private suspend fun waitForHardwareCenter() {
        delay(HARDWARE_CENTER_MIN_WAIT_MS)
        var previous = preview.captureThumbnailJpeg()
        var stableChecks = 0
        repeat(HARDWARE_CENTER_MAX_CHECKS) {
            delay(HARDWARE_CENTER_CHECK_MS)
            val current = preview.captureThumbnailJpeg()
            val verification = WaypointImageVerifier.verify(previous, current)
            val stable = verification != null && verification.confidence >= 0.25f &&
                verification.displacementPixels <= STABLE_RADIUS_PX
            stableChecks = if (stable) stableChecks + 1 else 0
            previous = current ?: previous
            if (stableChecks >= HARDWARE_CENTER_STABLE_CHECKS) return
        }
    }

    private data class EndStopSearch(
        val totalPulses: Int,
        val movingPulses: Int,
        val movingDurationMs: Long,
        val confidencePercent: Int,
        val annotatedJpeg: ByteArray?,
        val hardwareSignal: Boolean,
    )

    /** Aspetta finché due frame consecutivi non mostrano più inerzia apprezzabile. */
    private suspend fun captureAfterSettling(): SettledFrame {
        var elapsed = INITIAL_SETTLE_MS
        delay(INITIAL_SETTLE_MS)
        var latest = awaitFrame("Gimbal fermo · attendo il fotogramma di verifica")
        repeat(MAX_SETTLE_CHECKS) {
            delay(SETTLE_CHECK_MS)
            elapsed += SETTLE_CHECK_MS
            val next = awaitFrame("Gimbal fermo · anteprima sospesa, attendo il ritorno")
            val residual = WaypointImageVerifier.verify(latest, next)
            latest = next
            val stable = residual != null && residual.inlierMatches >= 5 &&
                hypot(residual.shiftX, residual.shiftY) <= STABLE_RADIUS_PX
            if (stable) return SettledFrame(latest, elapsed)
        }
        return SettledFrame(latest, elapsed)
    }

    private data class SettledFrame(val jpeg: ByteArray, val elapsedMs: Long)

    /**
     * Cambiare app non annulla il job: il servizio mantiene CPU, Wi-Fi e sessione. Se Android
     * distrugge la Surface H.265, qui si mette in pausa soltanto l'analisi delle immagini e si
     * riprende automaticamente appena torna un frame, senza perdere le misure già raccolte.
     */
    private suspend fun awaitFrame(reason: String): ByteArray {
        while (currentCoroutineContext().isActive) {
            preview.captureThumbnailJpeg()?.let {
                if (_state.value.pausedForPreview) {
                    _state.value = _state.value.copy(pausedForPreview = false, message = "Anteprima ripristinata · riprendo la calibrazione")
                    log.info("CALIBRAZIONE GIMBAL · ANTEPRIMA RIPRISTINATA", "Riprendo dal passaggio ${_state.value.completedSteps + 1}.")
                }
                return it
            }
            if (!_state.value.pausedForPreview) {
                log.warn(
                    "CALIBRAZIONE GIMBAL · IN PAUSA",
                    "Fotogramma non disponibile. Le misure restano in memoria e la calibrazione riprenderà automaticamente.",
                )
            }
            _state.value = _state.value.copy(pausedForPreview = true, message = reason)
            delay(RETRY_PREVIEW_MS)
        }
        throw CancellationException("Calibrazione interrotta")
    }

    private fun controlPointPercent(verification: ImageVerification): Int =
        if (verification.candidateMatches <= 0) 0
        else (verification.inlierMatches * 100 / verification.candidateMatches).coerceIn(0, 100)

    /** Coerenza della risposta osservata rispetto alle misure precedenti dello stesso asse. */
    private fun responseScorePercent(sample: GimbalCalibrationSample, previous: List<GimbalCalibrationSample>): Int {
        val peers = previous.filter {
            it.usable && it.intensityPercent == sample.intensityPercent && it.axis == sample.axis
        }.map { it.signedPixelsPerSecond }.sorted()
        if (peers.isEmpty()) return (sample.confidence * 100f).toInt().coerceIn(0, 100)
        val expected = peers[peers.size / 2]
        if (abs(expected) < 1f) return 0
        val errorRatio = abs(sample.signedPixelsPerSecond - expected) / abs(expected)
        return ((1f - errorRatio).coerceIn(0f, 1f) * 100f).toInt()
    }

    /** Percentuale intuitiva del ritorno all'origine della coppia di impulsi. */
    private fun returnPositionPercent(verification: ImageVerification): Int {
        val geometry = (1f - verification.displacementPixels / RETURN_ZERO_SCORE_RADIUS_PX).coerceIn(0f, 1f)
        val points = controlPointPercent(verification) / 100f
        return ((geometry * 0.65f + points * 0.20f + verification.confidence * 0.15f) * 100f)
            .toInt().coerceIn(0, 100)
    }

    private fun axisLabel(axis: String) = if (axis == GimbalCalibrationSample.AXIS_PAN) "orizzontale" else "verticale"
    private fun directionLabel(axis: String, direction: Float): String = when {
        axis == GimbalCalibrationSample.AXIS_PAN && direction > 0f -> "destra"
        axis == GimbalCalibrationSample.AXIS_PAN -> "sinistra"
        direction > 0f -> "alto"
        else -> "basso"
    }

    private companion object {
        val INTENSITY_PERCENTAGES = intArrayOf(1, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
        const val REPETITIONS = 1
        const val TARGET_RESPONSE_ARC_DEG = 45f
        const val MIN_RESPONSE_SWEEP_MS = 1_000L
        const val MAX_RESPONSE_SWEEP_MS = 8_000L
        const val MAX_RESPONSE_STEP_MS = 650L
        const val MIN_RESPONSE_SUBSTEPS = 3
        const val MIN_RESPONSE_INLIERS = 5
        const val MIN_RESPONSE_CONFIDENCE = 0.18f
        const val ENDSTOP_INTENSITY_PERCENT = 40
        const val ENDSTOP_PULSE_MS = 650L
        const val MAX_ENDSTOP_PULSES = 120
        const val ENDSTOP_BACKOFF_INTENSITY_PERCENT = 20
        const val ENDSTOP_BACKOFF_INTENSITY = ENDSTOP_BACKOFF_INTENSITY_PERCENT / 100f
        const val ENDSTOP_BACKOFF_MS = 1_200L
        const val FINE_ENDSTOP_INTENSITY_PERCENT = 10
        const val FINE_ENDSTOP_PULSE_MS = 280L
        const val MAX_FINE_ENDSTOP_PULSES = 90
        const val MIN_ENDSTOP_TRAVEL_PULSES = 6
        const val ENDSTOP_CONFIRMATIONS = 3
        const val ENDSTOP_LOG_EVERY_PULSES = 5
        const val MIN_ENDSTOP_INLIERS = 5
        const val ENDSTOP_STILL_RADIUS_PX = 2.4f
        const val BETWEEN_ENDSTOP_PULSES_MS = 120L
        const val ENDSTOP_SETTLE_MS = 180L
        // Intervalli controllabili ufficiali (non i limiti meccanici più ampi).
        const val OFFICIAL_PAN_MIN_DEG = -57f
        const val OFFICIAL_PAN_MAX_DEG = 235f
        const val OFFICIAL_TILT_MIN_DEG = -57f
        const val OFFICIAL_TILT_MAX_DEG = 120f
        const val MIN_SCENE_CONFIDENCE = 0.20f
        const val INITIAL_SETTLE_MS = 140L
        const val SETTLE_CHECK_MS = 120L
        const val MAX_SETTLE_CHECKS = 4
        const val STABLE_RADIUS_PX = 2.2f
        const val BETWEEN_SAMPLES_MS = 180L
        const val LOG_EVERY_STEPS = 12
        const val RETRY_PREVIEW_MS = 800L
        const val RETURN_ZERO_SCORE_RADIUS_PX = 28f
        const val HARDWARE_CENTER_MIN_WAIT_MS = 3_000L
        const val HARDWARE_CENTER_CHECK_MS = 300L
        const val HARDWARE_CENTER_MAX_CHECKS = 30
        const val HARDWARE_CENTER_STABLE_CHECKS = 3
        const val VALIDATION_FAST_INTENSITY_PERCENT = 100
        const val VALIDATION_FINE_INTENSITY_PERCENT = 20
        const val VALIDATION_FAST_STEP_MS = 800f
        const val VALIDATION_FINE_STEP_MS = 220f
        const val VALIDATION_EXTRA_STEP_DEG = 2f
        const val VALIDATION_EXTRA_SEARCH_DEG = 16f
        const val VALIDATION_ENDPOINT_TOLERANCE_DEG = 10f
        const val ZERO_REPEATABILITY_RADIUS_PX = 12f

        // Soglie per credere a uno scarto dello zero. Sotto queste, il confronto ha trovato
        // una traslazione ma non un consenso: è la scena che si è mossa, non il gimbal.
        const val ZERO_MIN_CONFIDENCE = 0.45f
        const val ZERO_MIN_INLIERS = 10
        const val ZERO_MIN_INLIER_PERCENT = 45
    }
}
