package it.persoft.lunaultra.gimbal

import android.graphics.BitmapFactory
import it.persoft.lunaultra.data.GimbalCalibrationBuilder
import it.persoft.lunaultra.data.GimbalAxisLimits
import it.persoft.lunaultra.protocol.ProtoField
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

    // Cosa sta rilevando adesso. Fino a ieri il pannello mostrava punti di controllo e
    // percentuali di somiglianza fra immagini, che erano il metodo di prima: adesso la curva si
    // conta a impulsi contro un segnale hardware, e quello che si guarda deve essere quello.
    /** Vero mentre si sta correndo verso un fine corsa aspettando il segnale 8302. */
    val seekingEndstop: Boolean = false,
    /** Vero nell'istante in cui il limite si è fatto sentire. */
    val endstopReached: Boolean = false,
    /** Quanti impulsi si sono dati in questa corsa, e quanti al massimo prima di arrendersi. */
    val pulsesInRun: Int = 0,
    val maxPulsesInRun: Int = 0,
    /** Quante volte la camera ha annunciato un limite da quando la calibrazione è partita. */
    val endstopSignals: Int = 0,
    /** Vero nelle fasi che guardano davvero i fotogrammi: la ricerca dei limiti e i collaudi. */
    val usesImages: Boolean = false,
    /** La curva mentre si costruisce, un punto per intensità e per asse. */
    val curve: List<CurveReading> = emptyList(),
    /** Quello che la procedura ha accertato finora, in ordine di scoperta. */
    val findings: List<Finding> = emptyList(),
) {
    val progress: Float get() = overallPercent.coerceIn(0, 100) / 100f

    /** Frazione del tetto di sicurezza già consumata dalla corsa in atto. */
    val runProgress: Float
        get() = if (maxPulsesInRun <= 0) 0f else (pulsesInRun.toFloat() / maxPulsesInRun).coerceIn(0f, 1f)

    companion object {
        const val TOTAL_STEPS = 12 * 2 * 2
    }
}

/** Un punto della curva appena misurato, con entrambe le unità che servono a capirlo. */
data class CurveReading(
    val intensityPercent: Int,
    val panAxis: Boolean,
    val degreesPerSecond: Float,
    val degreesPerPulse: Float,
    val pulses: Int,
)

/**
 * Un fatto accertato dalla procedura, da mostrare mentre succede.
 *
 * Una calibrazione lunga sette minuti in cui lo schermo dice solo «34%» chiede fiducia senza
 * dare niente in cambio. Questi sono i fatti man mano che arrivano: dove sta un fine corsa,
 * quanto muove un'intensità, di quanto sbaglia lo zero. Chi guarda capisce se sta andando bene
 * molto prima della fine, e se qualcosa non torna lo vede subito invece che nel log.
 */
data class Finding(
    val label: String,
    val detail: String,
    val kind: FindingKind = FindingKind.FACT,
)

enum class FindingKind { FACT, GOOD, WARN }

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
 * L'ultimo impulso si appoggia al limite a metà strada, non lo percorre tutto.
 *
 * Contarlo intero direbbe che il motore è più lento di quanto sia; non contarlo direbbe il
 * contrario. Mezzo impulso è il valore atteso quando il momento in cui si tocca il limite è
 * distribuito uniformemente dentro l'impulso, che è esattamente quello che succede.
 */
internal fun effectivePulses(pulses: Int): Float = (pulses - 0.5f).coerceAtLeast(0.5f)

/**
 * L'arretramento giusto perché gli impulsi lenti da contare siano circa [targetSlowPulses].
 *
 * Un impulso al 40% copre grosso modo quaranta volte quello che copre un impulso all'1%,
 * quindi l'arretramento in impulsi di riferimento scala col rapporto fra le due intensità.
 * Se la stima è imprecisa non importa: il tratto vale comunque quello che vale, perché è
 * misurato in impulsi di riferimento già calibrati — la stima decide solo quanto dura la
 * misura, non quanto è giusta.
 */
