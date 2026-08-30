package it.persoft.lunaultra.timelapse

import it.persoft.lunaultra.camera.CameraMode
import it.persoft.lunaultra.camera.ExposureReading
import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.data.GimbalCalibrationProfile
import it.persoft.lunaultra.diagnostics.ImageVerification
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

enum class RunPhase { IDLE, PREPARING, RUNNING, STOPPING, COMPLETED, ABORTED }

/** Uno scatto accettato dalla camera, con il percorso del file se lo dichiara. */
internal data class FiredShot(val uri: String?)

/** Dove va la camera per uno scatto, e a che punto del percorso si trova. */
internal data class PhotoTarget(
    val pan: Float,
    val tilt: Float,
    val legIndex: Int,
    val legProgress: Float,
)

/**
 * Quanto aspettare, dopo il comando di scatto, prima di poter muovere il gimbal.
 *
 * L'unico momento in cui il gimbal deve stare fermo è la posa. Quello che viene dopo —
 * compressione e scrittura sulla scheda, che su questa camera durano insieme cinque secondi —
 * non ha niente a che vedere con l'inquadratura: il sensore è già stato letto. Quei cinque
 * secondi si possono quindi spendere andando verso lo scatto successivo invece di stare a
 * guardare, ed è la differenza fra 8,6 e 5,5 secondi per scatto.
 *
 * L'attesa è la posa dichiarata dalla camera, raddoppiata perché fra il comando e l'apertura
 * dell'otturatore passa un pezzo di tempo che nessuno dichiara, più un margine fisso. Quando la
 * camera non dichiara niente si usa il tetto: due secondi, che è più di quanto duri una posa in
 * qualunque situazione in cui abbia senso fare una panoramica — al buio la posa sarebbe lunga,
 * ma una panoramica al buio viene comunque mossa dal gimbal che riparte.
 */
internal fun exposureGuardMillis(exposure: ExposureReading?): Long {
    if (exposure == null || !exposure.knowsShutter) return MAX_EXPOSURE_GUARD_MS
    val millis = (exposure.shutterSeconds * EXPOSURE_SAFETY_FACTOR * 1000.0) + EXPOSURE_MARGIN_MS
    return millis.toLong().coerceIn(MIN_EXPOSURE_GUARD_MS, MAX_EXPOSURE_GUARD_MS)
}

/** Come raccontare l'esposizione nel log: la posa in frazione di secondo, come la si legge. */
internal fun describeExposure(exposure: ExposureReading?): String {
    if (exposure == null) return "non dichiarata dalla camera"
    val program = if (exposure.isManual) "manuale" else "automatica"
    if (!exposure.knowsShutter) return "$program, posa non dichiarata"
    val shutter = if (exposure.shutterSeconds >= 1.0) {
        "%.1f s".format(exposure.shutterSeconds)
    } else {
        "1/${(1.0 / exposure.shutterSeconds).roundToInt()} s"
    }
    val iso = if (exposure.iso > 0) " · ISO ${exposure.iso}" else ""
    return "$program, posa $shutter$iso"
}

/** Il raddoppio copre il ritardo fra il comando e l'apertura dell'otturatore. */
private const val EXPOSURE_SAFETY_FACTOR = 2.0
private const val EXPOSURE_MARGIN_MS = 250.0
private const val MIN_EXPOSURE_GUARD_MS = 300L

/**
 * Il tetto, e la ragione per cui è due secondi: nessuna posa che valga una panoramica dura di
 * più, e oltre i due secondi si perderebbe comunque il vantaggio.
 */
private const val MAX_EXPOSURE_GUARD_MS = 2_000L

/**
 * L'orientamento di uno scatto, come lo aveva chiesto il piano, e il file che ne è uscito.
 *
 * [uri] arriva dalla risposta della camera allo scatto, che porta il percorso del file appena
 * scritto. Quando c'è, unire la panoramica non richiede di indovinare niente: si sa quale foto
 * sta a quale angolo. Manca sui firmware che rispondono a mani vuote, e allora si torna a
 * confrontare l'elenco dei file prima e dopo.
 */
