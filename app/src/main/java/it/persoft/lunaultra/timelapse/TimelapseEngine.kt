package it.persoft.lunaultra.timelapse

import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.diagnostics.PositionVerdict
import it.persoft.lunaultra.diagnostics.WaypointImageVerifier
import it.persoft.lunaultra.gimbal.GimbalController
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.net.LogLevel
import it.persoft.lunaultra.preview.PreviewController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class RunPhase { IDLE, PREPARING, RUNNING, STOPPING, COMPLETED, ABORTED }

data class RunState(
    val phase: RunPhase = RunPhase.IDLE,
    val mode: ShootingMode = ShootingMode.VIDEO,
    val legIndex: Int = 0,
    val legCount: Int = 0,
    val legProgress: Float = 0f,
    val elapsedSeconds: Float = 0f,
    val totalSeconds: Float = 0f,
    val targetPan: Float = 0f,
    val targetTilt: Float = 0f,
    val shotsTaken: Int = 0,
    val shotsPlanned: Int = 0,
    val message: String? = null,
) {
    val running: Boolean get() = phase == RunPhase.PREPARING || phase == RunPhase.RUNNING || phase == RunPhase.STOPPING
    val overallProgress: Float
        get() = when {
            mode == ShootingMode.FOTO && shotsPlanned > 0 ->
                (shotsTaken.toFloat() / shotsPlanned).coerceIn(0f, 1f)

            totalSeconds > 0f -> (elapsedSeconds / totalSeconds).coerceIn(0f, 1f)
            else -> 0f
        }
}

/**
 * Esegue la sequenza secondo la modalità scelta.
 *
 * Le modalità continue (video e timelapse della camera) avviano la registrazione e muovono il
 * gimbal senza fermarsi. La modalità foto fa l'opposto: si sposta, si ferma, aspetta che
 * l'inerzia si esaurisca e scatta. Sono due cicli distinti perché condividerne uno solo
 * significherebbe riempirlo di condizioni, e la differenza è sostanziale.
 */
