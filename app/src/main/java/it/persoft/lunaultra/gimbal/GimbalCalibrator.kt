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
import kotlin.math.abs
import kotlin.math.hypot

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
        const val TOTAL_STEPS = 12 * 2 * 2 * 2
    }
}

/**
 * Trova prima i quattro fine corsa con impulsi consecutivi al 20%, quindi misura la risposta
 * reale del comando dall'1% al 100%. Il riferimento zero è l'inquadratura frontale di avvio:
 * non viene confuso con il centro aritmetico della corsa asimmetrica.
 */
class GimbalCalibrator(
    private val gimbal: GimbalController,
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
            val firstFrame = awaitFrame("Avvio: attendo un fotogramma verificabile…")
            val firstCheck = WaypointImageVerifier.verify(firstFrame, firstFrame)
            if (firstCheck == null || firstCheck.referenceFeatures < MIN_SCENE_FEATURES) {
                throw IllegalStateException("La scena ha pochi dettagli: inquadra oggetti fermi con bordi ben visibili")
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
                "Impulsi al ${ENDSTOP_INTENSITY_PERCENT}% fino a ${ENDSTOP_CONFIRMATIONS} verifiche consecutive senza movimento.",
            )
            val panLimits = calibrateAxisLimits(
                axis = GimbalCalibrationSample.AXIS_PAN,
                minimumDeg = OFFICIAL_PAN_MIN_DEG,
                maximumDeg = OFFICIAL_PAN_MAX_DEG,
                phaseStartPercent = 2,
                phaseEndPercent = 14,
            )
            returnAxisToZero(GimbalCalibrationSample.AXIS_PAN, panLimits, 14, 17)
            val tiltLimits = calibrateAxisLimits(
                axis = GimbalCalibrationSample.AXIS_TILT,
                minimumDeg = OFFICIAL_TILT_MIN_DEG,
                maximumDeg = OFFICIAL_TILT_MAX_DEG,
                phaseStartPercent = 17,
                phaseEndPercent = 27,
            )
            returnAxisToZero(GimbalCalibrationSample.AXIS_TILT, tiltLimits, 27, 30)

            _state.value = _state.value.copy(
                overallPercent = 30,
                phaseLabel = "Curva velocità 1–100%",
                message = "Fine corsa trovati · misuro ora la curva dei comandi",
            )

            for (intensityIndex in INTENSITY_PERCENTAGES.indices) {
                val intensityPercent = INTENSITY_PERCENTAGES[intensityIndex]
                val intensity = intensityPercent / 100f
                val pulseMs = PULSE_DURATIONS_MS[intensityIndex]
                for (axis in listOf(GimbalCalibrationSample.AXIS_PAN, GimbalCalibrationSample.AXIS_TILT)) {
                    repeat(REPETITIONS) {
                        var pairOrigin: ByteArray? = null
                        for (direction in listOf(1f, -1f)) {
                                val command = intensity * direction
                                val before = awaitFrame("Anteprima temporaneamente assente · calibrazione in pausa")
                                if (direction > 0f) pairOrigin = before
                                val beforePosition = gimbal.position.value
                                _state.value = _state.value.copy(
                                    pausedForPreview = false,
                                    axisLabel = axisLabel(axis),
                                    directionLabel = directionLabel(axis, direction),
                                    intensityPercent = intensityPercent,
                                    pulseMs = pulseMs,
                                    theoreticalPan = beforePosition.pan,
                                    theoreticalTilt = beforePosition.tilt,
                                    message = "$intensityPercent% · ${axisLabel(axis)} · comando ${directionLabel(axis, direction)}",
                                    verificationLabel = "MOVIMENTO IN CORSO",
                                )
                                val started = System.nanoTime()
                                gimbal.calibrationPulse(
                                    panPercent = if (axis == GimbalCalibrationSample.AXIS_PAN) command else 0f,
                                    tiltPercent = if (axis == GimbalCalibrationSample.AXIS_TILT) command else 0f,
                                    durationMs = pulseMs,
                                ).getOrElse { throw IllegalStateException("Movimento di calibrazione non riuscito: ${it.message}", it) }
                                val commandElapsedMs = (System.nanoTime() - started) / 1_000_000L
                                val settled = captureAfterSettling()
                                val movementVerification = WaypointImageVerifier.verify(before, settled.jpeg)
                                val sample = movementVerification?.let { verification ->
                                    GimbalCalibrationSample(
                                        intensityPercent = intensityPercent,
                                        axis = axis,
                                        command = command,
                                        pulseMs = pulseMs,
                                        shiftX = verification.shiftX,
                                        shiftY = verification.shiftY,
                                        inliers = verification.inlierMatches,
                                        confidence = verification.confidence,
                                        commandOverheadMs = (commandElapsedMs - pulseMs).coerceAtLeast(0L),
                                        settleMs = settled.elapsedMs,
                                    )
                                }
                                val positioningPercent = sample?.let { responseScorePercent(it, samples) } ?: 0
                                if (sample != null) samples += sample

                                // Dopo l'impulso inverso la verifica più intuitiva è il ritorno
                                // all'immagine iniziale della coppia; in andata si mostra invece
                                // il movimento misurato e la sua coerenza col modello corrente.
                                val displayVerification = if (direction < 0f && pairOrigin != null) {
                                    WaypointImageVerifier.verify(pairOrigin, settled.jpeg)
                                } else movementVerification
                                val displayPositionPercent = if (direction < 0f && displayVerification != null) {
                                    returnPositionPercent(displayVerification)
                                } else positioningPercent
                                val afterPosition = gimbal.position.value
                                val done = _state.value.completedSteps + 1
                                _state.value = _state.value.copy(
                                    pausedForPreview = false,
                                    completedSteps = done,
                                    overallPercent = 30 + (done * 70 / GimbalCalibrationState.TOTAL_STEPS),
                                    phaseLabel = "Curva velocità 1–100%",
                                    message = "$intensityPercent% · ${axisLabel(axis)} · prova $done/${GimbalCalibrationState.TOTAL_STEPS}",
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
                                        settled.jpeg,
                                        displayVerification,
                                    ),
                                )
                                if (direction < 0f) {
                                    log.info(
                                        message = "CALIBRAZIONE · $intensityPercent% · ${axisLabel(axis)} · RITORNO",
                                        detail = buildString {
                                            appendLine("Coordinate teoriche: pan %.3f° · tilt %.3f°".format(afterPosition.pan, afterPosition.tilt))
                                            appendLine("Spostamento residuo: Δx %+.1f px · Δy %+.1f px".format(
                                                displayVerification?.shiftX ?: 0f,
                                                displayVerification?.shiftY ?: 0f,
                                            ))
                                            appendLine("Punti coerenti: ${displayVerification?.inlierMatches ?: 0}/${displayVerification?.candidateMatches ?: 0} · ${displayVerification?.let(::controlPointPercent) ?: 0}%")
                                            append("Posizionamento corretto: $displayPositionPercent%")
                                        },
                                        imageJpeg = WaypointImageVerifier.annotatedCurrentJpeg(
                                            settled.jpeg,
                                            displayVerification,
                                        ),
                                    )
                                }
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
     * Trova prima il limite negativo, poi attraversa tutta la corsa fino al limite positivo.
     * Un fine corsa è dichiarato soltanto dopo più impulsi consecutivi che non producono più
     * spostamento nell'immagine: una singola verifica incerta non basta a fermare la ricerca.
     */
    private suspend fun calibrateAxisLimits(
        axis: String,
        minimumDeg: Float,
        maximumDeg: Float,
        phaseStartPercent: Int,
        phaseEndPercent: Int,
    ): GimbalAxisLimits {
        val negative = seekEndStop(
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

        val positive = seekEndStop(
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
            travelSecondsAtSweepIntensity = positive.movingPulses * ENDSTOP_PULSE_MS / 1000f,
            movingPulses = positive.movingPulses,
            endpointConfidencePercent = positive.confidencePercent,
        )
        if (!limits.isValid) {
            throw IllegalStateException("Fine corsa ${axisLabel(axis)} non affidabili: libera il movimento della camera")
        }
        log.info(
            "CALIBRAZIONE · CORSA ${axisLabel(axis).uppercase()} COMPLETA",
            buildString {
                appendLine("Limiti: %.1f°…%+.1f° · ampiezza %.1f°".format(minimumDeg, maximumDeg, limits.spanDeg))
                appendLine(
                    "Tempo al ${ENDSTOP_INTENSITY_PERCENT}%: %.1f s · ${positive.movingPulses} impulsi utili".format(
                        limits.travelSecondsAtSweepIntensity,
                    ),
                )
                append("Affidabilità fine corsa: ${positive.confidencePercent}%")
            },
            imageJpeg = positive.annotatedJpeg,
        )
        return limits
    }

    private suspend fun seekEndStop(
        axis: String,
        direction: Float,
        phaseLabel: String,
        phaseStartPercent: Int,
        phaseEndPercent: Int,
        requireTravel: Boolean,
    ): EndStopSearch {
        var consecutiveStill = 0
        var movingPulses = 0
        var lastAnnotated: ByteArray? = null
        var endpointConfidenceSum = 0f

        for (pulse in 1..MAX_ENDSTOP_PULSES) {
            val before = awaitFrame("Ricerca fine corsa in pausa · attendo il ritorno dell'anteprima")
            val beforePosition = gimbal.position.value
            _state.value = _state.value.copy(
                phaseLabel = phaseLabel,
                overallPercent = phaseStartPercent +
                    ((phaseEndPercent - phaseStartPercent) * pulse / MAX_ENDSTOP_PULSES),
                axisLabel = axisLabel(axis),
                directionLabel = directionLabel(axis, direction),
                intensityPercent = ENDSTOP_INTENSITY_PERCENT,
                pulseMs = ENDSTOP_PULSE_MS,
                theoreticalPan = beforePosition.pan,
                theoreticalTilt = beforePosition.tilt,
                message = "$phaseLabel · impulso $pulse",
                verificationLabel = "CERCO MOVIMENTO NELL'IMMAGINE",
            )
            gimbal.calibrationPulse(
                panPercent = if (axis == GimbalCalibrationSample.AXIS_PAN) direction * ENDSTOP_INTENSITY else 0f,
                tiltPercent = if (axis == GimbalCalibrationSample.AXIS_TILT) direction * ENDSTOP_INTENSITY else 0f,
                durationMs = ENDSTOP_PULSE_MS,
            ).getOrElse { throw IllegalStateException("Ricerca fine corsa non riuscita: ${it.message}", it) }
            val settled = captureAfterSettling()
            val verification = WaypointImageVerifier.verify(before, settled.jpeg)
            val still = verification != null && verification.inlierMatches >= MIN_ENDSTOP_INLIERS &&
                verification.displacementPixels <= ENDSTOP_STILL_RADIUS_PX
            if (still) {
                consecutiveStill++
                endpointConfidenceSum += verification?.confidence ?: 0f
            } else {
                consecutiveStill = 0
                endpointConfidenceSum = 0f
                movingPulses++
            }
            lastAnnotated = WaypointImageVerifier.annotatedCurrentJpeg(settled.jpeg, verification)
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
                verificationLabel = if (still) {
                    "NESSUN MOVIMENTO · CONFERMA $consecutiveStill/$ENDSTOP_CONFIRMATIONS"
                } else {
                    "MOVIMENTO RILEVATO · IL FINE CORSA NON È ANCORA QUI"
                },
                annotatedJpeg = lastAnnotated,
            )
            if (pulse % ENDSTOP_LOG_EVERY_PULSES == 0 || consecutiveStill > 0) {
                log.info(
                    "CALIBRAZIONE · $phaseLabel · IMPULSO $pulse",
                    buildString {
                        appendLine("Comando: ${ENDSTOP_INTENSITY_PERCENT}% · ${directionLabel(axis, direction)} · ${ENDSTOP_PULSE_MS} ms")
                        appendLine("Δx %+.1f px · Δy %+.1f px".format(verification?.shiftX ?: 0f, verification?.shiftY ?: 0f))
                        appendLine("Punti coerenti: ${verification?.inlierMatches ?: 0}/${verification?.candidateMatches ?: 0}")
                        append("Conferme senza movimento: $consecutiveStill/$ENDSTOP_CONFIRMATIONS")
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
                    confidencePercent = confidence,
                    annotatedJpeg = lastAnnotated,
                )
            }
            delay(BETWEEN_ENDSTOP_PULSES_MS)
        }
        throw IllegalStateException(
            "Fine corsa ${directionLabel(axis, direction)} non trovato entro il limite di sicurezza",
        )
    }

    /** Ritorna allo zero frontale usando la frazione misurata della corsa, non il suo centro. */
    private suspend fun returnAxisToZero(
        axis: String,
        limits: GimbalAxisLimits,
        phaseStartPercent: Int,
        phaseEndPercent: Int,
    ) {
        val returnPulses = (limits.movingPulses * limits.maximumDeg / limits.spanDeg)
            .toInt().coerceAtLeast(1)
        _state.value = _state.value.copy(
            phaseLabel = "Ritorno allo zero frontale",
            overallPercent = phaseStartPercent,
            axisLabel = axisLabel(axis),
            directionLabel = directionLabel(axis, -1f),
            intensityPercent = ENDSTOP_INTENSITY_PERCENT,
            message = "Ritorno a 0° dopo la misura ${axisLabel(axis)}",
        )
        repeat(returnPulses) { index ->
            gimbal.calibrationPulse(
                panPercent = if (axis == GimbalCalibrationSample.AXIS_PAN) -ENDSTOP_INTENSITY else 0f,
                tiltPercent = if (axis == GimbalCalibrationSample.AXIS_TILT) -ENDSTOP_INTENSITY else 0f,
                durationMs = ENDSTOP_PULSE_MS,
            ).getOrElse { throw IllegalStateException("Ritorno allo zero non riuscito: ${it.message}", it) }
            val doneFraction = (index + 1f) / returnPulses
            _state.value = _state.value.copy(
                overallPercent = phaseStartPercent + ((phaseEndPercent - phaseStartPercent) * doneFraction).toInt(),
            )
        }
        val current = gimbal.position.value
        gimbal.setEstimated(
            pan = if (axis == GimbalCalibrationSample.AXIS_PAN) 0f else current.pan,
            tilt = if (axis == GimbalCalibrationSample.AXIS_TILT) 0f else current.tilt,
        )
        delay(INITIAL_SETTLE_MS)
        _state.value = _state.value.copy(
            theoreticalPan = gimbal.position.value.pan,
            theoreticalTilt = gimbal.position.value.tilt,
            overallPercent = phaseEndPercent,
            verificationLabel = "ZERO FRONTALE RIPRISTINATO",
        )
    }

    private data class EndStopSearch(
        val totalPulses: Int,
        val movingPulses: Int,
        val confidencePercent: Int,
        val annotatedJpeg: ByteArray?,
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
        // Il log reale mostra ~228 ms di latenza: gli impulsi da 80–160 ms della versione
        // precedente misuravano soprattutto il ritardo, non la velocità. Tutte le intensità
        // hanno ora una finestra utile oltre tale latenza.
        val PULSE_DURATIONS_MS = longArrayOf(
            5_000L, 1_600L, 900L, 600L, 500L, 450L, 400L, 360L, 340L, 320L, 300L, 300L,
        )
        const val REPETITIONS = 2
        const val ENDSTOP_INTENSITY_PERCENT = 20
        const val ENDSTOP_INTENSITY = ENDSTOP_INTENSITY_PERCENT / 100f
        const val ENDSTOP_PULSE_MS = 600L
        const val MAX_ENDSTOP_PULSES = 180
        const val MIN_ENDSTOP_TRAVEL_PULSES = 6
        const val ENDSTOP_CONFIRMATIONS = 3
        const val ENDSTOP_LOG_EVERY_PULSES = 5
        const val MIN_ENDSTOP_INLIERS = 5
        const val ENDSTOP_STILL_RADIUS_PX = 2.4f
        const val BETWEEN_ENDSTOP_PULSES_MS = 120L
        // Intervalli controllabili ufficiali (non i limiti meccanici più ampi).
        const val OFFICIAL_PAN_MIN_DEG = -57f
        const val OFFICIAL_PAN_MAX_DEG = 235f
        const val OFFICIAL_TILT_MIN_DEG = -57f
        const val OFFICIAL_TILT_MAX_DEG = 120f
        const val MIN_SCENE_FEATURES = 14
        const val INITIAL_SETTLE_MS = 140L
        const val SETTLE_CHECK_MS = 120L
        const val MAX_SETTLE_CHECKS = 4
        const val STABLE_RADIUS_PX = 2.2f
        const val BETWEEN_SAMPLES_MS = 180L
        const val LOG_EVERY_STEPS = 12
        const val RETRY_PREVIEW_MS = 800L
        const val RETURN_ZERO_SCORE_RADIUS_PX = 28f
    }
}
