package it.persoft.lunaultra.gimbal

import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.data.GimbalCalibrationBuilder
import it.persoft.lunaultra.data.GimbalCalibrationProfile
import it.persoft.lunaultra.data.GimbalCalibrationSample
import it.persoft.lunaultra.data.JsonFileStore
import it.persoft.lunaultra.diagnostics.WaypointImageVerifier
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.preview.PreviewController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

data class GimbalCalibrationState(
    val running: Boolean = false,
    val completedSteps: Int = 0,
    val totalSteps: Int = TOTAL_STEPS,
    val message: String = "",
    val error: String? = null,
) {
    val progress: Float get() = if (totalSteps == 0) 0f else completedSteps.toFloat() / totalSteps

    companion object {
        const val TOTAL_STEPS = 3 * 2 * 2 * 3 * 3
    }
}

/**
 * Misura la risposta reale del medesimo hardware a L/M/V, sui due assi, in entrambe le
 * direzioni e a tre intensità. Le direzioni sono sempre alternate, quindi ogni prova positiva
 * è seguita dalla prova inversa e la camera resta vicina all'inquadratura iniziale.
 */
class GimbalCalibrator(
    private val commands: LunaCommands,
    private val gimbal: GimbalController,
    private val preview: PreviewController,
    private val store: JsonFileStore<GimbalCalibrationProfile>,
    private val log: EventLog,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(GimbalCalibrationState())
    val state: StateFlow<GimbalCalibrationState> = _state

    private var job: Job? = null

    fun start(cameraModel: String, firmware: String, originalHardwareLevel: Int) {
        if (job?.isActive == true) return
        job = scope.launch {
            runCalibration(cameraModel, firmware, originalHardwareLevel.coerceIn(1, 3))
        }
    }

    fun cancel() {
        job?.cancel(CancellationException("Calibrazione interrotta dall'utente"))
    }

    private suspend fun runCalibration(cameraModel: String, firmware: String, originalLevel: Int) {
        val samples = mutableListOf<GimbalCalibrationSample>()
        _state.value = GimbalCalibrationState(running = true, message = "Avvio anteprima e controllo immagine…")
        log.info("CALIBRAZIONE GIMBAL · AVVIO", "Profilo precedente conservato fino al completamento della nuova misura.")
        try {
            val firstFrame = preview.captureThumbnailJpeg()
                ?: throw IllegalStateException("Anteprima non disponibile: apri il mirino e lascia visibile una scena con dettagli")
            val firstCheck = WaypointImageVerifier.verify(firstFrame, firstFrame)
            if (firstCheck == null || firstCheck.referenceFeatures < MIN_SCENE_FEATURES) {
                throw IllegalStateException("La scena ha pochi dettagli: inquadra oggetti fermi con bordi ben visibili")
            }

            for (level in 1..3) {
                _state.value = _state.value.copy(message = "Velocità ${levelLabel(level)} · impostazione camera…")
                commands.setGimbalHardwareSpeed(level)
                    .getOrElse { throw IllegalStateException("La camera non accetta la velocità ${levelLabel(level)}: ${it.message}", it) }
                delay(HARDWARE_LEVEL_SETTLE_MS)

                for (axis in listOf(GimbalCalibrationSample.AXIS_PAN, GimbalCalibrationSample.AXIS_TILT)) {
                    for (intensityIndex in INTENSITIES.indices) {
                        val intensity = INTENSITIES[intensityIndex]
                        val pulseMs = PULSE_DURATIONS_MS[intensityIndex]
                        repeat(REPETITIONS) {
                            for (direction in listOf(1f, -1f)) {
                                val command = intensity * direction
                                val before = preview.captureThumbnailJpeg()
                                    ?: throw IllegalStateException("Fotogramma perso durante la calibrazione")
                                val started = System.nanoTime()
                                gimbal.correctionPulse(
                                    panPercent = if (axis == GimbalCalibrationSample.AXIS_PAN) command else 0f,
                                    tiltPercent = if (axis == GimbalCalibrationSample.AXIS_TILT) command else 0f,
                                    durationMs = pulseMs,
                                ).getOrElse { throw IllegalStateException("Movimento di calibrazione non riuscito: ${it.message}", it) }
                                val commandElapsedMs = (System.nanoTime() - started) / 1_000_000L
                                val settled = captureAfterSettling()
                                val verification = WaypointImageVerifier.verify(before, settled.jpeg)
                                if (verification != null) {
                                    samples += GimbalCalibrationSample(
                                        hardwareLevel = level,
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
                                val done = _state.value.completedSteps + 1
                                _state.value = _state.value.copy(
                                    completedSteps = done,
                                    message = "${levelLabel(level)} · ${axisLabel(axis)} · prova $done/${GimbalCalibrationState.TOTAL_STEPS}",
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
                    profile.levels.forEach { level ->
                        appendLine(
                            "${levelLabel(level.hardwareLevel)}: pan %+.1f px/s · tilt %+.1f px/s · scala %.2f/%.2f".format(
                                level.panImagePixelsPerSecond,
                                level.tiltImagePixelsPerSecond,
                                level.panSpeedScale,
                                level.tiltSpeedScale,
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
                commands.setGimbalHardwareSpeed(originalLevel)
                    .onFailure { log.warn("Ripristino velocità gimbal non riuscito: ${it.message}") }
            }
            job = null
        }
    }

    /** Aspetta finché due frame consecutivi non mostrano più inerzia apprezzabile. */
    private suspend fun captureAfterSettling(): SettledFrame {
        var elapsed = INITIAL_SETTLE_MS
        delay(INITIAL_SETTLE_MS)
        var latest = preview.captureThumbnailJpeg()
            ?: throw IllegalStateException("Fotogramma non disponibile dopo il movimento")
        repeat(MAX_SETTLE_CHECKS) {
            delay(SETTLE_CHECK_MS)
            elapsed += SETTLE_CHECK_MS
            val next = preview.captureThumbnailJpeg() ?: return@repeat
            val residual = WaypointImageVerifier.verify(latest, next)
            latest = next
            val stable = residual != null && residual.inlierMatches >= 5 &&
                hypot(residual.shiftX, residual.shiftY) <= STABLE_RADIUS_PX
            if (stable) return SettledFrame(latest, elapsed)
        }
        return SettledFrame(latest, elapsed)
    }

    private data class SettledFrame(val jpeg: ByteArray, val elapsedMs: Long)

    private fun levelLabel(level: Int) = when (level) { 1 -> "Lenta"; 2 -> "Media"; else -> "Veloce" }
    private fun axisLabel(axis: String) = if (axis == GimbalCalibrationSample.AXIS_PAN) "orizzontale" else "verticale"

    private companion object {
        val INTENSITIES = floatArrayOf(0.15f, 0.25f, 0.35f)
        val PULSE_DURATIONS_MS = longArrayOf(600L, 480L, 380L)
        const val REPETITIONS = 3
        const val MIN_SCENE_FEATURES = 14
        const val HARDWARE_LEVEL_SETTLE_MS = 500L
        const val INITIAL_SETTLE_MS = 140L
        const val SETTLE_CHECK_MS = 120L
        const val MAX_SETTLE_CHECKS = 4
        const val STABLE_RADIUS_PX = 2.2f
        const val BETWEEN_SAMPLES_MS = 180L
        const val LOG_EVERY_STEPS = 12
    }
}
