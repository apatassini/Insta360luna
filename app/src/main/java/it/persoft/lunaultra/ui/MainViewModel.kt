package it.persoft.lunaultra.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.persoft.lunaultra.AppContainer
import it.persoft.lunaultra.BuildConfig
import it.persoft.lunaultra.camera.CameraMode
import it.persoft.lunaultra.camera.CameraStatus
import it.persoft.lunaultra.camera.CodeProbe
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.camera.takePictureStateFrom
import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.data.GimbalSettings
import it.persoft.lunaultra.data.PhotoSettings
import it.persoft.lunaultra.data.LunaVideoProfiles
import it.persoft.lunaultra.data.VideoSettings
import it.persoft.lunaultra.diagnostics.WaypointImageVerifier
import it.persoft.lunaultra.media.Favorites
import it.persoft.lunaultra.media.MediaItem
import it.persoft.lunaultra.data.StitchSettings
import it.persoft.lunaultra.stitch.PanoJob
import it.persoft.lunaultra.stitch.PanoramaStitcher
import it.persoft.lunaultra.stitch.PanoramaView
import it.persoft.lunaultra.stitch.PreviewImage
import it.persoft.lunaultra.stitch.PreviewShape
import it.persoft.lunaultra.stitch.PanoJobList
import it.persoft.lunaultra.stitch.ProcessVitals
import it.persoft.lunaultra.stitch.StitchVitals
import it.persoft.lunaultra.stitch.StitchProjection
import it.persoft.lunaultra.stitch.StitchTestLab
import it.persoft.lunaultra.stitch.StitchTuning
import it.persoft.lunaultra.stitch.StitchUiState
import it.persoft.lunaultra.stitch.sphericalCoverage
import it.persoft.lunaultra.preview.PreviewState
import it.persoft.lunaultra.protocol.Hex
import it.persoft.lunaultra.protocol.LunaMessages
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.service.LunaConnectionService
import it.persoft.lunaultra.timelapse.InterpolationMode
import it.persoft.lunaultra.timelapse.LunaOptics
import it.persoft.lunaultra.timelapse.PanoramaPlan
import it.persoft.lunaultra.timelapse.PanoramaPlanner
import it.persoft.lunaultra.timelapse.PanoramaPreset
import it.persoft.lunaultra.timelapse.PhotoFrameAspect
import it.persoft.lunaultra.timelapse.RunPhase
import it.persoft.lunaultra.timelapse.ShootingMode
import it.persoft.lunaultra.timelapse.ShotAngle
import it.persoft.lunaultra.timelapse.TimelapseSequence
import it.persoft.lunaultra.timelapse.Waypoint
import it.persoft.lunaultra.update.UpdateManager
import it.persoft.lunaultra.ui.viewfinder.CaptureMode
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Una notifica spontanea osservata sul canale di controllo. */
data class NotificationSighting(
    val code: Int,
    val count: Int,
    val distinctPayloads: Int,
    val lastDump: String,
) {
    val name: String get() = LunaProtocolCodes.nameOf(code) ?: "SCONOSCIUTO_$code"
    val isNamed: Boolean get() = LunaProtocolCodes.nameOf(code) != null
}

/** Come si comporta un singolo codice sotto osservazione. */
data class MonitorEntry(
    val code: Int,
    val reads: Int = 0,
    val changes: Int = 0,
    val distinct: Int = 0,
    val dump: String = "",
) {
    /** Quello che cambia mentre muovi il gimbal è quello che sta leggendo il gimbal. */
    val moves: Boolean get() = changes > 0
}

/**
 * Lettura ripetuta di più getter insieme.
 *
 * Osservarne uno alla volta costringe a indovinare da quale partire; a rotazione si guardano
 * tutti mentre il gimbal si muove, e quello che cambia si fa riconoscere da solo.
 */
data class MonitorState(
    val running: Boolean = false,
    val entries: List<MonitorEntry> = emptyList(),
) {
    /** In cima quelli che cambiano di più: è lì che si guarda. */
    val ranked: List<MonitorEntry> get() = entries.sortedByDescending { it.changes }
}

/**
 * Avanzamento della caccia al comando del gimbal.
 *
 * Tiene solo i tentativi che hanno detto qualcosa: su settanta corpi provati, i rifiuti sono
 * la norma e riempirebbero lo schermo nascondendo l'unico che conta.
 */
data class HuntUiState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val steps: List<CodeProbe.HuntStep> = emptyList(),
) {
    val interesting: List<CodeProbe.HuntStep> get() = steps.filter { it.interesting }
}

data class ProbeUiState(
    val running: Boolean = false,
    /** Gamma in corso di scansione: la UI abilita "Interrompi" solo su quella. */
    val range: CodeProbe.Range? = null,
    val done: Int = 0,
    val total: Int = 0,
    val hits: List<CodeProbe.Hit> = emptyList(),
    val calibration: CodeProbe.Calibration? = null,
)

/**
 * La libreria della camera vista dalla UI.
 *
 * Gli scaricamenti in corso stanno qui e non nella schermata: chi chiude la galleria mentre sta
 * salvando un video non si aspetta che il salvataggio muoia con la schermata.
 */
data class GalleryState(
    val loading: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val error: String? = null,
    val selected: Set<String> = emptySet(),
    /** Percorso del file → avanzamento, da 0 a 1. */
    val downloads: Map<String, Float> = emptyMap(),
    /** Quanti file conta lo scaricamento in corso e a che punto è: «3 di 6». */
    val queueTotal: Int = 0,
    val queueDone: Int = 0,
    val loadedAtMs: Long = 0L,
    /**
     * Cambia quando arrivano miniature nuove in blocco. Le caselle si ridisegnano solo se
     * qualcosa nel loro stato cambia, e un file comparso in cache non è qualcosa che vedono.
     */
    val thumbnailsVersion: Int = 0,
) {
    val selectionMode: Boolean get() = selected.isNotEmpty()
    val photos: Int get() = items.count { !it.isVideo }
    val videos: Int get() = items.count { it.isVideo }
}