internal fun backoffPulses(
    intensityPercent: Int,
    referenceIntensity: Int,
    targetSlowPulses: Int,
    maxBackoffPulses: Int,
): Int {
    if (referenceIntensity <= 0 || intensityPercent <= 0) return 1
    val expected = targetSlowPulses.toFloat() * intensityPercent / referenceIntensity
    return expected.roundToInt().coerceIn(1, maxBackoffPulses)
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
                phaseLabel = "Curva a impulsi 1–100%",
                message = "Fine corsa trovati · conto gli impulsi che servono per arrivarci",
            )

            // La curva si misura contando gli impulsi contro i fine corsa, non con le immagini
            // e non col cronometro: l'impulso dura sempre uguale, quindi non c'è nessun tempo
            // da leggere. Due fatti — il limite che la camera annuncia e i gradi che lo
            // separano dallo zero — invece di una scommessa su cosa c'è davanti all'obiettivo.
            val panCurve = measureCurveByPulses(
                axis = GimbalCalibrationSample.AXIS_PAN,
                limits = panLimits,
                phaseStartPercent = 30,
                phaseEndPercent = 60,
            )
            val tiltCurve = measureCurveByPulses(
                axis = GimbalCalibrationSample.AXIS_TILT,
                limits = tiltLimits,
                phaseStartPercent = 60,
                phaseEndPercent = 88,
            )
            returnHome("Ritorno all'inquadratura di partenza dopo la curva", 88, 90)

            // La correzione di scala sopravvive alla ricalibrazione, e deve.
            //
            // Una calibrazione nuova rimisura tempi e impulsi — che erano già giusti — e li
            // divide di nuovo per la corsa di catalogo, che è il numero sbagliato. Rifare la
            // calibrazione quindi non ripara la scala: la ricostruisce identica. Buttare via la
            // correzione qui vorrebbe dire tornare al 31% di errore ogni volta che si ritara.
            val previous = store.state.value
            val profile = GimbalCalibrationBuilder.buildFromDegrees(
                panCurve = panCurve.points,
                tiltCurve = tiltCurve.points,
                cameraModel = cameraModel,
                firmware = firmware,
                panLimits = panLimits,
                tiltLimits = tiltLimits,
            ).withAngularScale(previous.panAngularScale, previous.tiltAngularScale)
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
                    appendLine("Curva misurata contando gli impulsi sui fine corsa, senza immagini e senza cronometro.")
                    appendLine(zeroCheckSummary("orizzontale", panCurve.zero))
                    appendLine(zeroCheckSummary("verticale", tiltCurve.zero))
                    corrected.responsePoints.forEach { point ->
                        appendLine(
                            "%3d%%: orizzontale %.2f °/s · verticale %.2f °/s".format(
                                point.intensityPercent,
                                point.panDegreesPerSecond,
                                point.tiltDegreesPerSecond,
                            ),
                        )
                    }
                    appendLine(
                        "Comando più veloce: %d%% in orizzontale (%.1f °/s), %d%% in verticale (%.1f °/s)".format(
                            corrected.fastestCommandPercent(true),
                            corrected.maxAngularRate(true),
                            corrected.fastestCommandPercent(false),
                            corrected.maxAngularRate(false),
                        ),
                    )
                    listOf(true to "orizzontale", false to "verticale").forEach { (panAxis, name) ->
                        corrected.nonMonotonicPoints(panAxis).forEach { (intensity, loss) ->
                            appendLine(
                                ("Attenzione: sul %s il %d%% muove %.1f °/s in meno di un comando " +
                                    "più basso. Non è un errore di misura: la camera fa così, e il " +
                                    "profilo lo tiene invece di lisciarlo.")
                                    .format(name, intensity, loss),
                            )
                        }
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
     * La curva misurata contando gli impulsi da un fine corsa all'altro.
     *
     * L'impulso è l'unità di misura, e dura sempre uguale: non c'è nessun tempo da leggere,
     * c'è un numero da contare. La corsa fra i due fine corsa è nota — 292° in orizzontale,
     * 177° in verticale — e i due estremi la camera li annuncia da sola sul codice 8302.
     * Quindi si parte appoggiati a un limite, si conta fino all'altro, e i gradi della corsa
     * divisi per gli impulsi contati sono i gradi per impulso di quell'intensità.
     *
     * Percorrere la corsa intera invece di metà è la scelta che dà la misura migliore: più
     * impulsi da contare vuol dire che il mezzo impulso finale — quello che si appoggia al
     * limite a metà strada — pesa meno. Al 100% dallo zero al fine corsa vicino ci sono due
     * impulsi e mezzo, e mezzo impulso su due e mezzo è un errore del venti per cento; sulla
     * corsa intera gli impulsi sono cinquanta e l'incertezza è l'uno per cento.
     *
     * E soprattutto: fra una misura e l'altra non si ricentra mai. Il ricentraggio non è uno
     * zero assoluto — va nella posizione di riposo più vicina, e le posizioni di riposo sono
     * due, il fronte a 0° e il selfie a 180°. Dal fine corsa destro, che sta a +235°, il
     * ricentraggio finisce nel selfie: l'app crede di essere a zero e invece guarda dall'altra
     * parte, e tutte le misure successive sono contro il limite sbagliato. Qui si ricentra una
     * volta sola, alla fine, e solo dal fine corsa vicino allo zero, che è l'unico punto da
     * cui il ricentraggio è davvero uno zero.
     *
     * Le intensità lente non attraverserebbero mai la corsa in un numero ragionevole di
     * impulsi — all'1% ne servirebbero centinaia — e allora si contano solo gli ultimi: ci si
     * stacca dal limite di un tratto noto, misurato in impulsi di un'intensità già calibrata,
     * e si conta quanti impulsi lenti servono per riappoggiarsi.
     *
     * I versi si alternano da soli, perché ogni misura parte dove è finita la precedente. Se
     * andare da una parte costasse più che andare dall'altra si vedrebbe come un'oscillazione
     * fra intensità vicine, invece di restare nascosto in un assunto.
     */
    private suspend fun measureCurveByPulses(
        axis: String,
        limits: GimbalAxisLimits,
        phaseStartPercent: Int,
        phaseEndPercent: Int,
    ): PulseCurve {
        // Il fine corsa "vicino" è quello dalla parte dello zero di accensione. Per il pan è
        // l'unico dal quale un ricentraggio torna sul fronte invece che sul selfie.
        val nearIsMinimum = abs(limits.minimumDeg) <= abs(limits.maximumDeg)
        val nearDirection = if (nearIsMinimum) -1f else 1f
        val nearTravelDeg = abs(if (nearIsMinimum) limits.minimumDeg else limits.maximumDeg)
        val spanDeg = abs(limits.spanDeg)
        val pulseSeconds = CURVE_PULSE_MS / 1000f
        val measured = mutableListOf<Pair<Int, Float>>()

        log.info(
            "CALIBRAZIONE · CURVA A IMPULSI ${axisLabel(axis).uppercase()}",
            ("Impulsi da %d ms contati da un fine corsa all'altro: %.0f° di corsa. " +
                "Nessun ricentraggio fra una misura e l'altra, perché il ricentraggio va nella " +
                "posizione di riposo più vicina e da %s quella non è lo zero.")
                .format(CURVE_PULSE_MS, spanDeg, directionLabel(axis, -nearDirection)),
        )

        // Appoggiati al limite vicino: da qui ogni misura è una corsa fra due fatti.
        if (pulsesToEndstop(axis, nearDirection, APPROACH_INTENSITY_PERCENT, MAX_CURVE_PULSES) <= 0) {
            throw IllegalStateException(
                "Il fine corsa ${directionLabel(axis, nearDirection)} non risponde: la curva non può partire",
            )
        }
        var atNear = true
        var referenceIntensity = 0
        var referenceDegPerPulse = 0f

        INTENSITY_PERCENTAGES.reversed().forEachIndexed { index, intensity ->
            val done = index + 1
            val slowPath = referenceDegPerPulse > 0f && intensity <= SLOW_INTENSITY_THRESHOLD
            // Verso la corsa intera si va sempre via dal limite su cui si è appoggiati.
            val direction = if (atNear) -nearDirection else nearDirection
            _state.value = _state.value.copy(
                phaseLabel = "Curva a impulsi · ${axisLabel(axis)}",
                overallPercent = phaseStartPercent +
                    (phaseEndPercent - phaseStartPercent) * done / INTENSITY_PERCENTAGES.size,
                axisLabel = axisLabel(axis),
                directionLabel = directionLabel(axis, direction),
                intensityPercent = intensity,
                message = if (slowPath) {
                    "$intensity% · conto gli ultimi impulsi contro il fine corsa"
                } else {
                    "$intensity% · conto gli impulsi lungo la corsa intera"
                },
                verificationLabel = "CONTO IMPULSI · NESSUNA IMMAGINE",
            )

            var degPerPulse = 0f
            var pulses = 0
            if (slowPath) {
                // La misura lenta parte e finisce sullo stesso limite: [atNear] non cambia.
                degPerPulse = measureSlowFromEndstop(
                    axis = axis,
                    awayDirection = direction,
                    intensityPercent = intensity,
                    referenceIntensity = referenceIntensity,
                    referenceDegPerPulse = referenceDegPerPulse,
                )
            } else {
                pulses = pulsesToEndstop(axis, direction, intensity, MAX_CURVE_PULSES)
                if (pulses > 0) {
                    degPerPulse = spanDeg / effectivePulses(pulses)
                    atNear = !atNear
                } else {
                    // Rimasta a metà corsa: si torna sul limite da cui si era partiti, o la
                    // misura successiva comincerebbe da un punto che nessuno conosce.
                    pulsesToEndstop(axis, -direction, APPROACH_INTENSITY_PERCENT, MAX_CURVE_PULSES)
                }
            }

            if (degPerPulse > 0f) {
                measured += intensity to (degPerPulse / pulseSeconds)
                _state.value = _state.value.copy(
                    curve = _state.value.curve + CurveReading(
                        intensityPercent = intensity,
                        panAxis = axis == GimbalCalibrationSample.AXIS_PAN,
                        degreesPerSecond = degPerPulse / pulseSeconds,
                        degreesPerPulse = degPerPulse,
                        pulses = pulses,
                    ),
                )
                addFinding(
                    "$intensity% ${axisLabel(axis)}",
                    "%.1f °/s · %.2f° per impulso%s".format(
                        degPerPulse / pulseSeconds,
                        degPerPulse,
                        if (pulses > 0) " · $pulses impulsi contati" else " · misurato sugli ultimi impulsi",
                    ),
                )
                log.info(
                    "CALIBRAZIONE · $intensity% ${axisLabel(axis).uppercase()}",
                    if (slowPath) {
                        "%.3f °/impulso · contato sugli ultimi impulsi contro il fine corsa".format(degPerPulse)
                    } else {
                        "%.3f °/impulso · %d impulsi per %.0f° di corsa verso %s"
                            .format(degPerPulse, pulses, spanDeg, directionLabel(axis, direction))
                    },
                )
                if (intensity == REFERENCE_INTENSITY_PERCENT || referenceDegPerPulse <= 0f) {
                    referenceIntensity = intensity
                    referenceDegPerPulse = degPerPulse
                }
            } else {
                addFinding(
                    "$intensity% ${axisLabel(axis)} non misurato",
                    "Il fine corsa non è arrivato entro $MAX_CURVE_PULSES impulsi.",
                    FindingKind.WARN,
                )
                log.warn(
                    "CALIBRAZIONE · $intensity% ${axisLabel(axis).uppercase()} NON MISURATO",
                    "Il fine corsa non è arrivato entro il numero massimo di impulsi.",
                )
            }
        }

        // Si chiude sul limite vicino, perché è l'unico da cui il ricentraggio è uno zero.
        if (!atNear) {
            pulsesToEndstop(axis, nearDirection, APPROACH_INTENSITY_PERCENT, MAX_CURVE_PULSES)
        }
        val zeroCheck = if (referenceDegPerPulse > 0f) {
            checkZeroFromNearEndstop(
                axis = axis,
                nearDirection = nearDirection,
                nearTravelDeg = nearTravelDeg,
                intensityPercent = referenceIntensity,
                degreesPerPulse = referenceDegPerPulse,
            )
        } else {
            zeroByHardware("Zero dopo la curva ${axisLabel(axis)}")
            ZeroPulseCheck.NONE
        }
        return PulseCurve(points = measured, zero = zeroCheck)
    }

    /** La curva di un asse è, insieme, quanto ha sbagliato lo zero raggiunto col ricentraggio. */
    private data class PulseCurve(
        val points: List<Pair<Int, Float>>,
        val zero: ZeroPulseCheck,
    )

    /**
     * Quanto sbaglia lo zero del ricentraggio, misurato in impulsi contro il fine corsa.
     *
     * [deltaPulses] è la differenza fra gli impulsi che sono davvero serviti per andare dallo
     * zero al fine corsa vicino e quelli che ci sarebbero voluti se lo zero fosse dove la
     * camera dichiara. Se il ricentraggio fosse esatto sarebbe zero: ogni impulso di
     * differenza è errore vero, e [deltaDegrees] lo dice in gradi.
     */
    private data class ZeroPulseCheck(
        val deltaPulses: Int,
        val deltaDegrees: Float,
        val intensityPercent: Int,
        val measured: Boolean,
    ) {
        companion object {
            val NONE = ZeroPulseCheck(0, 0f, 0, measured = false)
        }
    }

    /**
     * Il ricentraggio come zero, con la sola precauzione che lo rende vero: partire da vicino.
     *
     * Il gimbal ha due posizioni di riposo, il fronte a 0° e il selfie a 180°, e il
     * ricentraggio va nella più vicina delle due. Oltre i 90° dal fronte "centro" vuol dire
     * selfie. Dal fine corsa vicino — a 57° dallo zero su entrambi gli assi — si è dentro quel
     * confine con un margine largo, quindi il ricentraggio è uno zero e non un mezzo giro.
     */
    private suspend fun zeroByHardware(label: String) {
        gimbal.recenter().recoverCatching {
            // Un ricentraggio che non risponde entro il timeout non è per forza fallito: la
            // camera può essere occupata a fermare il gimbal. Un secondo tentativo costa poco,
            // e buttare tre minuti di misure per una risposta mancata costa molto.
            log.warn("CALIBRAZIONE · RICENTRAGGIO SENZA RISPOSTA", "${it.message} · riprovo una volta.")
            delay(HARDWARE_ZERO_RETRY_MS)
            gimbal.recenter().getOrThrow()
        }.getOrElse { throw IllegalStateException("Ricentraggio non riuscito: ${it.message}", it) }
        delay(HARDWARE_ZERO_SETTLE_MS)
        gimbal.setEstimated(0f, 0f)
        _state.value = _state.value.copy(theoreticalPan = 0f, theoreticalTilt = 0f, message = label)
    }

    /** Un impulso di misura: durata fissa, intensità fissa, nessun conteggio di rientro. */
    private suspend fun pulseOnce(axis: String, direction: Float, intensityPercent: Int) {
        val panAxis = axis == GimbalCalibrationSample.AXIS_PAN
        val command = direction * intensityPercent / 100f
        calPulse(
            panPercent = if (panAxis) command else 0f,
            tiltPercent = if (panAxis) 0f else command,
            durationMs = CURVE_PULSE_MS,
            record = false,
        ).getOrElse { throw IllegalStateException("Impulso di misura non riuscito: ${it.message}", it) }
    }

    /**
     * Quanti impulsi servono per arrivare al fine corsa. Zero se non ci arriva.
     *
     * Il criterio è la notifica hardware del limite, non un'immagine: la camera dice da sola
     * quando si è appoggiata, ed è l'unico fatto di cui ci si può fidare al buio, davanti a un
     * muro bianco o davanti a una tenda a righe.
     */
    private suspend fun pulsesToEndstop(
        axis: String,
        direction: Float,
        intensityPercent: Int,
        maxPulses: Int,
    ): Int {
        var pulses = 0
        _state.value = _state.value.copy(
            seekingEndstop = true,
            endstopReached = false,
            pulsesInRun = 0,
            maxPulsesInRun = maxPulses,
            usesImages = false,
        )
        while (pulses < maxPulses) {
            val mark = limitMonitor.mark()
            pulseOnce(axis, direction, intensityPercent)
            pulses += 1
            _state.value = _state.value.copy(pulseMs = pulses * CURVE_PULSE_MS, pulsesInRun = pulses)
            if (limitMonitor.reached(axis, mark)) {
                _state.value = _state.value.copy(
                    seekingEndstop = false,
                    endstopReached = true,
                    endstopSignals = _state.value.endstopSignals + 1,
                )
                return pulses
            }
        }
        _state.value = _state.value.copy(seekingEndstop = false, endstopReached = false)
        return 0
    }

    /**
     * Il circuito chiuso: lo zero del ricentraggio contro il fine corsa che gli sta di fronte.
     *
     * Si è appoggiati al limite vicino, si ricentra — e da lì il ricentraggio è uno zero vero —
     * poi si torna al limite contando gli impulsi. La camera dichiara che quel limite sta a 57°
     * dallo zero; la curva appena misurata dice quanti impulsi ci vogliono per fare 57°. Se i
     * due numeri coincidono lo zero era giusto; se non coincidono, la differenza *è* l'errore,
     * in impulsi e quindi in gradi.
     *
     * Non serve nessuna foto per saperlo, e nessuna interpretazione: sono numeri contati contro
     * lo stesso fatto hardware.
     */
    private suspend fun checkZeroFromNearEndstop(
        axis: String,
        nearDirection: Float,
        nearTravelDeg: Float,
        intensityPercent: Int,
        degreesPerPulse: Float,
    ): ZeroPulseCheck {
        zeroByHardware("Zero dal fine corsa ${directionLabel(axis, nearDirection)}")
        _state.value = _state.value.copy(
            message = "$intensityPercent% · torno al fine corsa e conto",
            verificationLabel = "VERIFICA ZERO · CONTO IMPULSI",
        )
        val counted = pulsesToEndstop(axis, nearDirection, intensityPercent, MAX_CURVE_PULSES)
        if (counted <= 0) {
            log.warn(
                "CALIBRAZIONE · VERIFICA ZERO ${axisLabel(axis).uppercase()} NON CONCLUSA",
                "Dallo zero il fine corsa ${directionLabel(axis, nearDirection)} non è arrivato " +
                    "entro $MAX_CURVE_PULSES impulsi.",
            )
            return ZeroPulseCheck.NONE
        }

        val expectedPulses = nearTravelDeg / degreesPerPulse
        val measuredDeg = effectivePulses(counted) * degreesPerPulse
        val deltaDegrees = measuredDeg - nearTravelDeg
        val deltaPulses = (effectivePulses(counted) - expectedPulses).roundToInt()
        val verdict = if (abs(deltaPulses) <= ZERO_TOLERANCE_PULSES) "zero corretto" else "zero da correggere"
        addFinding(
            "Zero ${axisLabel(axis)} verificato",
            "%d impulsi contati, %.1f attesi · scarto %+.1f° · %s".format(
                counted,
                expectedPulses,
                deltaDegrees,
                verdict,
            ),
            if (abs(deltaPulses) <= ZERO_TOLERANCE_PULSES) FindingKind.GOOD else FindingKind.WARN,
        )
        log.info(
            "CALIBRAZIONE · VERIFICA ZERO ${axisLabel(axis).uppercase()}",
            ("Dallo zero al fine corsa %s: %d impulsi contati al %d%%, %.1f attesi per i %.0f° " +
                "dichiarati. Misurati %.1f°, delta %+.1f° (%+d impulsi) · %s.")
                .format(
                    directionLabel(axis, nearDirection),
                    counted,
                    intensityPercent,
                    expectedPulses,
                    nearTravelDeg,
                    measuredDeg,
                    deltaDegrees,
                    deltaPulses,
                    verdict,
                ),
        )
        // Si resta appoggiati al limite: il ricentraggio da qui torna sullo zero.
        zeroByHardware("Zero dopo la verifica ${axisLabel(axis)}")
        return ZeroPulseCheck(
            deltaPulses = deltaPulses,
            deltaDegrees = deltaDegrees,
            intensityPercent = intensityPercent,
            measured = true,
        )
    }

    /**
     * Le intensità lente, misurate sugli ultimi impulsi contro il fine corsa.
     *
     * All'1% attraversare la corsa intera richiederebbe centinaia di impulsi, e dodici
     * intensità così sono una calibrazione che nessuno finisce. Ma non serve attraversarla: si
     * è già appoggiati a un limite, quindi ci si stacca di un tratto noto — tanti impulsi di
     * un'intensità già calibrata, e quanto vale uno di quegli impulsi è già misurato — e da lì
     * si conta quanti impulsi lenti servono per tornare ad appoggiarsi. Il tratto diviso gli
     * impulsi lenti sono i gradi per impulso di quell'intensità.
     *
     * Si parte e si finisce sullo stesso limite, quindi la misura lenta non cambia da che parte
     * si trova la camera e non ha bisogno di nessun ricentraggio.
     *
     * L'arretramento è dimensionato perché gli impulsi lenti da contare siano una ventina:
     * abbastanza da rendere trascurabile il mezzo impulso finale, pochi da non allungare la
     * misura.
     */
    private suspend fun measureSlowFromEndstop(
        axis: String,
        awayDirection: Float,
        intensityPercent: Int,
        referenceIntensity: Int,
        referenceDegPerPulse: Float,
    ): Float {
        val backoff = backoffPulses(
            intensityPercent = intensityPercent,
            referenceIntensity = referenceIntensity,
            targetSlowPulses = TARGET_SLOW_PULSES,
            maxBackoffPulses = MAX_BACKOFF_PULSES,
        )
        repeat(backoff) { pulseOnce(axis, awayDirection, referenceIntensity) }
        val gapDegrees = backoff * referenceDegPerPulse

        _state.value = _state.value.copy(
            message = "$intensityPercent% · $backoff impulsi al $referenceIntensity% di stacco, poi conto i lenti",
        )
        val slowPulses = pulsesToEndstop(axis, -awayDirection, intensityPercent, MAX_SLOW_PULSES)
        if (slowPulses <= 0) {
            // Non si è tornati sul limite: si rientra con l'intensità di riferimento, o la
            // prossima misura partirebbe da un punto che nessuno conosce.
            pulsesToEndstop(axis, -awayDirection, referenceIntensity, MAX_CURVE_PULSES)
            return 0f
        }
        return gapDegrees / effectivePulses(slowPulses)
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
        addFinding(
            "Andata e ritorno",
            when {
                comparable == 0 -> "Nessuna tappa confrontabile: la scena non permette di misurarlo."
                worst == null -> "Nessuno scarto misurabile."
                else -> "Scarto massimo %.2f° su %d tappe confrontabili".format(worst, comparable)
            },
            when {
                worst == null -> FindingKind.WARN
                worst <= ROUND_TRIP_GOOD_DEG -> FindingKind.GOOD
                else -> FindingKind.WARN
            },
        )
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
    /**
     * Fotografa i nove campi della notifica 8302 nel momento in cui sappiamo dove siamo.
     *
     * Di questa notifica conosciamo due campi: i flag di finecorsa. Gli altri sette nessuno li
     * ha mai letti, e la domanda che contano è se lì dentro c'è la posizione. Se c'è, tutta la
     * navigazione a stima — con il suo errore di scala che si porta dietro il numero di
     * catalogo — diventa inutile: basta leggere.
     *
     * Il finecorsa è l'unico istante in cui la posizione è nota senza doverla calcolare. Quindi
     * si scrive nel log com'era il payload lì, e da due estremi noti si ricava sia quale campo
     * è l'angolo sia in che unità lo esprime.
     */
    private fun logPtzSnapshot(where: String, fields: List<ProtoField.VarInt>) {
        if (fields.isEmpty()) {
            log.info("CALIBRAZIONE · PTZ AL ${where.uppercase()}", "Nessuna notifica 8302 ricevuta qui")
            return
        }
        log.info(
            "CALIBRAZIONE · PTZ AL ${where.uppercase()}",
            fields.joinToString("\n") { field ->
                // Il valore grezzo e la sua lettura con segno: un angolo negativo in protobuf si
                // scrive quasi sempre a zig-zag, e senza scioglierlo sembra un numero enorme.
                "campo %d = %d (con segno %d)".format(field.number, field.value, field.asSInt)
            },
        )
    }

    /**
     * Quanto è cambiato ogni campo attraversando l'asse per intero.
     *
     * È la riga che decide. Se un campo è l'angolo, la sua differenza fra i due estremi è la
     * corsa vera espressa nella sua unità: un numero tondo — 3600 se sono decimi di grado su un
     * giro, 36000 se sono centesimi — e a quel punto non serve più indovinare niente.
     */
    private fun logPtzTravel(
        axis: String,
        atMinimum: List<ProtoField.VarInt>,
        atMaximum: List<ProtoField.VarInt>,
    ) {
        if (atMinimum.isEmpty() || atMaximum.isEmpty()) return
        val first = atMinimum.associateBy { it.number }
        val last = atMaximum.associateBy { it.number }
        val righe = (first.keys + last.keys).sorted().mapNotNull { number ->
            val a = first[number] ?: return@mapNotNull null
            val b = last[number] ?: return@mapNotNull null
            val raw = b.value - a.value
            val signed = b.asSInt - a.asSInt
            if (raw == 0L && signed == 0) return@mapNotNull "campo %d: fermo".format(number)
            "campo %d: %+d grezzo, %+d con segno · se fossero decimi di grado sarebbero %.1f°"
                .format(number, raw, signed, abs(signed) / 10.0)
        }
        log.info(
            "CALIBRAZIONE · CORSA ${axisLabel(axis).uppercase()} SECONDO LA CAMERA",
            (righe + listOf(
                "Un campo che cambia di un numero tondo attraversando tutta la corsa è la posizione.",
            )).joinToString("\n"),
        )
    }


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
        val atMinimum = limitMonitor.lastPtzFields
        log.info(
            "CALIBRAZIONE · FINE CORSA ${directionLabel(axis, -1f).uppercase()}",
            "Rilevato dopo ${negative.totalPulses} impulsi · coordinate fissate a %.1f°".format(minimumDeg),
            imageJpeg = negative.annotatedJpeg,
        )
        logPtzSnapshot("fine corsa ${directionLabel(axis, -1f)}", atMinimum)

        val positive = findPreciseEndStop(
            axis = axis,
            direction = 1f,
            phaseLabel = "Corsa completa verso ${directionLabel(axis, 1f)}",
            phaseStartPercent = phaseStartPercent + (phaseEndPercent - phaseStartPercent) / 3,
            phaseEndPercent = phaseEndPercent,
            requireTravel = true,
        )
        anchorFrame(axis, maximumDeg)
        val atMaximum = limitMonitor.lastPtzFields
        logPtzSnapshot("fine corsa ${directionLabel(axis, 1f)}", atMaximum)
        logPtzTravel(axis, atMinimum, atMaximum)
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
        addFinding(
            "Corsa ${axisLabel(axis)} misurata",
            "%.0f°…%+.0f° · %.0f° di corsa · affidabilità %d%%".format(
                limits.minimumDeg,
                limits.maximumDeg,
                limits.spanDeg,
                limits.endpointConfidencePercent,
            ),
            if (limits.endpointConfidencePercent >= 70) FindingKind.GOOD else FindingKind.WARN,
        )
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
            usesImages = true,
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
                usesImages = true,
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

    /** Il verdetto sullo zero in una riga: gli impulsi di scarto e i gradi che valgono. */
    private fun zeroCheckSummary(axisName: String, check: ZeroPulseCheck): String = when {
        !check.measured -> "Verifica zero $axisName: non conclusa."
        abs(check.deltaPulses) <= ZERO_TOLERANCE_PULSES ->
            "Verifica zero $axisName: %+d impulsi (%+.2f°) al %d%% · il ricentraggio è uno zero."
                .format(check.deltaPulses, check.deltaDegrees, check.intensityPercent)
        else ->
            "Verifica zero $axisName: %+d impulsi (%+.2f°) al %d%% · questo è l'errore del ricentraggio."
                .format(check.deltaPulses, check.deltaDegrees, check.intensityPercent)
    }

    /**
     * Aggiunge un fatto accertato all'elenco che si vede mentre la calibrazione va avanti.
     *
     * Sette minuti di barra di avanzamento chiedono fiducia senza dare niente in cambio. Questi
     * sono i fatti man mano che arrivano — dove sta un limite, quanto muove un'intensità, di
     * quanto sbaglia lo zero — e permettono di capire se sta andando bene molto prima della
     * fine. I più recenti in cima, perché è lì che si guarda.
     */
    private fun addFinding(label: String, detail: String, kind: FindingKind = FindingKind.FACT) {
        val updated = (listOf(Finding(label, detail, kind)) + _state.value.findings).take(MAX_FINDINGS)
        _state.value = _state.value.copy(findings = updated)
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

        // Curva misurata contando gli impulsi sul fine corsa. L'impulso dura sempre uguale:
        // è l'unità di misura, non un tempo da cronometrare.
        const val CURVE_PULSE_MS = 400L

        /** Tetto di sicurezza: oltre questi impulsi il fine corsa non sta arrivando. */
        const val MAX_CURVE_PULSES = 130

        /** L'intensità con cui ci si riporta su un fine corsa senza misurare niente. */
        const val APPROACH_INTENSITY_PERCENT = 40

        /** Le intensità lente possono richiederne di più, ma non all'infinito. */
        const val MAX_SLOW_PULSES = 80

        /** Quanti impulsi lenti si vuole contare: abbastanza da rendere trascurabile l'ultimo. */
        const val TARGET_SLOW_PULSES = 20

        /** L'arretramento non supera questo, altrimenti la corsa non basta a contenerlo. */
        const val MAX_BACKOFF_PULSES = 15


        /** Sotto questo scarto il ritorno allo zero è dentro il mezzo impulso di incertezza. */
        const val ZERO_TOLERANCE_PULSES = 1

        /** Quanti rilevamenti tenere sotto gli occhi: oltre, l'elenco smette di essere leggibile. */
        const val MAX_FINDINGS = 24

        const val SLOW_INTENSITY_THRESHOLD = 10
        const val REFERENCE_INTENSITY_PERCENT = 40
        /**
         * Il ricentraggio parte dal fine corsa vicino, cioè da 57° dallo zero: va aspettato.
         * Adesso succede due volte per asse invece di una per intensità, quindi può permetterselo.
         */
        const val HARDWARE_ZERO_SETTLE_MS = 5_000L

        /** Pausa prima di riprovare un ricentraggio che non ha risposto. */
        const val HARDWARE_ZERO_RETRY_MS = 1_500L


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