data class ShotAngle(
    val panDegrees: Float,
    val tiltDegrees: Float,
    val uri: String? = null,
)

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
    /**
     * Dove guardava la camera a ogni scatto, in ordine.
     *
     * Serve a unire le foto dopo: la camera non dice come ha chiamato il file che ha appena
     * salvato, quindi l'unico modo di sapere quale foto sta dove è tenere il conto degli angoli
     * mentre si scatta e accoppiarli in ordine con i file nuovi trovati alla fine.
     */
    val shotAngles: List<ShotAngle> = emptyList(),
    /**
     * Secondi che mancano alla fine, misurati e non calcolati.
     *
     * Il tempo di uno scatto non si conosce prima: dipende da quanto lontano è il punto
     * successivo e da quanto ci mette la camera a scrivere il file. Si stima quindi sulla media
     * degli scatti già fatti, e finché non ce n'è nessuno vale la cadenza nota della camera.
     */
    val secondsRemaining: Float? = null,
    /** Scatti che la camera non ha eseguito: la panoramica avrà un buco lì. */
    val shotsMissed: Int = 0,
) {
    val running: Boolean get() = phase == RunPhase.PREPARING || phase == RunPhase.RUNNING || phase == RunPhase.STOPPING
    val overallProgress: Float
        get() = when {
            // Anche uno scatto perso è un passo fatto: la barra segue il percorso, non il
            // raccolto, altrimenti si ferma proprio quando qualcosa va storto.
            mode == ShootingMode.FOTO && shotsPlanned > 0 ->
                ((shotsTaken + shotsMissed).toFloat() / shotsPlanned).coerceIn(0f, 1f)

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
    private val settings: StateFlow<AppSettings>,
    private val calibration: StateFlow<GimbalCalibrationProfile>,
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
            val startAligned = visuallyAlign(
                traceId = traceId,
                target = first,
                phase = "PUNTO 1 PRIMA DEL VIDEO",
                initialJpeg = actualStartJpeg,
            )
            if (!startAligned) {
                // Il controllo visivo è una rifinitura, non un permesso di registrare.
                //
                // Il gimbal qui è già sul bersaglio — l'errore meccanico è nel log, e vale
                // centesimi di grado. Se l'immagine non lo conferma il motivo è quasi sempre
                // la scena, non la posizione: fra quando il punto è stato memorizzato e adesso
                // l'acqua si è mossa, la luce è cambiata, qualcuno è passato davanti. Rifiutare
                // di partire per questo vuol dire che una sequenza perfettamente puntata non si
                // gira, ed è esattamente quello che succedeva.
                log.warn(
                    "RUN $traceId · PUNTO 1 NON CONFERMATO DALL'IMMAGINE · PARTO LO STESSO",
                    targetDetail(first, gimbal.position.value) +
                        "\nIl gimbal è sul bersaglio: si registra con le coordinate, " +
                        "senza aspettare la conferma visiva.",
                )
            }

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
            val correctionBudget = visualCorrectionBudget(legSeconds, to, sequence.mode)
            val motionSeconds = (legSeconds - correctionBudget).coerceAtLeast(MIN_VISUAL_MOTION_SECONDS)
            val atLegStart = gimbal.position.value
            log.info(
                "RUN $traceId · TRATTO ${legIndex + 1}/${sequence.legCount}: ${from.name} → ${to.name}",
                buildString {
                    appendLine("Durata movimento: %.3f s".format(legSeconds))
                    appendLine("Movimento stimato: %.3f s · riserva allineamento: %.3f s".format(motionSeconds, correctionBudget))
                    appendLine("Partenza stimata: pan %.3f° · tilt %.3f°".format(atLegStart.pan, atLegStart.tilt))
                    appendLine("Arrivo configurato: pan %.3f° · tilt %.3f°".format(to.pan, to.tilt))
                    append("Vettore richiesto: Δpan %+.3f° · Δtilt %+.3f°".format(to.pan - atLegStart.pan, to.tilt - atLegStart.tilt))
                },
            )

            val startedAt = System.nanoTime()
            val legDeadline = startedAt + (legSeconds * 1_000_000_000.0).toLong()
            var legElapsed = 0f
            while (legElapsed < motionSeconds) {
                if (!currentCoroutineContext().isActive) return
                legElapsed = ((System.nanoTime() - startedAt) / 1_000_000_000.0).toFloat()
                val t = (legElapsed / motionSeconds).coerceIn(0f, 1f)
                // Dopo una correzione fotografica la stima può non coincidere più con le vecchie
                // coordinate salvate: il tratto successivo parte dalla posizione realmente
                // raggiunta, altrimenti il primo tick annullerebbe la correzione appena fatta.
                val targetPan = Interpolation.position(atLegStart.pan, to.pan, t, sequence.interpolation, sequence.easingPeak)
                val targetTilt = Interpolation.position(atLegStart.tilt, to.tilt, t, sequence.interpolation, sequence.easingPeak)

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
            // Allineamento esatto sul waypoint di arrivo, per evitare derive cumulative.
            gimbal.driveTo(to.pan, to.tilt, stepSeconds)
            gimbal.stop()

            val estimatedArrivalJpeg = preview.logSnapshot(
                message = "RUN $traceId · PUNTO ${legIndex + 2} RAGGIUNTO DALLA STIMA · ${to.name}",
                detail = targetDetail(to, gimbal.position.value),
            )
            val aligned = visuallyAlign(
                traceId = traceId,
                target = to,
                phase = "PUNTO ${legIndex + 2} DURANTE IL VIDEO",
                initialJpeg = estimatedArrivalJpeg,
                deadlineNanos = legDeadline,
            )
            if (!aligned) {
                // A maggior ragione qui: la registrazione è già in corso da minuti. Fermarla
                // perché una miniatura non combacia butterebbe via tutta la ripresa fatta
                // fin qui, per un difetto che al massimo vale qualche pixel di inquadratura.
                log.warn(
                    "RUN $traceId · ${to.name} NON CONFERMATO DALL'IMMAGINE · PROSEGUO",
                    targetDetail(to, gimbal.position.value) +
                        "\nSi prosegue con le coordinate stimate: interrompere qui vorrebbe " +
                        "dire buttare via la ripresa già registrata.",
                )
            }

            // Se la correzione finisce in anticipo, resta fermo fino alla durata esatta del tratto.
            val remainingNanos = legDeadline - System.nanoTime()
            if (remainingNanos > 0L) delay(remainingNanos / 1_000_000L)
            elapsedTotal += legSeconds
        }

        gimbal.stop()
        val last = sequence.waypoints.last()
        val actualArrivalJpeg = preview.logSnapshot(
            message = "RUN $traceId · ARRIVO REALE · ${last.name}",
            detail = targetDetail(last, gimbal.position.value) +
                "\nIl gimbal è fermo; la registrazione verrà arrestata dopo il fermo finale.",
        )
        logImageVerification(traceId, last, actualArrivalJpeg, "PUNTO FINALE CONFERMATO")
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
        var taken = 0
        var missed = 0
        var stuck = 0
        val startedAtMs = System.currentTimeMillis()

        // L'anteprima resta accesa, e non è una svista.
        //
        // Spegnerla sembrava gratis: nessuno la guarda mentre il gimbal gira, e la camera
        // risparmierebbe un flusso HEVC da comprimere. Misurato sulla camera fa l'opposto: con
        // il flusso fermo la camera entra in SINGLE_SHOOTING al primo scatto e non ne esce più.
        // Sedici scatti, tre minuti e mezzo, zero file salvati; poi l'anteprima è ripartita e
        // un secondo dopo lo stato è tornato a riposo. La cattura di una foto passa dalla stessa
        // catena che tiene vivo il flusso: senza flusso, non si chiude.
        // Il flusso deve essere acceso prima del primo scatto: senza, la camera entra in
        // cattura e non ne esce, e la sequenza gira a vuoto dall'inizio alla fine.
        if (!preview.ensureRunningForCapture()) {
            log.warn(
                "Il flusso dell'anteprima non è partito",
                "Scatto lo stesso, ma se la camera non salva è questo il motivo.",
            )
        }

        log.info(
            "Modalità foto: $planned scatti, $shotsPerLeg per tratto",
            "Stima iniziale %s, alla cadenza di %.1f s per scatto.".format(
                formatSeconds(planned * NOMINAL_SHOT_SECONDS),
                NOMINAL_SHOT_SECONDS,
            ),
        )

        // Quanto dura la posa lo dice la camera: è l'unico momento in cui il gimbal deve
        // stare fermo davvero. Si chiede una volta, prima di cominciare, e vale per tutta la
        // sequenza — la scena non cambia da uno scatto all'altro di una panoramica.
        val exposure = commands.fetchStillExposure(CameraMode.FOTO)
        log.info(
            "Esposizione dichiarata: ${describeExposure(exposure)}",
            "È il dato di pre-scatto: dice quanto dura la posa prima che la posa cominci.",
        )

        val targets = photoTargets(sequence)
        val guardMs = exposureGuardMillis(exposure)

        // La camera tiene in pancia qualche scatto e li scrive con calma: aspettare che abbia
        // finito dopo *ogni* scatto vuol dire pagare cinque secondi di scrittura a ogni foto,
        // quando si possono pagare una volta ogni tre. Il ritmo veloce sfrutta il buffer: si
        // scatta, si protegge la posa, ci si sposta mentre lei scrive, e ci si ferma ad
        // aspettarla solo a buffer pieno. Quello prudente si ferma a ogni scatto.
        val fastPipeline = sequence.moveWhileSaving
        val depth = if (fastPipeline) CAMERA_SHOT_BUFFER else 1
        var inFlight = 0
        log.info(
            if (fastPipeline) "Ritmo veloce: uso il buffer della camera" else "Ritmo prudente: uno scatto alla volta",
            if (fastPipeline) {
                "Fino a $depth scatti in coda; posa protetta per $guardMs ms, poi il gimbal riparte."
            } else {
                "Aspetto che ogni scatto sia scritto prima di muovermi."
            },
        )

        // Quando la camera resta occupata oltre il tempo massimo, il riavvio del flusso è la
        // leva che la sblocca (misurato: un secondo dopo lo stato torna a riposo). Due volte
        // appesa senza recupero = sequenza interrotta: meglio fermarsi che scattare nel vuoto.
        suspend fun drainCamera() {
            _state.value = _state.value.copy(message = "Aspetto che la camera scriva…")
            if (commands.awaitCaptureIdle()) {
                stuck = 0
                return
            }
            stuck++
            log.warn(
                "La camera è rimasta occupata oltre il tempo massimo",
                "Riavvio il flusso dell'anteprima per sbloccarla.",
            )
            preview.restart()
            preview.ensureRunningForCapture()
            if (commands.awaitCaptureIdle()) return
            if (stuck >= MAX_STUCK_SHOTS) {
                throw IllegalStateException(
                    "La camera non chiude più le catture nemmeno riavviando il flusso. " +
                        "Sequenza interrotta invece di continuare a vuoto: spegni e riaccendi la camera.",
                )
            }
        }

        for ((index, target) in targets.withIndex()) {
            if (!currentCoroutineContext().isActive) return

            val done = taken + missed
            _state.value = _state.value.copy(
                phase = RunPhase.RUNNING,
                legIndex = target.legIndex,
                legProgress = target.legProgress,
                targetPan = target.pan,
                targetTilt = target.tilt,
                shotsTaken = taken,
                shotsPlanned = planned,
                elapsedSeconds = (System.currentTimeMillis() - startedAtMs) / 1000f,
                secondsRemaining = estimateRemaining(startedAtMs, done, planned),
                shotsMissed = missed,
                message = "In posizione per lo scatto ${done + 1}/$planned",
            )

            // Prima di muoversi verso uno scatto nuovo, a buffer pieno si aspetta la
            // camera: è l'unico punto in cui il ritmo veloce si ferma.
            if (inFlight >= depth) {
                drainCamera()
                inFlight = 0
            }

            // Dal secondo scatto in poi il gimbal è spesso già arrivato: si è mosso mentre la
            // camera salvava. Il richiamo resta perché è a vuoto se la posizione è già quella,
            // e non lo è se lo spostamento non era finito.
            approachShot(target, sequence)

            _state.value = _state.value.copy(message = "Scatto ${done + 1}/$planned")
            val fired = fireShot(done + 1, planned, target)
            if (fired == null) {
                missed++
                _state.value = _state.value.copy(
                    shotsMissed = missed,
                    secondsRemaining = estimateRemaining(startedAtMs, taken + missed, planned),
                )
                continue
            }
            inFlight++

            // La posa va protetta: è l'unico momento in cui il gimbal deve stare fermo.
            delay(guardMs)

            // Posa finita: la camera comprime e scrive per conto suo, e quel tempo si spende
            // andando verso lo scatto successivo invece di stare a guardare.
            val next = targets.getOrNull(index + 1)
            if (fastPipeline && next != null) {
                _state.value = _state.value.copy(
                    message = "Verso lo scatto ${done + 2}/$planned mentre la camera salva",
                )
                approachShot(next, sequence)
            }

            taken++
            _state.value = _state.value.copy(
                shotsTaken = taken,
                shotsMissed = missed,
                elapsedSeconds = (System.currentTimeMillis() - startedAtMs) / 1000f,
                secondsRemaining = estimateRemaining(startedAtMs, taken + missed, planned),
                // Solo gli scatti riusciti: un angolo senza foto sfaserebbe
                // l'accoppiamento fra angoli e file, e l'unione metterebbe ogni
                // fotogramma nel posto del successivo.
                shotAngles = _state.value.shotAngles +
                    ShotAngle(target.pan, target.tilt, fired.uri),
            )
        }

        // Gli ultimi scatti sono ancora nel buffer della camera: si aspettano qui, prima
        // che qualcuno vada a contare i file.
        if (inFlight > 0) drainCamera()

        val elapsed = (System.currentTimeMillis() - startedAtMs) / 1000f
        _state.value = _state.value.copy(elapsedSeconds = elapsed, secondsRemaining = 0f)
        if (missed > 0) {
            log.warn(
                "$taken scatti su $planned in ${formatSeconds(elapsed)}",
                "$missed non sono riusciti nemmeno riprovando: l'unione avrà dei buchi.",
            )
        } else {
            log.info(
                "$taken scatti su $planned in ${formatSeconds(elapsed)}",
                "%.1f s per scatto.".format(if (taken > 0) elapsed / taken else 0f),
            )
        }
    }

    /**
     * Manda lo scatto e aspetta solo che la camera lo accetti, con un secondo tentativo.
     *
     * Accettare e salvare sono due momenti diversi: la camera risponde in tre decimi di secondo,
     * poi resta occupata cinque secondi a comprimere e scrivere. Questa funzione si ferma al
     * primo momento — chi la chiama decide cosa fare del secondo — e la ragione è che nel mezzo
     * c'è la sola cosa che vincola il gimbal, la posa, e finita quella il gimbal è libero.
     *
     * Il secondo tentativo avviene sul posto, prima che ci si sia mossi: quando il comando non
     * ha risposta non c'è stato nessuno scatto, e riprovare da qui costa un secondo mentre un
     * buco in mezzo a una panoramica costa molto di più.
     */
    private suspend fun fireShot(number: Int, planned: Int, target: PhotoTarget): FiredShot? {
        repeat(SHOT_ATTEMPTS) { attempt ->
            if (!currentCoroutineContext().isActive) return null
            if (attempt > 0) {
                _state.value = _state.value.copy(message = "Riprovo lo scatto $number/$planned")
                log.warn("Scatto $number: riprovo")
                delay(SHOT_RETRY_DELAY_MS)
            }
            // La risposta 200 non dice niente: il verdetto vero è la notifica. Un rifiuto
            // (8201, di solito «occupata») qui non è la fine: si aspetta che la camera
            // torni libera e si riprova, perché uno scatto saltato è un buco nell'unione.
            val confirmed = commands.takePictureConfirmed()
                .getOrElse {
                    log.warn("Scatto $number non riuscito: ${it.message}")
                    null
                }
            when (val outcome = confirmed?.outcome) {
                is LunaCommands.ShotOutcome.Refused -> {
                    log.warn(
                        "Scatto $number rifiutato dalla camera (${outcome.reason()})",
                        "Aspetto che si liberi e riprovo.",
                    )
                    commands.awaitCaptureIdle()
                }

                is LunaCommands.ShotOutcome.Accepted, is LunaCommands.ShotOutcome.Silent -> {
                    log.info(
                        "Scatto $number/$planned a %.1f° / %.1f°".format(target.pan, target.tilt),
                        confirmed.uri?.let { uri -> "File: $uri" },
                    )
                    return FiredShot(confirmed.uri)
                }

                null -> Unit
            }
        }
        log.warn(
            "Scatto $number/$planned saltato",
            "A %.1f° / %.1f° la camera non ha risposto nemmeno al secondo tentativo: l'unione avrà un buco lì."
                .format(target.pan, target.tilt),
        )
        return null
    }

    /** Va in posizione e aspetta che l'inerzia si esaurisca. A destinazione raggiunta è a vuoto. */
    private suspend fun approachShot(target: PhotoTarget, sequence: TimelapseSequence) {
        gimbal.moveToPositionAtMaximum(target.pan, target.tilt)
            .getOrElse { throw IllegalStateException("Spostamento fotografico non riuscito: ${it.message}", it) }
        delay((sequence.settleSeconds * 1000).toLong().coerceAtLeast(0L))
    }

    /**
     * Le posizioni degli scatti, in ordine, con il tratto a cui appartengono.
     *
     * L'ultimo scatto di un tratto coincide con il primo del successivo e va contato una volta
     * sola: su una panoramica due scatti identici nello stesso punto complicano l'unione invece
     * di aiutarla. Distesi in un elenco unico perché ogni scatto deve sapere qual è il prossimo,
     * e attraverso due cicli annidati non si vede.
     */
    private fun photoTargets(sequence: TimelapseSequence): List<PhotoTarget> {
        val shotsPerLeg = sequence.effectiveShotsPerLeg()
        return buildList {
            for (legIndex in 0 until sequence.legCount) {
                val from = sequence.waypoints[legIndex]
                val to = sequence.waypoints[legIndex + 1]
                for (shot in 0 until shotsPerLeg) {
                    if (shot == 0 && legIndex > 0) continue
                    val t = shot.toFloat() / (shotsPerLeg - 1)
                    add(
                        PhotoTarget(
                            pan = Interpolation.position(from.pan, to.pan, t, sequence.interpolation, sequence.easingPeak),
                            tilt = Interpolation.position(from.tilt, to.tilt, t, sequence.interpolation, sequence.easingPeak),
                            legIndex = legIndex,
                            legProgress = t,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Quanto manca, sulla media di quello che è già successo.
     *
     * Prima del primo scatto non c'è media, e vale la cadenza nota della camera; dopo, il tempo
     * vero comprende già gli spostamenti e le attese, quindi è una stima migliore di qualunque
     * calcolo fatto sui gradi.
     */
    private fun estimateRemaining(startedAtMs: Long, done: Int, planned: Int): Float {
        val left = (planned - done).coerceAtLeast(0)
        if (left == 0) return 0f
        val perShot = if (done > 0) {
            (System.currentTimeMillis() - startedAtMs) / 1000f / done
        } else {
            NOMINAL_SHOT_SECONDS
        }
        return left * perShot
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
    private suspend fun visuallyAlign(
        traceId: String,
        target: Waypoint,
        phase: String,
        initialJpeg: ByteArray?,
        deadlineNanos: Long? = null,
    ): Boolean {
        var currentJpeg = initialJpeg
        if (target.generatedByPanoramaPlanner) {
            log.info(
                "RUN $traceId · POSIZIONE PANORAMA CALIBRATA · $phase",
                "Waypoint generato entro i fine corsa: uso le coordinate assolute del profilo e registro il frame reale nel log.",
                imageJpeg = currentJpeg,
            )
            return true
        }
        if (!settings.value.gimbal.visualWaypointCorrection) {
            log.info("RUN $traceId · CORREZIONE VISIVA DISATTIVATA · $phase")
            logImageVerification(traceId, target, currentJpeg, "$phase · SOLA VERIFICA")
            return true
        }

        repeat(MAX_VISUAL_ATTEMPTS) { attempt ->
            val verification = logImageVerification(
                traceId = traceId,
                target = target,
                actualJpeg = currentJpeg,
                phase = "$phase · TENTATIVO ${attempt + 1}",
            ) ?: return false

            if (verification.verdict == PositionVerdict.CORRECT) {
                log.info(
                    "RUN $traceId · ALLINEAMENTO VISIVO COMPLETATO · $phase",
                    "Tentativi: ${attempt + 1} · errore residuo %.1f px".format(verification.displacementPixels),
                )
                return true
            }

            val usable = verification.inlierMatches >= MIN_CORRECTION_INLIERS &&
                verification.confidence >= MIN_CORRECTION_CONFIDENCE &&
                verification.displacementPixels > 0f
            if (!usable) {
                log.warn(
                    "RUN $traceId · CORREZIONE VISIVA IMPOSSIBILE · $phase",
                    "I punti di controllo non danno una direzione abbastanza affidabile.",
                )
                return false
            }
            if (attempt == MAX_VISUAL_ATTEMPTS - 1) return false

            if (deadlineNanos != null) {
                val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (remainingMs < MIN_CORRECTION_REMAINING_MS) {
                    log.warn(
                        "RUN $traceId · TEMPO DI CORREZIONE ESAURITO · $phase",
                        "Restavano ${remainingMs.coerceAtLeast(0L)} ms del tratto.",
                    )
                    return false
                }
            }

            val profile = calibration.value.takeIf(GimbalCalibrationProfile::isValid)
            // Con un profilo valido non si presume più il verso: è quello misurato sulla camera.
            // Il comando deve produrre nell'immagine lo spostamento opposto all'errore rilevato.
            val panPulse = correctionAxis(
                errorPixels = verification.shiftX,
                measuredPositiveRate = profile?.imageRateAt(100f, panAxis = true),
                fallbackSignedError = verification.shiftX,
            )
            val tiltPulse = correctionAxis(
                errorPixels = verification.shiftY,
                measuredPositiveRate = profile?.imageRateAt(100f, panAxis = false),
                fallbackSignedError = -verification.shiftY,
            )
            val pulseMs = correctionPulseMs(verification, panPulse, tiltPulse, profile)
            _state.value = _state.value.copy(
                phase = RunPhase.RUNNING,
                message = "Correzione visiva ${target.name} · ${attempt + 1}/$MAX_VISUAL_ATTEMPTS",
            )
            log.info(
                "RUN $traceId · IMPULSO CORRETTIVO · $phase",
                "pan %+.3f · tilt %+.3f · %d ms".format(panPulse, tiltPulse, pulseMs),
            )
            gimbal.correctionPulse(panPulse, tiltPulse, pulseMs)
                .getOrElse { throw IllegalStateException("Impulso correttivo non inviato: ${it.message}", it) }
            delay(calibration.value.takeIf(GimbalCalibrationProfile::isValid)?.settleMs ?: VISUAL_SETTLE_MS)
            currentJpeg = preview.captureThumbnailJpeg()
        }
        log.warn("RUN $traceId · ALLINEAMENTO VISIVO NON RIUSCITO · $phase")
        return false
    }

    private fun logImageVerification(
        traceId: String,
        target: Waypoint,
        actualJpeg: ByteArray?,
        phase: String,
    ): ImageVerification? {
        val referenceJpeg = target.previewJpeg()
        val verification = WaypointImageVerifier.verify(referenceJpeg, actualJpeg)
        if (verification == null) {
            log.warn(
                "RUN $traceId · VERIFICA VISIVA $phase NON DISPONIBILE",
                "Manca la miniatura del waypoint o il frame reale. Rimemorizza il punto con l'anteprima attiva.",
            )
            return null
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
        return verification
    }

    /**
     * Quanto tempo del tratto si tiene da parte per l'allineamento visivo.
     *
     * Zero quando si sta registrando un video, e non e' un'ottimizzazione: la modalita' Video
     * promette «durata reale = durata della sequenza». Tenendo da parte il 12% del tratto, il
     * movimento finiva prima — trenta secondi chiesti, ventisei e mezzo di movimento e poi
     * l'inquadratura ferma — e se il controllo passava al primo colpo, come succede quasi
     * sempre, quel tempo non veniva nemmeno usato: il tratto durava davvero ventisei secondi e
     * mezzo. In un video una coda immobile di tre secondi e mezzo si vede eccome.
     *
     * Il controllo continua a farsi, ma **dopo** essere arrivati invece che al posto dell'ultimo
     * pezzo di movimento: quando non c'e' niente da correggere costa quaranta millisecondi, e
     * quando c'e' qualcosa da correggere e' meglio uno scarto a fine tratto che una fermata in
     * mezzo alla panoramica.
     *
     * Nelle modalita' a scatti la riserva resta: li' il gimbal si ferma comunque a ogni punto,
     * quindi il tempo dell'allineamento non toglie niente a nessuno.
     */
    private fun visualCorrectionBudget(
        legSeconds: Float,
        target: Waypoint,
        mode: ShootingMode,
    ): Float {
        if (mode == ShootingMode.VIDEO) return 0f
        if (!settings.value.gimbal.visualWaypointCorrection || target.previewJpegBase64 == null) return 0f
        val desired = max(MIN_VISUAL_BUDGET_SECONDS, legSeconds * VISUAL_BUDGET_FRACTION)
        return min(desired, MAX_VISUAL_BUDGET_SECONDS)
            .coerceAtMost((legSeconds - MIN_VISUAL_MOTION_SECONDS).coerceAtLeast(0f))
    }

    private fun correctionAxis(
        errorPixels: Float,
        measuredPositiveRate: Float?,
        fallbackSignedError: Float,
    ): Float {
        if (abs(errorPixels) <= CORRECTION_DEAD_ZONE_PX) return 0f
        val magnitude = (abs(errorPixels) / CORRECTION_FULL_SCALE_PX)
            .coerceIn(MIN_CORRECTION_SPEED, MAX_CORRECTION_SPEED)
        val signedCommand = if (measuredPositiveRate != null && abs(measuredPositiveRate) >= GimbalCalibrationProfile.MIN_RATE) {
            -errorPixels / measuredPositiveRate
        } else fallbackSignedError
        return magnitude * sign(signedCommand)
    }

    private fun correctionPulseMs(
        verification: ImageVerification,
        panPulse: Float,
        tiltPulse: Float,
        profile: GimbalCalibrationProfile?,
    ): Long {
        if (profile != null) {
            val panRate = abs(profile.imageRateAt(abs(panPulse) * 100f, panAxis = true))
            val tiltRate = abs(profile.imageRateAt(abs(tiltPulse) * 100f, panAxis = false))
            val panMs = if (abs(panPulse) > 0f && panRate >= GimbalCalibrationProfile.MIN_RATE) {
                abs(verification.shiftX) / panRate * 1000f
            } else 0f
            val tiltMs = if (abs(tiltPulse) > 0f && tiltRate >= GimbalCalibrationProfile.MIN_RATE) {
                abs(verification.shiftY) / tiltRate * 1000f
            } else 0f
            return max(panMs, tiltMs).toLong().coerceIn(MIN_CORRECTION_PULSE_MS, MAX_CORRECTION_PULSE_MS)
        }
        return (BASE_CORRECTION_PULSE_MS + verification.displacementPixels * CORRECTION_MS_PER_PIXEL)
            .toLong().coerceIn(MIN_CORRECTION_PULSE_MS, MAX_CORRECTION_PULSE_MS)
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
        /**
         * Il ritmo che la camera regge davvero: otto scatti in venti secondi.
         *
         * È una misura fatta sulla camera, non una scelta. Serve solo a dire quanto manca prima
         * che ci sia uno scatto vero su cui fare la media: il ritmo effettivo lo detta la camera,
         * che viene aspettata scatto per scatto.
         */
        const val NOMINAL_SHOT_SECONDS = 2.5f

        /** Uno scatto perso lascia un buco nell'unione: vale un secondo tentativo, non di più. */
        const val SHOT_ATTEMPTS = 2

        /**
         * Quante volte di fila la camera può restare occupata prima di fermare tutto.
         *
         * Non è prudenza eccessiva: quando questa camera smette di chiudere le catture non
         * ricomincia da sola, e la sequenza continua a scattare nel vuoto. Sedici scatti, tre
         * minuti e mezzo, zero file — e non se ne accorge nessuno finché non è finita.
         */
        const val MAX_STUCK_SHOTS = 2

        /**
         * Quanti scatti la camera tiene in coda mentre scrive. Misurato da chi la usa: quattro,
         * poi bisogna aspettare. Qui tre, per non ballare mai sul bordo.
         */
        const val CAMERA_SHOT_BUFFER = 3
        const val SHOT_RETRY_DELAY_MS = 700L

        const val MOVE_TICK_HZ = 10
        const val MIN_APPROACH_SECONDS = 1f
        const val PRE_RECORD_SETTLE_MS = 500L
        const val MAX_VISUAL_ATTEMPTS = 5
        /**
         * Quanti punti concordi servono per fidarsi della direzione della correzione.
         *
         * Erano cinque, cioè lo stesso numero che serve per avere delle *corrispondenze
         * candidate*: e siccome il consenso geometrico ne scarta sempre qualcuna, con cinque
         * candidati il consenso non poteva arrivarci mai. Tre punti che concordano sulla
         * stessa traslazione entro pochi pixel non sono una coincidenza — e la confidenza
         * minima resta lì a fare il secondo controllo.
         */
        const val MIN_CORRECTION_INLIERS = 3
        const val MIN_CORRECTION_CONFIDENCE = 0.30f
        const val MIN_CORRECTION_REMAINING_MS = 420L
        const val VISUAL_SETTLE_MS = 260L
        const val MIN_VISUAL_MOTION_SECONDS = 0.5f
        const val MIN_VISUAL_BUDGET_SECONDS = 2f
        const val MAX_VISUAL_BUDGET_SECONDS = 4f
        const val VISUAL_BUDGET_FRACTION = 0.12f
        const val CORRECTION_DEAD_ZONE_PX = 2.5f
        const val CORRECTION_FULL_SCALE_PX = 46f
        const val MIN_CORRECTION_SPEED = 0.08f
        const val MAX_CORRECTION_SPEED = 0.32f
        const val BASE_CORRECTION_PULSE_MS = 140f
        const val CORRECTION_MS_PER_PIXEL = 4.5f
        const val MIN_CORRECTION_PULSE_MS = 160L
        const val MAX_CORRECTION_PULSE_MS = 600L
    }
}

/** Secondi in forma leggibile: sotto il minuto i secondi, sopra minuti e secondi. */
private fun formatSeconds(seconds: Float): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    return if (total < 60) "${total}s" else "%d:%02d".format(total / 60, total % 60)
}