/** Il file aperto a schermo intero. */
data class ViewerState(
    val item: MediaItem? = null,
    val index: Int = -1,
    val loading: Boolean = false,
    val progress: Float = 0f,
    val photo: android.graphics.Bitmap? = null,
    /** Percorso locale del video già scaricato, pronto per il lettore di sistema. */
    val videoFile: String? = null,
    val message: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer(application, viewModelScope)
    private val updateManager = UpdateManager(application)

    val settings: StateFlow<AppSettings> = container.settingsStore.state
    val sequence: StateFlow<TimelapseSequence> = container.sequenceStore.state
    val connectionState: StateFlow<ConnectionState> = container.session.state
    val connectionError: StateFlow<String?> = container.session.lastError
    val logEntries = container.log.entries
    val ptz = container.gimbal.position
    val gimbalMoving = container.gimbal.moving
    val gimbalPosition = container.gimbal.position
    val gimbalCalibration = container.calibrationStore.state
    val gimbalCalibrationState = container.calibrator.state
    val runState = container.engine.state
    val preview: StateFlow<PreviewState> = container.preview.state

    private val _status = MutableStateFlow(CameraStatus())
    val status: StateFlow<CameraStatus> = _status

    /**
     * La camera sta salvando lo scatto, e lo dice lei.
     *
     * Serve a spiegare l'attesa invece di lasciarla senza motivo: fra uno scatto e il successivo
     * di una sequenza non è l'app che ci mette, è la camera che comprime e scrive.
     *
     * Sta qui sopra, prima di `init`, e non è un dettaglio di stile: `init` avvia i collettori,
     * `viewModelScope` gira su `Dispatchers.Main.immediate`, e uno `StateFlow` consegna subito il
     * valore che ha. Il corpo del collettore parte quindi *dentro* il costruttore, e tutto quello
     * che tocca deve essere già stato costruito. Dichiarato più in basso valeva null, e l'app si
     * chiudeva all'avvio senza arrivare a disegnare niente.
     */
    private val _cameraSaving = MutableStateFlow(false)
    val cameraSaving: StateFlow<Boolean> = _cameraSaving

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Modalità selezionata sulla ghiera: decide cosa fa il pulsante di scatto. */
    private val _captureMode = MutableStateFlow(CaptureMode.VIDEO)
    val captureMode: StateFlow<CaptureMode> = _captureMode

    private val _wifiConnecting = MutableStateFlow(false)
    val wifiConnecting: StateFlow<Boolean> = _wifiConnecting

    private val _photoCountdownSeconds = MutableStateFlow(0)
    val photoCountdownSeconds: StateFlow<Int> = _photoCountdownSeconds

    /**
     * Istante in cui è partita la ripresa, per il cronometro dell'HUD. Zero = ferma.
     *
     * Serve perché la camera dice da quanto sta registrando solo quando la si interroga, ogni
     * tre secondi: un cronometro che avanza a scatti di tre secondi si legge come un difetto.
     */
    private val _recordingSinceMs = MutableStateFlow(0L)
    val recordingSinceMs: StateFlow<Long> = _recordingSinceMs

    private val _gallery = MutableStateFlow(GalleryState())

    /**
     * L'ultima foto salvata sulla camera, in miniatura: vive sul pulsante della galleria.
     *
     * È anche la conferma visiva che lo scatto è andato: quando la miniatura cambia, il file
     * c'è. L'app ufficiale fa lo stesso, ed è il posto giusto — dove l'occhio va da solo.
     */
    private val _latestShotThumb = MutableStateFlow<android.graphics.Bitmap?>(null)
    val latestShotThumb: StateFlow<android.graphics.Bitmap?> = _latestShotThumb

    /** Il percorso dell'ultima foto nota: serve a riconoscere quando ne compare una nuova. */
    @Volatile
    private var lastKnownNewestPhotoPath: String? = null
    val gallery: StateFlow<GalleryState> = _gallery

    /** I file segnati con la stella, ricaricati all'avvio. */
    val favorites: StateFlow<Favorites> = container.favoritesStore.state

    private val _viewer = MutableStateFlow(ViewerState())
    val viewer: StateFlow<ViewerState> = _viewer

    /**
     * Quante miniature si chiedono insieme. Passano dalla sessione di controllo, che è una sola:
     * scatenarne cinquanta in parallelo significa mettere in coda anche i comandi di ripresa.
     */
    private val thumbnailGate = Semaphore(THUMBNAIL_CONCURRENCY)

    private val _probe = MutableStateFlow(ProbeUiState())
    val probe: StateFlow<ProbeUiState> = _probe

    private val _selector = MutableStateFlow<List<CodeProbe.SelectorResult>>(emptyList())
    val selector: StateFlow<List<CodeProbe.SelectorResult>> = _selector

    private val _monitor = MutableStateFlow(MonitorState())
    val monitor: StateFlow<MonitorState> = _monitor

    private val _shape = MutableStateFlow<List<CodeProbe.ShapeResult>>(emptyList())
    val shape: StateFlow<List<CodeProbe.ShapeResult>> = _shape

    private val _hunt = MutableStateFlow(HuntUiState())
    val hunt: StateFlow<HuntUiState> = _hunt

    private val _shapeRunning = MutableStateFlow(false)
    val shapeRunning: StateFlow<Boolean> = _shapeRunning

    private val _sightings = MutableStateFlow<List<NotificationSighting>>(emptyList())
    val sightings: StateFlow<List<NotificationSighting>> = _sightings

    private val payloadsByCode = mutableMapOf<Int, MutableSet<String>>()
    private val countsByCode = mutableMapOf<Int, Int>()

    /** L'utente vuole essere connesso: resta vero finché non preme «disconnetti». */
    /**
     * La volontà dell'utente di stare connesso, come flusso: la legge anche il servizio in
     * primo piano, che deve restare in piedi *durante* una riconnessione. Se cadesse lì,
     * l'app in secondo piano perderebbe il diritto di richiedere la rete della camera e la
     * riconnessione morirebbe in silenzio — era il «cambio app e non si riconnette più».
     */
    private val wantConnectedFlow = MutableStateFlow(false)
    private var wantConnected: Boolean
        get() = wantConnectedFlow.value
        set(value) { wantConnectedFlow.value = value }
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var connectionJob: Job? = null
    private var updateCheckStarted = false
    private var updateJob: Job? = null

    /**
     * Come installare, ricevuto dall'Activity al primo controllo.
     *
     * Il ViewModel non può aprire l'installer da solo — serve un Context di Activity — quindi
     * se lo tiene per quando il pulsante in Impostazioni chiederà di rifare il giro.
     */
    private var installUpdate: ((File) -> Unit)? = null

    private val _update = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val update: StateFlow<UpdateUiState> = _update
    private var photoCountdownJob: Job? = null

    private var pollJob: Job? = null

    /** Il campionatore del diario delle posizioni: gira solo mentre si è connessi. */
    private var locationJob: Job? = null
    private var viewerJob: Job? = null
    private var prefetchJob: Job? = null
    private var warmJob: Job? = null
    private var probeJob: Job? = null
    private var monitorJob: Job? = null

    /**
     * I collettori partono da qui, e partono *davvero* da qui.
     *
     * `viewModelScope` gira su `Dispatchers.Main.immediate`: una `launch` chiamata dal thread
     * principale non viene rimandata, comincia subito. E uno `StateFlow` consegna il valore che
     * ha già al primo collettore che arriva. Il corpo di questi osservatori esegue quindi il suo
     * primo giro *dentro* il costruttore, prima che le proprietà dichiarate più sotto esistano.
     *
     * Regola: tutto ciò che questi osservatori toccano va dichiarato sopra questo blocco. Una
     * riga aggiunta a un collettore che leggeva una proprietà dichiarata più in basso ha chiuso
     * l'app all'avvio, senza arrivare a disegnare niente e senza lasciare traccia nel log.
     */
    init {
        viewModelScope.launch { container.load() }
        observeNotifications()
        observeConnection()
        observeFinishedRuns()
    }

    /**
     * Guarda quando una corsa finisce, per unire da sé gli scatti di una panoramica.
     *
     * Il momento giusto è il passaggio a completata e non un istante prima: gli angoli sono
     * completi solo alla fine, e i file sulla camera pure. Una corsa interrotta non produce
     * niente da unire, e infatti si guarda solo il completamento.
     */
    private fun observeFinishedRuns() {
        viewModelScope.launch {
            var previous = runState.value.phase
            runState.collect { state ->
                if (previous != RunPhase.COMPLETED && state.phase == RunPhase.COMPLETED) {
                    // I file nuovi sono sulla camera: la galleria deve saperlo, e il pulsante
                    // deve mostrare l'ultimo scatto della sequenza appena finita.
                    markGalleryStale()
                    viewModelScope.launch {
                        container.media.list().onSuccess { noteNewestPhoto(it) }
                    }
                    stitchPanoramaIfRequested(state.shotAngles)
                    returnGimbalAfterPanorama()
                }
                // L'ultimo scatto non ha un seguito che spenga l'avviso: la notifica dice
                // «scrittura» e poi la camera tace, quindi lo spegne la fine della corsa.
                if (!state.running) _cameraSaving.value = false
                previous = state.phase
            }
        }
    }

    /**
     * Tiene in piedi il servizio in primo piano finché la sessione è aperta.
     *
     * Senza, il processo viene congelato pochi secondi dopo che l'app finisce in background: il
     * keep-alive smette di battere e la camera chiude. Il testo della notifica segue lo stato,
     * così dalla tendina si vede se sta ancora girando qualcosa.
     */
    private fun observeForegroundService() {
        viewModelScope.launch {
            combine(
                combine(connectionState, wantConnectedFlow) { c, w -> c to w },
                combine(_recordingSinceMs, _stitchState) { r, s -> r to s },
                runState,
                _status,
                gimbalCalibrationState,
            ) { (connection, wanted), (recordingSince, stitch), run, status, calibration ->
                when {
                    // L'unione per prima, e non per importanza: è l'unica di queste attività
                    // che gira **senza** la camera connessa. Un job lanciato la sera, a
                    // camera spenta, faceva cadere tutte le altre condizioni e il servizio si
                    // spegneva — e da Android 12 un processo senza servizio in primo piano
                    // viene congelato pochi secondi dopo che si cambia applicazione. La
                    // cucitura non si interrompeva con un errore: si fermava e basta,
                    // riprendendo al rientro. Da qui la sensazione che cambiando app si fermi.
                    stitch is StitchUiState.Working ->
                        "Unione panoramica — %d%%".format((stitch.fraction * 100).roundToInt()) to true
                    // Durante una riconnessione il servizio deve restare in piedi: è lui che
                    // tiene l'app «in primo piano» per Android, e senza quello stato la
                    // richiesta della rete Wi-Fi della camera non è più permessa.
                    connection != ConnectionState.CONNECTED ->
                        if (wanted) "Riconnessione alla camera…" to false else null
                    calibration.running -> if (calibration.pausedForPreview) {
                        "Calibrazione gimbal in pausa — riapri l'app per l'anteprima" to false
                    } else {
                        "Calibrazione gimbal — ${(calibration.progress * 100).toInt()}%" to false
                    }
                    run.running ->
                        "Sequenza in corso — tratto ${run.legIndex + 1}/${run.legCount.coerceAtLeast(1)}" to false

                    recordingSince > 0L || status.recording == true -> "Ripresa in corso" to false
                    else -> "Connessa — la sessione resta aperta" to false
                }
            }.distinctUntilChanged().collect { notice ->
                val context = getApplication<Application>()
                if (notice == null) {
                    LunaConnectionService.stop(context)
                } else {
                    LunaConnectionService.start(context, notice.first, dataSync = notice.second)
                }
            }
        }
    }

    // ---------------------------------------------------------------- connessione

    /**
     * Prima di chiedere la rete locale della camera usa la normale connessione Internet per
     * controllare la release GitHub. Il download è automatico; Android mostra comunque la sua
     * conferma di installazione, che un'app normale non può aggirare.
     */
    /**
     * Controllo automatico all'avvio: parte una volta sola per sessione.
     *
     * Il pulsante in Impostazioni chiama invece [checkForUpdateNow], che non ha quel vincolo:
     * chi lo preme sta chiedendo di rifare il controllo, non di saltarlo perché è già stato
     * fatto.
     */
    fun checkForUpdate(onReadyToInstall: (File) -> Unit) {
        if (updateCheckStarted) return
        updateCheckStarted = true
        installUpdate = onReadyToInstall
        runUpdateCheck(onReadyToInstall)
    }

    /** Verifica richiesta dall'utente. */
    fun checkForUpdateNow() {
        val installer = installUpdate
        if (installer == null) {
            showMessage("Installazione non disponibile in questa schermata")
            return
        }
        if (_update.value is UpdateUiState.Checking || _update.value is UpdateUiState.Downloading) return
        runUpdateCheck(installer)
    }

    private fun runUpdateCheck(onReadyToInstall: (File) -> Unit, attempt: Int = 1) {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            val branch = settings.value.updateBranch.ifBlank { BuildConfig.GIT_BRANCH }
            _update.value = UpdateUiState.Checking(branch)
            container.log.info(
                "AGGIORNAMENTI",
                "Cerco la release del branch \"$branch\" (build corrente: ${BuildConfig.GIT_SHA.take(12)}).",
            )
            updateManager.downloadIfAvailable(BuildConfig.GIT_SHA, branch) { downloaded, total ->
                _update.value = UpdateUiState.Downloading(branch, downloaded, total)
            }
                .onSuccess { update ->
                    if (update != null) {
                        _update.value = UpdateUiState.ReadyToInstall(branch, update.commitSha, update.publishedAtMs)
                        container.log.info(
                            "AGGIORNAMENTI · SCARICATO",
                            "Commit ${update.commitSha.take(12)} · ${update.apk.length() / 1024} KB. " +
                                "Android chiede comunque conferma per installare.",
                        )
                        onReadyToInstall(update.apk)
                    } else {
                        _update.value = UpdateUiState.UpToDate(branch)
                    }
                }
                .onFailure { error ->
                    // La pubblicazione cancella e ricrea la release: chi controlla in quella
                    // finestra trova il vuoto — 404, oppure la release senza ancora l'APK.
                    // Non è un guasto, è un momento sbagliato: si riprova da soli fra poco.
                    val transient = error is java.io.FileNotFoundException ||
                        error.message?.contains("Nessun APK") == true
                    if (transient && attempt < UPDATE_RETRY_ATTEMPTS) {
                        container.log.info(
                            "AGGIORNAMENTI",
                            "La release di \"$branch\" è in ripubblicazione: riprovo fra ${UPDATE_RETRY_DELAY_MS / 1000} secondi.",
                        )
                        _update.value = UpdateUiState.Checking(branch)
                        delay(UPDATE_RETRY_DELAY_MS)
                        runUpdateCheck(onReadyToInstall, attempt + 1)
                        return@launch
                    }
                    container.log.warn("Controllo aggiornamenti non riuscito: ${error.message}")
                    _update.value = UpdateUiState.Failed(
                        branch,
                        if (transient) {
                            "la release è in ripubblicazione: riprova fra un minuto"
                        } else {
                            error.message ?: "motivo sconosciuto"
                        },
                    )
                }
        }
    }

    /** Toglie dallo schermo l'esito, quando è solo un'informazione e non un'azione. */
    fun dismissUpdateNotice() {
        _update.value = UpdateUiState.Idle
    }

    fun connect() = beginConnect(showFailure = true)

    private fun beginConnect(showFailure: Boolean) {
        if (connectionState.value == ConnectionState.CONNECTED) return
        if (connectionJob?.isActive == true) return
        wantConnected = true
        reconnectAttempts = 0
        connectionJob = viewModelScope.launch {
            _wifiConnecting.value = true
            container.log.info("Ricerca della rete Luna Ultra…")
            val currentSettings = settings.value
            val network = container.wifiBinder.acquire(
                password = currentSettings.cameraWifiPassword,
                cameraHost = currentSettings.host,
            )
            _wifiConnecting.value = false
            if (network == null) {
                wantConnected = false
                if (showFailure) {
                    val detail = if (currentSettings.cameraWifiPassword.isBlank()) {
                        "collegati una volta alla Luna oppure inserisci la password nelle Impostazioni"
                    } else {
                        "Android non ha autorizzato il cambio rete; verifica password e conferma di sistema"
                    }
                    showMessage("Connessione Wi-Fi automatica non riuscita: $detail")
                }
                return@launch
            }
            container.session.connect()
                .onFailure {
                    if (showFailure) showMessage("Connessione fallita: ${it.message}")
                    else container.log.warn("Connessione automatica non riuscita: ${it.message}")
                }
                .onSuccess {
                    learnCameraWifiPassword()
                    if (showFailure) showMessage("Luna Ultra connessa")
                    refreshStatus()
                    syncCameraMode()
                }
        }
    }

    fun disconnect() {
        wantConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        connectionJob?.cancel()
        connectionJob = null
        photoCountdownJob?.cancel()
        photoCountdownJob = null
        _photoCountdownSeconds.value = 0
        _wifiConnecting.value = false
        viewModelScope.launch {
            container.engine.stop("Disconnessione")
            container.session.disconnect()
            container.wifiBinder.release()
            _status.value = CameraStatus()
            _recordingSinceMs.value = 0L
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            container.commands.fetchStatus()
                .onSuccess { _status.value = _status.value.mergedWith(it) }
                .onFailure { showMessage("Stato non disponibile: ${it.message}") }
            container.commands.fetchCameraInfo()
                .onSuccess { _status.value = _status.value.mergedWith(it) }
        }
    }

    /**
     * Prova a scrivere sulla scheda della camera e dice cosa ha risposto.
     *
     * Serve a rispondere a una domanda che non si può dedurre: i file si scaricano in HTTP,
     * quindi sulla camera gira un server, ma un server che serve file è quasi sempre in sola
     * lettura — e «quasi sempre» non è una risposta. Il risultato, con ogni tentativo e ogni
     * codice ricevuto, finisce nel log della Diagnostica.
     */
    fun probeCameraWrite() {
        viewModelScope.launch {
            showMessage("Provo a scrivere sulla camera…")
            val result = container.writeProbe.probe(settings.value.host)
            showMessage(
                if (result.canWrite) {
                    "La camera accetta la scrittura su ${result.writablePath}"
                } else {
                    "La camera non accetta la scrittura: i dettagli sono nel log"
                },
            )
        }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            connectionState.collect { state ->
                pollJob?.cancel()
                locationJob?.cancel()
                when (state) {
                    ConnectionState.CONNECTED -> {
                        reconnectAttempts = 0
                        // Un tentativo di riconnessione ancora in attesa va spento subito:
                        // se scattasse ora aprirebbe una seconda connessione di controllo,
                        // e la camera ne ammette una sola — chiuderebbe questa per far
                        // posto all'altra, cioè ci butteremmo giù da soli.
                        reconnectJob?.cancel()
                        reconnectJob = null
                        // La connessione resta manuale, ma una volta riuscita il mirino deve
                        // mostrare subito l'immagine senza richiedere un secondo pulsante.
                        container.preview.start()
                        container.log.info("Anteprima avviata automaticamente dopo la connessione")
                        // Il pulsante della galleria mostra l'ultima foto: appena connessi si
                        // va a vedere qual è, senza aspettare che qualcuno apra la galleria.
                        if (lastKnownNewestPhotoPath == null) {
                            viewModelScope.launch {
                                container.media.list().onSuccess { noteNewestPhoto(it) }
                            }
                        }
                        pollJob = viewModelScope.launch {
                            while (isActive) {
                                delay(STATUS_POLL_MS)
                                container.commands.fetchStatus()
                                    .onSuccess {
                                        _status.value = _status.value.mergedWith(it)
                                        syncRecordingClock()
                                    }
                            }
                        }
                        // La risoluzione foto si legge dalla camera, che può essere stata
                        // cambiata dal suo schermo: l'interruttore mostra la verità.
                        refreshPhotoSize()
                        // Finché si è connessi si sta fotografando: il diario annota dove,
                        // così le copie scaricate ricevono le coordinate negli EXIF.
                        locationJob = viewModelScope.launch {
                            while (isActive) {
                                container.locationDiary.sample()
                                delay(LOCATION_SAMPLE_MS)
                            }
                        }
                    }

                    ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                        container.preview.stop()
                        scheduleReconnect()
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Riaggancia da sola una sessione caduta.
     *
     * Finché l'utente non preme «disconnetti», restare connessi è quello che vuole: una camera
     * che sparisce perché il telefono ha cambiato rete per due secondi non è una scelta di
     * nessuno. I tentativi sono a distanza crescente e finiti — insistere all'infinito su una
     * camera spenta scalda soltanto la batteria.
     */
    private fun scheduleReconnect() {
        if (!wantConnected) return
        if (reconnectJob?.isActive == true) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            wantConnected = false
            showMessage("Sessione caduta e riconnessione non riuscita: riprova a mano")
            return
        }
        reconnectJob = viewModelScope.launch {
            val attempt = ++reconnectAttempts
            val wait = RECONNECT_BASE_MS * (1L shl (attempt - 1).coerceAtMost(3))
            container.log.warn("Sessione caduta: riprovo fra ${wait / 1000}s (tentativo $attempt)")
            delay(wait)
            if (!wantConnected) return@launch
            // Nel frattempo la connessione principale può essere riuscita da sola:
            // in quel caso questo tentativo non deve toccare niente.
            if (connectionState.value == ConnectionState.CONNECTED) return@launch
            val currentSettings = settings.value
            val network = container.wifiBinder.acquire(
                password = currentSettings.cameraWifiPassword,
                cameraHost = currentSettings.host,
            )
            if (network == null) {
                container.log.warn("Riconnessione Wi-Fi non riuscita")
                return@launch
            }
            container.session.connect()
                .onSuccess {
                    learnCameraWifiPassword()
                    showMessage("Riconnessa alla camera")
                    refreshStatus()
                    syncCameraMode()
                }
                .onFailure { container.log.warn("Riconnessione non riuscita: ${it.message}") }
        }
    }

    /**
     * Dopo una prima connessione manuale la camera stessa ci consegna la sua password Wi-Fi.
     * Non viene mai scritta nel log; serve soltanto ai successivi `WifiNetworkSpecifier`.
     */
    private suspend fun learnCameraWifiPassword() {
        container.commands.fetchWifiInfo()
            .onSuccess { info ->
                val learned = info.password?.takeIf { it.isNotBlank() } ?: return@onSuccess
                if (learned != settings.value.cameraWifiPassword) {
                    container.settingsStore.update { it.copy(cameraWifiPassword = learned) }
                    container.log.info("Credenziali Wi-Fi Luna memorizzate per le connessioni automatiche")
                }
            }
            .onFailure { container.log.warn("Informazioni Wi-Fi della camera non leggibili: ${it.message}") }
    }

    /**
     * Le notifiche servono a due cose: aggiornare lo stato senza interrogare la camera, e
     * costruire l'inventario dei codici visti — che è il modo per riconoscere quale notifica
     * accompagna il movimento del gimbal.
     */
    private fun observeNotifications() {
        viewModelScope.launch {
            container.session.notifications.collect { frame ->
                container.commands.statusFromNotification(frame)?.let {
                    _status.value = _status.value.mergedWith(it)
                }
                // La camera dice cosa sta facendo mentre scatta. Il momento che interessa è la
                // scrittura sulla scheda: è lì che non risponde, ed è il motivo per cui una
                // sequenza troppo fitta perdeva scatti senza che si capisse perché.
                takePictureStateFrom(frame)?.let { state ->
                    container.log.debug("Scatto: ${LunaProtocolCodes.TakePictureState.name(state)}")
                    _cameraSaving.value = state == LunaProtocolCodes.TakePictureState.COMPRESS ||
                        state == LunaProtocolCodes.TakePictureState.WRITE_FILE
                }
                if (
                    settings.value.gimbal.useExperimentalPtzPosition &&
                    frame.code == settings.value.gimbal.ptzNotificationCode
                ) {
                    container.commands.parsePtz(frame)?.let { container.gimbal.onCameraPosition(it) }
                }
                // La notifica L/M/V viene registrata in Diagnostica ma non modifica più la UI:
                // le prove reali mostrano che i tre livelli producono lo stesso movimento.
                recordSighting(frame.code, frame.describePayload(), Hex.encode(frame.payload, limit = 32))
            }
        }
    }

    private fun recordSighting(code: Int, dump: String, hex: String) {
        countsByCode[code] = (countsByCode[code] ?: 0) + 1
        payloadsByCode.getOrPut(code) { mutableSetOf() }.add(hex)
        lastDumpByCode[code] = dump
        _sightings.value = countsByCode.entries
            .sortedByDescending { it.value }
            .map { (seen, count) ->
                NotificationSighting(
                    code = seen,
                    count = count,
                    distinctPayloads = payloadsByCode[seen]?.size ?: 0,
                    lastDump = lastDumpByCode[seen].orEmpty(),
                )
            }
    }

    private val lastDumpByCode = mutableMapOf<Int, String>()

    fun clearSightings() {
        countsByCode.clear()
        payloadsByCode.clear()
        lastDumpByCode.clear()
        _sightings.value = emptyList()
    }

    // ---------------------------------------------------------------- anteprima

    fun startPreview() {
        if (connectionState.value != ConnectionState.CONNECTED) {
            showMessage("Connettiti alla camera prima di aprire l'anteprima")
            return
        }
        container.preview.start()
    }

    fun stopPreview() = container.preview.stop()

    fun togglePreview() {
        if (preview.value.active) stopPreview() else startPreview()
    }

    fun attachPreviewSurface(surface: android.view.Surface?) =
        container.preview.attachSurface(surface)

    // ---------------------------------------------------------------- gimbal

    fun jogStart(pan: Float, tilt: Float) {
        container.gimbal.startJog(pan, tilt)
    }

    /**
     * Movimento con i due assi insieme, per la levetta analogica.
     *
     * Arriva a ogni spostamento del dito: non riavvia il comando, ne cambia la direzione mentre
     * gira. Riavviarlo a ogni frazione di grado significherebbe un ciclo di comandi nuovo
     * sessanta volte al secondo, con la camera che vede raffiche di start invece di un movimento.
     */
    fun jogVector(pan: Float, tilt: Float) {
        container.gimbal.setJog(pan, tilt)
    }

    fun setManualSpeed(percent: Int) {
        val value = percent.coerceIn(1, 100)
        val preset = when (value) { 25 -> 1; 50 -> 2; 75 -> 3; else -> 0 }
        updateGimbal { it.copy(manualSpeedPercent = value, hardwareSpeedLevel = preset) }
    }

    fun setGimbalHardwareSpeed(level: Int) {
        val percent = when (level) { 1 -> 25; 2 -> 50; else -> 75 }
        updateGimbal { it.copy(hardwareSpeedLevel = level, manualSpeedPercent = percent) }
        showMessage("Intensità joystick: $percent% (${gimbalSpeedLabel(level)})")
    }

    fun startGimbalCalibration() {
        if (connectionState.value != ConnectionState.CONNECTED) {
            showMessage("Connettiti alla camera prima della calibrazione")
            return
        }
        if (runState.value.running || gimbalCalibrationState.value.running) {
            showMessage("Ferma la sequenza in corso prima della calibrazione")
            return
        }
        container.calibrator.start(
            cameraModel = status.value.model.orEmpty(),
            firmware = status.value.firmware.orEmpty(),
            zoomScale = settings.value.photo.zoomScale,
        )
        showMessage("Calibrazione avviata · non muovere camera o scena")
    }

    fun cancelGimbalCalibration() {
        container.calibrator.cancel()
        showMessage("Interruzione calibrazione…")
    }

    private fun gimbalSpeedLabel(level: Int): String = when (level) {
        1 -> "lenta"
        2 -> "media"
        else -> "veloce"
    }

    private var gimbalProbeJob: Job? = null

    fun jogStop() {
        viewModelScope.launch { container.gimbal.stop() }
    }

    /**
     * Zero hardware del firmware.
     *
     * Il messaggio dice dov'è quello zero perché è la domanda che arriva sempre: 0° è il
     * fronte del corpo camera, non il centro della corsa -57°…+235°. Se la camera è
     * appoggiata con il fronte verso di noi, ricentrare significa inquadrarci.
     */
    fun zeroPosition() {
        viewModelScope.launch {
            container.gimbal.recenter()
                .onSuccess {
                    container.gimbal.setEstimated(0f, 0f)
                    showMessage("Gimbal sullo zero hardware (0° = fronte della camera)")
                }
                .onFailure { showMessage("Ricentraggio non riuscito: ${it.message}") }
        }
    }

    /** Lato in cui l'app crede di essere: è una convinzione, la camera non lo pubblica. */
    val selfieEngaged: StateFlow<Boolean> = container.gimbal.selfieEngaged

    /** Commuta fra fronte e selfie. Con l'azione nativa è un interruttore, non una rotazione. */
    fun selfieTurn() {
        viewModelScope.launch {
            container.gimbal.selfieTurn()
                .onSuccess {
                    showMessage(
                        if (selfieEngaged.value) "Selfie: la camera guarda dalla tua parte"
                        else "Fronte: la camera guarda in avanti",
                    )
                }
                .onFailure { showMessage("Mezzo giro non riuscito: ${it.message}") }
        }
    }

    /**
     * Prova le azioni del gimbal, una o un intervallo, dalla Diagnostica.
     *
     * È così che sono stati trovati i tre numeri che conosciamo: nessuna estrazione pubblica
     * dei `.proto` contiene il gimbal, quindi l'unico modo onesto è provarli sulla camera
     * guardando l'anteprima. Ogni azione lascia nel log la miniatura prima e dopo, e la
     * scansione ricentra fra un tentativo e l'altro perché ognuno parta dalla stessa
     * inquadratura — senza, dopo la prima azione che muove non si capisce più chi ha fatto cosa.
     */
    fun probeGimbalActions(from: Int, to: Int = from) {
        if (gimbalProbeJob?.isActive == true) {
            showMessage("Scansione delle azioni già in corso")
            return
        }
        val first = from.coerceIn(0, 127)
        val last = to.coerceIn(first, 127).coerceAtMost(first + MAX_GIMBAL_ACTION_SPAN)
        gimbalProbeJob = viewModelScope.launch {
            container.log.info(
                "AZIONI GIMBAL · SCANSIONE $first…$last",
                "Note: 1 muove, 2 ricentra sul lato corrente, 3 commuta fronte/selfie.",
            )
            for (action in first..last) {
                if (action == LunaMessages.GimbalAction.MOVE) {
                    container.log.info("AZIONE GIMBAL 1 · SALTATA", "È il movimento: senza assi non fa nulla.")
                    continue
                }
                val before = container.preview.captureThumbnailJpeg()
                container.log.info(
                    "AZIONE GIMBAL $action · PRIMA",
                    "Invio GIMBAL_CONTROL campo 1 = $action.",
                    imageJpeg = before,
                )
                val sent = container.commands.gimbalAction(action)
                if (sent.isFailure) {
                    container.log.warn("AZIONE GIMBAL $action · NON INVIATA", sent.exceptionOrNull()?.message)
                    continue
                }
                delay(GIMBAL_ACTION_PROBE_WAIT_MS)
                val after = container.preview.captureThumbnailJpeg()
                val verification = WaypointImageVerifier.verify(before, after)
                val moved = verification != null &&
                    verification.displacementPixels > GIMBAL_ACTION_MOVED_PX
                container.log.info(
                    "AZIONE GIMBAL $action · ${if (moved) "HA MOSSO" else "nessun movimento visibile"}",
                    verification?.describe() ?: "Anteprima non confrontabile: guarda lo schermo della camera.",
                    imageJpeg = WaypointImageVerifier.annotatedCurrentJpeg(after, verification),
                )
                if (action != last) {
                    container.gimbal.recenter()
                    delay(GIMBAL_ACTION_PROBE_WAIT_MS)
                    container.gimbal.setEstimated(0f, 0f)
                }
            }
            showMessage("Scansione $first…$last finita · leggi il log")
        }
    }

    /** Zero di accensione: prima il lato fronte, poi il ricentraggio. */
    fun returnToBootZero() {
        viewModelScope.launch {
            container.gimbal.returnToBootZero()
                .onSuccess { showMessage("Gimbal sullo zero di accensione") }
                .onFailure { showMessage("Zero di accensione non raggiunto: ${it.message}") }
        }
    }

    /** Cambia il ramo di cui cercare gli aggiornamenti; vuoto = quello che ha prodotto l'APK. */
    fun setUpdateBranch(branch: String) {
        container.settingsStore.update { it.copy(updateBranch = branch.trim()) }
    }

    /** Scrive il codice dell'azione selfie trovata con la prova qui sopra. */
    fun setSelfieActionCode(code: Int) {
        updateGimbal { it.copy(selfieActionCode = code.coerceIn(0, 127)) }
    }

    fun goToWaypoint(waypoint: Waypoint, seconds: Float = 3f) {
        viewModelScope.launch {
            container.gimbal.moveToPosition(waypoint.pan, waypoint.tilt, minimumSeconds = seconds)
                .onFailure { showMessage("Punto non raggiunto: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- waypoint

    fun captureWaypoint() {
        viewModelScope.launch {
            // Acquisisce la posizione solo dopo il vettore zero: così il tempo fra l'ultimo
            // comando e lo stop entra nella stima e non sposta il punto.
            container.gimbal.stop()
            val current = ptz.value
            val jpeg = container.preview.captureThumbnailJpeg()
            val pointIndex = sequence.value.waypoints.size
            val name = nextWaypointName(pointIndex)
            container.sequenceStore.update { seq ->
                seq.copy(
                    waypoints = seq.waypoints + Waypoint(
                        name = name,
                        pan = current.pan,
                        tilt = current.tilt,
                        positionModelVersion = Waypoint.CURRENT_POSITION_MODEL_VERSION,
                        previewJpegBase64 = Waypoint.encodePreview(jpeg),
                    ),
                )
            }
            container.log.info(
                message = "WAYPOINT ${pointIndex + 1} MEMORIZZATO · $name",
                detail = buildString {
                    appendLine("Ordine percorso: ${pointIndex + 1}")
                    appendLine("Pan stimato: %.3f°".format(current.pan))
                    appendLine("Tilt stimato: %.3f°".format(current.tilt))
                    append("Posizione da camera: ${current.fromCamera}")
                    if (jpeg == null) append("\nMiniatura: non disponibile (attiva l'anteprima prima di memorizzare)")
                },
                imageJpeg = jpeg,
            )
            showMessage("Punto memorizzato a %.1f° / %.1f°".format(current.pan, current.tilt))
        }
    }

    fun removeWaypoint(id: String) = container.sequenceStore.update { seq ->
        seq.copy(waypoints = seq.waypoints.filterNot { it.id == id })
    }

    fun moveWaypoint(id: String, delta: Int) = container.sequenceStore.update { seq ->
        val list = seq.waypoints.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in list.indices) return@update seq
        val item = list.removeAt(index)
        list.add(target, item)
        seq.copy(waypoints = list)
    }

    fun renameWaypoint(id: String, name: String) = container.sequenceStore.update { seq ->
        seq.copy(waypoints = seq.waypoints.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun setWaypointDuration(id: String, seconds: Float) = container.sequenceStore.update { seq ->
        seq.copy(waypoints = seq.waypoints.map { if (it.id == id) it.copy(durationToNextSeconds = seconds) else it })
    }

    fun updateWaypointToCurrent(id: String) {
        viewModelScope.launch {
            container.gimbal.stop()
            val current = ptz.value
            val jpeg = container.preview.captureThumbnailJpeg()
            val previous = sequence.value.waypoints.firstOrNull { it.id == id }
            val pointIndex = sequence.value.waypoints.indexOfFirst { it.id == id }
            container.sequenceStore.update { seq ->
                seq.copy(
                    waypoints = seq.waypoints.map {
                        if (it.id == id) {
                            it.copy(
                                pan = current.pan,
                                tilt = current.tilt,
                                positionModelVersion = Waypoint.CURRENT_POSITION_MODEL_VERSION,
                                previewJpegBase64 = Waypoint.encodePreview(jpeg),
                            )
                        } else it
                    },
                )
            }
            container.log.info(
                message = "WAYPOINT ${pointIndex + 1} AGGIORNATO · ${previous?.name ?: id}",
                detail = buildString {
                    previous?.let {
                        appendLine("Prima: pan %.3f° · tilt %.3f°".format(it.pan, it.tilt))
                    }
                    appendLine("Adesso: pan %.3f° · tilt %.3f°".format(current.pan, current.tilt))
                    append("Posizione da camera: ${current.fromCamera}")
                    if (jpeg == null) append("\nMiniatura: non disponibile (attiva l'anteprima prima di aggiornare)")
                },
                imageJpeg = jpeg,
            )
            showMessage("Punto aggiornato a %.1f° / %.1f°".format(current.pan, current.tilt))
        }
    }

    fun clearWaypoints() = container.sequenceStore.update { it.copy(waypoints = emptyList()) }

    /**
     * Toglie l'ultimo punto memorizzato: è il gesto di chi si è accorto subito di aver sbagliato.
     *
     * Dal mirino serve questo e non lo svuotamento: un percorso si costruisce un punto per
     * volta guardando l'inquadratura, e l'errore che si fa è memorizzare quello appena
     * sbagliato. Cancellarli tutti resta nel pannello delle automazioni, dove si vede la lista
     * e si sa cosa si sta buttando via.
     */
    fun removeLastWaypoint() = container.sequenceStore.update { seq ->
        if (seq.waypoints.isEmpty()) seq else seq.copy(waypoints = seq.waypoints.dropLast(1))
    }

    // ---------------------------------------------------------------- sequenza

    fun setTotalDuration(seconds: Float) =
        container.sequenceStore.update { it.copy(totalDurationSeconds = seconds.coerceAtLeast(1f)) }

    fun setInterval(seconds: Float) =
        container.sequenceStore.update { it.copy(intervalSeconds = seconds.coerceAtLeast(0.1f)) }

    fun setInterpolation(mode: InterpolationMode) =
        container.sequenceStore.update { it.copy(interpolation = mode) }

    fun setUseTotalDuration(enabled: Boolean) =
        container.sequenceStore.update { it.copy(useTotalDuration = enabled) }

    fun setControlRecording(enabled: Boolean) =
        container.sequenceStore.update { it.copy(controlRecording = enabled) }

    fun setConfigureCameraTimelapse(enabled: Boolean) =
        container.sequenceStore.update { it.copy(configureCameraTimelapse = enabled) }

    fun setShootingMode(mode: ShootingMode) =
        container.sequenceStore.update { it.copy(mode = mode) }

    /**
     * Scelta della modalità guidata dal pannello della sequenza.
     *
     * Aggiorna anche la ghiera del mirino: sono due modi di dire la stessa cosa, e vederli in
     * disaccordo — pannello su «panorama», ghiera su «video» — è peggio che non averne uno.
     */
    fun selectSequenceMode(mode: ShootingMode) {
        setShootingMode(mode)
        _captureMode.value = CaptureMode.forSequence(mode)
    }

    fun setShotsPerLeg(shots: Int) =
        container.sequenceStore.update { it.copy(shotsPerLeg = shots.coerceIn(2, 200)) }

    fun setSettleSeconds(seconds: Float) =
        container.sequenceStore.update { it.copy(settleSeconds = seconds.coerceIn(0f, 30f)) }

    fun setPanoramaHorizontalDegrees(degrees: Float) =
        container.sequenceStore.update { it.copy(panoramaHorizontalDegrees = degrees.coerceIn(1f, 360f)) }

    fun setPanoramaVerticalDegrees(degrees: Float) =
        container.sequenceStore.update { it.copy(panoramaVerticalDegrees = degrees.coerceIn(0f, 180f)) }

    fun setPanoramaOverlap(percent: Int) =
        container.sequenceStore.update { it.copy(panoramaOverlapPercent = percent.coerceIn(10, 60)) }

    /** Applica una copertura pronta: i due angoli insieme, perché è la coppia a dare la forma. */
    fun setPanoramaPreset(preset: PanoramaPreset) {
        container.sequenceStore.update {
            it.copy(
                panoramaHorizontalDegrees = preset.horizontalDegrees,
                panoramaVerticalDegrees = preset.verticalDegrees,
            )
        }
    }

    fun setPanoramaAspect(aspect: PhotoFrameAspect) =
        container.sequenceStore.update { it.copy(panoramaAspect = aspect) }

    /** Crea la griglia a serpentina solo se tutta la copertura rientra nei fine corsa misurati. */
    fun createPanoramaPlan() {
        val profile = gimbalCalibration.value
        if (!profile.isValid) {
            showMessage("Esegui prima la calibrazione completa dei fine corsa")
            return
        }
        val seq = sequence.value
        val current = container.gimbal.position.value
        val zoom = settings.value.photo.zoomScale
        // Nello scatto sferico i gradi non li sceglie chi scatta: li detta la corsa misurata
        // dalla calibrazione, e il centro è il centro della corsa invece dell'inquadratura
        // attuale — una sfera non ha un davanti, e partire da dove si guarda adesso
        // sprecherebbe metà della corsa da un lato.
        val fov = LunaOptics.fieldOfView(zoom, seq.panoramaAspect)
        val spherical = sphericalCoverage(
            panMinimumDeg = profile.panLimits.minimumDeg,
            panMaximumDeg = profile.panLimits.maximumDeg,
            tiltMinimumDeg = profile.tiltLimits.minimumDeg,
            tiltMaximumDeg = profile.tiltLimits.maximumDeg,
            horizontalFovDegrees = fov.horizontalDegrees,
            verticalFovDegrees = fov.verticalDegrees,
        )
        val result = runCatching {
            PanoramaPlanner.plan(
                centerPan = if (seq.panoramaSpherical) spherical.centerPanDegrees else current.pan,
                centerTilt = if (seq.panoramaSpherical) spherical.centerTiltDegrees else current.tilt,
                horizontalCoverage = if (seq.panoramaSpherical) {
                    spherical.horizontalDegrees
                } else {
                    seq.panoramaHorizontalDegrees
                },
                verticalCoverage = if (seq.panoramaSpherical) {
                    spherical.verticalDegrees
                } else {
                    seq.panoramaVerticalDegrees
                },
                overlapPercent = if (seq.panoramaSpherical) {
                    SPHERICAL_OVERLAP_PERCENT
                } else {
                    seq.panoramaOverlapPercent
                },
                zoomScale = zoom,
                aspect = seq.panoramaAspect,
                panLimits = profile.panLimits,
                tiltLimits = profile.tiltLimits,
            ).getOrThrow()
        }
        result.onSuccess { plan ->
            container.sequenceStore.update {
                it.copy(
                    mode = ShootingMode.FOTO,
                    waypoints = plan.waypoints,
                    shotsPerLeg = 2,
                    useTotalDuration = false,
                )
            }
            _captureMode.value = CaptureMode.forSequence(ShootingMode.FOTO)
            container.log.info(
                "PANORAMA PIANIFICATO",
                buildString {
                    appendLine("Copertura: ${seq.panoramaHorizontalDegrees.toInt()}° × ${seq.panoramaVerticalDegrees.toInt()}°")
                    appendLine("Zoom: ${zoom}× · FOV stimato %.1f° × %.1f°".format(
                        plan.fieldOfView.horizontalDegrees,
                        plan.fieldOfView.verticalDegrees,
                    ))
                    appendLine("Sovrapposizione: ${seq.panoramaOverlapPercent}%")
                    appendLine("Griglia: ${plan.columns} × ${plan.rows} · ${plan.totalShots} scatti")
                    append("Scatti per fila: ${plan.columnsPerRow.joinToString("·")}")
                    if (plan.shotsSavedAtPoles > 0) {
                        append(
                            " (${plan.shotsSavedAtPoles} risparmiati in alto: lassù un fotogramma " +
                                "copre molti più gradi di pan)",
                        )
                    }
                },
            )
            _panoramaPlan.value = plan
            showMessage("Panorama pronto: ${plan.columns}×${plan.rows}, ${plan.totalShots} scatti")
        }.onFailure { error ->
            _panoramaPlan.value = null
            showMessage(error.message ?: "Il panorama non entra nei fine corsa disponibili")
        }
    }

    /**
     * L'anteprima del piano: quante colonne, quante righe, quanti scatti.
     *
     * Si ricalcola a ogni cambio di copertura, obiettivo o sovrapposizione, perché il numero
     * di scatti è la cosa che decide se la panoramica vale la pena: 12 scatti sono un minuto,
     * 40 sono cinque, e conviene saperlo prima di cominciare, non dopo.
     */
    private val _panoramaPlan = MutableStateFlow<PanoramaPlan?>(null)
    val panoramaPlan: StateFlow<PanoramaPlan?> = _panoramaPlan

    /** Ricalcola il piano senza muovere niente: serve solo a mostrarne le dimensioni. */
    fun refreshPanoramaPreview() {
        val profile = gimbalCalibration.value
        if (!profile.isValid) {
            _panoramaPlan.value = null
            return
        }
        val seq = sequence.value
        val current = container.gimbal.position.value
        _panoramaPlan.value = PanoramaPlanner.plan(
            centerPan = current.pan,
            centerTilt = current.tilt,
            horizontalCoverage = seq.panoramaHorizontalDegrees,
            verticalCoverage = seq.panoramaVerticalDegrees,
            overlapPercent = seq.panoramaOverlapPercent,
            zoomScale = settings.value.photo.zoomScale,
            aspect = seq.panoramaAspect,
            panLimits = profile.panLimits,
            tiltLimits = profile.tiltLimits,
        ).getOrNull()
    }

    /** Pianifica e parte: per chi scatta una panoramica, sono un gesto solo. */
    /**
     * La sovrapposizione dello scatto sferico, che non si tocca.
     *
     * Al 20% i fotogrammi si accavallano abbastanza da unirsi bene anche vicino ai poli, dove i
     * meridiani si stringono e due scatti affiancati condividono molto meno di quanto dicano i
     * gradi di longitudine che li separano. Più giù la giunzione in alto e in basso si apre.
     */
    private val SPHERICAL_OVERLAP_PERCENT = 20

    /** Quante volte richiedere l'elenco aspettando che la camera abbia finito di scrivere. */
    private val FILE_SETTLE_ATTEMPTS = 8

    /** Pausa fra una lettura e l'altra: la camera salva una foto in poco più di un secondo. */
    private val FILE_SETTLE_DELAY_MS = 1_500L

    /** Quanto sta fermo il dito prima che l'anteprima si ridisegni. */
    private val POINT_OF_VIEW_SETTLE_MS = 90L

    /** Il lato lungo dell'anteprima: abbastanza da giudicare, abbastanza poco da seguire il dito. */
    private val POINT_OF_VIEW_LONG_SIDE = 640

    /** Oltre lo zenit non si va: la panoramica si rovescerebbe. */
    private val POINT_OF_VIEW_MAX_TILT = 89f

    /** Stato dell'unione automatica, per il pannello della panoramica. */
    private val _stitchState = MutableStateFlow<StitchUiState>(StitchUiState.Idle)
    val stitchState: StateFlow<StitchUiState> = _stitchState

    /**
     * La fase intermedia: la cucitura si è fermata e aspetta che si scelga da dove guardare.
     *
     * È un'attesa vera, non una schermata che si può ignorare: la corutina della cucitura sta
     * ferma su un `await` finché non arriva una risposta. Per questo la finestra si chiude solo
     * con un tasto — non con il tasto indietro, non uscendo dal pannello — e per questo, quando
     * si chiude, la promessa viene sempre mantenuta: una corutina lasciata ad aspettare per
     * sempre e` un lavoro che non finisce e una panoramica che non arriva.
     */
    private val _pointOfViewOpen = MutableStateFlow(false)
    val pointOfViewOpen: StateFlow<Boolean> = _pointOfViewOpen

    /** Il punto di vista scelto in questo momento. */
    private val _pointOfView = MutableStateFlow(PanoramaView())
    val pointOfView: StateFlow<PanoramaView> = _pointOfView

    /** L'anteprima dipinta, con quanti gradi copre: senza i gradi il dito non combacia. */
    private val _pointOfViewImage = MutableStateFlow<PreviewImage?>(null)
    val pointOfViewImage: StateFlow<PreviewImage?> = _pointOfViewImage

    /** Che forma prende la panoramica così: proiezione, quanto sale, quanto deforma. */
    private val _pointOfViewShape = MutableStateFlow<PreviewShape?>(null)
    val pointOfViewShape: StateFlow<PreviewShape?> = _pointOfViewShape

    private var pointOfViewPainter: PanoramaStitcher.Preview? = null
    private var pointOfViewAnswer: CompletableDeferred<PanoramaView?>? = null
    private var pointOfViewJob: Job? = null

    /**
     * Quello che la cucitura chiama quando arriva alla fase intermedia.
     *
     * Apre la finestra, dipinge la prima anteprima e si mette ad aspettare. Il `finally` non è
     * prudenza: se la cucitura viene annullata mentre si sta scegliendo, la finestra deve
     * chiudersi da sola, altrimenti resta lì a chiedere una risposta per una panoramica che non
     * esiste più.
     */
    private suspend fun choosePointOfView(preview: PanoramaStitcher.Preview): PanoramaView? {
        val answer = CompletableDeferred<PanoramaView?>()
        pointOfViewPainter = preview
        pointOfViewAnswer = answer
        _pointOfView.value = preview.suggested
        _pointOfViewImage.value = null
        _pointOfViewOpen.value = true
        repaintPointOfView(immediate = true)
        return try {
            answer.await()
        } finally {
            _pointOfViewOpen.value = false
            pointOfViewJob?.cancel()
            pointOfViewJob = null
            pointOfViewPainter = null
            pointOfViewAnswer = null
            _pointOfViewImage.value = null
            _pointOfViewShape.value = null
        }
    }

    /**
     * Ridipinge l'anteprima, ma non a ogni pixel del dito.
     *
     * I numeri della deformazione si aggiornano subito, perché costano niente e sono quelli che
     * si guardano mentre si muove. Il disegno invece aspetta un attimo di fermo: ridipingerlo a
     * ogni frame del trascinamento vorrebbe dire buttare via nove disegni su dieci prima ancora
     * che finiscano, e il decimo arriverebbe comunque in ritardo.
     */
    private fun repaintPointOfView(immediate: Boolean = false) {
        val painter = pointOfViewPainter ?: return
        val view = _pointOfView.value
        _pointOfViewShape.value = runCatching { painter.deformation(view) }.getOrNull()
        pointOfViewJob?.cancel()
        pointOfViewJob = viewModelScope.launch {
            if (!immediate) delay(POINT_OF_VIEW_SETTLE_MS)
            runCatching { painter.paint(view, POINT_OF_VIEW_LONG_SIDE) }
                .onSuccess { _pointOfViewImage.value = it }
        }
    }

    /** Il trascinamento: gira la panoramica di tanti gradi quanti il dito ne ha attraversati. */
    fun dragPointOfView(panDegrees: Float, tiltDegrees: Float) {
        val now = _pointOfView.value
        _pointOfView.value = now.copy(
            panDegrees = now.panDegrees + panDegrees,
            tiltDegrees = (now.tiltDegrees + tiltDegrees).coerceIn(-POINT_OF_VIEW_MAX_TILT, POINT_OF_VIEW_MAX_TILT),
        )
        repaintPointOfView()
    }

    fun setPointOfViewRoll(rollDegrees: Float) {
        _pointOfView.value = _pointOfView.value.copy(rollDegrees = rollDegrees)
        repaintPointOfView()
    }

    fun setPointOfViewProjection(projection: StitchProjection?) {
        _pointOfView.value = _pointOfView.value.copy(projection = projection)
        repaintPointOfView(immediate = true)
    }

    fun setPointOfViewLimit(limitDegrees: Float) {
        _pointOfView.value = _pointOfView.value.copy(verticalLimitDegrees = limitDegrees)
        repaintPointOfView(immediate = true)
    }

    /** Torna al punto di vista che la cucitura avrebbe scelto da sola. */
    fun resetPointOfView() {
        _pointOfView.value = pointOfViewPainter?.suggested ?: PanoramaView()
        repaintPointOfView(immediate = true)
    }

    /** «Cuci così»: la piena risoluzione parte con questo punto di vista. */
    fun confirmPointOfView() {
        pointOfViewAnswer?.complete(_pointOfView.value)
    }

    /** «Lascia decidere all'app»: si va avanti come se la fase intermedia non ci fosse stata. */
    fun skipPointOfView() {
        pointOfViewAnswer?.complete(null)
    }

    /** L'elenco dei file com'era prima di scattare: serve a riconoscere quelli nuovi. */
    private var filesBeforePanorama: List<MediaItem> = emptyList()
    private var stitchJob: Job? = null

    /** Dove guardava il gimbal prima della panoramica: a corsa finita ci si torna. */
    private var panoramaReturnPosition: Pair<Float, Float>? = null

    /** La risoluzione che la camera aveva prima di abbassarla per una panoramica di taratura. */
    private var photoSizeBeforePanorama: Int? = null

    /**
     * Riporta il gimbal all'inquadratura di prima della panoramica.
     *
     * Una panoramica finisce sempre nell'ultimo angolo del giro, che non è mai quello che
     * interessa: chi aveva inquadrato una scena se la ritrova, senza dover ricomporre a mano.
     * Vale solo per le corse del pianificatore — le sequenze disegnate a mano finiscono dove
     * il loro autore ha deciso che finiscano.
     */
    private fun returnGimbalAfterPanorama() {
        restorePhotoSizeAfterPanorama()
        val target = panoramaReturnPosition ?: return
        panoramaReturnPosition = null
        if (!sequence.value.waypoints.all { it.generatedByPanoramaPlanner }) return
        viewModelScope.launch {
            container.gimbal.moveToPositionAtMaximum(target.first, target.second)
                .onSuccess {
                    container.log.info(
                        "Gimbal tornato all'inquadratura di partenza (%.1f° / %.1f°)"
                            .format(target.first, target.second),
                    )
                }
                .onFailure {
                    container.log.warn("Ritorno all'inquadratura di partenza non riuscito: ${it.message}")
                }
        }
    }

    /**
     * Rimette la risoluzione che c'era prima della panoramica.
     *
     * Abbassarla e lasciarla abbassata sarebbe il modo di far scattare a dodici megapixel una
     * foto che si voleva grande, e di scoprirlo dopo. Quindi la si ripristina appena la corsa
     * finisce, dallo stesso punto in cui il gimbal torna all'inquadratura di partenza.
     */
    private fun restorePhotoSizeAfterPanorama() {
        val previous = photoSizeBeforePanorama ?: return
        photoSizeBeforePanorama = null
        viewModelScope.launch {
            container.commands.setPhotoSize(previous, useSizeId = photoSizeUsesSizeId)
                .onSuccess {
                    updatePhotoSettings { it.copy(photoSizeCode = previous) }
                    container.log.info(
                        "Risoluzione foto ripristinata: ${LunaProtocolCodes.PhotoSize.label(previous)}",
                    )
                }
                .onFailure {
                    container.log.warn(
                        "Risoluzione foto non ripristinata: resta a " +
                            LunaProtocolCodes.PhotoSize.label(LunaProtocolCodes.PhotoSize.STANDARD_12MP),
                        it.message,
                    )
                }
        }
    }

    fun setPanoramaLowResolution(enabled: Boolean) =
        container.sequenceStore.update { it.copy(panoramaLowResolution = enabled) }

    /** Chiude la carta dell'unione: l'errore resta finché non lo si è letto. */
    fun clearStitchState() {
        _stitchState.value = StitchUiState.Idle
    }

    fun setAutoStitchPanorama(enabled: Boolean) =
        container.sequenceStore.update { it.copy(autoStitchPanorama = enabled) }

    fun setMoveWhileSaving(enabled: Boolean) =
        container.sequenceStore.update { it.copy(moveWhileSaving = enabled) }

    fun setPanoramaSpherical(enabled: Boolean) {
        container.sequenceStore.update { it.copy(panoramaSpherical = enabled) }
        refreshPanoramaPreview()
    }

    /**
     * Scatta la panoramica, e se l'unione automatica è accesa se ne ricorda l'inizio.
     *
     * La fotografia dell'elenco file va presa *prima* di scattare: è l'unico modo di sapere
     * quali file sono nuovi, perché la camera non dice come chiama quello che salva.
     */
    fun shootPanorama() {
        createPanoramaPlan()
        if (sequence.value.waypoints.size < 2) return
        _stitchState.value = StitchUiState.Idle
        // Dove sta guardando adesso: a panoramica finita si torna qui, perché chi ha
        // inquadrato una scena non vuole ritrovarsi il gimbal puntato nell'ultimo angolo.
        panoramaReturnPosition = ptz.value.pan to ptz.value.tilt
        viewModelScope.launch {
            // A dodici megapixel, quando servono i numeri e non i pixel: l'unione lavora
            // comunque a 3200 px, e da scaricare sono un terzo.
            val wanted = LunaProtocolCodes.PhotoSize.STANDARD_12MP
            val current = settings.value.photo.photoSizeCode
            if (sequence.value.panoramaLowResolution && current != wanted) {
                container.commands.setPhotoSize(wanted, useSizeId = photoSizeUsesSizeId)
                    .onSuccess {
                        photoSizeBeforePanorama = current
                        updatePhotoSettings { it.copy(photoSizeCode = wanted) }
                        container.log.info(
                            "Panoramica a bassa risoluzione: scatti a " +
                                LunaProtocolCodes.PhotoSize.label(wanted) +
                                " (si torna a ${LunaProtocolCodes.PhotoSize.label(current)} a fine corsa)",
                        )
                    }
                    .onFailure {
                        container.log.warn("Risoluzione non abbassata: si scatta com'era", it.message)
                    }
            }
            filesBeforePanorama = if (sequence.value.autoStitchPanorama) {
                container.media.list().getOrElse { emptyList() }
            } else {
                emptyList()
            }
            startRun()
        }
    }

    /**
     * L'elenco dei file quando la camera ha smesso di aggiungerne.
     *
     * Il salvataggio è asincrono e continua dopo l'ultimo scatto. Chiedere l'elenco subito dà un
     * conto parziale, e con un conto parziale l'unione non parte — è successo davvero: ventitré
     * scatti comandati, tredici file trovati. Si richiede finché due letture di fila danno lo
     * stesso numero, oppure finché scade il tempo: a quel punto quello che c'è è quello che c'è,
     * e chi legge il messaggio lo sa.
     */
    private suspend fun awaitSettledFileList(
        baseline: Int,
        expected: Set<String>,
    ): Result<List<MediaItem>> {
        var previous = -1
        var latest: List<MediaItem> = emptyList()
        repeat(FILE_SETTLE_ATTEMPTS) { attempt ->
            delay(FILE_SETTLE_DELAY_MS)
            val listed = container.media.list().getOrElse { return Result.failure(it) }
            latest = listed
            // Quando la camera ha detto come si chiamano le foto, non c'è niente da aspettare
            // oltre: appena ci sono tutte l'elenco è quello buono, senza dover stare a vedere
            // se il conto smette di crescere.
            if (expected.isNotEmpty() && listed.mapTo(mutableSetOf()) { it.name }.containsAll(expected)) {
                return Result.success(listed)
            }
            val fresh = listed.size - baseline
            _stitchState.value = StitchUiState.Working(
                0f,
                if (expected.isEmpty()) {
                    "Aspetto che la camera finisca di salvare · $fresh file nuovi"
                } else {
                    val found = listed.count { it.name in expected }
                    "Aspetto che la camera finisca di salvare · $found di ${expected.size}"
                },
            )
            if (listed.size == previous && attempt > 0) return Result.success(listed)
            previous = listed.size
        }
        return Result.success(latest)
    }

    /**
     * Unisce foto già esistenti, scelte a mano: il banco di prova dell'unione.
     *
     * Serve a lavorare sulla qualità senza rifare gli scatti ogni volta. Le foto selezionate
     * nella galleria della camera si ordinano da sole per ora di scatto — l'ordine in cui sono
     * state fatte — si scaricano e si uniscono come una fila orizzontale. Gli angoli veri non
     * ci sono: il passo si assume dal campo visivo dello zoom attuale e dalla sovrapposizione
     * scelta nel pannello, e l'allineamento trova il resto.
     */
    fun stitchSelectedFromCamera() {
        if (stitchJob?.isActive == true) {
            showMessage("Un'unione è già in corso")
            return
        }
        val chosen = _gallery.value.items
            .filter { it.path in _gallery.value.selected && !it.isVideo }
            .sortedWith(compareBy({ it.takenAtMs }, { it.name }))
        if (chosen.size < 2) {
            showMessage("Seleziona almeno due foto (non video) da unire")
            return
        }
        stitchJob = viewModelScope.launch {
            _stitchState.value = StitchUiState.Working(0f, "Scarico ${chosen.size} foto dalla camera")
            val files = mutableListOf<java.io.File>()
            chosen.forEachIndexed { index, item ->
                val file = container.media.cache(item) { fraction ->
                    _stitchState.value = StitchUiState.Working(
                        0.25f * (index + fraction) / chosen.size,
                        "Scarico ${item.name}",
                    )
                }.getOrElse {
                    _stitchState.value = StitchUiState.Failed("Scaricamento di ${item.name} non riuscito: ${it.message}")
                    return@launch
                }
                files += file
            }
            stitchFiles(files, downloadShare = 0.25f)
        }
    }

    /**
     * Crea un job di unione dalle foto selezionate nella camera, senza unirle adesso.
     *
     * È il banco di prova fisso: le foto difficili si scaricano **una volta sola** e restano
     * come job, e da lì la stessa terna si riunisce quante volte si vuole per confrontare le
     * ricette. Senza questo, ogni prova costava una selezione e uno scaricamento da capo.
     */
    fun createJobFromSelectedCamera() {
        if (stitchJob?.isActive == true) {
            showMessage("Un'unione è già in corso")
            return
        }
        val chosen = _gallery.value.items
            .filter { it.path in _gallery.value.selected && !it.isVideo }
            .sortedWith(compareBy({ it.takenAtMs }, { it.name }))
        if (chosen.size < 2) {
            showMessage("Seleziona almeno due foto (non video) per creare un job")
            return
        }
        val fov = LunaOptics.fieldOfView(settings.value.photo.zoomScale, sequence.value.panoramaAspect)
        stitchJob = viewModelScope.launch {
            _stitchState.value = StitchUiState.Working(0f, "Preparo un job con ${chosen.size} foto")
            val panoramaId = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            container.stitchJob.collectChosenForJob(
                items = chosen,
                panoramaId = panoramaId,
                onProgress = { fraction, message ->
                    _stitchState.value = StitchUiState.Working(fraction, message)
                },
            ).onSuccess { files ->
                container.panoJobStore.update { list ->
                    list.copy(
                        jobs = list.jobs + PanoJob(
                            id = panoramaId,
                            createdAtMs = System.currentTimeMillis(),
                            files = files.map { it.absolutePath },
                            fovDegrees = fov.horizontalDegrees,
                            spherical = false,
                        ),
                    )
                }
                _stitchState.value = StitchUiState.Queued(panoramaId, files.size)
                clearSelection()
                showMessage("Job di prova creato: ${files.size} foto, lo lanci dalla scheda dei lavori")
            }.onFailure {
                _stitchState.value = StitchUiState.Failed(it.message ?: "job non creato")
                showMessage("Job non creato: ${it.message}")
            }
        }
    }

    /**
     * Unisce foto scelte dalla galleria del telefono, nell'ordine in cui sono state toccate.
     *
     * Il selettore di sistema restituisce le foto nell'ordine della scelta: toccarle una, due,
     * tre come sono state scattate è il modo di dare l'ordine.
     */
    fun stitchPickedPhotos(context: android.content.Context, uris: List<android.net.Uri>) {
        if (uris.size < 2) {
            showMessage("Scegli almeno due foto, nell'ordine in cui sono state scattate")
            return
        }
        if (stitchJob?.isActive == true) {
            showMessage("Un'unione è già in corso")
            return
        }
        stitchJob = viewModelScope.launch {
            _stitchState.value = StitchUiState.Working(0f, "Leggo ${uris.size} foto dal telefono")
            val files = withContext(Dispatchers.IO) {
                runCatching {
                    uris.mapIndexed { index, uri ->
                        val target = java.io.File(context.cacheDir, "stitch-input-$index.jpg")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("la foto ${index + 1} non si apre")
                        target
                    }
                }
            }.getOrElse {
                _stitchState.value = StitchUiState.Failed("Foto non leggibili: ${it.message}")
                return@launch
            }
            stitchFiles(files, downloadShare = 0.05f)
        }
    }

    /** Il tratto comune: fila orizzontale nell'ordine dato, FOV dallo zoom, unione, esito. */
    private suspend fun stitchFiles(files: List<java.io.File>, downloadShare: Float) {
        val seq = sequence.value
        val fov = LunaOptics.fieldOfView(settings.value.photo.zoomScale, seq.panoramaAspect)
        container.stitchJob.runOnFiles(
            files = files,
            horizontalFovDegrees = effectiveFov(fov.horizontalDegrees),
            overlapPercent = seq.panoramaOverlapPercent,
            tuning = stitchTuning(),
            testMode = settings.value.stitch.testMode,
            onPreview = if (settings.value.stitch.chooseViewpoint) ::choosePointOfView else null,
            onProgress = { fraction, message ->
                _stitchState.value = StitchUiState.Working(
                    downloadShare + (1f - downloadShare) * fraction,
                    message,
                )
            },
        ).onSuccess {
            _stitchState.value = it
            showMessage("Panoramica unita: ${it.fileName}")
        }.onFailure {
            _stitchState.value = StitchUiState.Failed(it.message ?: "unione non riuscita")
        }
    }

    /**
     * Mette in coda gli scatti appena finiti, se erano di una panoramica e l'opzione è accesa.
     *
     * Non unisce più subito: scarica gli scatti in `DCIM › Luna Ultra › Panoramiche`, li marca
     * con il passaporto EXIF, e registra un job. Unire sono minuti di calcolo e possono
     * aspettare la sera; scaricare no, va fatto finché la camera è a portata di Wi-Fi. Chi
     * scatta riprende subito a fare altro, e i job si lanciano dalla scheda in basso a destra.
     */
    private fun stitchPanoramaIfRequested(shots: List<ShotAngle>) {
        val seq = sequence.value
        if (!seq.autoStitchPanorama || shots.size < 2) return
        if (!seq.waypoints.all { it.generatedByPanoramaPlanner }) return
        if (stitchJob?.isActive == true) return
        val fov = LunaOptics.fieldOfView(settings.value.photo.zoomScale, seq.panoramaAspect)
        val before = filesBeforePanorama
        val spherical = seq.panoramaSpherical
        stitchJob = viewModelScope.launch {
            _stitchState.value = StitchUiState.Working(0f, "Aspetto che la camera finisca di salvare")
            // La camera scrive i file dopo aver risposto allo scatto: chiedere l'elenco appena
            // finita la sequenza vuol dire contarne meno di quanti ce ne sono. Si aspetta che
            // il conto smetta di crescere, che è il solo modo di sapere che ha finito davvero.
            val expected = shots.mapNotNull { it.uri?.substringAfterLast('/') }
                .takeIf { it.size == shots.size }
                .orEmpty()
                .toSet()
            val after = awaitSettledFileList(before.size, expected).getOrElse {
                _stitchState.value = StitchUiState.Failed("Elenco dei file non disponibile: ${it.message}")
                return@launch
            }
            val panoramaId = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            container.stitchJob.collectForJob(
                before = before,
                after = after,
                angles = shots,
                horizontalFovDegrees = fov.horizontalDegrees,
                panoramaId = panoramaId,
                onProgress = { fraction, message ->
                    _stitchState.value = StitchUiState.Working(fraction, message)
                },
            ).onSuccess { files ->
                container.panoJobStore.update { list ->
                    list.copy(
                        jobs = list.jobs + PanoJob(
                            id = panoramaId,
                            createdAtMs = System.currentTimeMillis(),
                            files = files.map { it.absolutePath },
                            fovDegrees = fov.horizontalDegrees,
                            spherical = spherical,
                        ),
                    )
                }
                _stitchState.value = StitchUiState.Queued(panoramaId, files.size)
                showMessage("Panoramica in coda: ${files.size} scatti al sicuro sul telefono")
            }.onFailure {
                _stitchState.value = StitchUiState.Failed(it.message ?: "scaricamento non riuscito")
                showMessage("Panoramica non messa in coda: ${it.message}")
            }
        }
    }

    /**
     * CPU e memoria mentre l'unione lavora, per chi sta aspettando.
     *
     * Un'unione dura minuti e diceva solo «Cucio Foto 4»: da fuori non si distingue una
     * macchina che macina da una che si è impantanata. La sonda vive quanto dura l'unione e
     * non un istante di più — campionare `/proc` a vuoto non serve a nessuno.
     */
    private val _stitchVitals = MutableStateFlow<StitchVitals?>(null)
    val stitchVitals: StateFlow<StitchVitals?> = _stitchVitals
    private var vitalsJob: Job? = null

    /**
     * La sottoscrizione parte da qui e non dall'`init` in cima, per la regola scritta lassù:
     * un osservatore non può toccare proprietà dichiarate più in basso di dove parte. Questo
     * legge `_stitchState`, che sta mille righe sopra ma pur sempre sotto quell'`init` —
     * metterlo lì ha chiuso l'app all'avvio, esattamente come il commento avvertiva.
     */
    init {
        observeStitchVitals()
        // Anche il servizio in primo piano guarda `_stitchState`, quindi vale la stessa
        // regola: da qui in giù, non dall'`init` in cima.
        observeForegroundService()
    }

    private fun observeStitchVitals() {
        viewModelScope.launch {
            _stitchState.collect { state ->
                if (state is StitchUiState.Working) {
                    if (vitalsJob == null) startStitchVitals()
                } else {
                    vitalsJob?.cancel()
                    vitalsJob = null
                    _stitchVitals.value = null
                }
            }
        }
    }

    private fun startStitchVitals() {
        val probe = ProcessVitals()
        vitalsJob = viewModelScope.launch {
            // Il primo campione fissa solo la base dei tempi di CPU: la differenza esiste
            // dal secondo in poi, e mostrare zero core occupati sarebbe una bugia.
            probe.sample()
            while (isActive) {
                delay(VITALS_INTERVAL_MS)
                _stitchVitals.value = probe.sample()
            }
        }
    }

    /** I lavori in attesa, per la scheda dei job nel mirino. */
    val panoJobs: StateFlow<PanoJobList> = container.panoJobStore.state

    /**
     * Lancia l'unione di un job: la parte lenta, quando lo decide chi ha scattato.
     *
     * Le foto portano il passaporto EXIF, quindi ordine e angoli sono esatti. Solo se tutto
     * va a buon fine gli scatti temporanei si cancellano e il job sparisce; un'unione fallita
     * lascia tutto com'era, pronta per riprovare.
     */
    fun runPanoJob(job: PanoJob) {
        if (stitchJob?.isActive == true) {
            showMessage("Un'unione è già in corso")
            return
        }
        stitchJob = viewModelScope.launch {
            val files = job.files.map { java.io.File(it) }.filter { it.exists() && it.length() > 0 }
            if (files.size < 2) {
                _stitchState.value = StitchUiState.Failed(
                    "Del job restano ${files.size} foto su ${job.files.size}: non si può unire. " +
                        "Se le hai spostate, riportale in DCIM › Luna Ultra › Panoramiche.",
                )
                return@launch
            }
            val testMode = settings.value.stitch.testMode
            _stitchState.value = StitchUiState.Working(
                0f,
                if (testMode) "Modalità test: provo le ricette su 3 foto" else "Unisco le ${files.size} foto del job",
            )
            container.stitchJob.runOnFiles(
                files = files,
                horizontalFovDegrees = effectiveFov(job.fovDegrees),
                overlapPercent = sequence.value.panoramaOverlapPercent,
                fillNadir = job.spherical,
                shotAtMs = job.createdAtMs,
                tuning = stitchTuning(),
                testMode = testMode,
                onProgress = { fraction, message ->
                    _stitchState.value = StitchUiState.Working(fraction, message)
                },
            ).onSuccess { done ->
                // Gli scatti temporanei e il job si buttano solo a panoramica salvata E se
                // l'interruttore lo chiede: con l'opzione spenta il job resta lì, pronto per
                // la prova successiva — è il banco di prova dell'unione. In modalità test
                // non si butta mai niente: le prove servono a rifare, non a chiudere.
                if (settings.value.deleteJobAfterStitch && !testMode) {
                    withContext(Dispatchers.IO) {
                        container.stitchJob.discardJobFiles(files.map { it.absolutePath })
                    }
                    container.panoJobStore.update { list ->
                        list.copy(jobs = list.jobs.filterNot { it.id == job.id })
                    }
                }
                _stitchState.value = done
                showMessage("Panoramica unita: ${done.fileName}")
            }.onFailure {
                _stitchState.value = StitchUiState.Failed(it.message ?: "unione non riuscita")
            }
        }
    }

    /** A unione riuscita: buttare scatti e job, o tenerli per la prova successiva? */
    fun setDeleteJobAfterStitch(enabled: Boolean) {
        container.settingsStore.update { it.copy(deleteJobAfterStitch = enabled) }
    }

    /** Le manopole dell'unione foto, dalla sezione dedicata delle impostazioni. */
    fun updateStitch(transform: (StitchSettings) -> StitchSettings) =
        container.settingsStore.update { it.copy(stitch = transform(it.stitch)) }

    /**
     * La ricetta dell'unione costruita dalle impostazioni. Il «100%» delle impostazioni
     * diventa 0,99: una correlazione esattamente 1,0 non esiste in virgola mobile, e la
     * soglia a 1,0 butterebbe tutti i punti invece di tenere solo i perfetti.
     */
    private fun stitchTuning(): StitchTuning = tuningOf(settings.value.stitch)

    /** La lettera della ricetta a cui corrispondono le impostazioni, se ce n'è una. */
    fun stitchRecipeLetter(stitch: StitchSettings): String? = StitchTestLab.letterOf(tuningOf(stitch))

    /**
     * Scrive nelle impostazioni la ricetta scelta, così l'unione normale fa esattamente
     * quello che ha fatto quella prova. Senza questo, la lettera che aveva convinto restava
     * chiusa nella modalità test.
     */
    fun applyStitchRecipe(letter: String) {
        container.settingsStore.update { current ->
            val recipe = StitchTestLab.recipes(tuningOf(current.stitch))
                .firstOrNull { it.letter == letter } ?: return@update current
            val tuning = recipe.tuning
            current.copy(
                stitch = current.stitch.copy(
                    levelHorizon = tuning.levelHorizon,
                    seamMinimalDifference = tuning.seamMinimalDifference,
                    localWarp = tuning.localWarp,
                    warpStrength = tuning.warpStrength,
                    multiband = tuning.multiband,
                    focalFreedomPercent = (tuning.focalFreedom * 100).roundToInt(),
                    controlDensity = tuning.candidateScale.roundToInt(),
                ),
            )
        }
    }

    private fun tuningOf(stitch: StitchSettings): StitchTuning {
        return StitchTuning(
            keepNcc = stitch.controlQualityPercent.coerceIn(50, 99) / 100f,
            multiband = stitch.multiband,
            candidateScale = stitch.controlDensity.coerceIn(1, 4).toFloat(),
            seamMinimalDifference = stitch.seamMinimalDifference,
            localWarp = stitch.localWarp,
            focalFreedom = stitch.focalFreedomPercent.coerceIn(0, 35) / 100f,
            warpStrength = stitch.warpStrength,
            projection = when (stitch.projectionCode) {
                0 -> StitchProjection.EQUIRECTANGULAR
                2 -> StitchProjection.MERCATOR
                else -> StitchProjection.CYLINDRICAL
            },
            verticalLimitDegrees = stitch.verticalLimitDegrees,
            levelHorizon = stitch.levelHorizon,
            cameraPitchDegrees = stitch.cameraPitchDegrees,
            gpuRecognise = stitch.gpuRecognise,
            gpuPaint = stitch.gpuPaint,
            gpuBlend = stitch.gpuBlend,
        )
    }

    /**
     * Il campo visivo da usare davvero: quello misurato a mano se c'è, altrimenti il
     * dichiarato. Vale al momento dell'unione, non della creazione del job: così le prove
     * su un job già fatto possono cambiare idea sul campo senza riscaricare le foto.
     */
    private fun effectiveFov(declaredDegrees: Float): Float {
        val override = settings.value.stitch.fovOverrideDegrees
        return if (override in 5f..170f) override else declaredDegrees
    }

    /** Annulla un job: sparisce dall'elenco, le foto restano dove sono. */
    fun cancelPanoJob(job: PanoJob) {
        container.panoJobStore.update { list ->
            list.copy(jobs = list.jobs.filterNot { it.id == job.id })
        }
        showMessage("Job annullato: le foto restano in DCIM › Luna Ultra › Panoramiche")
    }

    fun setStartHoldSeconds(seconds: Float) =
        container.sequenceStore.update { it.copy(startHoldSeconds = seconds.coerceIn(0f, 30f)) }

    fun setEndHoldSeconds(seconds: Float) =
        container.sequenceStore.update { it.copy(endHoldSeconds = seconds.coerceIn(0f, 30f)) }

    /**
     * Avvia la sequenza, oppure spiega perché non può partire.
     *
     * Le condizioni rimaste sono connessione e almeno due punti del percorso. Il comando gimbal
     * non è più una configurazione sperimentale: `0x00E2` è fissato dal protocollo verificato.
     */
    fun startRun(behaviour: ShootingMode? = null) {
        val seq = sequence.value.let { if (behaviour != null) it.copy(mode = behaviour) else it }
        if (connectionState.value != ConnectionState.CONNECTED) {
            showMessage("Connettiti alla camera prima di avviare")
            return
        }
        if (!seq.isRunnable) {
            showMessage("Servono almeno due punti: inquadra e premi il tasto con la bandierina")
            return
        }
        if (seq.hasLegacyWaypoints) {
            showMessage("Aggiorna i vecchi punti con ‘Qui’ oppure rimemorizzali: usano la stima precedente")
            return
        }
        if (seq.hasUnverifiedManualWaypoints) {
            showMessage("Aggiorna ogni punto con ‘Qui’: serve la foto di controllo per verificare partenza e arrivo")
            return
        }
        viewModelScope.launch {
            ensureCameraMode(_captureMode.value)
            container.engine.start(seq)
        }
    }

    /**
     * La durata del percorso, cambiata dal mirino con un passo che segue la scala.
     *
     * Sotto i dieci secondi si va di secondo, sopra di cinque, sopra il minuto di dieci: un
     * passo fisso costringerebbe a venti tocchi per passare da dieci secondi a tre minuti, o a
     * non poter distinguere quattro secondi da cinque.
     */
    fun nudgeTotalDuration(up: Boolean) {
        val current = sequence.value.totalDurationSeconds
        val step = when {
            current < 10f -> 1f
            current < 60f -> 5f
            else -> 10f
        }
        val next = if (up) current + step else current - stepBelow(current)
        setTotalDuration(next)
        if (!sequence.value.useTotalDuration) setUseTotalDuration(true)
    }

    /** Scendendo, il passo è quello dello scalino sotto: altrimenti 10 s tornerebbe a 5 e poi a 0. */
    private fun stepBelow(current: Float): Float = when {
        current <= 10f -> 1f
        current <= 60f -> 5f
        else -> 10f
    }

    fun emergencyStop() {
        container.engine.stop("STOP di emergenza")
        viewModelScope.launch { container.gimbal.stop() }
    }

    private val usesCameraTimelapse: Boolean
        get() = sequence.value.mode == ShootingMode.TIMELAPSE_CAMERA

    fun startRecording() = startCapture(usesCameraTimelapse)

    fun stopRecording() = stopCapture(usesCameraTimelapse)

    private fun startCapture(cameraTimelapse: Boolean) {
        viewModelScope.launch {
            val capture = _captureMode.value.cameraMode.captureMode ?: LunaProtocolCodes.CaptureMode.NORMAL
            container.commands.startRecording(cameraTimelapse, capture)
                .onSuccess {
                    _recordingSinceMs.value = System.currentTimeMillis()
                    _status.value = _status.value.mergedWith(CameraStatus(recording = true))
                    showMessage(if (cameraTimelapse) "Timelapse avviato" else "Registrazione avviata")
                }
                .onFailure { showMessage("Start non riuscito: ${it.message}") }
        }
    }

    private fun stopCapture(cameraTimelapse: Boolean) {
        viewModelScope.launch {
            val capture = _captureMode.value.cameraMode.captureMode ?: LunaProtocolCodes.CaptureMode.NORMAL
            container.commands.stopRecording(cameraTimelapse, capture)
                .onSuccess {
                    _recordingSinceMs.value = 0L
                    _status.value = _status.value.mergedWith(CameraStatus(recording = false))
                    showMessage("Ripresa fermata")
                }
                .onFailure { showMessage("Stop non riuscito: ${it.message}") }
        }
    }

    /** La camera è la fonte di verità: se dice che è ferma, il cronometro si azzera. */
    private fun syncRecordingClock() {
        if (_status.value.recording == false) _recordingSinceMs.value = 0L
    }

    private val isRecording: Boolean
        get() = _recordingSinceMs.value > 0L || _status.value.recording == true

    // ---------------------------------------------------------------- ghiera e scatto

    fun setCaptureMode(mode: CaptureMode) {
        if (_captureMode.value == mode) return
        _captureMode.value = mode
        // Scegliere una modalità guidata dalla ghiera è lo stesso gesto che sceglierla nel
        // pannello della sequenza: deve valere anche là, altrimenti si finisce con due verità.
        mode.sequenceMode?.let(::setShootingMode)
        // E la camera ci va davvero: la ghiera non è un promemoria, è un comando.
        viewModelScope.launch { ensureCameraMode(mode) }
    }

    /**
     * Il pulsante di scatto. Cosa fa dipende dalla ghiera, come su qualunque camera: uno scatto,
     * una registrazione da avviare o fermare, oppure la sequenza sui punti memorizzati.
     */
    fun onShutter() {
        if (photoCountdownJob?.isActive == true) {
            photoCountdownJob?.cancel()
            photoCountdownJob = null
            _photoCountdownSeconds.value = 0
            showMessage("Timer annullato")
            return
        }
        if (connectionState.value != ConnectionState.CONNECTED) {
            showMessage("Connettiti alla camera prima di scattare")
            return
        }
        val mode = _captureMode.value
        // La panoramica dell'app si avvia da qui come tutto il resto: le opzioni stanno nel suo
        // pannello, il via si dà dal mirino. Un pulsante «scatta» dentro un pannello di
        // impostazioni si preme guardando le impostazioni invece dell'inquadratura.
        if (mode.plansPanorama) {
            if (runState.value.running) emergencyStop() else shootPanorama()
            return
        }
        // Un percorso memorizzato vale per qualunque cosa si stia riprendendo: se ci sono due
        // punti e si preme registra, il gimbal li percorre. Prima lo faceva solo se la voce
        // scelta si chiamava «Sequenza…», e memorizzare dei punti in modalità Video non
        // produceva nessun movimento — il che rendeva i punti una cosa che si imposta e poi non
        // succede niente.
        val path = mode.pathBehaviour
        if (path != null && (mode.usesSequence || sequence.value.waypoints.size >= 2)) {
            if (runState.value.running) emergencyStop() else startRun(path)
            return
        }
        val timerSeconds = settings.value.photo.timerSeconds
        if (mode.cameraMode.isPhoto && timerSeconds > 0) {
            photoCountdownJob = viewModelScope.launch {
                for (second in timerSeconds downTo 1) {
                    _photoCountdownSeconds.value = second
                    delay(1_000)
                }
                _photoCountdownSeconds.value = 0
                ensureCameraMode(mode)
                shoot(mode)
                photoCountdownJob = null
            }
            return
        }
        viewModelScope.launch {
            ensureCameraMode(mode)
            when {
                mode.cameraMode.isPhoto -> shoot(mode)
                isRecording -> stopCapture(mode.cameraTimelapse)
                else -> startCapture(mode.cameraTimelapse)
            }
        }
    }

    /** Scatto singolo nella modalità selezionata sulla ghiera. */
    fun takePicture() {
        viewModelScope.launch {
            ensureCameraMode(_captureMode.value)
            shoot(_captureMode.value)
        }
    }

    /**
     * Uno scatto singolo: risposta subito, verifica dietro le quinte.
     *
     * La camera accetta in tre decimi di secondo e scrive il file nei cinque successivi, con un
     * buffer che tiene qualche scatto in coda: far aspettare chi preme il pulsante fino a fine
     * scrittura rende «lentissimo» uno scatto che in realtà è già partito. Quindi la conferma
     * arriva appena la camera accetta — che è quando l'otturatore lavora — e la scrittura la
     * controlla un guardiano in coda: parla solo se qualcosa va storto, e in quel caso riavvia
     * il flusso dell'anteprima, che è la leva che sblocca la camera.
     */
    private suspend fun shoot(mode: CaptureMode) {
        val pano = mode.cameraMode == CameraMode.PANORAMA
        // Senza flusso la camera non chiude la cattura: si accende prima di chiedere lo
        // scatto, non dopo aver scoperto che il file non c'è.
        container.preview.ensureRunningForCapture()
        // La camera può dire «non ora» (notifica 8201, di solito perché sta ancora
        // scrivendo): non è un guasto, è un momento sbagliato. Si riprova da soli, a
        // distanza crescente, e si molla solo dopo aver insistito davvero.
        repeat(SHOT_REFUSED_ATTEMPTS) { attempt ->
            val outcome = container.commands.takePictureConfirmed(instaPano = pano)
                .getOrElse {
                    showMessage("Scatto non riuscito: ${it.message}")
                    return
                }
                .outcome
            when (outcome) {
                is LunaCommands.ShotOutcome.Refused -> {
                    val last = attempt == SHOT_REFUSED_ATTEMPTS - 1
                    container.log.warn(
                        "La camera ha rifiutato lo scatto (${outcome.reason()})",
                        if (last) "Mi arrendo dopo $SHOT_REFUSED_ATTEMPTS tentativi." else "Riprovo da solo fra poco.",
                    )
                    if (last) {
                        showMessage("La camera rifiuta lo scatto (${outcome.reason()}): riprova fra qualche secondo")
                        return
                    }
                    showMessage("La camera è ${outcome.reason()}: riprovo…")
                    delay(SHOT_REFUSED_RETRY_MS * (attempt + 1))
                }

                // Accettato — o nessun verdetto, e allora si fa come prima: il guardiano
                // dei file dirà se lo scatto esiste davvero.
                else -> {
                    showMessage(if (pano) "Panoramica in corso" else "Scatto eseguito")
                    markGalleryStale()
                    watchCaptureCompletion()
                    return
                }
            }
        }
    }

    private var captureWatchJob: Job? = null

    /**
     * Il guardiano della scrittura: la verità è il file, e la verifica arriva fin lì.
     *
     * Aspettare che la camera torni libera non basta più a dire «salvato»: libera lo è anche
     * quando ha buttato lo scatto. Quindi, tornata libera, si rilegge l'elenco dei file: se in
     * cima c'è una foto nuova lo scatto esiste, la miniatura sul pulsante della galleria si
     * aggiorna con quella, e la galleria riceve l'elenco fresco. Se non c'è, lo si dice.
     *
     * Un guardiano solo anche con più scatti in coda: la domanda è «la camera sta ancora
     * scrivendo o si è piantata?», e la risposta vale per tutta la coda.
     */
    private fun watchCaptureCompletion() {
        if (captureWatchJob?.isActive == true) return
        captureWatchJob = viewModelScope.launch {
            if (!container.commands.awaitCaptureIdle(SINGLE_SHOT_SAVE_TIMEOUT_MS)) {
                container.log.warn(
                    "La camera è rimasta appesa in cattura",
                    "Riavvio il flusso dell'anteprima: è quello che la sblocca.",
                )
                container.preview.restart()
                if (!container.commands.awaitCaptureIdle()) {
                    showMessage("La camera non sta salvando: spegnila e riaccendila")
                    return@launch
                }
            }
            val before = lastKnownNewestPhotoPath
            container.media.list().onSuccess { items ->
                val newest = items.firstOrNull { !it.isVideo }
                if (newest != null && newest.path != before) {
                    _gallery.value = _gallery.value.copy(
                        items = items,
                        error = null,
                        loadedAtMs = System.currentTimeMillis(),
                    )
                    noteNewestPhoto(items)
                    showMessage("Salvata: ${newest.name}")
                } else {
                    container.log.warn(
                        "Scatto senza file",
                        "La camera è tornata libera ma l'elenco non ha foto nuove.",
                    )
                    showMessage("La camera non ha salvato nessun file nuovo")
                }
            }
        }
    }

    /** Prende nota dell'ultima foto e ne carica la miniatura per il pulsante della galleria. */
    private fun noteNewestPhoto(items: List<MediaItem>) {
        val newest = items.firstOrNull { !it.isVideo } ?: return
        if (newest.path == lastKnownNewestPhotoPath && _latestShotThumb.value != null) return
        lastKnownNewestPhotoPath = newest.path
        viewModelScope.launch {
            container.media.thumbnail(newest)?.let { _latestShotThumb.value = it }
        }
    }

    // ---------------------------------------------------------------- modalità della camera

    /**
     * Mette la camera nella modalità che la ghiera dice di essere.
     *
     * È il rimedio a un difetto misurato: il comando di scatto non dice cosa scattare, e con la
     * camera rimasta in panoramica «foto» produceva una panoramica. La sotto-modalità si invia
     * prima di ogni scatto perché la camera può essere stata cambiata dal suo schermo mentre
     * l'app era aperta.
     */
    private suspend fun ensureCameraMode(mode: CaptureMode): Boolean {
        if (connectionState.value != ConnectionState.CONNECTED) return false
        val applied = container.commands.applyMode(mode.cameraMode)
            .onFailure { showMessage("Modalità ${mode.cameraMode.label} non accettata: ${it.message}") }
            .isSuccess
        if (applied && mode.hasPanoAspect) {
            container.commands.setPanoAspect(settings.value.panoAspect)
                .onFailure { container.log.warn("Proporzione panoramica non accettata: ${it.message}") }
        }
        if (applied && mode.cameraMode.isPhoto) {
            container.commands.applyPhotoSettings(settings.value.photo, mode.cameraMode)
                .onFailure { container.log.warn("Regolazioni foto non accettate: ${it.message}") }
        }
        if (applied && !mode.cameraMode.isPhoto) {
            val selected = LunaVideoProfiles.selected(settings.value.video.profileCode, mode.cameraMode)
            val current = settings.value.video.copy(profileCode = selected.code)
            container.commands.applyVideoSettings(videoSettingsForMode(current, mode.cameraMode), mode.cameraMode)
                .onFailure { container.log.warn("Formato video non accettato: ${it.message}") }
            container.commands.setZoomScale(settings.value.photo.zoomScale, mode.cameraMode)
                .onFailure { container.log.warn("Zoom non accettato: ${it.message}") }
        }
        return applied
    }

    /** All'aggancio la ghiera adotta la modalità in cui la camera si trova già. */
    private fun syncCameraMode() {
        viewModelScope.launch {
            container.commands.fetchCameraMode()
                .onSuccess { cameraMode ->
                    if (cameraMode == null) return@onSuccess
                    if (_captureMode.value.cameraMode != cameraMode) {
                        _captureMode.value = CaptureMode.forCamera(cameraMode)
                    }
                    // La scala è un'impostazione persistente dell'app: riallinea la camera
                    // appena connessa anche se questa era rimasta su uno zoom diverso.
                    container.commands.setZoomScale(settings.value.photo.zoomScale, cameraMode)
                        .onFailure { container.log.warn("Zoom iniziale non accettato: ${it.message}") }
                }
                .onFailure { container.log.warn("Modalità della camera non leggibile: ${it.message}") }
        }
    }

    /** Sferica 360° o 2:1: la scelta della panoramica della camera. */
    fun setPanoAspect(aspect: Int) {
        container.settingsStore.update { it.copy(panoAspect = aspect) }
        if (connectionState.value != ConnectionState.CONNECTED) return
        viewModelScope.launch {
            container.commands.setPanoAspect(aspect)
                .onSuccess {
                    showMessage(
                        if (aspect == LunaProtocolCodes.PanoAspect.SPHERE_360) "Panoramica sferica 360°"
                        else "Panoramica 2:1"
                    )
                }
                .onFailure { showMessage("Proporzione non accettata: ${it.message}") }
        }
    }

    fun togglePanoAspect() = setPanoAspect(
        if (settings.value.panoAspect == LunaProtocolCodes.PanoAspect.SPHERE_360) {
            LunaProtocolCodes.PanoAspect.RATIO_2_1
        } else {
            LunaProtocolCodes.PanoAspect.SPHERE_360
        }
    )

    // ---------------------------------------------------------------- impostazioni

    fun setHost(host: String) = container.settingsStore.update { it.copy(host = host.trim()) }

    fun setPort(port: Int) = container.settingsStore.update { it.copy(port = port) }

    fun setCameraWifiPassword(password: String) =
        container.settingsStore.update { it.copy(cameraWifiPassword = password.trim()) }

    fun setPhotoTimer(seconds: Int) = updatePhotoSettings { it.copy(timerSeconds = seconds.coerceIn(0, 20)) }

    fun setPhotoProMode(enabled: Boolean) = updatePhotoSettings { it.copy(proMode = enabled) }

    fun setPhotoRawCapture(type: Int) = updatePhotoSettings {
        it.copy(rawCaptureType = type.coerceIn(LunaProtocolCodes.RawCaptureType.OFF, LunaProtocolCodes.RawCaptureType.DNG))
    }

    /**
     * Ultra o Standard: la risoluzione delle foto, come nell'app ufficiale.
     *
     * Si scrive sulla camera e si crede solo alla rilettura: questo firmware accetta tutto in
     * silenzio, quindi ciò che l'interruttore mostra è ciò che la camera ha dichiarato, non
     * ciò che le è stato chiesto. Se i due non coincidono, i numeri della mappa sono da
     * correggere e il log dice quali sono quelli veri.
     */
    /** Su quale dei due campi possibili la camera risponde: si impara dalla prima lettura. */
    private var photoSizeUsesSizeId = true

    fun setPhotoResolution(sizeCode: Int) {
        viewModelScope.launch {
            container.commands.setPhotoSize(sizeCode, useSizeId = photoSizeUsesSizeId)
                .onSuccess { actual ->
                    val value = actual?.value
                    updatePhotoSettings { it.copy(photoSizeCode = value ?: -1) }
                    if (value == sizeCode) {
                        showMessage("Risoluzione foto: ${LunaProtocolCodes.PhotoSize.label(sizeCode)}")
                    } else {
                        showMessage(
                            "La camera dichiara " +
                                (value?.let { "#$it" } ?: "nessuna risoluzione") +
                                ": mandami il log",
                        )
                    }
                }
                .onFailure { showMessage("Risoluzione non impostata: ${it.message}") }
        }
    }

    /** Legge dalla camera la risoluzione foto attuale, per mostrare la verità e non un ricordo. */
    private fun refreshPhotoSize() {
        viewModelScope.launch {
            val reading = container.commands.fetchPhotoSize()
            if (reading?.value == null) {
                container.log.info(
                    "Risoluzione foto: la camera non risponde su nessuno dei due campi noti",
                    "Chiesti photo_size_id (30) e photo_resolution (40) sul function mode foto.",
                )
                return@launch
            }
            photoSizeUsesSizeId = reading.usesSizeId
            val code = reading.value ?: return@launch
            if (code != settings.value.photo.photoSizeCode) {
                updatePhotoSettings { it.copy(photoSizeCode = code) }
            }
            container.log.info(
                "Risoluzione foto attuale: ${LunaProtocolCodes.PhotoSize.label(code)}",
                "Campo: " + if (reading.usesSizeId) "photo_size_id (enum PhotoSize)" else "photo_resolution (enum VideoResolution)",
            )
        }
    }

    fun setPhotoBrightness(value: Int) = updatePhotoSettings { it.copy(brightness = value.coerceIn(-2, 2)) }

    fun setPhotoExposureBias(thirds: Int) =
        updatePhotoSettings { it.copy(exposureBiasThirds = thirds.coerceIn(-6, 6)) }

    fun setPhotoWhiteBalance(kelvin: Int) = updatePhotoSettings {
        it.copy(whiteBalanceKelvin = if (kelvin == 0) 0 else kelvin.coerceIn(2_000, 10_000))
    }

    fun setPhotoZoom(scale: Int) {
        val zoom = scale.takeIf { it in listOf(1, 2, 3, 6, 12) } ?: 1
        container.settingsStore.update { it.copy(photo = it.photo.copy(zoomScale = zoom)) }
        if (connectionState.value != ConnectionState.CONNECTED) return
        viewModelScope.launch {
            container.commands.setZoomScale(zoom, _captureMode.value.cameraMode)
                .onSuccess { showMessage("Zoom ${zoom}×") }
                .onFailure { showMessage("Zoom non accettato: ${it.message}") }
        }
    }

    private fun updatePhotoSettings(transform: (PhotoSettings) -> PhotoSettings) {
        container.settingsStore.update { it.copy(photo = transform(it.photo)) }
        val mode = _captureMode.value
        if (connectionState.value != ConnectionState.CONNECTED || !mode.cameraMode.isPhoto) return
        viewModelScope.launch {
            container.commands.applyPhotoSettings(settings.value.photo, mode.cameraMode)
                .onFailure { showMessage("Regolazione non accettata: ${it.message}") }
        }
    }

    fun setVideoProfile(code: Int) {
        val mode = _captureMode.value.cameraMode
        val selected = LunaVideoProfiles.forMode(mode).firstOrNull { it.code == code } ?: return
        container.settingsStore.update { it.copy(video = it.video.copy(profileCode = selected.code)) }
        if (connectionState.value != ConnectionState.CONNECTED || mode.isPhoto) return
        viewModelScope.launch {
            val current = settings.value.video.copy(profileCode = selected.code)
            container.commands.applyVideoSettings(videoSettingsForMode(current, mode), mode)
                .onSuccess { showMessage("Video ${selected.resolution} · ${selected.fps} fps") }
                .onFailure { showMessage("Formato video non accettato: ${it.message}") }
        }
    }

    fun setVideoProMode(enabled: Boolean) = updateVideoSettings { it.copy(proMode = enabled) }

    fun setVideoIso(value: Int) = updateVideoSettings { it.copy(iso = value.coerceIn(0, 6400)) }

    fun setVideoShutter(seconds: Double) = updateVideoSettings {
        it.copy(shutterSeconds = seconds.coerceIn(0.0, 60.0))
    }

    fun setVideoExposureBias(thirds: Int) = updateVideoSettings {
        it.copy(exposureBiasThirds = thirds.coerceIn(-12, 12))
    }

    fun setVideoWhiteBalance(kelvin: Int) = updateVideoSettings {
        it.copy(whiteBalanceKelvin = if (kelvin == 0) 0 else kelvin.coerceIn(2_000, 10_000))
    }

    fun setVideoColorMode(value: Int) = updateVideoSettings {
        val allowed = setOf(
            LunaProtocolCodes.ColorMode.STANDARD,
            LunaProtocolCodes.ColorMode.I_LOG,
            LunaProtocolCodes.ColorMode.DOLBY_VISION,
        )
        it.copy(
            colorMode = if (value in allowed) value else LunaProtocolCodes.ColorMode.STANDARD,
            filter = if (value == LunaProtocolCodes.ColorMode.DOLBY_VISION) {
                LunaProtocolCodes.Filter.ORIGINAL
            } else it.filter,
        )
    }

    fun setVideoFilter(value: Int) = updateVideoSettings { it.copy(filter = value) }

    fun setVideoFilterIntensity(value: Int) = updateVideoSettings {
        it.copy(filterIntensity = value.coerceIn(LunaProtocolCodes.FilterIntensity.LOW, LunaProtocolCodes.FilterIntensity.HIGH))
    }

    fun setVideoSharpness(value: Int) = updateVideoSettings { it.copy(sharpness = value.coerceIn(0, 4)) }

    private fun updateVideoSettings(transform: (VideoSettings) -> VideoSettings) {
        container.settingsStore.update { it.copy(video = transform(it.video)) }
        val mode = _captureMode.value.cameraMode
        if (connectionState.value != ConnectionState.CONNECTED || mode.isPhoto) return
        viewModelScope.launch {
            val current = settings.value.video
            container.commands.applyVideoSettings(videoSettingsForMode(current, mode), mode)
                .onFailure { showMessage("Regolazione video non accettata: ${it.message}") }
        }
    }

    /** PureVideo, Slow-mo e Timelapse espongono soltanto Standard sulla camera reale. */
    private fun videoSettingsForMode(value: VideoSettings, mode: CameraMode): VideoSettings =
        if (mode == CameraMode.VIDEO) value else value.copy(colorMode = LunaProtocolCodes.ColorMode.STANDARD)

    fun updateGimbal(transform: (GimbalSettings) -> GimbalSettings) =
        container.settingsStore.update { it.copy(gimbal = transform(it.gimbal)) }

    fun setTimelapseMode(mode: Int) = container.settingsStore.update { it.copy(timelapseMode = mode) }

    fun exportSettings(): String = container.settingsStore.exportJson()

    /**
     * La calibrazione fuori dall'app, e di nuovo dentro.
     *
     * Misurarla costa sette minuti; è una misura dell'hardware e non cambia. L'unica ragione per
     * rifarla era averla persa reinstallando l'app, e questa è la ragione che sparisce.
     */
    fun saveCalibrationToDownloads(context: android.content.Context) {
        if (!gimbalCalibration.value.isValid) {
            showMessage("Non c'è ancora una calibrazione da salvare")
            return
        }
        val json = container.calibrationStore.exportJson()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CalibrationBackup.saveToDownloads(context, json) }
                .onSuccess { showMessage("Calibrazione salvata in $it") }
                .onFailure { showMessage("Non salvata: ${it.message}") }
        }
    }

    fun shareCalibration(context: android.content.Context) {
        if (!gimbalCalibration.value.isValid) {
            showMessage("Non c'è ancora una calibrazione da condividere")
            return
        }
        CalibrationBackup.share(context, container.calibrationStore.exportJson())
            .onFailure { showMessage("Condivisione non riuscita: ${it.message}") }
    }

    fun importCalibration(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { CalibrationBackup.read(context, uri) }
                .getOrElse {
                    showMessage("File non leggibile: ${it.message}")
                    return@launch
                }
            container.calibrationStore.importJson(text)
                .onSuccess {
                    val profile = gimbalCalibration.value
                    if (profile.isValid) {
                        container.log.info(
                            "Calibrazione importata",
                            "Misurata il " + java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.ITALIAN)
                                .format(java.util.Date(profile.calibratedAtMs)),
                        )
                        showMessage("Calibrazione importata")
                    } else {
                        // Il JSON era valido ma il profilo dentro non lo è: dirlo adesso, invece
                        // di lasciare che una panoramica finisca dove capita.
                        showMessage("Il file è leggibile ma la calibrazione dentro non è completa")
                    }
                }
                .onFailure { showMessage("Non è una calibrazione: ${it.message}") }
        }
    }

    fun importSettings(text: String) {
        container.settingsStore.importJson(text)
            .onSuccess { showMessage("Impostazioni importate") }
            .onFailure { showMessage("JSON non valido: ${it.message}") }
    }

    // ---------------------------------------------------------------- diagnostica

    fun sendRaw(codeText: String, payloadHex: String) {
        val code = parseIntFlexible(codeText)
        if (code == null) {
            showMessage("Codice comando non valido")
            return
        }
        val payload = if (payloadHex.isBlank()) ByteArray(0) else Hex.decodeOrNull(payloadHex)
        if (payload == null) {
            showMessage("Payload esadecimale non valido")
            return
        }
        viewModelScope.launch {
            container.session.requestRaw(code, payload)
                .onSuccess { container.log.info("Risposta:\n${it.describePayload()}") }
                .onFailure { showMessage(it.message ?: "Nessuna risposta") }
        }
    }

    /**
     * Misura come risponde la camera nei casi noti. Senza questo passo la scansione non parte:
     * se un codice inesistente rispondesse come uno esistente, i risultati non direbbero nulla.
     */
    fun calibrateProbe() {
        if (_probe.value.running) return
        if (connectionState.value != ConnectionState.CONNECTED) {
            showMessage("Connettiti prima di calibrare")
            return
        }
        probeJob = viewModelScope.launch {
            _probe.value = _probe.value.copy(running = true, done = 0, total = 0)
            val calibration = container.probe.calibrate()
            _probe.value = _probe.value.copy(running = false, calibration = calibration)
            showMessage(
                if (calibration.usable) "Misura fatta: la scansione può partire"
                else "Su questa camera la scansione non distinguerebbe nulla"
            )
        }
    }

    /** Avvia (o interrompe) la scansione di una gamma di codici. */
    fun scanRange(range: CodeProbe.Range) {
        if (_probe.value.running) {
            probeJob?.cancel()
            _probe.value = _probe.value.copy(running = false, range = null)
            return
        }
        val calibration = _probe.value.calibration
        if (calibration == null || !calibration.usable) {
            showMessage("Misura prima le risposte note")
            return
        }
        probeJob = viewModelScope.launch {
            _probe.value = _probe.value.copy(
                running = true,
                range = range,
                done = 0,
                total = range.codes().size,
                hits = emptyList(),
            )
            val hits = try {
                container.probe.scan(
                    range = range,
                    calibration = calibration,
                    onProgress = { done, total, _ ->
                        _probe.value = _probe.value.copy(done = done, total = total)
                    },
                )
            } finally {
                _probe.value = _probe.value.copy(running = false, range = null)
            }
            _probe.value = _probe.value.copy(hits = hits)
            showMessage("Scansione conclusa: ${hits.size} risposte diverse da un codice inesistente")
        }
    }

    /**
     * Caccia al comando del gimbal in un colpo solo.
     *
     * Esiste perché la prova che serviva — tenere fisso il selettore e cercare il campo
     * mancante — richiedeva di scrivere `0803` a mano in un campo esadecimale, e una prova che
     * dipende da un passaggio del genere non viene fatta. Qui è un pulsante, e il verdetto non
     * è più "guarda la camera" ma la rilettura dei getter dopo ogni tentativo.
     */
    fun huntGimbal(codeText: String, selectorText: String) {
        if (_hunt.value.running) {
            probeJob?.cancel()
            _hunt.value = _hunt.value.copy(running = false)
            return
        }
        val code = parseIntFlexible(codeText)
        val selector = parseIntFlexible(selectorText) ?: 3
        if (code == null) {
            showMessage("Codice non valido")
            return
        }
        if (connectionState.value != ConnectionState.CONNECTED) {
            showMessage("Connettiti prima di cacciare")
            return
        }
        probeJob = viewModelScope.launch {
            _hunt.value = HuntUiState(running = true)
            // Le notifiche NON vanno silenziate: sono l'oracolo. La sonda sì, altrimenti il
            // log si riempie di richieste e risposte e la notifica non si distingue.
            try {
                val steps = container.probe.huntGimbal(
                    code = code,
                    selectorValue = selector,
                    // Copia dei conteggi: la caccia confronta prima e dopo per sapere quali
                    // notifiche sono arrivate durante ogni tentativo.
                    notificationSnapshot = { countsByCode.toMap() },
                    onProgress = { done, total ->
                        _hunt.value = _hunt.value.copy(done = done, total = total)
                    },
                )
                _hunt.value = _hunt.value.copy(steps = steps)
            } finally {
                _hunt.value = _hunt.value.copy(running = false)
            }
            val moved = _hunt.value.steps.count { it.moved }
            showMessage(
                if (moved > 0) "$moved corpi hanno fatto arrivare una notifica: guarda il log"
                else "Caccia conclusa: nessuna notifica, nessun movimento"
            )
        }
    }

    /**
     * Prova le forme del messaggio di un codice.
     *
     * Non è read-only: ogni corpo che la camera accetta viene eseguito. Per questo parte solo
     * su richiesta esplicita, su un codice alla volta.
     */
    fun probeShape(codeText: String, prefixHex: String = "") {
        if (_shapeRunning.value) {
            probeJob?.cancel()
            _shapeRunning.value = false
            return
        }
        val code = parseIntFlexible(codeText)
        if (code == null) {
            showMessage("Codice non valido")
            return
        }
        if (connectionState.value != ConnectionState.CONNECTED) {
            showMessage("Connettiti prima di sondare")
            return
        }
        probeJob = viewModelScope.launch {
            _shapeRunning.value = true
            _shape.value = emptyList()
            try {
                val prefix = if (prefixHex.isBlank()) ByteArray(0) else Hex.decodeOrNull(prefixHex)
                if (prefix == null) {
                    showMessage("Prefisso esadecimale non valido")
                    return@launch
                }
                _shape.value = container.probe.shape(code, prefix)
            } finally {
                _shapeRunning.value = false
            }
            showMessage("Sonda conclusa: guarda quali forme sono state accettate")
        }
    }

    /**
     * Prova i valori di un campo su un comando, per capire se è un selettore di sotto-comando.
     * Come [probeShape], i valori validi vengono eseguiti dalla camera.
     */
    fun sweepSelector(codeText: String, fieldText: String, toText: String) {
        if (_shapeRunning.value) {
            probeJob?.cancel()
            _shapeRunning.value = false
            return
        }
        val code = parseIntFlexible(codeText)
        val field = parseIntFlexible(fieldText) ?: 1
        val to = parseIntFlexible(toText) ?: 63
        if (code == null) {
            showMessage("Codice non valido")
            return
        }
        if (connectionState.value != ConnectionState.CONNECTED) {
            showMessage("Connettiti prima di sondare")
            return
        }
        probeJob = viewModelScope.launch {
            _shapeRunning.value = true
            _selector.value = emptyList()
            try {
                _selector.value = container.probe.sweepSelector(code, field, 0, to)
            } finally {
                _shapeRunning.value = false
            }
            showMessage("Prova conclusa: guarda quali valori sono stati accettati")
        }
    }

    /**
     * Interroga ripetutamente un codice che risponde con dati, e mostra i campi decodificati.
     *
     * Serve a riconoscere il getter della posizione: muovendo il gimbal a mano si guarda quale
     * numero cambia. Read-only, e su un codice che la scansione ha già mostrato innocuo.
     */
    fun toggleMonitor(codesText: String) {
        if (_monitor.value.running) {
            monitorJob?.cancel()
            _monitor.value = _monitor.value.copy(running = false)
            return
        }
        val codes = codesText.split(',', ' ', ';')
            .mapNotNull { parseIntFlexible(it) }
            .distinct()
        if (codes.isEmpty()) {
            showMessage("Nessun codice valido: scrivili separati da virgola")
            return
        }
        if (connectionState.value != ConnectionState.CONNECTED) {
            showMessage("Connettiti prima di leggere")
            return
        }
        monitorJob = viewModelScope.launch {
            _monitor.value = MonitorState(running = true, entries = codes.map { MonitorEntry(it) })
            container.session.quiet = true
            // I payload già visti per codice: contarli distingue un valore che oscilla fra due
            // stati da uno che segue davvero un movimento.
            val seen = codes.associateWith { mutableSetOf<String>() }
            try {
                while (isActive) {
                    for (code in codes) {
                        if (!isActive) break
                        val frame = container.session.requestRaw(code, ByteArray(0), 1_200).getOrNull()
                            ?: continue
                        val dump = frame.describePayload()
                        seen[code]?.add(Hex.encode(frame.payload, separator = ""))
                        _monitor.value = _monitor.value.copy(
                            entries = _monitor.value.entries.map { entry ->
                                if (entry.code != code) entry else entry.copy(
                                    reads = entry.reads + 1,
                                    changes = if (entry.dump.isNotEmpty() && entry.dump != dump) {
                                        entry.changes + 1
                                    } else {
                                        entry.changes
                                    },
                                    distinct = seen[code]?.size ?: 0,
                                    dump = dump,
                                )
                            }
                        )
                        delay(MONITOR_PERIOD_MS)
                    }
                }
            } finally {
                container.session.quiet = false
                _monitor.value = _monitor.value.copy(running = false)
            }
        }
    }

    // ---------------------------------------------------------------- galleria

    /**
     * Rilegge la libreria dalla camera. Senza [force] non rifà il giro se l'elenco è recente:
     * aprire e chiudere la galleria non deve costare un'enumerazione di migliaia di file.
     */
    /**
     * Uno scatto nuovo rende vecchio l'elenco: al prossimo giro in galleria si rilegge.
     *
     * Senza questa riga la galleria mostrava l'elenco di prima finché la sua età non scadeva,
     * e le foto appena fatte comparivano solo riavviando l'app.
     */
    fun markGalleryStale() {
        _gallery.value = _gallery.value.copy(loadedAtMs = 0L)
    }

    fun refreshGallery(force: Boolean = false) {
        if (_gallery.value.loading) return
        if (connectionState.value != ConnectionState.CONNECTED) {
            _gallery.value = _gallery.value.copy(error = "Connettiti alla camera per vedere i file")
            return
        }
        val age = System.currentTimeMillis() - _gallery.value.loadedAtMs
        if (!force && _gallery.value.items.isNotEmpty() && age < GALLERY_FRESH_MS) return
        // «Aggiorna» vuol dire anche «riprova le miniature che non erano venute».
        if (force) container.media.retryThumbnails()

        viewModelScope.launch {
            _gallery.value = _gallery.value.copy(loading = true, error = null)
            container.media.list()
                .onSuccess { items ->
                    _gallery.value = _gallery.value.copy(
                        loading = false,
                        items = items,
                        error = if (items.isEmpty()) "Nessun file sulla camera" else null,
                        loadedAtMs = System.currentTimeMillis(),
                    )
                    noteNewestPhoto(items)
                    warmThumbnails(items)
                }
                .onFailure {
                    _gallery.value = _gallery.value.copy(
                        loading = false,
                        error = "Elenco non riuscito: ${it.message}",
                    )
                }
        }
    }

    /**
     * Chiede alla camera tutte le miniature in blocco, se sa darle.
     *
     * Gira dopo l'elenco e in parallelo alla griglia: le caselle intanto si arrangiano da sole,
     * e quando il blocco arriva si ridisegnano con quello che è stato messo in cache.
     */
    private fun warmThumbnails(items: List<MediaItem>) {
        warmJob?.cancel()
        warmJob = viewModelScope.launch {
            val stored = container.media.warmThumbnails(items)
            if (stored > 0) {
                _gallery.value = _gallery.value.copy(
                    thumbnailsVersion = _gallery.value.thumbnailsVersion + 1,
                )
            }
        }
    }

    /** La miniatura di un file. Restituisce null quando non c'è modo di averne una. */
    suspend fun thumbnail(item: MediaItem): android.graphics.Bitmap? =
        thumbnailGate.withPermit { container.media.thumbnail(item) }

    fun toggleSelection(item: MediaItem) {
        val selected = _gallery.value.selected
        _gallery.value = _gallery.value.copy(
            selected = if (item.path in selected) selected - item.path else selected + item.path,
        )
    }

    fun clearSelection() {
        _gallery.value = _gallery.value.copy(selected = emptySet())
    }

    fun selectAll() {
        _gallery.value = _gallery.value.copy(selected = _gallery.value.items.map { it.path }.toSet())
    }

    /** Salva nella galleria del telefono i file selezionati, uno alla volta. */
    fun downloadSelected() {
        val state = _gallery.value
        downloadAll(state.items.filter { it.path in state.selected })
        clearSelection()
    }

    /** Salva tutti i preferiti, senza doverli selezionare a mano. */
    fun downloadFavorites() {
        val marked = favorites.value.paths
        downloadAll(_gallery.value.items.filter { it.path in marked })
    }

    fun download(item: MediaItem) = downloadAll(listOf(item))

    /**
     * Una coda sola per tutti gli scaricamenti.
     *
     * Il conteggio «3 di 6» sta nello stato e non nel messaggio perché la barra deve dire dove
     * si è arrivati mentre va, non a cose fatte: sei file identici che dicono tutti
     * «scaricamento in corso» non sono un avanzamento, sono un'attesa al buio.
     */
    private fun downloadAll(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val already = _gallery.value.queueTotal - _gallery.value.queueDone
        _gallery.value = _gallery.value.copy(
            queueTotal = if (already > 0) _gallery.value.queueTotal + items.size else items.size,
            queueDone = if (already > 0) _gallery.value.queueDone else 0,
        )
        viewModelScope.launch {
            var saved = 0
            for (item in items) {
                if (saveOne(item)) saved++
                _gallery.value = _gallery.value.copy(queueDone = _gallery.value.queueDone + 1)
            }
            val state = _gallery.value
            if (state.queueDone >= state.queueTotal) {
                _gallery.value = state.copy(queueTotal = 0, queueDone = 0)
            }
            showMessage(
                when {
                    saved == items.size && saved == 1 -> "Salvato nella galleria del telefono"
                    saved == items.size -> "$saved file salvati nella galleria del telefono"
                    else -> "Salvati $saved file su ${items.size}"
                }
            )
        }
    }

    // ---------------------------------------------------------------- preferiti

    /**
     * Elimina dalla scheda della camera i file selezionati. Irreversibile, e infatti chi
     * chiama è il dialogo di conferma, mai un pulsante diretto.
     *
     * I preferiti dei file eliminati si dimenticano: una stella su un file che non esiste
     * più è un conteggio che non torna.
     */
    fun deleteSelectedFromCamera() {
        val paths = _gallery.value.selected.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            showMessage("Elimino ${paths.size} file…")
            container.commands.deleteFiles(paths)
                .onSuccess { failed ->
                    val removed = paths.size - failed.size
                    container.log.info(
                        "Eliminati $removed file dalla camera",
                        if (failed.isEmpty()) null
                        else "Rifiutati: ${failed.joinToString()}",
                    )
                    container.favoritesStore.update { favorites ->
                        favorites.copy(paths = favorites.paths - paths.toSet())
                    }
                    _gallery.value = _gallery.value.copy(selected = emptySet())
                    lastKnownNewestPhotoPath = null
                    refreshGallery(force = true)
                    showMessage(
                        if (failed.isEmpty()) "$removed file eliminati"
                        else "$removed eliminati · ${failed.size} rifiutati dalla camera",
                    )
                }
                .onFailure { showMessage("Eliminazione non riuscita: ${it.message}") }
        }
    }

    /**
     * La prova del canale «extra», seconda stesura: prima la mappa, poi l'andata e ritorno.
     *
     * La prima stesura scriveva un tipo inventato (200) e la camera lo scartava in silenzio:
     * l'enum del firmware registra solo i tipi 0-17, e fuori da quelli il SET risponde OK
     * senza fare niente — questa camera non manda mai errori. Quindi adesso si lavora dentro
     * il recinto: si leggono tutti i tipi registrati sull'ultima foto, per vedere cosa c'è, e
     * poi si prova l'andata e ritorno sul GPS (tipo 7) — che è il tipo pensato apposta per
     * essere scritto dal telefono dopo lo scatto. Se il GPS fa il giro, il canale scrive
     * davvero sulla scheda: e per una panoramica, longitudine e latitudine possono
     * trasportare pan e tilt.
     */
    fun probeFileExtra() {
        viewModelScope.launch {
            val target = _gallery.value.items.firstOrNull { !it.isVideo }
                ?: container.media.list().getOrNull()?.firstOrNull { !it.isVideo }
            if (target == null) {
                showMessage("Serve almeno una foto sulla camera per la prova")
                return@launch
            }

            val map = StringBuilder()
            for (type in 0..LunaProtocolCodes.ExtraType.LAST_KNOWN) {
                container.commands.getFileExtra(target.path, type).onSuccess { bytes ->
                    if (bytes.isNotEmpty()) {
                        map.appendLine(
                            "tipo $type (${LunaProtocolCodes.ExtraType.name(type)}): " +
                                "${bytes.size} byte · ${Hex.encode(bytes, limit = 24)}",
                        )
                    }
                }
            }
            container.log.info(
                "CANALE EXTRA · MAPPA DI ${target.name}",
                map.toString().ifBlank { "Nessun tipo risponde con dati." },
            )

            // L'andata e ritorno, sui campi che non bruciano niente. Il GPS non si tocca:
            // quello serve al GPS — magari un giorno a quello vero del telefono. Si provano
            // le telemetrie che su una foto sono quasi certamente vuote, in ordine di
            // eleganza: EULER sono letteralmente angoli di orientamento, il resto è spazio.
            // Regola ferrea: un tipo che contiene già dati non si scrive.
            val payload = "LUNAPANO-PROVA-${System.currentTimeMillis()}".toByteArray()
            var slot: Int? = null
            for (type in PROBE_EXTRA_CANDIDATES) {
                val existing = container.commands.getFileExtra(target.path, type).getOrNull()
                if (existing != null && existing.isNotEmpty()) {
                    container.log.info(
                        "Tipo $type (${LunaProtocolCodes.ExtraType.name(type)}) già occupato: non lo tocco",
                    )
                    continue
                }
                container.commands.setFileExtra(target.path, type, payload)
                val back = container.commands.getFileExtra(target.path, type).getOrNull()
                if (back != null && String(back, Charsets.UTF_8).contains(String(payload, Charsets.UTF_8))) {
                    slot = type
                    break
                }
                container.log.info(
                    "Tipo $type (${LunaProtocolCodes.ExtraType.name(type)}): scrittura senza effetto" +
                        (back?.let { " · riletti ${it.size} byte" } ?: ""),
                )
            }
            if (slot != null) {
                container.log.info(
                    "TROVATO LO SCOMPARTO: tipo $slot (${LunaProtocolCodes.ExtraType.name(slot)})",
                    "Scritto e riletto identico. Il passaporto delle panoramiche può vivere " +
                        "sulla scheda, senza toccare il GPS.",
                )
            } else {
                container.log.warn(
                    "Nessuno scomparto scrivibile fra i candidati",
                    "Il firmware conserva solo i tipi che riempie lui. Il passaporto resta " +
                        "sulle copie del telefono — che comunque funziona.",
                )
            }
            showMessage("Prova finita: l'esito è nel log")
        }
    }

    fun toggleFavorite(item: MediaItem) {
        container.favoritesStore.update { it.toggled(item.path) }
    }

    fun isFavorite(item: MediaItem): Boolean = item.path in favorites.value

    private suspend fun saveOne(item: MediaItem): Boolean {
        updateProgress(item.path, 0f)
        val result = container.media.saveToGallery(item) { progress -> updateProgress(item.path, progress) }
        clearProgress(item.path)
        return result
            .onFailure { showMessage("Salvataggio di ${item.name} non riuscito: ${it.message}") }
            .isSuccess
    }

    private fun updateProgress(path: String, progress: Float) {
        _gallery.value = _gallery.value.copy(downloads = _gallery.value.downloads + (path to progress))
    }

    private fun clearProgress(path: String) {
        _gallery.value = _gallery.value.copy(downloads = _gallery.value.downloads - path)
    }

    // ---------------------------------------------------------------- visione

    /**
     * Apre un file a schermo intero.
     *
     * Foto: si scarica e si decodifica ridotta a quanto serve per lo schermo. Video: si scarica
     * il proxy a bassa risoluzione, che la camera salva apposta accanto a ogni ripresa — il file
     * grosso si scarica solo se lo chiedi.
     */
    fun openViewer(item: MediaItem) {
        val index = _gallery.value.items.indexOfFirst { it.path == item.path }
        viewerJob?.cancel()
        prefetchJob?.cancel()
        _viewer.value = ViewerState(item = item, index = index, loading = true)
        // Mentre si guarda questa, arrivano le prossime: sfogliare è un gesto prevedibile.
        prefetchJob = viewModelScope.launch { prefetchAfter(index) }
        viewerJob = viewModelScope.launch {
            when {
                item.isVideo -> {
                    val proxy = item.proxyPath != null
                    container.media.cache(item, preferProxy = proxy) { progress ->
                        _viewer.value = _viewer.value.copy(progress = progress)
                    }
                        .onSuccess { file ->
                            _viewer.value = _viewer.value.copy(
                                loading = false,
                                videoFile = file.absolutePath,
                                message = if (proxy) "anteprima a bassa risoluzione" else null,
                            )
                        }
                        .onFailure {
                            _viewer.value = _viewer.value.copy(
                                loading = false,
                                message = "Video non scaricabile: ${it.message}",
                            )
                        }
                }

                !item.renderable && item.previewPath == null -> {
                    _viewer.value = _viewer.value.copy(
                        loading = false,
                        message = "Formato ${item.extension.uppercase()} non visualizzabile sul telefono: puoi scaricarlo",
                    )
                }

                else -> {
                    // Una panoramica si guarda da dentro e si ingrandisce: le serve più
                    // risoluzione di una foto piatta, che sullo schermo ci sta tutta.
                    val maxSize = if (item.panoramic) PANO_VIEW_MAX_SIZE else PHOTO_VIEW_MAX_SIZE
                    container.media.loadPhoto(item, maxSize) { progress ->
                        _viewer.value = _viewer.value.copy(progress = progress)
                    }
                        .onSuccess { bitmap ->
                            _viewer.value = _viewer.value.copy(loading = false, photo = bitmap)
                        }
                        .onFailure {
                            _viewer.value = _viewer.value.copy(
                                loading = false,
                                message = "Foto non caricata: ${it.message}",
                            )
                        }
                }
            }
        }
    }

    fun closeViewer() {
        viewerJob?.cancel()
        viewerJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        _viewer.value = ViewerState()
    }

    /**
     * Scarica in anticipo i file che vengono dopo quello aperto.
     *
     * Uno alla volta e dopo una breve attesa: la foto che si sta guardando ha la precedenza
     * sulla rete, e chi sfoglia veloce cambia idea prima che il precaricamento serva.
     */
    private suspend fun prefetchAfter(index: Int) {
        delay(PREFETCH_DELAY_MS)
        val items = _gallery.value.items
        for (offset in 1..PREFETCH_AHEAD) {
            val next = items.getOrNull(index + offset) ?: return
            val maxSize = if (next.panoramic) PANO_VIEW_MAX_SIZE else PHOTO_VIEW_MAX_SIZE
            container.media.prefetch(next, maxSize)
        }
    }

    /** Passa al file precedente o successivo restando a schermo intero. */
    fun stepViewer(delta: Int) {
        val items = _gallery.value.items
        val next = _viewer.value.index + delta
        if (next !in items.indices) return
        openViewer(items[next])
    }

    fun clearGalleryCache() {
        container.media.clearDownloads()
        showMessage("Copie locali cancellate")
    }

    fun clearLog() = container.log.clear()

    fun exportLog(): String = container.log.exportText()

    /**
     * Salva il log su file e apre la condivisione. L'intestazione porta host, stato e codice
     * gimbal in uso: senza quel contesto le righe del log si leggono a metà.
     */
    fun shareLog(context: android.content.Context) {
        LogSharing.share(context, container.log.entries.value, logHeader())
            .onSuccess { showMessage("Log pronto per la condivisione") }
            .onFailure { showMessage("Condivisione non riuscita: ${it.message}") }
    }

    /** Salva il log in Download; viene azzerato esclusivamente dopo una scrittura riuscita. */
    fun saveLogToDownloads(context: android.content.Context) {
        val entries = container.log.entries.value
        if (entries.isEmpty()) {
            showMessage("Il log è vuoto")
            return
        }
        val header = logHeader()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                LogSharing.saveToDownloads(context, entries, header)
            }
            result
                .onSuccess { path ->
                    container.log.clear()
                    showMessage("Log salvato in $path · log azzerato")
                }
                .onFailure { showMessage("Salvataggio log non riuscito: ${it.message}") }
        }
    }

    private fun logHeader(): List<String> =
        listOf(
            "camera: ${settings.value.host}:${settings.value.port}",
            "stato: ${connectionState.value}",
            "codice gimbal: ${LunaProtocolCodes.GIMBAL_CONTROL} (0x00E2)",
            "notifica PTZ: ${settings.value.gimbal.ptzNotificationCode}",
            "modello: ${status.value.model ?: "?"} firmware: ${status.value.firmware ?: "?"}",
            "modalità sequenza: ${sequence.value.mode.name}",
        )

    fun showMessage(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        super.onCleared()
        container.wifiBinder.release()
    }

    private fun nextWaypointName(index: Int): String {
        val letters = ('A'..'Z').toList()
        return if (index < letters.size) letters[index].toString() else "P${index + 1}"
    }

    private fun parseIntFlexible(text: String): Int? {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> null
            trimmed.startsWith("0x", ignoreCase = true) -> trimmed.substring(2).toIntOrNull(16)
            else -> trimmed.toIntOrNull()
        }
    }

    private companion object {
        /** Ogni quanto si guardano CPU e memoria durante l'unione. */
        const val VITALS_INTERVAL_MS = 700L

        const val STATUS_POLL_MS = 3_000L

        /** Ogni quanto il diario delle posizioni annota dove sta il telefono, da connessi. */
        const val LOCATION_SAMPLE_MS = 5 * 60_000L

        /** La ripubblicazione della release dura qualche minuto: si riprova, non si molla. */
        const val UPDATE_RETRY_ATTEMPTS = 3
        const val UPDATE_RETRY_DELAY_MS = 90_000L

        /**
         * Quante volte si insiste su uno scatto che la camera rifiuta (8201), e la base
         * dell'attesa fra un tentativo e l'altro (cresce: 3, 6, 9… secondi). Dal vivo un
         * rifiuto è durato quaranta secondi: cinque tentativi coprono quella scena.
         */
        const val SHOT_REFUSED_ATTEMPTS = 5
        const val SHOT_REFUSED_RETRY_MS = 3_000L

        /**
         * Quanto il guardiano concede alla camera per scrivere uno scatto prima di dichiararla
         * appesa. Largo apposta: con il buffer pieno la coda vera può essere di più scatti.
         */
        const val SINGLE_SHOT_SAVE_TIMEOUT_MS = 20_000L

        /**
         * Gli scomparti da provare per il passaporto, in ordine di eleganza. Il GPS non c'è
         * apposta: quello resta al suo mestiere. EULER (14) sono angoli di orientamento —
         * il significato giusto — e gli altri sono telemetrie vuote sulle foto.
         */
        val PROBE_EXTRA_CANDIDATES = intArrayOf(14, 17, 16, 13, 8, 10)

        /** Attesa fra l'azione di prova e la miniatura di confronto: il gimbal deve arrivare. */
        const val GIMBAL_ACTION_PROBE_WAIT_MS = 4_000L

        /** Sopra questo scarto l'azione ha mosso qualcosa; sotto, non è successo niente. */
        const val GIMBAL_ACTION_MOVED_PX = 12f

        /** Una scansione a mano non deve poter diventare un giro di mezz'ora sulla camera. */
        const val MAX_GIMBAL_ACTION_SPAN = 16

        /** Quanto resta valido un elenco della libreria prima di rifare il giro. */
        const val GALLERY_FRESH_MS = 60_000L
        const val THUMBNAIL_CONCURRENCY = 4

        /** Quanti file caricare in anticipo davanti a quello aperto, e dopo quanto iniziare. */
        const val PREFETCH_AHEAD = 2
        const val PREFETCH_DELAY_MS = 400L
        const val PHOTO_VIEW_MAX_SIZE = 2_048
        const val PANO_VIEW_MAX_SIZE = 4_096

        /** Attesa del primo tentativo di riaggancio; i successivi raddoppiano. */
        const val RECONNECT_BASE_MS = 2_000L

        /**
         * Tanti, apposta: con il servizio in piedi ritentare costa poco, e una sequenza che
         * gira mentre l'app è in secondo piano merita più di un minuto di pazienza. Il passo
         * si ferma a sedici secondi: dodici tentativi sono circa tre minuti.
         */
        const val MAX_RECONNECT_ATTEMPTS = 12

        /**
         * Pausa fra una lettura e la successiva. Con più codici a rotazione questo è il passo
         * per codice, non per giro: abbastanza fitto da vedere un movimento del gimbal.
         */
        const val MONITOR_PERIOD_MS = 350L
    }
}