class TimelapseEngine(
    private val commands: LunaCommands,
    private val gimbal: GimbalController,
    private val preview: PreviewController,
    private val log: EventLog,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state

    private var job: Job? = null

    /** Ricordato all'avvio: la fase di chiusura deve rispettare la scelta dell'utente. */
    @Volatile
    private var controlRecording: Boolean = true

    @Volatile
    private var runningMode: ShootingMode = ShootingMode.VIDEO

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
        val traceId = System.currentTimeMillis().toString().takeLast(8)
        controlRecording = sequence.controlRecording
        runningMode = sequence.mode
        val durations = sequence.legDurations()
        val total = durations.sum()

        _state.value = RunState(
            phase = RunPhase.PREPARING,
            mode = sequence.mode,
            legCount = sequence.legCount,
            totalSeconds = if (sequence.mode == ShootingMode.FOTO) {
                sequence.estimatedPhotoSeconds()
            } else {
                sequence.estimatedRecordingSeconds()
            },
            shotsPlanned = if (sequence.mode == ShootingMode.FOTO) sequence.totalShots() else 0,
            message = "Ritorno al punto 1 · registrazione ferma",
        )

        try {
            val first = sequence.waypoints.first()
            logSequencePlan(traceId, sequence, durations)
            val beforeReturn = gimbal.position.value
            preview.logSnapshot(
                message = "RUN $traceId · POSIZIONE PRIMA DEL RITORNO",
                detail = targetDetail(first, beforeReturn) +
                    "\nIl gimbal deve ora andare verso il Punto 1 con registrazione ferma.",
            )
            log.info("Modalità ${sequence.mode.label}: vado al punto iniziale ${first.name}")
            approach(first.pan, first.tilt)
            val actualStartJpeg = preview.logSnapshot(
                message = "RUN $traceId · PUNTO 1 RAGGIUNTO · ${first.name}",
                detail = targetDetail(first, gimbal.position.value) +
                    "\nProssima azione: avvio della registrazione.",
            )
            logImageVerification(traceId, first, actualStartJpeg, "PUNTO 1 PRIMA DEL VIDEO")

            when (sequence.mode) {
                ShootingMode.FOTO -> runPhotos(sequence)
                else -> runContinuous(sequence, durations, total, tickHz, traceId)
            }

            shutdown(aborted = false, reason = "Sequenza completata")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Errore durante la sequenza: ${e.message}")
            shutdown(aborted = true, reason = e.message ?: "Errore")
        }
    }

    /** Video e timelapse della camera: registrazione avviata una volta, movimento continuo. */
    private suspend fun runContinuous(
        sequence: TimelapseSequence,
        durations: List<Float>,
        total: Float,
        tickHz: Int,
        traceId: String,
    ) {
        if (sequence.mode == ShootingMode.TIMELAPSE_CAMERA && sequence.configureCameraTimelapse) {
            commands.setTimelapseOptions(
                sequence.estimatedRecordingSeconds().roundToInt(),
                sequence.intervalSeconds.roundToInt(),
            )
                .onFailure { log.warn("Parametri timelapse non accettati: ${it.message}") }
                .onSuccess { log.info("Timelapse impostato: ${total.roundToInt()}s ogni ${sequence.intervalSeconds}s") }
        }
        if (sequence.controlRecording) {
            _state.value = _state.value.copy(message = "Punto 1 raggiunto · avvio registrazione")
            commands.startRecording(sequence.mode == ShootingMode.TIMELAPSE_CAMERA)
                .getOrElse { throw IllegalStateException("Avvio registrazione non riuscito: ${it.message}", it) }
            log.info(
                "RUN $traceId · REGISTRAZIONE AVVIATA SUL PUNTO 1",
                targetDetail(sequence.waypoints.first(), gimbal.position.value),
            )
            hold(sequence.startHoldSeconds, "Fermo iniziale sul punto ${sequence.waypoints.first().name}")
            _state.value = _state.value.copy(elapsedSeconds = sequence.startHoldSeconds.coerceAtLeast(0f))
        }

        val periodMs = (1000L / tickHz.coerceIn(1, 50)).coerceAtLeast(20L)
        val stepSeconds = periodMs / 1000f
        var elapsedTotal = 0f

        for (legIndex in 0 until sequence.legCount) {
            val from = sequence.waypoints[legIndex]
            val to = sequence.waypoints[legIndex + 1]
            val legSeconds = durations[legIndex]
            val atLegStart = gimbal.position.value
            log.info(
                "RUN $traceId · TRATTO ${legIndex + 1}/${sequence.legCount}: ${from.name} → ${to.name}",
                buildString {
                    appendLine("Durata movimento: %.3f s".format(legSeconds))
                    appendLine("Partenza stimata: pan %.3f° · tilt %.3f°".format(atLegStart.pan, atLegStart.tilt))
                    appendLine("Arrivo configurato: pan %.3f° · tilt %.3f°".format(to.pan, to.tilt))
                    append("Vettore richiesto: Δpan %+.3f° · Δtilt %+.3f°".format(to.pan - atLegStart.pan, to.tilt - atLegStart.tilt))
                },
            )

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
                    elapsedSeconds = sequence.startHoldSeconds.coerceAtLeast(0f) + elapsedTotal + legElapsed,
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

        gimbal.stop()
        val last = sequence.waypoints.last()
        val actualArrivalJpeg = preview.logSnapshot(
            message = "RUN $traceId · ARRIVO REALE · ${last.name}",
            detail = targetDetail(last, gimbal.position.value) +
                "\nIl gimbal è fermo; la registrazione verrà arrestata dopo il fermo finale.",
        )
        logImageVerification(traceId, last, actualArrivalJpeg, "PUNTO FINALE DURANTE IL VIDEO")
        if (sequence.controlRecording) {
            hold(sequence.endHoldSeconds, "Fermo finale sul punto ${sequence.waypoints.last().name}")
            _state.value = _state.value.copy(elapsedSeconds = sequence.estimatedRecordingSeconds())
        }
    }

    /**
     * Modalità foto: si scatta a intervalli regolari lungo il percorso, fermandosi ogni volta.
     *
     * L'ultimo scatto di un tratto coincide con il primo del successivo, quindi viene saltato:
     * su una panoramica, due scatti identici nello stesso punto complicano l'unione invece di
     * aiutarla.
     */
    private suspend fun runPhotos(sequence: TimelapseSequence) {
        val shotsPerLeg = sequence.effectiveShotsPerLeg()
        val planned = sequence.totalShots()
        val durations = sequence.legDurations()
        var taken = 0

        log.info("Modalità foto: $planned scatti, $shotsPerLeg per tratto")

        for (legIndex in 0 until sequence.legCount) {
            val from = sequence.waypoints[legIndex]
            val to = sequence.waypoints[legIndex + 1]
            val moveSeconds = durations[legIndex] / (shotsPerLeg - 1)

            for (shot in 0 until shotsPerLeg) {
                if (!currentCoroutineContext().isActive) return
                // Il primo scatto di un tratto successivo al primo è già stato fatto.
                if (shot == 0 && legIndex > 0) continue

                val t = shot.toFloat() / (shotsPerLeg - 1)
                val targetPan = Interpolation.position(from.pan, to.pan, t, sequence.interpolation)
                val targetTilt = Interpolation.position(from.tilt, to.tilt, t, sequence.interpolation)

                _state.value = _state.value.copy(
                    phase = RunPhase.RUNNING,
                    legIndex = legIndex,
                    legProgress = t,
                    targetPan = targetPan,
                    targetTilt = targetTilt,
                    shotsTaken = taken,
                    shotsPlanned = planned,
                    message = "In posizione per lo scatto ${taken + 1}/$planned",
                )

                moveTo(targetPan, targetTilt, moveSeconds)
                gimbal.stop()

                // Il gimbal deve essere davvero fermo prima dello scatto.
                delay((sequence.settleSeconds * 1000).toLong().coerceAtLeast(0L))

                _state.value = _state.value.copy(message = "Scatto ${taken + 1}/$planned")
                commands.takePicture()
                    .onSuccess { log.info("Scatto ${taken + 1}/$planned a %.1f° / %.1f°".format(targetPan, targetTilt)) }
                    .onFailure { log.warn("Scatto ${taken + 1} non riuscito: ${it.message}") }

                taken++
                _state.value = _state.value.copy(
                    shotsTaken = taken,
                    elapsedSeconds = taken * (moveSeconds + sequence.settleSeconds),
                )
            }
        }
    }

    /** Avvicinamento progressivo a una posizione, con il gimbal fermato all'arrivo. */
    private suspend fun moveTo(pan: Float, tilt: Float, seconds: Float) {
        val steps = (seconds * MOVE_TICK_HZ).toInt().coerceIn(1, 600)
        val stepSeconds = seconds / steps
        repeat(steps) {
            if (!currentCoroutineContext().isActive) return
            gimbal.driveTo(pan, tilt, stepSeconds)
            delay((stepSeconds * 1000).toLong().coerceAtLeast(20L))
        }
    }

    /** Avvicinamento al primo waypoint prima di iniziare a registrare. */
    private suspend fun approach(pan: Float, tilt: Float) {
        gimbal.moveToPosition(pan, tilt, minimumSeconds = MIN_APPROACH_SECONDS)
            .getOrElse { throw IllegalStateException("Punto iniziale non raggiunto: ${it.message}", it) }
        delay(PRE_RECORD_SETTLE_MS)
    }

    private suspend fun hold(seconds: Float, message: String) {
        val millis = (seconds.coerceAtLeast(0f) * 1000f).toLong()
        if (millis <= 0L) return
        _state.value = _state.value.copy(phase = RunPhase.RUNNING, message = message)
        delay(millis)
    }

    /** Piano completo con le immagini originali: permette di verificare anche l'ordine 1→2. */
    private fun logSequencePlan(traceId: String, sequence: TimelapseSequence, durations: List<Float>) {
        log.info(
            "RUN $traceId · PIANO SEQUENZA",
            buildString {
                appendLine("Modalità: ${sequence.mode.label}")
                appendLine("Ordine: ${sequence.waypoints.joinToString(" → ") { it.name }}")
                appendLine("Tratti: ${durations.joinToString { "%.3f s".format(it) }}")
                append("Registrazione gestita dall'app: ${sequence.controlRecording}")
            },
        )
        sequence.waypoints.forEachIndexed { index, point ->
            log.info(
                message = "RUN $traceId · PUNTO ${index + 1} CONFIGURATO · ${point.name}",
                detail = buildString {
                    appendLine("Pan salvato: %.3f°".format(point.pan))
                    appendLine("Tilt salvato: %.3f°".format(point.tilt))
                    append("Miniatura: ${if (point.previewJpegBase64 == null) "assente" else "acquisita quando il punto è stato memorizzato"}")
                },
                imageJpeg = point.previewJpeg(),
            )
        }
    }

    private fun targetDetail(target: Waypoint, actual: it.persoft.lunaultra.camera.PtzState): String =
        buildString {
            appendLine("Bersaglio: pan %.3f° · tilt %.3f°".format(target.pan, target.tilt))
            appendLine("Stima attuale: pan %.3f° · tilt %.3f°".format(actual.pan, actual.tilt))
            appendLine("Errore stimato: Δpan %+.3f° · Δtilt %+.3f°".format(target.pan - actual.pan, target.tilt - actual.tilt))
            append("Posizione da camera: ${actual.fromCamera}")
        }

    /**
     * Confronto visuale indipendente dal dead reckoning: gli inlier sono punti che concordano
     * sullo stesso spostamento, come nella fase di allineamento di uno stitch panoramico.
     */
    private fun logImageVerification(
        traceId: String,
        target: Waypoint,
        actualJpeg: ByteArray?,
        phase: String,
    ) {
        val referenceJpeg = target.previewJpeg()
        val verification = WaypointImageVerifier.verify(referenceJpeg, actualJpeg)
        if (verification == null) {
            log.warn(
                "RUN $traceId · VERIFICA VISIVA $phase NON DISPONIBILE",
                "Manca la miniatura del waypoint o il frame reale. Rimemorizza il punto con l'anteprima attiva.",
            )
            return
        }
        val annotated = WaypointImageVerifier.annotatedCurrentJpeg(actualJpeg, verification)
        val level = when (verification.verdict) {
            PositionVerdict.CORRECT -> LogLevel.INFO
            else -> LogLevel.WARN
        }
        log.log(
            level = level,
            message = "RUN $traceId · CONTROLLO PUNTI $phase · ${verification.verdict.label}",
            detail = verification.describe() +
                "\nCerchi verdi: corrispondenze coerenti · cerchi rossi: scarti." +
                "\nΔx positivo = immagine reale spostata a destra rispetto al waypoint salvato.",
            imageJpeg = annotated,
        )
    }

    private suspend fun shutdown(aborted: Boolean, reason: String) {
        runCatching { gimbal.stop() }
        // In modalità foto non c'è nessuna registrazione da fermare.
        if (controlRecording && runningMode.movesContinuously) {
            runCatching {
                commands.stopRecording(runningMode == ShootingMode.TIMELAPSE_CAMERA)
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
        const val MOVE_TICK_HZ = 10
        const val MIN_APPROACH_SECONDS = 1f
        const val PRE_RECORD_SETTLE_MS = 500L
    }
}
