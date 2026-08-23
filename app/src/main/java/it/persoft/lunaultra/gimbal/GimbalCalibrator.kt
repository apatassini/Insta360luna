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
import kotlin.math.roundToInt

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
 * Posizione della casa lungo la corsa, dai soli tempi comandati.
 *
 * [toMinimumMs] è il tempo di comando speso per andare da casa al primo fine corsa, [spanMs]
 * quello speso per percorrere la corsa intera fra i due: alla stessa intensità il loro rapporto
 * è la frazione di corsa a cui sta la casa, e la velocità — ignota proprio mentre la si sta
 * misurando — si semplifica. Zero significa "non misurabile", e allora non si inventa nulla.
 */
internal fun homeDegreesFromTravel(
    minimumDeg: Float,
    maximumDeg: Float,
    toMinimumMs: Long,
    spanMs: Long,
): Float {
    if (spanMs <= 0L) return 0f
    val fraction = (toMinimumMs.toFloat() / spanMs.toFloat()).coerceIn(0f, 1f)
    return minimumDeg + (maximumDeg - minimumDeg) * fraction
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

    /**
     * L'inquadratura da cui la calibrazione parte e a cui torna sempre.
     *
     * Non è lo zero hardware, ed è deliberato: quello zero guarda dove guarda il corpo camera,
     * che è quasi sempre verso chi la sta usando. Otto minuti di misure passati a inquadrare
     * persone che si muovono non danno solo fastidio — rovinano il confronto fra fotogrammi,
     * che è lo strumento con cui la calibrazione misura. La casa è dove l'ha puntata chi
     * calibra: la si sceglie ferma, e ci si torna.
     */
    private var homePan = 0f
    private var homeTilt = 0f
    private var homeFrame: ByteArray? = null

    /**
     * Comandi inviati da casa in poi, raggruppati per intensità: intensità → millisecondi
     * con segno. È questo che riporta la camera a casa, non le coordinate.
     *
     * Per disfare un movimento non serve sapere quanti gradi al secondo fa il gimbal: basta
     * rimandare lo stesso comando, per lo stesso tempo, nel verso opposto. Il tempo è
     * simmetrico anche quando la velocità è ignota — e durante la ricerca dei fine corsa la
     * velocità *è* ignota, perché è esattamente quello che si sta per misurare. Tornare a casa
     * con le coordinate significava fidarsi di un modello non ancora esistente: il ripiego era
     * 30°/s per il pan e 20°/s per il tilt, valori di prima approssimazione, e con quelli la
     * casa finiva calcolata a 75° di tilt quando era a un grado dall'orizzonte.
     */
    private val travelPan = LinkedHashMap<Int, Long>()
    private val travelTilt = LinkedHashMap<Int, Long>()

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
            // Nessun ricentraggio d'avvio: la posizione in cui la camera si trova adesso è la
            // casa, e le coordinate del modello partono da lì. Ricentrare qui butterebbe via
            // l'unica informazione che l'app non può ricostruire da sola — dove l'utente ha
            // deciso di guardare.
            gimbal.stop()
            gimbal.setEstimated(0f, 0f)
            homePan = 0f
            homeTilt = 0f
            val firstFrame = awaitFrame("Avvio: attendo un fotogramma verificabile…")
            homeFrame = firstFrame
            log.info(
                "CALIBRAZIONE · CASA",
                "Parto dall'inquadratura attuale e ci torno dopo ogni fase. Tienila ferma: " +
                    "è il riferimento con cui vengono misurati tutti gli spostamenti.",
                imageJpeg = firstFrame,
            )
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
            keepHomeInsideLimits(panLimits, GimbalCalibrationSample.AXIS_PAN)
            returnHome("Ritorno all'inquadratura di partenza dopo il pan", 14, 17)
            val tiltLimits = calibrateAxisLimits(
                axis = GimbalCalibrationSample.AXIS_TILT,
                minimumDeg = OFFICIAL_TILT_MIN_DEG,
                maximumDeg = OFFICIAL_TILT_MAX_DEG,
                phaseStartPercent = 17,
                phaseEndPercent = 27,
            )
            keepHomeInsideLimits(tiltLimits, GimbalCalibrationSample.AXIS_TILT)
            returnHome("Ritorno all'inquadratura di partenza dopo il tilt", 27, 30)

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
                    // Ogni intensità riparte dalla casa: gli errori della coppia andata/ritorno
                    // non si accumulano, e l'inquadratura resta quella scelta da chi calibra
                    // invece di scivolare verso lo zero hardware a ogni giro.
                    returnHome("Ritorno a casa fra le intensità", _state.value.overallPercent, _state.value.overallPercent)
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
            val corrected = validateMotionModel(profile, firstFrame)
            store.update { corrected }
            // La validazione lavora sullo zero hardware, che è dove guarda il corpo camera:
            // finirla lì significherebbe lasciare la camera puntata dove non serve. Il profilo
            // ora è salvato, quindi il ritorno usa la curva appena misurata.
            returnHome("Ritorno all'inquadratura di partenza", 99, 100, byReplay = false)
            log.info(
                "CALIBRAZIONE GIMBAL · COMPLETATA",
                buildString {
                    appendLine("Qualità: ${corrected.qualityPercent}% · ${corrected.validSamples}/${corrected.totalSamples} campioni")
                    appendLine(
                        "Scala corretta sui fine corsa: orizzontale ×%.3f · verticale ×%.3f"
                            .format(corrected.panAngularScale, corrected.tiltAngularScale),
                    )
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
            calPulse(
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

        // Alle intensità basse ogni singolo passo sposta meno di un pixel: la somma dei passi
        // fa zero e il campione esce inutilizzabile, mentre l'arco intero è largo una decina di
        // pixel e si misura benissimo. Quindi si confronta anche la prima con l'ultima
        // inquadratura, e si tiene quella delle due misure che ha davvero visto qualcosa.
        // Il multi-step serve alle intensità alte, dove un movimento solo uscirebbe dal
        // fotogramma e non ci sarebbe più niente da confrontare.
        val wholeArc = WaypointImageVerifier.verify(origin, finalFrame)
        val wholeArcUsable = wholeArc != null &&
            wholeArc.inlierMatches >= MIN_RESPONSE_INLIERS &&
            wholeArc.confidence >= MIN_RESPONSE_CONFIDENCE &&
            hypot(wholeArc.shiftX, wholeArc.shiftY) > hypot(shiftX, shiftY)
        val sample = if (wholeArcUsable) {
            GimbalCalibrationSample(
                intensityPercent = intensityPercent,
                axis = axis,
                command = command,
                pulseMs = totalDurationMs,
                shiftX = wholeArc!!.shiftX,
                shiftY = wholeArc.shiftY,
                inliers = wholeArc.inlierMatches,
                confidence = wholeArc.confidence,
                commandOverheadMs = if (validSegments > 0) overheadSumMs / validSegments else 0L,
                settleMs = if (validSegments > 0) settleSumMs / validSegments else DEFAULT_SETTLE_FALLBACK_MS,
            )
        } else if (validSegments > 0 && measuredDurationMs > 0L) {
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
     * Il riferimento visivo dello zero viene ripreso qui, non ereditato dall'avvio, per due
     * motivi. Il primo: fra la prima inquadratura e questo punto passano otto minuti, e in otto
     * minuti la scena cambia da sola — le persone si spostano, la luce gira, il vento muove le
     * foglie; con un riferimento vecchio la ripetibilità dello zero misura il mondo, non il
     * gimbal. Il secondo: la prima inquadratura è la **casa**, cioè dove la camera è stata
     * puntata, mentre i collaudi partono dallo zero hardware. Sono due posti diversi.
     */
    private data class AxisScale(val axis: String, val scale: Float)

    private suspend fun validateMotionModel(
        profile: GimbalCalibrationProfile,
        startReference: ByteArray?,
    ): GimbalCalibrationProfile {
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

        log.info(
            "CALIBRAZIONE · VALIDAZIONE",
            "I quattro collaudi partono da casa, non dallo zero hardware. Quello zero non è un " +
                "punto fisso: il ricentraggio agisce sul lato in cui la camera si trova, e il " +
                "lato dipende da quanto si è girato. Casa invece è misurata come frazione della " +
                "corsa, e non cambia significato a seconda di dove si è passati.",
        )

        val zeroChecks = mutableListOf<ZeroCheck>()
        val measured = mutableListOf<AxisScale>()
        // Il profilo si corregge strada facendo: il secondo collaudo parte già più preciso del
        // primo, perché usa la scala misurata dal primo.
        var working = profile
        targets.forEachIndexed { index, target ->
            returnHome(
                "Ritorno a casa prima della verifica ${directionLabel(target.axis, target.endpoint)}",
                90 + index * 2,
                91 + index * 2,
                byReplay = false,
                profileOverride = working,
            )
            gimbal.setEstimated(homePan, homeTilt)
            zeroChecks += verifyRepeatedZero(homeFrame, index + 1, targets.size)
            val homeDeg = if (target.axis == GimbalCalibrationSample.AXIS_PAN) homePan else homeTilt
            // Da casa al fine corsa: questa è la distanza vera, e il fine corsa la certifica.
            val trueDistance = target.endpoint - homeDeg
            if (abs(trueDistance) < MIN_VALIDATION_DISTANCE_DEG) {
                log.info(
                    "CALIBRAZIONE · VALIDAZIONE ${directionLabel(target.axis, target.endpoint).uppercase()} SALTATA",
                    "Casa è a %.1f° dal fine corsa: troppo vicina perché la misura significhi qualcosa."
                        .format(abs(trueDistance)),
                )
                return@forEachIndexed
            }
            val checkpoint = trueDistance * VALIDATION_CHECKPOINT_FRACTION
            // Il modello viene spinto verso il fine corsa e si contano i gradi che *crede* di
            // aver percorso. Il fine corsa è verità: la camera lo annuncia. Il rapporto fra i
            // gradi veri e quelli creduti è la correzione di scala — l'informazione che questa
            // fase produce, e che prima veniva buttata via insieme a tutta la calibrazione.
            var commandedDeg = 0f
            val first = movePredictedDegrees(working, target.axis, checkpoint, VALIDATION_FAST_INTENSITY_PERCENT, true)
            commandedDeg += first.predictedDegrees
            var reached = first.limitReached

            if (!reached) {
                val approach = movePredictedDegrees(
                    working,
                    target.axis,
                    trueDistance - checkpoint,
                    VALIDATION_FINE_INTENSITY_PERCENT,
                    true,
                )
                commandedDeg += approach.predictedDegrees
                reached = approach.limitReached
            }

            // La finestra di ricerca è una frazione della corsa, non un numero fisso: su 235°
            // un margine di 16° è lo 0,7%, che qualunque errore di taratura consuma subito.
            val searchLimitDeg = abs(trueDistance) * VALIDATION_SEARCH_FRACTION + VALIDATION_EXTRA_SEARCH_DEG
            val direction = kotlin.math.sign(trueDistance)
            while (!reached && abs(commandedDeg) < abs(trueDistance) + searchLimitDeg) {
                val extra = movePredictedDegrees(
                    working,
                    target.axis,
                    VALIDATION_EXTRA_STEP_DEG * direction,
                    VALIDATION_FINE_INTENSITY_PERCENT,
                    true,
                )
                commandedDeg += extra.predictedDegrees
                reached = extra.limitReached
            }
            if (!reached) {
                throw IllegalStateException(
                    "Il fine corsa ${directionLabel(target.axis, target.endpoint)} non arriva neanche dopo %.0f° comandati contro i %.0f° che lo separano da casa: il gimbal è ostacolato oppure il segnale 8302 non arriva"
                        .format(abs(commandedDeg), abs(trueDistance)),
                )
            }

            // Il fine corsa è raggiunto: quel punto *è* il limite, e le coordinate lo dicono.
            anchorFrame(target.axis, target.endpoint)

            // Reali / creduti. Sotto 1 il modello si credeva più veloce di quanto sia.
            val scale = abs(trueDistance) / abs(commandedDeg).coerceAtLeast(1f)
            measured += AxisScale(target.axis, scale)
            working = applyRunningScale(working, target.axis, measured)
            val errorDeg = abs(commandedDeg) - abs(trueDistance)
            _state.value = _state.value.copy(
                overallPercent = 92 + index * 2,
                axisLabel = axisLabel(target.axis),
                directionLabel = directionLabel(target.axis, target.endpoint),
                theoreticalPan = if (target.axis == GimbalCalibrationSample.AXIS_PAN) target.endpoint else 0f,
                theoreticalTilt = if (target.axis == GimbalCalibrationSample.AXIS_TILT) target.endpoint else 0f,
                positioningPercent = ((1f - abs(1f - scale) / MAX_ACCEPTABLE_SCALE_ERROR)
                    .coerceIn(0f, 1f) * 100f).toInt(),
                verificationLabel = "FINE CORSA RAGGIUNTO · SCALA ×%.3f".format(scale),
                message = "${directionLabel(target.axis, target.endpoint)}: %.0f° comandati per %.0f° reali".format(abs(commandedDeg), abs(target.endpoint)),
            )
            log.info(
                "CALIBRAZIONE · VALIDAZIONE ${directionLabel(target.axis, target.endpoint).uppercase()}",
                buildString {
                    appendLine("Da casa (%.1f°) al fine corsa (%.1f°)".format(homeDeg, target.endpoint))
                    appendLine("Gradi comandati per arrivarci: %.1f°".format(abs(commandedDeg)))
                    appendLine("Gradi reali di quella corsa: %.1f°".format(abs(trueDistance)))
                    appendLine("Scarto del modello: %+.1f°".format(errorDeg))
                    append("Correzione di scala su questo asse e verso: ×%.3f".format(scale))
                },
                imageJpeg = preview.captureThumbnailJpeg(),
            )
        }

        returnHome("Verifica finale di casa", 98, 99, byReplay = false, profileOverride = working)
        gimbal.setEstimated(homePan, homeTilt)
        zeroChecks += verifyRepeatedZero(homeFrame, targets.size, targets.size)

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

        return applyMeasuredScale(profile, measured)
    }

    /**
     * Applica al profilo la scala misurata sui fine corsa.
     *
     * Ogni asse ha due misure, una per verso, e si prende la media: un verso solo porterebbe
     * dentro l'asimmetria del singolo finecorsa. Una correzione fuori da un intervallo
     * ragionevole non è una taratura ma un sintomo — gimbal ostacolato, segnale di limite
     * sbagliato, corsa diversa da quella dichiarata — e allora è giusto fermarsi.
     */
    /** Scala parziale ricavata dai collaudi già fatti su quell'asse, per i successivi. */
    private fun applyRunningScale(
        profile: GimbalCalibrationProfile,
        axis: String,
        measured: List<AxisScale>,
    ): GimbalCalibrationProfile {
        val values = measured.filter { it.axis == axis }.map(AxisScale::scale)
        if (values.isEmpty()) return profile
        val scale = values.average().toFloat()
            .coerceIn(GimbalCalibrationProfile.MIN_ANGULAR_SCALE, GimbalCalibrationProfile.MAX_ANGULAR_SCALE)
        return if (axis == GimbalCalibrationSample.AXIS_PAN) {
            profile.copy(panAngularScale = profile.panAngularScale * scale)
        } else {
            profile.copy(tiltAngularScale = profile.tiltAngularScale * scale)
        }
    }

    private fun applyMeasuredScale(
        profile: GimbalCalibrationProfile,
        measured: List<AxisScale>,
    ): GimbalCalibrationProfile {
        fun scaleFor(axis: String): Float {
            val values = measured.filter { it.axis == axis }.map(AxisScale::scale)
            if (values.isEmpty()) return 1f
            return values.average().toFloat()
        }
        val pan = scaleFor(GimbalCalibrationSample.AXIS_PAN)
        val tilt = scaleFor(GimbalCalibrationSample.AXIS_TILT)
        listOf("orizzontale" to pan, "verticale" to tilt).forEach { (label, value) ->
            if (value < GimbalCalibrationProfile.MIN_ANGULAR_SCALE || value > GimbalCalibrationProfile.MAX_ANGULAR_SCALE) {
                throw IllegalStateException(
                    "Correzione di scala %s fuori scala (×%.2f): il gimbal è ostacolato oppure la corsa non è quella dichiarata"
                        .format(label, value),
                )
            }
        }
        log.info(
            "CALIBRAZIONE · SCALA CORRETTA SUI FINE CORSA",
            buildString {
                appendLine("Orizzontale ×%.3f · verticale ×%.3f".format(pan, tilt))
                appendLine(
                    "Sotto 1 il modello si credeva più veloce del vero: i gradi comandati erano " +
                        "più di quelli percorsi, ed è così che si mancava il fine corsa.",
                )
                append("Le velocità del profilo sono state riscalate di conseguenza.")
            },
        )
        return profile.copy(panAngularScale = pan, tiltAngularScale = tilt)
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
    private suspend fun verifyRepeatedZero(reference: ByteArray?, attempt: Int, attempts: Int): ZeroCheck {
        val current = awaitFrame("Verifico che l'inquadratura di casa sia sempre la stessa")
        val verification = if (reference != null) WaypointImageVerifier.verify(reference, current) else null
        val check = classifyZero(verification)
        val annotated = WaypointImageVerifier.annotatedCurrentJpeg(current, verification)
        val detail = buildString {
            appendLine(verification?.describe() ?: "Nessun riferimento di casa confrontabile.")
            append(check.explanation)
        }
        if (check.mismatch) {
            log.warn("CALIBRAZIONE · CASA PRIMA DELLA PROVA $attempt", detail, imageJpeg = annotated)
        } else {
            log.info("CALIBRAZIONE · CASA PRIMA DELLA PROVA $attempt", detail, imageJpeg = annotated)
        }
        _state.value = _state.value.copy(
            shiftX = verification?.shiftX ?: 0f,
            shiftY = verification?.shiftY ?: 0f,
            positioningPercent = verification?.let(::returnPositionPercent) ?: 0,
            verificationLabel = when {
                check.mismatch -> "CASA SPOSTATA · PROVA $attempt/$attempts"
                check.comparable -> "CASA RITROVATA · PROVA $attempt/$attempts"
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
            calPulse(
                panPercent = if (panAxis) direction * intensityPercent / 100f else 0f,
                tiltPercent = if (!panAxis) direction * intensityPercent / 100f else 0f,
                durationMs = stepMs,
                record = false,
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
        anchorFrame(axis, minimumDeg)
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
        anchorFrame(axis, maximumDeg)
        val limits = GimbalAxisLimits(
            minimumDeg = minimumDeg,
            maximumDeg = maximumDeg,
            sweepIntensityPercent = ENDSTOP_INTENSITY_PERCENT,
            travelSecondsAtSweepIntensity = positive.movingDurationMs / 1000f,
            movingPulses = positive.movingPulses,
            endpointConfidencePercent = positive.confidencePercent,
        )
        locateHome(axis, limits, negative.movingDurationMs, positive.movingDurationMs)
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
        calPulse(
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

        // Durata dei comandi inviati prima di toccare il limite. Non è la somma degli impulsi
        // "in cui si è visto un movimento": a questa velocità due fotogrammi consecutivi non si
        // sovrappongono abbastanza perché il confronto funzioni, e un impulso che ha mosso
        // eccome finisce contato come fermo. Un solo impulso perso su quindici sposta la scala
        // in gradi del 7%, e su una corsa da 235° sono 16° — abbastanza da mancare il finecorsa.
        // Qui si contano i comandi, che sono un fatto, non una deduzione.
        var commandedDurationMs = 0L
        var travelDurationMs = 0L

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
            val panCommand = if (axis == GimbalCalibrationSample.AXIS_PAN) direction * intensityPercent / 100f else 0f
            val tiltCommand = if (axis == GimbalCalibrationSample.AXIS_TILT) direction * intensityPercent / 100f else 0f
            calPulse(panCommand, tiltCommand, pulseMs, record = false)
                .getOrElse { throw IllegalStateException("Ricerca fine corsa non riuscita: ${it.message}", it) }
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
            // Il fine corsa per sola immagine richiede comunque di essersi mossi almeno un po'.
            // Senza questa condizione bastavano tre confronti sbagliati di fila — e su una
            // scena a motivo ripetitivo arrivano subito — per dichiarare un fine corsa al primo
            // impulso, con la camera in mezzo alla corsa. La casa finiva calcolata sul limite.
            // Chi è davvero già appoggiato al limite viene riconosciuto dal segnale hardware,
            // che non ha bisogno di aver percorso nulla.
            val acceptedVisualStill = still &&
                movingPulses >= MIN_VISUAL_ENDSTOP_TRAVEL_PULSES &&
                (!requireTravel || movingPulses >= MIN_ENDSTOP_TRAVEL_PULSES)
            if (acceptedHardwareLimit) {
                consecutiveStill = ENDSTOP_CONFIRMATIONS
                endpointConfidenceSum = ENDSTOP_CONFIRMATIONS.toFloat()
                // Durante quest'ultimo impulso il gimbal ha percorso un tratto e poi si è
                // fermato contro il limite: metà è la stima meno arbitraria che ci sia.
                travelDurationMs = commandedDurationMs + pulseMs / 2
                // Metà impulso ha mosso, metà è finita contro il limite: solo la prima va
                // rigiocata all'indietro, o il ritorno supererebbe la casa.
                recordTravel(travelPan, panCommand, pulseMs / 2)
                recordTravel(travelTilt, tiltCommand, pulseMs / 2)
            } else if (acceptedVisualStill) {
                consecutiveStill++
                endpointConfidenceSum += verification.confidence
                if (travelDurationMs == 0L) travelDurationMs = commandedDurationMs
            } else {
                consecutiveStill = 0
                endpointConfidenceSum = 0f
                movingPulses++
                commandedDurationMs += pulseMs
                recordTravel(travelPan, panCommand, pulseMs)
                recordTravel(travelTilt, tiltCommand, pulseMs)
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
                    movingDurationMs = if (travelDurationMs > 0L) travelDurationMs else commandedDurationMs,
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

    /**
     * Impulso di calibrazione che tiene il conto di quanto si è usciti di casa.
     *
     * Tutti i movimenti della calibrazione passano da qui: è l'unico modo perché il ritorno
     * possa essere esatto senza conoscere la velocità. [record] è falso per gli impulsi che
     * non hanno prodotto movimento — quelli spesi contro un fine corsa — perché rigiocarli
     * all'indietro farebbe superare la casa.
     */
    private suspend fun calPulse(
        panPercent: Float,
        tiltPercent: Float,
        durationMs: Long,
        record: Boolean = true,
    ): Result<Unit> {
        if (record) {
            recordTravel(travelPan, panPercent, durationMs)
            recordTravel(travelTilt, tiltPercent, durationMs)
        }
        return gimbal.calibrationPulse(panPercent, tiltPercent, durationMs)
    }

    private fun recordTravel(into: MutableMap<Int, Long>, command: Float, durationMs: Long) {
        if (command == 0f || durationMs <= 0L) return
        val bucket = (abs(command) * 100f).roundToInt().coerceIn(1, 100)
        val signed = if (command > 0f) durationMs else -durationMs
        into[bucket] = (into[bucket] ?: 0L) + signed
    }

    /**
     * Rigioca all'indietro i comandi accumulati, dai più forti ai più deboli.
     *
     * Stessa intensità, stesso tempo, verso opposto: la velocità si cancella da sola qualunque
     * sia, e non serve nessun profilo. È l'unico ritorno affidabile prima che la curva esista.
     */
    private suspend fun replayHome() {
        listOf(travelPan to true, travelTilt to false).forEach { (travel, panAxis) ->
            travel.entries.sortedByDescending { it.key }.forEach { (intensity, netMs) ->
                var remaining = abs(netMs)
                val command = (if (netMs > 0L) -1f else 1f) * intensity / 100f
                while (remaining > 0L) {
                    val step = min(remaining, MAX_REPLAY_STEP_MS)
                    calPulse(
                        panPercent = if (panAxis) command else 0f,
                        tiltPercent = if (panAxis) 0f else command,
                        durationMs = step,
                        record = false,
                    ).getOrElse { throw IllegalStateException("Ritorno a casa non riuscito: ${it.message}", it) }
                    remaining -= step
                }
            }
            travel.clear()
        }
    }

    /** Un fine corsa raggiunto ridefinisce le coordinate: quel punto *è* il limite dichiarato. */
    private fun anchorFrame(axis: String, anchorDeg: Float) {
        val panAxis = axis == GimbalCalibrationSample.AXIS_PAN
        val current = gimbal.position.value
        gimbal.setEstimated(
            pan = if (panAxis) anchorDeg else current.pan,
            tilt = if (panAxis) current.tilt else anchorDeg,
        )
    }

    /**
     * Dove sta la casa lungo la corsa, misurato invece che dedotto.
     *
     * Due tempi comandati alla stessa intensità: casa → primo fine corsa, e primo fine corsa →
     * secondo. Il loro rapporto è la posizione della casa come frazione della corsa, e la
     * velocità si semplifica — non serve saperla, il che è provvidenziale visto che è proprio
     * quello che la calibrazione deve ancora misurare.
     *
     * È la correzione del difetto che portava la camera a fare la curva puntata al soffitto:
     * la casa veniva integrata con le velocità di ripiego (30°/s e 20°/s) lungo tutta la
     * ricerca dei fine corsa, e usciva a 75° di tilt quando era a un grado dall'orizzonte.
     */
    private fun locateHome(axis: String, limits: GimbalAxisLimits, toMinimumMs: Long, spanMs: Long) {
        if (spanMs <= 0L) return
        val fraction = (toMinimumMs.toFloat() / spanMs.toFloat()).coerceIn(0f, 1f)
        val deg = homeDegreesFromTravel(limits.minimumDeg, limits.maximumDeg, toMinimumMs, spanMs)
        val panAxis = axis == GimbalCalibrationSample.AXIS_PAN
        if (panAxis) homePan = deg else homeTilt = deg
        log.info(
            "CALIBRAZIONE · CASA SULL'ASSE ${axisLabel(axis).uppercase()}",
            buildString {
                appendLine("Da casa al primo fine corsa: %.1f s di comando".format(toMinimumMs / 1000f))
                appendLine("Corsa intera fra i due fine corsa: %.1f s".format(spanMs / 1000f))
                append(
                    "Casa al %.0f%% della corsa, cioè %.1f°. Misura di tempi alla stessa intensità: la velocità si semplifica."
                        .format(fraction * 100f, deg),
                )
            },
        )
    }

    /**
     * Sposta la casa se è troppo vicina a un fine corsa per contenere gli archi di misura.
     *
     * Le prove della curva sono archi di ~45° attorno alla casa: se la casa sta a 20° dal
     * limite, l'arco ci sbatte contro e la misura esce falsata senza che nulla lo segnali.
     * Meglio spostarsi del minimo indispensabile e dirlo, che misurare contro un muro.
     */
    private fun keepHomeInsideLimits(limits: GimbalAxisLimits, axis: String) {
        val panAxis = axis == GimbalCalibrationSample.AXIS_PAN
        val margin = TARGET_RESPONSE_ARC_DEG + HOME_LIMIT_MARGIN_DEG
        val low = limits.minimumDeg + margin
        val high = limits.maximumDeg - margin
        if (low >= high) return
        val current = if (panAxis) homePan else homeTilt
        val safe = current.coerceIn(low, high)
        if (abs(safe - current) < 0.5f) return
        if (panAxis) homePan = safe else homeTilt = safe
        // Il ritorno rigioca i comandi, quindi anche lo spostamento della casa va espresso in
        // comandi: senza, si tornerebbe alla vecchia casa e questo spostamento non esisterebbe.
        val rate = limits.spanDeg / limits.travelSecondsAtSweepIntensity
        if (rate > 0f) {
            val shiftMs = ((safe - current) / rate * 1000f).toLong()
            val travel = if (panAxis) travelPan else travelTilt
            val bucket = limits.sweepIntensityPercent.coerceIn(1, 100)
            travel[bucket] = (travel[bucket] ?: 0L) - shiftMs
        }
        // La miniatura di casa non vale più: guarda dove la casa non è più. Azzerandola, il
        // ritorno riprende l'immagine da capo all'arrivo.
        homeFrame = null
        log.warn(
            "CALIBRAZIONE · CASA SPOSTATA SULL'ASSE ${axisLabel(axis).uppercase()}",
            "Era a %.0f°, troppo vicina al fine corsa per un arco di %.0f°: la porto a %.0f°. "
                .format(current, TARGET_RESPONSE_ARC_DEG, safe) +
                "L'inquadratura di riferimento cambia di conseguenza.",
        )
    }

    /**
     * Torna all'inquadratura di partenza: prima con le coordinate, poi con l'immagine.
     *
     * Le coordinate da sole accumulano errore lungo otto minuti di impulsi; l'immagine da sola
     * non sa in che direzione muoversi finché non ha un profilo. Insieme funzionano: il modello
     * porta vicino, il confronto con la miniatura di casa chiude gli ultimi pixel. Se il
     * confronto non è affidabile — scena cambiata, poca luce — ci si ferma alle coordinate e
     * lo si scrive, invece di inseguire una correzione basata su punti che non corrispondono.
     */
    private suspend fun returnHome(
        label: String,
        phaseStartPercent: Int,
        phaseEndPercent: Int,
        byReplay: Boolean = true,
        profileOverride: GimbalCalibrationProfile? = null,
    ) {
        _state.value = _state.value.copy(
            phaseLabel = label,
            overallPercent = phaseStartPercent,
            axisLabel = "entrambi",
            directionLabel = "casa",
            message = "Torno a pan %.0f° · tilt %.0f°".format(homePan, homeTilt),
            verificationLabel = "RITORNO ALL'INQUADRATURA DI PARTENZA",
        )
        if (byReplay) {
            replayHome()
        } else {
            gimbal.moveToPosition(homePan, homeTilt, minimumSeconds = 0f, profileOverride = profileOverride)
                .getOrElse { throw IllegalStateException("Ritorno all'inquadratura di partenza non riuscito: ${it.message}", it) }
        }
        delay(HOME_SETTLE_MS)

        val arrived = awaitFrame("Verifico il ritorno all'inquadratura di partenza")
        val reference = homeFrame
        if (reference == null) {
            // Prima casa, o casa appena spostata: questa inquadratura diventa il riferimento.
            homeFrame = arrived
        }
        var verification = if (reference != null) WaypointImageVerifier.verify(reference, arrived) else null
        var attempts = 0
        while (reference != null && attempts < MAX_HOME_CORRECTIONS) {
            val check = verification ?: break
            if (check.displacementPixels <= HOME_RADIUS_PX) break
            val usable = check.inlierMatches >= ZERO_MIN_INLIERS &&
                check.confidence >= ZERO_MIN_CONFIDENCE &&
                controlPointPercent(check) >= ZERO_MIN_INLIER_PERCENT
            if (!usable) break
            val profile = store.state.value.takeIf(GimbalCalibrationProfile::isValid)
            val panPulse = correctionSign(
                check.shiftX,
                profile?.imageRateAt(HOME_CORRECTION_INTENSITY_PERCENT.toFloat(), panAxis = true),
            ) * HOME_CORRECTION_INTENSITY_PERCENT / 100f
            val tiltPulse = correctionSign(
                -check.shiftY,
                profile?.imageRateAt(HOME_CORRECTION_INTENSITY_PERCENT.toFloat(), panAxis = false),
            ) * HOME_CORRECTION_INTENSITY_PERCENT / 100f
            gimbal.correctionPulse(panPulse, tiltPulse, HOME_CORRECTION_PULSE_MS)
            delay(HOME_SETTLE_MS)
            verification = WaypointImageVerifier.verify(
                reference,
                awaitFrame("Verifico il ritorno all'inquadratura di partenza"),
            )
            attempts++
        }
        val residual = verification
        gimbal.setEstimated(homePan, homeTilt)
        _state.value = _state.value.copy(
            overallPercent = phaseEndPercent,
            theoreticalPan = homePan,
            theoreticalTilt = homeTilt,
            shiftX = residual?.shiftX ?: 0f,
            shiftY = residual?.shiftY ?: 0f,
            verificationLabel = when {
                residual == null -> "CASA · SOLO COORDINATE"
                residual.displacementPixels <= HOME_RADIUS_PX -> "INQUADRATURA DI PARTENZA RITROVATA"
                else -> "CASA · SCARTO RESIDUO %.0f PX".format(residual.displacementPixels)
            },
        )
        log.info(
            "CALIBRAZIONE · $label",
            buildString {
                appendLine("Coordinate di casa: pan %.1f° · tilt %.1f°".format(homePan, homeTilt))
                appendLine("Ritocchi visivi: $attempts/$MAX_HOME_CORRECTIONS")
                append(residual?.describe() ?: "Nessun riferimento visivo: questa inquadratura diventa la casa.")
            },
            imageJpeg = preview.captureThumbnailJpeg(),
        )
    }

    /**
     * Verso del comando che riduce l'errore in pixel.
     *
     * Con un profilo valido il verso è quello misurato sulla camera; senza, si usa il segno
     * dell'errore. È la stessa regola della correzione visiva dei waypoint, e per la stessa
     * ragione: il verso degli assi non si presume, o la correzione raddoppia l'errore.
     */
    private fun correctionSign(errorPixels: Float, measuredPositiveRate: Float?): Float {
        if (abs(errorPixels) < 0.5f) return 0f
        val rate = measuredPositiveRate ?: return kotlin.math.sign(errorPixels)
        if (abs(rate) < 0.5f) return kotlin.math.sign(errorPixels)
        return -kotlin.math.sign(errorPixels) * kotlin.math.sign(rate)
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

        /** Anche senza obbligo di attraversare la corsa, un fine corsa visivo va guadagnato. */
        const val MIN_VISUAL_ENDSTOP_TRAVEL_PULSES = 3

        /** Assestamento da usare quando nessun passo intermedio è stato misurabile. */
        const val DEFAULT_SETTLE_FALLBACK_MS = 260L
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
        const val VALIDATION_FAST_INTENSITY_PERCENT = 100
        const val VALIDATION_FINE_INTENSITY_PERCENT = 20
        const val VALIDATION_FAST_STEP_MS = 800f
        const val VALIDATION_FINE_STEP_MS = 220f
        const val VALIDATION_EXTRA_STEP_DEG = 2f
        const val VALIDATION_EXTRA_SEARCH_DEG = 16f

        /** Quanto oltre la corsa prevista si continua a cercare il fine corsa, in frazione. */
        const val VALIDATION_SEARCH_FRACTION = 0.35f

        /** Il primo tratto verso il fine corsa, veloce; il resto si fa piano. */
        const val VALIDATION_CHECKPOINT_FRACTION = 0.8f

        /** Sotto questa distanza fra casa e fine corsa la misura di scala non dice nulla. */
        const val MIN_VALIDATION_DISTANCE_DEG = 25f

        /** Scarto di scala oltre il quale il posizionamento mostrato scende a zero. */
        const val MAX_ACCEPTABLE_SCALE_ERROR = 0.25f
        const val ZERO_REPEATABILITY_RADIUS_PX = 12f

        // Soglie per credere a uno scarto dello zero. Sotto queste, il confronto ha trovato
        // una traslazione ma non un consenso: è la scena che si è mossa, non il gimbal.
        const val ZERO_MIN_CONFIDENCE = 0.45f
        const val ZERO_MIN_INLIERS = 10
        const val ZERO_MIN_INLIER_PERCENT = 45

        // Ritorno alla casa: quanto vicino basta, quanti ritocchi al massimo, con che impulso.
        const val HOME_RADIUS_PX = 6f
        const val MAX_HOME_CORRECTIONS = 4
        const val HOME_CORRECTION_INTENSITY_PERCENT = 10
        const val HOME_CORRECTION_PULSE_MS = 160L
        const val HOME_SETTLE_MS = 500L

        /** Margine oltre l'arco di misura, perché la casa non finisca appiccicata al limite. */
        const val HOME_LIMIT_MARGIN_DEG = 10f

        /** Il ritorno a casa viene spezzato in impulsi, come ogni altro movimento. */
        const val MAX_REPLAY_STEP_MS = 700L
    }
}
