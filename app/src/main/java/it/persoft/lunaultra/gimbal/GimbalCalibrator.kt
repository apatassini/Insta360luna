package it.persoft.lunaultra.gimbal

import android.graphics.BitmapFactory
import it.persoft.lunaultra.data.GimbalCalibrationBuilder
import it.persoft.lunaultra.data.GimbalAxisLimits
import it.persoft.lunaultra.data.GimbalCalibrationProfile
import it.persoft.lunaultra.data.GimbalCalibrationSample
import it.persoft.lunaultra.data.JsonFileStore
import it.persoft.lunaultra.diagnostics.ImageVerification
import it.persoft.lunaultra.diagnostics.WaypointImageVerifier
import it.persoft.lunaultra.timelapse.LensFieldOfView
import it.persoft.lunaultra.timelapse.LunaOptics
import it.persoft.lunaultra.timelapse.PhotoFrameAspect
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

    /** Zoom in uso: il campo visivo dipende da quello, e da lì i pixel diventano gradi. */
    private var cameraZoomScale: Int = 1

    fun start(cameraModel: String, firmware: String, zoomScale: Int = 1) {
        cameraZoomScale = zoomScale
        if (job?.isActive == true) return
        job = scope.launch {
            runCalibration(cameraModel, firmware)
        }
    }

    fun cancel() {
        job?.cancel(CancellationException("Calibrazione interrotta dall'utente"))
    }

    private suspend fun runCalibration(cameraModel: String, firmware: String) {
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

            // La curva si misura col cronometro contro i fine corsa, non con le immagini:
            // due fatti — il limite che la camera annuncia e i gradi che lo separano dallo
            // zero — invece di una scommessa su cosa c'è davanti all'obiettivo.
            val panCurve = measureCurveByEndstop(
                axis = GimbalCalibrationSample.AXIS_PAN,
                limits = panLimits,
                phaseStartPercent = 30,
                phaseEndPercent = 60,
            )
            val tiltCurve = measureCurveByEndstop(
                axis = GimbalCalibrationSample.AXIS_TILT,
                limits = tiltLimits,
                phaseStartPercent = 60,
                phaseEndPercent = 88,
            )
            returnHome("Ritorno all'inquadratura di partenza dopo la curva", 88, 90)

            val profile = GimbalCalibrationBuilder.buildFromDegrees(
                panCurve = panCurve,
                tiltCurve = tiltCurve,
                cameraModel = cameraModel,
                firmware = firmware,
                panLimits = panLimits,
                tiltLimits = tiltLimits,
            )
            if (!profile.isValid) {
                throw IllegalStateException(
                    "${profile.invalidReason ?: "profilo incompleto"} " +
                        "(${profile.responsePoints.size} intensità misurate su ${INTENSITY_PERCENTAGES.size})",
                )
            }
            // Niente fase di validazione contro i fine corsa: la curva *nasce* da lì, quindi
            // rimisurare la stessa cosa sarebbe solo tempo. La verifica che resta è quella che
            // dice qualcosa di nuovo — l'andata e ritorno su angoli noti.
            val corrected = profile
            store.update { corrected }

            // La prova che dice davvero se il modello sa muoversi: andata e ritorno su angoli
            // noti, con le foto della stessa posizione a confronto. Viene dopo il salvataggio
            // perché è una verifica del profilo, non una condizione per averlo.
            val roundTrips = runCatching { runRoundTrips(corrected, cameraZoomScale) }.getOrElse {
                log.warn("CALIBRAZIONE · ANDATA E RITORNO NON COMPLETATA", it.message)
                emptyList()
            }
            logRoundTripSummary(roundTrips)
            // La validazione lavora sullo zero hardware, che è dove guarda il corpo camera:
            // finirla lì significherebbe lasciare la camera puntata dove non serve. Il profilo
            // ora è salvato, quindi il ritorno usa la curva appena misurata.
            returnHome("Ritorno all'inquadratura di partenza", 99, 100, byReplay = false)
            log.info(
                "CALIBRAZIONE GIMBAL · COMPLETATA",
                buildString {
                    appendLine("Curva misurata a cronometro sui fine corsa, senza immagini.")
                    corrected.responsePoints.forEach { point ->
                        appendLine(
                            "%3d%%: orizzontale %.2f °/s · verticale %.2f °/s".format(
                                point.intensityPercent,
                                point.panDegreesPerSecond,
                                point.tiltDegreesPerSecond,
                            ),
                        )
                    }
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
     * La curva misurata col cronometro contro i fine corsa. Nessuna immagine.
     *
     * Il principio è che il fine corsa è un fatto: la camera lo annuncia sul codice 8302, e la
     * corsa che lo separa dallo zero è nota. Quindi si parte dallo zero vero — ricentraggio
     * hardware, non «vicino allo zero» — si va verso il fine corsa **corto** contando il tempo
     * di comando, e alla fine gradi diviso secondi dà i gradi al secondo di quell'intensità.
     *
     * È l'unico modo che non dipende da cosa c'è davanti all'obiettivo. Intorno a una camera
     * non sempre c'è qualcosa di riconoscibile: una parete uniforme non ha punti da seguire, e
     * una a motivo ripetuto ne ha troppi e tutti uguali. Ogni misura basata sulle immagini è
     * una scommessa su come è fatta la stanza; questa no.
     *
     * Le intensità basse non arriverebbero mai al fine corsa in un tempo ragionevole — all'1%
     * ci vorrebbero minuti — e allora si misura per differenza: si corre a quell'intensità per
     * un tempo fisso, poi si copre il resto a un'intensità già misurata cronometrando. Quanto
     * resta dice quanto si era percorso. Sempre due fatti, nessuna immagine.
     *
     * Un solo verso per asse basta: il motore è lo stesso e la corsa corta è più veloce da
     * percorrere. L'assunto è che salire costi quanto scendere — ragionevole su un gimbal
     * stabilizzato, e comunque la prova di andata e ritorno alla fine lo smentirebbe.
     */
    private suspend fun measureCurveByEndstop(
        axis: String,
        limits: GimbalAxisLimits,
        phaseStartPercent: Int,
        phaseEndPercent: Int,
    ): List<Pair<Int, Float>> {
        val panAxis = axis == GimbalCalibrationSample.AXIS_PAN
        // Il verso corto: meno strada da fare a ogni misura, e sono tante.
        val towardMinimum = abs(limits.minimumDeg) <= abs(limits.maximumDeg)
        val direction = if (towardMinimum) -1f else 1f
        val travelDeg = abs(if (towardMinimum) limits.minimumDeg else limits.maximumDeg)
        val measured = mutableListOf<Pair<Int, Float>>()

        log.info(
            "CALIBRAZIONE · CURVA SUL FINE CORSA ${axisLabel(axis).uppercase()}",
            "Dallo zero al fine corsa ${directionLabel(axis, direction)}: %.0f° noti. Cronometro il comando, niente immagini."
                .format(travelDeg),
        )

        var referenceRate = 0f
        INTENSITY_PERCENTAGES.reversed().forEachIndexed { index, intensity ->
            val done = index + 1
            _state.value = _state.value.copy(
                phaseLabel = "Curva sul fine corsa · ${axisLabel(axis)}",
                overallPercent = phaseStartPercent +
                    (phaseEndPercent - phaseStartPercent) * done / INTENSITY_PERCENTAGES.size,
                axisLabel = axisLabel(axis),
                directionLabel = directionLabel(axis, direction),
                intensityPercent = intensity,
                message = "$intensity% · cronometro fino al fine corsa",
                verificationLabel = "MISURA A CRONOMETRO · NESSUNA IMMAGINE",
            )
            zeroByHardware("Zero prima del $intensity% ${axisLabel(axis)}")

            val rate = if (referenceRate > 0f && intensity <= SLOW_INTENSITY_THRESHOLD) {
                measureSlowByRemainder(axis, direction, intensity, travelDeg, referenceRate)
            } else {
                measureDirectToEndstop(axis, direction, intensity, travelDeg)
            }
            if (rate > 0f) {
                measured += intensity to rate
                if (intensity == REFERENCE_INTENSITY_PERCENT || referenceRate <= 0f) referenceRate = rate
                log.info(
                    "CALIBRAZIONE · $intensity% ${axisLabel(axis).uppercase()}",
                    "%.2f °/s misurati sul fine corsa".format(rate),
                )
            } else {
                log.warn(
                    "CALIBRAZIONE · $intensity% ${axisLabel(axis).uppercase()} NON MISURATO",
                    "Il fine corsa non è arrivato entro il tempo di sicurezza.",
                )
            }
        }
        zeroByHardware("Zero dopo la curva ${axisLabel(axis)}")
        return measured
    }

    /** Zero vero: ci si avvicina e poi si ricentra, perché «vicino allo zero» non è lo zero. */
    private suspend fun zeroByHardware(label: String) {
        gimbal.recenter().getOrElse { throw IllegalStateException("Ricentraggio non riuscito: ${it.message}", it) }
        delay(HARDWARE_ZERO_SETTLE_MS)
        gimbal.setEstimated(0f, 0f)
        _state.value = _state.value.copy(theoreticalPan = 0f, theoreticalTilt = 0f, message = label)
    }

    /** Corre fino al fine corsa contando il comando: gradi noti diviso secondi comandati. */
    private suspend fun measureDirectToEndstop(
        axis: String,
        direction: Float,
        intensityPercent: Int,
        travelDeg: Float,
    ): Float {
        val panAxis = axis == GimbalCalibrationSample.AXIS_PAN
        var commandedMs = 0L
        while (commandedMs < MAX_CURVE_RUN_MS) {
            val mark = limitMonitor.mark()
            calPulse(
                panPercent = if (panAxis) direction * intensityPercent / 100f else 0f,
                tiltPercent = if (panAxis) 0f else direction * intensityPercent / 100f,
                durationMs = CURVE_PULSE_MS,
                record = false,
            ).getOrElse { throw IllegalStateException("Corsa di misura non riuscita: ${it.message}", it) }
            if (limitMonitor.reached(axis, mark)) {
                // L'ultimo impulso ha mosso solo in parte prima di appoggiarsi al limite.
                val total = (commandedMs + CURVE_PULSE_MS / 2) / 1000f
                return if (total > 0f) travelDeg / total else 0f
            }
            commandedMs += CURVE_PULSE_MS
            _state.value = _state.value.copy(pulseMs = commandedMs)
        }
        return 0f
    }

    /**
     * Intensità lente, misurate per differenza.
     *
     * Si corre per un tempo fisso a quell'intensità, poi si copre il resto a un'intensità già
     * misurata cronometrando: quanto resta dice quanto si era percorso. All'1% arrivare al
     * fine corsa richiederebbe minuti, e dodici intensità di minuti sono una calibrazione che
     * nessuno finisce.
     */
    private suspend fun measureSlowByRemainder(
        axis: String,
        direction: Float,
        intensityPercent: Int,
        travelDeg: Float,
        referenceRate: Float,
    ): Float {
        val panAxis = axis == GimbalCalibrationSample.AXIS_PAN
        var slowMs = 0L
        while (slowMs < SLOW_RUN_MS) {
            val mark = limitMonitor.mark()
            calPulse(
                panPercent = if (panAxis) direction * intensityPercent / 100f else 0f,
                tiltPercent = if (panAxis) 0f else direction * intensityPercent / 100f,
                durationMs = CURVE_PULSE_MS,
                record = false,
            ).getOrElse { throw IllegalStateException("Corsa lenta non riuscita: ${it.message}", it) }
            slowMs += CURVE_PULSE_MS
            // Se il fine corsa arriva già qui, questa intensità è misurabile per intero.
            if (limitMonitor.reached(axis, mark)) {
                val total = (slowMs - CURVE_PULSE_MS / 2) / 1000f
                return if (total > 0f) travelDeg / total else 0f
            }
        }

        var fastMs = 0L
        while (fastMs < MAX_CURVE_RUN_MS) {
            val mark = limitMonitor.mark()
            calPulse(
                panPercent = if (panAxis) direction * REFERENCE_INTENSITY_PERCENT / 100f else 0f,
                tiltPercent = if (panAxis) 0f else direction * REFERENCE_INTENSITY_PERCENT / 100f,
                durationMs = CURVE_PULSE_MS,
                record = false,
            ).getOrElse { throw IllegalStateException("Corsa di riferimento non riuscita: ${it.message}", it) }
            if (limitMonitor.reached(axis, mark)) {
                val fastSeconds = (fastMs + CURVE_PULSE_MS / 2) / 1000f
                val coveredByFast = referenceRate * fastSeconds
                val coveredBySlow = travelDeg - coveredByFast
                val slowSeconds = slowMs / 1000f
                return if (coveredBySlow > 0f && slowSeconds > 0f) coveredBySlow / slowSeconds else 0f
            }
            fastMs += CURVE_PULSE_MS
        }
        return 0f
    }




    /**
     * Una tappa della prova di andata e ritorno: dove andare, e la foto di com'era là.
     *
     * Le foto sono il metro. Il confronto che conta non è mai fra due inquadrature diverse —
     * quelle *devono* differire, la camera si è mossa — ma fra la stessa posizione vista due
     * volte a distanza di un giro. Se combaciano, il modello ci sa tornare; se no, lo scarto
     * in pixel dice di quanto ha sbagliato, e i pixel si convertono in gradi perché il campo
     * visivo dell'obiettivo è noto.
     */
    private data class RoundTripStop(
        val label: String,
        val axis: String,
        val degrees: Float,
        var reference: ByteArray? = null,
    )

    /**
     * Andata e ritorno su angoli noti, con le foto a fare da metro.
     *
     * È la prova che dice se il modello sa muoversi: si va a +45°, si fotografa, si torna a
     * casa e si confronta con la foto di casa — deve combaciare — poi si torna a +45° e si
     * confronta con la foto di +45° — deve combaciare anche quella. Ogni scarto è un errore
     * vero, misurato in gradi, non una percentuale fra due immagini che non c'entrano nulla
     * fra loro.
     *
     * Il campo visivo dell'obiettivo converte i pixel in gradi: 256 pixel di miniatura coprono
     * l'intero fotogramma, e di quel fotogramma si conosce l'apertura angolare.
     */
    private suspend fun runRoundTrips(
        profile: GimbalCalibrationProfile,
        zoomScale: Int,
    ): List<RoundTripResult> {
        val fov = LunaOptics.fieldOfView(zoomScale, PhotoFrameAspect.FOUR_THREE)
        val stops = buildList {
            listOf(ROUND_TRIP_ARC_DEG, -ROUND_TRIP_ARC_DEG).forEach { deg ->
                add(RoundTripStop(directionLabel(GimbalCalibrationSample.AXIS_PAN, deg), GimbalCalibrationSample.AXIS_PAN, deg))
            }
            listOf(ROUND_TRIP_TILT_UP_DEG, -ROUND_TRIP_TILT_DOWN_DEG).forEach { deg ->
                add(RoundTripStop(directionLabel(GimbalCalibrationSample.AXIS_TILT, deg), GimbalCalibrationSample.AXIS_TILT, deg))
            }
        }
        val results = mutableListOf<RoundTripResult>()
        val homeReference = homeFrame ?: awaitFrame("Attendo l'immagine di casa").also { homeFrame = it }

        stops.forEachIndexed { index, stop ->
            _state.value = _state.value.copy(
                phaseLabel = "Andata e ritorno · ${stop.label}",
                overallPercent = 90 + index,
                axisLabel = axisLabel(stop.axis),
                directionLabel = stop.label,
                message = "Vado a %.0f° e torno: le foto devono combaciare".format(stop.degrees),
            )

            // Andata: il modello crede di percorrere questi gradi. Qui si fotografa e basta,
            // perché non c'è ancora niente con cui confrontare.
            moveByModel(profile, stop.axis, stop.degrees)
            stop.reference = awaitFrame("Fotografo la tappa ${stop.label}")

            // Ritorno a casa: la foto deve tornare quella di casa. Questo scarto è l'errore
            // del giro completo, e non dipende da cosa c'è nell'inquadratura.
            moveByModel(profile, stop.axis, -stop.degrees)
            val backHome = WaypointImageVerifier.verify(homeReference, awaitFrame("Verifico il ritorno a casa"))

            // Seconda andata: la foto deve tornare quella della tappa. Se combacia, il modello
            // ci sa tornare; se no, di quanto sbaglia lo dicono i pixel.
            moveByModel(profile, stop.axis, stop.degrees)
            val backAtStop = WaypointImageVerifier.verify(stop.reference, awaitFrame("Verifico il ritorno alla tappa"))

            // E si rientra, per lasciare la camera dove l'utente l'ha messa.
            moveByModel(profile, stop.axis, -stop.degrees)

            val panAxis = stop.axis == GimbalCalibrationSample.AXIS_PAN
            val degreesPerPixel = degreesPerPixel(stop.reference, fov, panAxis)
            val result = RoundTripResult(
                label = stop.label,
                axis = stop.axis,
                degrees = stop.degrees,
                homeErrorDeg = backHome?.displacementPixels?.times(degreesPerPixel),
                stopErrorDeg = backAtStop?.displacementPixels?.times(degreesPerPixel),
                homeComparable = backHome != null && backHome.confidence >= ZERO_MIN_CONFIDENCE,
                stopComparable = backAtStop != null && backAtStop.confidence >= ZERO_MIN_CONFIDENCE,
            )
            results += result
            _state.value = _state.value.copy(
                verificationLabel = result.verdict(),
                shiftX = backAtStop?.shiftX ?: 0f,
                shiftY = backAtStop?.shiftY ?: 0f,
                annotatedJpeg = WaypointImageVerifier.annotatedCurrentJpeg(stop.reference, backAtStop),
            )
            log.info(
                "CALIBRAZIONE · ANDATA E RITORNO ${stop.label.uppercase()}",
                buildString {
                    appendLine("Spostamento comandato: %.0f° · un pixel di miniatura vale %.3f°".format(stop.degrees, degreesPerPixel))
                    appendLine(
                        "Tornato a casa: " + (result.homeErrorDeg
                            ?.let { "scarto %.2f°".format(it) }
                            ?: "immagine non confrontabile"),
                    )
                    append(
                        "Tornato alla tappa: " + (result.stopErrorDeg
                            ?.let { "scarto %.2f°".format(it) }
                            ?: "immagine non confrontabile"),
                    )
                },
                imageJpeg = WaypointImageVerifier.annotatedCurrentJpeg(stop.reference, backAtStop),
            )
        }
        return results
    }

    /** Riepilogo della prova, in gradi di errore: il numero che conta. */
    private fun logRoundTripSummary(results: List<RoundTripResult>) {
        if (results.isEmpty()) return
        val worst = results.mapNotNull(RoundTripResult::worstErrorDeg).maxOrNull()
        val comparable = results.count { it.homeComparable || it.stopComparable }
        val summary = buildString {
            results.forEach { r ->
                appendLine(
                    "%-10s comandati %+.0f° · ritorno a casa %s · ritorno alla tappa %s".format(
                        r.label,
                        r.degrees,
                        r.homeErrorDeg?.let { "%.2f°".format(it) } ?: "—",
                        r.stopErrorDeg?.let { "%.2f°".format(it) } ?: "—",
                    ),
                )
            }
            append(
                when {
                    comparable == 0 ->
                        "Nessuna tappa confrontabile: la scena non permette di misurare il ritorno."
                    worst == null -> "Nessuno scarto misurabile."
                    worst <= ROUND_TRIP_GOOD_DEG ->
                        "Scarto massimo %.2f°: il modello torna dove dice di tornare.".format(worst)
                    else ->
                        "Scarto massimo %.2f°: il modello sbaglia di questo, ed è la misura che conta per i waypoint."
                            .format(worst)
                },
            )
        }
        if (worst != null && worst > ROUND_TRIP_GOOD_DEG) {
            log.warn("CALIBRAZIONE · ANDATA E RITORNO", summary)
        } else {
            log.info("CALIBRAZIONE · ANDATA E RITORNO", summary)
        }
    }

    /**
     * Quanti gradi vale un pixel della miniatura, sull'asse richiesto.
     *
     * Le dimensioni si leggono dall'immagine invece di darle per scontate: la miniatura ha la
     * larghezza fissa ma l'altezza segue il rapporto dello stream, che cambia con la modalità
     * della camera. Usare un'altezza presunta sbaglierebbe i gradi verticali di un terzo, e un
     * terzo su 45° sono quindici gradi di errore raccontati come misura.
     */
    private fun degreesPerPixel(reference: ByteArray?, fov: LensFieldOfView, panAxis: Boolean): Float {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (reference != null) BitmapFactory.decodeByteArray(reference, 0, reference.size, options)
        val span = if (panAxis) options.outWidth else options.outHeight
        if (span <= 0) return 0f
        return (if (panAxis) fov.horizontalDegrees else fov.verticalDegrees) / span
    }

    /** Muove dei gradi richiesti secondo il modello, senza fermarsi ai limiti. */
    private suspend fun moveByModel(profile: GimbalCalibrationProfile, axis: String, degrees: Float) {
        val panAxis = axis == GimbalCalibrationSample.AXIS_PAN
        val rate = profile.angularRateAt(ROUND_TRIP_INTENSITY_PERCENT.toFloat(), panAxis)
        if (rate <= 0f) throw IllegalStateException("Velocità ${axisLabel(axis)} non calcolabile")
        val direction = kotlin.math.sign(degrees)
        var remainingMs = (abs(degrees) / rate * 1000f).toLong()
        while (remainingMs > 0L) {
            val step = min(remainingMs, CURVE_PULSE_MS)
            val mark = limitMonitor.mark()
            calPulse(
                panPercent = if (panAxis) direction * ROUND_TRIP_INTENSITY_PERCENT / 100f else 0f,
                tiltPercent = if (panAxis) 0f else direction * ROUND_TRIP_INTENSITY_PERCENT / 100f,
                durationMs = step,
                record = false,
            ).getOrElse { throw IllegalStateException("Spostamento non riuscito: ${it.message}", it) }
            if (limitMonitor.reached(axis, mark)) break
            remainingMs -= step
        }
        gimbal.setEstimated(
            pan = gimbal.position.value.pan + if (panAxis) degrees else 0f,
            tilt = gimbal.position.value.tilt + if (panAxis) 0f else degrees,
        )
        delay(HOME_SETTLE_MS)
    }

    /** Esito di una tappa: gli scarti in gradi, che sono errori veri e non punteggi. */
    data class RoundTripResult(
        val label: String,
        val axis: String,
        val degrees: Float,
        val homeErrorDeg: Float?,
        val stopErrorDeg: Float?,
        val homeComparable: Boolean,
        val stopComparable: Boolean,
    ) {
        val worstErrorDeg: Float? get() = listOfNotNull(homeErrorDeg, stopErrorDeg).maxOrNull()

        fun verdict(): String = when {
            !homeComparable && !stopComparable -> "${label.uppercase()} · SCENA NON CONFRONTABILE"
            (worstErrorDeg ?: 0f) <= GOOD_RETURN_DEG -> "${label.uppercase()} · RITORNO ESATTO"
            else -> "${label.uppercase()} · SCARTO %.1f°".format(worstErrorDeg ?: 0f)
        }

        companion object {
            /** Sotto questo scarto il ritorno si considera esatto: è la tolleranza dei waypoint. */
            const val GOOD_RETURN_DEG = 2f
        }
    }


    /**
     * Applica al profilo la scala misurata sui fine corsa.
     *
     * Ogni asse ha due misure, una per verso, e si prende la media: un verso solo porterebbe
     * dentro l'asimmetria del singolo finecorsa. Una correzione fuori da un intervallo
     * ragionevole non è una taratura ma un sintomo — gimbal ostacolato, segnale di limite
     * sbagliato, corsa diversa da quella dichiarata — e allora è giusto fermarsi.
     */








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
        // Metà arco per lato, più un margine: la spazzata parte da casa e si allontana di
        // un arco in un verso solo per volta, non di un arco per parte.
        val margin = TARGET_RESPONSE_ARC_DEG / 2f + HOME_LIMIT_MARGIN_DEG
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




        // Andata e ritorno: quanto ci si allontana, con che intensità, e quale scarto è buono.
        const val ROUND_TRIP_ARC_DEG = 45f
        const val ROUND_TRIP_TILT_UP_DEG = 45f
        const val ROUND_TRIP_TILT_DOWN_DEG = 30f
        const val ROUND_TRIP_INTENSITY_PERCENT = 30
        const val ROUND_TRIP_GOOD_DEG = RoundTripResult.GOOD_RETURN_DEG

        // Curva misurata sul fine corsa: impulsi, tempi di sicurezza, intensità di riferimento.
        const val CURVE_PULSE_MS = 400L
        const val MAX_CURVE_RUN_MS = 40_000L
        const val SLOW_RUN_MS = 12_000L
        const val SLOW_INTENSITY_THRESHOLD = 10
        const val REFERENCE_INTENSITY_PERCENT = 40
        const val HARDWARE_ZERO_SETTLE_MS = 3_000L


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
