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
import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.data.GimbalSettings
import it.persoft.lunaultra.data.PhotoSettings
import it.persoft.lunaultra.data.LunaVideoProfiles
import it.persoft.lunaultra.data.VideoSettings
import it.persoft.lunaultra.media.Favorites
import it.persoft.lunaultra.media.MediaItem
import it.persoft.lunaultra.preview.PreviewState
import it.persoft.lunaultra.protocol.Hex
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.service.LunaConnectionService
import it.persoft.lunaultra.timelapse.InterpolationMode
import it.persoft.lunaultra.timelapse.ShootingMode
import it.persoft.lunaultra.timelapse.TimelapseSequence
import it.persoft.lunaultra.timelapse.Waypoint
import it.persoft.lunaultra.update.UpdateManager
import it.persoft.lunaultra.ui.viewfinder.CaptureMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
    val gimbalCalibration = container.calibrationStore.state
    val gimbalCalibrationState = container.calibrator.state
    val runState = container.engine.state
    val preview: StateFlow<PreviewState> = container.preview.state

    private val _status = MutableStateFlow(CameraStatus())
    val status: StateFlow<CameraStatus> = _status

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
    private var wantConnected = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var connectionJob: Job? = null
    private var updateCheckStarted = false
    private var photoCountdownJob: Job? = null

    private var pollJob: Job? = null
    private var viewerJob: Job? = null
    private var prefetchJob: Job? = null
    private var warmJob: Job? = null
    private var probeJob: Job? = null
    private var monitorJob: Job? = null

    init {
        viewModelScope.launch { container.load() }
        observeNotifications()
        observeConnection()
        observeForegroundService()
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
            combine(connectionState, _recordingSinceMs, runState, _status) { connection, recordingSince, run, status ->
                when {
                    connection != ConnectionState.CONNECTED -> null
                    run.running ->
                        "Sequenza in corso — tratto ${run.legIndex + 1}/${run.legCount.coerceAtLeast(1)}"

                    recordingSince > 0L || status.recording == true -> "Ripresa in corso"
                    else -> "Connessa — la sessione resta aperta"
                }
            }.collect { text ->
                val context = getApplication<Application>()
                if (text == null) LunaConnectionService.stop(context)
                else LunaConnectionService.start(context, text)
            }
        }
    }

    // ---------------------------------------------------------------- connessione

    /**
     * Prima di chiedere la rete locale della camera usa la normale connessione Internet per
     * controllare la release GitHub. Il download è automatico; Android mostra comunque la sua
     * conferma di installazione, che un'app normale non può aggirare.
     */
    fun checkForUpdate(onReadyToInstall: (File) -> Unit) {
        if (updateCheckStarted) return
        updateCheckStarted = true
        viewModelScope.launch {
            showMessage("Controllo aggiornamenti…")
            updateManager.downloadIfAvailable(BuildConfig.GIT_SHA)
                .onSuccess { update ->
                    if (update != null) {
                        showMessage("Aggiornamento scaricato: conferma l'installazione")
                        onReadyToInstall(update.apk)
                    } else {
                        showMessage("App aggiornata · premi Connetti quando vuoi")
                    }
                }
                .onFailure {
                    container.log.warn("Controllo aggiornamenti non riuscito: ${it.message}")
                    showMessage("Aggiornamento non verificabile · premi Connetti quando vuoi")
                }
        }
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

    private fun observeConnection() {
        viewModelScope.launch {
            connectionState.collect { state ->
                pollJob?.cancel()
                when (state) {
                    ConnectionState.CONNECTED -> {
                        reconnectAttempts = 0
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
                    }

                    ConnectionState.DISCONNECTED, ConnectionState.ERROR -> scheduleReconnect()
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
                if (
                    settings.value.gimbal.useExperimentalPtzPosition &&
                    frame.code == settings.value.gimbal.ptzNotificationCode
                ) {
                    container.commands.parsePtz(frame)?.let { container.gimbal.onCameraPosition(it) }
                }
                container.commands.gimbalSpeedFromNotification(frame)?.let { level ->
                    updateGimbal { it.copy(hardwareSpeedLevel = level) }
                }
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

    fun setManualSpeed(percent: Int) =
        updateGimbal { it.copy(manualSpeedPercent = percent.coerceIn(1, 100)) }

    fun setGimbalHardwareSpeed(level: Int) {
        if (connectionState.value != ConnectionState.CONNECTED) {
            showMessage("Connettiti alla camera per cambiare la velocità del gimbal")
            return
        }
        viewModelScope.launch {
            container.commands.setGimbalHardwareSpeed(level)
                .onSuccess {
                    updateGimbal { it.copy(hardwareSpeedLevel = level) }
                    showMessage("Velocità gimbal: ${gimbalSpeedLabel(level)}")
                }
                .onFailure { showMessage("Velocità gimbal non applicata: ${it.message}") }
        }
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
            originalHardwareLevel = settings.value.gimbal.hardwareSpeedLevel,
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

    fun jogStop() {
        viewModelScope.launch { container.gimbal.stop() }
    }

    fun zeroPosition() {
        container.gimbal.setEstimated(0f, 0f)
        showMessage("Posizione corrente impostata come 0°/0°")
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
    fun startRun() {
        val seq = sequence.value
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
        if (seq.waypoints.any { it.previewJpegBase64 == null }) {
            showMessage("Aggiorna ogni punto con ‘Qui’: serve la foto di controllo per verificare partenza e arrivo")
            return
        }
        viewModelScope.launch {
            ensureCameraMode(_captureMode.value)
            container.engine.start(seq)
        }
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
        if (mode.usesSequence) {
            if (runState.value.running) emergencyStop() else startRun()
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

    private suspend fun shoot(mode: CaptureMode) {
        val pano = mode.cameraMode == CameraMode.PANORAMA
        container.commands.takePicture(instaPano = pano)
            .onSuccess { showMessage(if (pano) "Panoramica in corso" else "Scatto eseguito") }
            .onFailure { showMessage("Scatto non riuscito: ${it.message}") }
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
        }
        return applied
    }

    /** All'aggancio la ghiera adotta la modalità in cui la camera si trova già. */
    private fun syncCameraMode() {
        viewModelScope.launch {
            container.commands.fetchCameraMode()
                .onSuccess { cameraMode ->
                    if (cameraMode == null) return@onSuccess
                    if (_captureMode.value.cameraMode == cameraMode) return@onSuccess
                    _captureMode.value = CaptureMode.forCamera(cameraMode)
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

    fun setPhotoBrightness(value: Int) = updatePhotoSettings { it.copy(brightness = value.coerceIn(-2, 2)) }

    fun setPhotoExposureBias(thirds: Int) =
        updatePhotoSettings { it.copy(exposureBiasThirds = thirds.coerceIn(-6, 6)) }

    fun setPhotoWhiteBalance(kelvin: Int) = updatePhotoSettings {
        it.copy(whiteBalanceKelvin = if (kelvin == 0) 0 else kelvin.coerceIn(2_000, 10_000))
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
        const val STATUS_POLL_MS = 3_000L

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
        const val MAX_RECONNECT_ATTEMPTS = 6

        /**
         * Pausa fra una lettura e la successiva. Con più codici a rotazione questo è il passo
         * per codice, non per giro: abbastanza fitto da vedere un movimento del gimbal.
         */
        const val MONITOR_PERIOD_MS = 350L
    }
}
