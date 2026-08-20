package it.persoft.lunaultra.timelapse

import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.gimbal.GimbalController
import it.persoft.lunaultra.net.EventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class RunPhase { IDLE, PREPARING, RUNNING, STOPPING, COMPLETED, ABORTED }

data class RunState(
    val phase: RunPhase = RunPhase.IDLE,
    val legIndex: Int = 0,
    val legCount: Int = 0,
    val legProgress: Float = 0f,
    val elapsedSeconds: Float = 0f,
    val totalSeconds: Float = 0f,
    val targetPan: Float = 0f,
    val targetTilt: Float = 0f,
    val message: String? = null,
) {
    val running: Boolean get() = phase == RunPhase.PREPARING || phase == RunPhase.RUNNING || phase == RunPhase.STOPPING
    val overallProgress: Float
        get() = if (totalSeconds <= 0f) 0f else (elapsedSeconds / totalSeconds).coerceIn(0f, 1f)
}

/**
 * Esegue la sequenza: opzionalmente imposta la modalità Timelapse e avvia la registrazione,
 * poi muove il gimbal lungo i waypoint con l'interpolazione scelta e infine ferma tutto.
 */
class TimelapseEngine(
    private val commands: LunaCommands,
    private val gimbal: GimbalController,
    private val log: EventLog,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state

    private var job: Job? = null

    /** Ricordato all'avvio: la fase di chiusura deve rispettare la scelta dell'utente. */
    @Volatile
    private var controlRecording: Boolean = true

    fun start(sequence: TimelapseSequence, tickHz: Int = 10) {
        if (_state.value.running) {
            log.warn("Sequenza già in esecuzione")
            return
        }
        if (!sequence.isRunnable) {
            _state.value = RunState(phase = RunPhase.IDLE, message = "Servono almeno 2 punti")
            return
        }
        job = scope.launch { run(sequence, tickHz) }
    }

    /** STOP di emergenza: ferma il gimbal e la registrazione il prima possibile. */
    fun stop(reason: String = "Interrotto dall'utente") {
        val current = _state.value
        if (!current.running) return
        _state.value = current.copy(phase = RunPhase.STOPPING, message = reason)
        job?.cancel(CancellationException(reason))
        job = null
        scope.launch { shutdown(aborted = true, reason = reason) }
    }

    private suspend fun run(sequence: TimelapseSequence, tickHz: Int) {
        controlRecording = sequence.controlRecording
        val durations = sequence.legDurations()
        val total = durations.sum()
        _state.value = RunState(
            phase = RunPhase.PREPARING,
            legCount = sequence.legCount,
            totalSeconds = total,
            message = "Preparazione",
        )

        try {
            // 1. Posizionamento sul primo waypoint.
            val first = sequence.waypoints.first()
            log.info("Vado al punto iniziale ${first.name}")
            approach(first.pan, first.tilt)

            // 2. Modalità e avvio registrazione.
            if (sequence.setTimelapseMode) {
                commands.selectTimelapseMode()
                    .onFailure { log.warn("Impostazione modalità Timelapse non riuscita: ${it.message}") }
            }
            if (sequence.controlRecording) {
                commands.startCapture()
                    .onFailure { log.warn("Avvio registrazione non riuscito: ${it.message}") }
                    .onSuccess { log.info("Registrazione avviata") }
            }

            // 3. Percorso fra i waypoint.
            val periodMs = (1000L / tickHz.coerceIn(1, 50)).coerceAtLeast(20L)
            val stepSeconds = periodMs / 1000f
            var elapsedTotal = 0f

            for (legIndex in 0 until sequence.legCount) {
                val from = sequence.waypoints[legIndex]
                val to = sequence.waypoints[legIndex + 1]
                val legSeconds = durations[legIndex]
                log.info("Tratto ${legIndex + 1}/${sequence.legCount}: ${from.name} → ${to.name} in ${legSeconds.roundToInt()}s")

                val startedAt = System.nanoTime()
                var legElapsed = 0f
                while (legElapsed < legSeconds) {
                    if (!currentCoroutineContext().isActive) return
                    legElapsed = ((System.nanoTime() - startedAt) / 1_000_000_000.0).toFloat()
                    val t = (legElapsed / legSeconds).coerceIn(0f, 1f)
                    val targetPan = Interpolation.position(from.pan, to.pan, t, sequence.interpolation)
                    val targetTilt = Interpolation.position(from.tilt, to.tilt, t, sequence.interpolation)

                    gimbal.driveTo(targetPan, targetTilt, stepSeconds)
                        .onFailure { log.warn("Comando gimbal fallito: ${it.message}") }

                    _state.value = _state.value.copy(
                        phase = RunPhase.RUNNING,
                        legIndex = legIndex,
                        legProgress = t,
                        elapsedSeconds = elapsedTotal + legElapsed,
                        targetPan = targetPan,
                        targetTilt = targetTilt,
                        message = "${from.name} → ${to.name}",
                    )
                    delay(periodMs)
                }
                elapsedTotal += legSeconds
                // Allineamento esatto sul waypoint di arrivo, per evitare derive cumulative.
                gimbal.driveTo(to.pan, to.tilt, stepSeconds)
            }

            shutdown(aborted = false, reason = "Sequenza completata")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Errore durante la sequenza: ${e.message}")
            shutdown(aborted = true, reason = e.message ?: "Errore")
        }
    }

    /** Avvicinamento al primo waypoint prima di iniziare a registrare. */
    private suspend fun approach(pan: Float, tilt: Float) {
        repeat(APPROACH_TICKS) {
            gimbal.driveTo(pan, tilt, APPROACH_STEP_SECONDS)
            delay((APPROACH_STEP_SECONDS * 1000).toLong())
        }
        gimbal.stop()
    }

    private suspend fun shutdown(aborted: Boolean, reason: String) {
        runCatching { gimbal.stop() }
        if (controlRecording) {
            runCatching {
                commands.stopCapture()
                    .onFailure { log.warn("Stop registrazione non confermato: ${it.message}") }
                    .onSuccess { log.info("Registrazione fermata") }
            }
        }
        _state.value = _state.value.copy(
            phase = if (aborted) RunPhase.ABORTED else RunPhase.COMPLETED,
            legProgress = if (aborted) _state.value.legProgress else 1f,
            message = reason,
        )
        log.info(reason)
    }

    private companion object {
        const val APPROACH_TICKS = 20
        const val APPROACH_STEP_SECONDS = 0.1f
    }
}
