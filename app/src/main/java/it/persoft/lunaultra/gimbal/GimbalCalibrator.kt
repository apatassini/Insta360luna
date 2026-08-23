package it.persoft.lunaultra.gimbal

import it.persoft.lunaultra.data.GimbalCalibrationBuilder
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
    val progress: Float get() = if (totalSteps == 0) 0f else completedSteps.toFloat() / totalSteps

    companion object {
        const val TOTAL_STEPS = 12 * 2 * 2 * 2
    }
}

/**
 * Misura la risposta reale del comando gimbal dall'1% al 100%, sui due assi e in entrambe le
 * direzioni. I livelli hardware L/M/V sono esclusi perché equivalenti nelle prove e inaffidabili
 * nel log. Ogni impulso è seguito dall'inverso, mantenendo la camera vicina all'origine.
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
        _state.value = GimbalCalibrationState(running = true, message = "Avvio anteprima e controllo immagine…")
        log.info("CALIBRAZIONE GIMBAL · AVVIO", "Profilo precedente conservato fino al completamento della nuova misura.")
        try {
            val firstFrame = awaitFrame("Avvio: attendo un fotogramma verificabile…")
            val firstCheck = WaypointImageVerifier.verify(firstFrame, firstFrame)
            if (firstCheck == null || firstCheck.referenceFeatures < MIN_SCENE_FEATURES) {
                throw IllegalStateException("La scena ha pochi dettagli: inquadra oggetti fermi con bordi ben visibili")
            }
            _state.value = _state.value.copy(
                verificationLabel = "SCENA RICONOSCIUTA",
                referenceFeatures = firstCheck.referenceFeatures,
                currentFeatures = firstCheck.currentFeatures,
                candidateMatches = firstCheck.candidateMatches,
                inlierMatches = firstCheck.inlierMatches,
                controlPointsPercent = controlPointPercent(firstCheck),
                positioningPercent = 100,
                annotatedJpeg = WaypointImageVerifier.annotatedCurrentJpeg(firstFrame, firstCheck),
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

            val profile = GimbalCalibrationBuilder.build(samples, cameraModel, firmware)
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
            _state.value = _state.value.copy(running = false, message = "Calibrazione salvata e attiva", error = null)
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
        // A intensità minime serve un impulso più lungo perché lo spostamento sia misurabile.
        val PULSE_DURATIONS_MS = longArrayOf(5_000L, 1_600L, 800L, 400L, 270L, 200L, 160L, 135L, 115L, 100L, 90L, 80L)
        const val REPETITIONS = 2
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
