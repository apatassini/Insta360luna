package it.persoft.lunaultra.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.persoft.lunaultra.AppContainer
import it.persoft.lunaultra.camera.CameraStatus
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.camera.LunaCommand
import it.persoft.lunaultra.camera.LunaNotification
import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.data.GimbalDriveMode
import it.persoft.lunaultra.data.GimbalSettings
import it.persoft.lunaultra.data.LayoutSettings
import it.persoft.lunaultra.protocol.Hex
import it.persoft.lunaultra.timelapse.InterpolationMode
import it.persoft.lunaultra.timelapse.TimelapseSequence
import it.persoft.lunaultra.timelapse.Waypoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ScanResult(val commandId: Int, val errorCode: Int, val payloadSize: Int, val dump: String)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer(application, viewModelScope)

    val settings: StateFlow<AppSettings> = container.settingsStore.state
    val sequence: StateFlow<TimelapseSequence> = container.sequenceStore.state
    val connectionState: StateFlow<ConnectionState> = container.session.state
    val connectionError: StateFlow<String?> = container.session.lastError
    val logEntries = container.log.entries
    val ptz = container.gimbal.position
    val gimbalMoving = container.gimbal.moving
    val runState = container.engine.state

    private val _status = MutableStateFlow(CameraStatus())
    val status: StateFlow<CameraStatus> = _status

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private var pollJob: Job? = null
    private var scanJob: Job? = null

    init {
        viewModelScope.launch { container.load() }
        observeNotifications()
        observeConnection()
    }

    // ---------------------------------------------------------------- connessione

    fun connect() {
        viewModelScope.launch {
            container.log.info("Acquisizione rete Wi-Fi…")
            container.wifiBinder.acquire()
            container.session.connect()
                .onFailure { showMessage("Connessione fallita: ${it.message}") }
                .onSuccess { showMessage("Connesso a ${settings.value.host}") }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            container.engine.stop("Disconnessione")
            container.session.disconnect()
            container.wifiBinder.release()
            _status.value = CameraStatus()
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            container.commands.fetchStatus()
                .onSuccess { _status.value = it }
                .onFailure { showMessage("Stato non disponibile: ${it.message}") }
            container.commands.fetchCameraInfo().onSuccess { info ->
                _status.value = _status.value.copy(model = info.model, firmware = info.firmware)
            }
            container.commands.readPtz().onSuccess { container.gimbal.onCameraPosition(it) }
        }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            connectionState.collect { state ->
                pollJob?.cancel()
                if (state == ConnectionState.CONNECTED) {
                    pollJob = viewModelScope.launch {
                        while (isActive) {
                            container.commands.fetchStatus().onSuccess { _status.value = it }
                            delay(STATUS_POLL_MS)
                        }
                    }
                }
            }
        }
    }

    private fun observeNotifications() {
        viewModelScope.launch {
            container.session.notifications.collect { frame ->
                when (container.registry.notificationFor(frame.commandId)) {
                    LunaNotification.PTZ_STATE ->
                        container.commands.parsePtz(frame)?.let { container.gimbal.onCameraPosition(it) }

                    LunaNotification.CAMERA_STATE ->
                        _status.value = container.commands.parseStatus(frame)

                    else -> Unit
                }
            }
        }
    }

    // ---------------------------------------------------------------- gimbal

    fun jogStart(pan: Float, tilt: Float) = container.gimbal.startJog(pan, tilt)

    fun jogStop() {
        viewModelScope.launch { container.gimbal.stop() }
    }

    fun zeroPosition() {
        container.gimbal.setEstimated(0f, 0f)
        showMessage("Posizione corrente impostata come 0°/0°")
    }

    fun goToWaypoint(waypoint: Waypoint, seconds: Float = 3f) {
        viewModelScope.launch {
            val start = ptz.value
            val steps = (seconds * 10).toInt().coerceAtLeast(1)
            repeat(steps) { i ->
                val t = (i + 1f) / steps
                val pan = start.pan + (waypoint.pan - start.pan) * t
                val tilt = start.tilt + (waypoint.tilt - start.tilt) * t
                container.gimbal.driveTo(pan, tilt, 0.1f)
                delay(100)
            }
            container.gimbal.stop()
        }
    }

    // ---------------------------------------------------------------- waypoint

    fun captureWaypoint() {
        val current = ptz.value
        container.sequenceStore.update { seq ->
            val name = nextWaypointName(seq.waypoints.size)
            seq.copy(waypoints = seq.waypoints + Waypoint(name = name, pan = current.pan, tilt = current.tilt))
        }
        showMessage("Punto memorizzato a %.1f° / %.1f°".format(current.pan, current.tilt))
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
        val current = ptz.value
        container.sequenceStore.update { seq ->
            seq.copy(
                waypoints = seq.waypoints.map {
                    if (it.id == id) it.copy(pan = current.pan, tilt = current.tilt) else it
                }
            )
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

    fun setSetTimelapseMode(enabled: Boolean) =
        container.sequenceStore.update { it.copy(setTimelapseMode = enabled) }

    fun startRun() {
        val seq = sequence.value
        if (!seq.isRunnable) {
            showMessage("Servono almeno 2 punti memorizzati")
            return
        }
        if (connectionState.value != ConnectionState.CONNECTED) {
            showMessage("Connettiti alla camera prima di avviare")
            return
        }
        container.engine.start(seq)
    }

    fun emergencyStop() {
        container.engine.stop("STOP di emergenza")
        viewModelScope.launch { container.gimbal.stop() }
    }

    fun startRecording() {
        viewModelScope.launch {
            container.commands.startCapture()
                .onFailure { showMessage("Start non riuscito: ${it.message}") }
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            container.commands.stopCapture()
                .onFailure { showMessage("Stop non riuscito: ${it.message}") }
        }
    }

    fun selectTimelapseMode() {
        viewModelScope.launch {
            container.commands.selectTimelapseMode()
                .onFailure { showMessage("Modalità non impostata: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- impostazioni

    fun setHost(host: String) = container.settingsStore.update { it.copy(host = host.trim()) }

    fun setPort(port: Int) = container.settingsStore.update { it.copy(port = port) }

    fun setCommandId(command: LunaCommand, id: Int) = container.settingsStore.update { settings ->
        val ids = settings.commandIds.toMutableMap()
        if (id == 0) ids.remove(command.key) else ids[command.key] = id
        settings.copy(commandIds = ids)
    }

    fun setNotificationId(notification: LunaNotification, id: Int) = container.settingsStore.update { settings ->
        val ids = settings.notificationIds.toMutableMap()
        if (id == 0) ids.remove(notification.key) else ids[notification.key] = id
        settings.copy(notificationIds = ids)
    }

    fun updateHandshake(enabled: Boolean) =
        container.settingsStore.update { it.copy(handshakeEnabled = enabled) }

    fun updateLayout(transform: (LayoutSettings) -> LayoutSettings) =
        container.settingsStore.update { it.copy(layout = transform(it.layout)) }

    fun updateGimbal(transform: (GimbalSettings) -> GimbalSettings) =
        container.settingsStore.update { it.copy(gimbal = transform(it.gimbal)) }

    fun setDriveMode(mode: GimbalDriveMode) = updateGimbal { it.copy(driveMode = mode) }

    fun setTimelapseModeValue(value: Int) = container.settingsStore.update { it.copy(timelapseModeValue = value) }

    fun exportSettings(): String = container.settingsStore.exportJson()

    fun importSettings(text: String) {
        container.settingsStore.importJson(text)
            .onSuccess { showMessage("Impostazioni importate") }
            .onFailure { showMessage("JSON non valido: ${it.message}") }
    }

    // ---------------------------------------------------------------- diagnostica

    fun sendRaw(commandIdText: String, payloadHex: String) {
        val commandId = parseIntFlexible(commandIdText)
        if (commandId == null) {
            showMessage("Id comando non valido")
            return
        }
        val payload = if (payloadHex.isBlank()) ByteArray(0) else Hex.decodeOrNull(payloadHex)
        if (payload == null) {
            showMessage("Payload esadecimale non valido")
            return
        }
        viewModelScope.launch {
            container.session.requestRaw(commandId, payload)
                .onSuccess { container.log.info("Risposta:\n${it.describePayload()}") }
                .onFailure { showMessage(it.message ?: "Nessuna risposta") }
        }
    }

    /**
     * Sonda una serie di id comando con payload vuoto e registra quelli che rispondono:
     * è il modo pratico per ricostruire la tabella dei comandi senza documentazione.
     */
    fun scanCommands(fromText: String, toText: String) {
        if (_scanning.value) {
            scanJob?.cancel()
            _scanning.value = false
            return
        }
        val from = parseIntFlexible(fromText) ?: return showMessage("Range iniziale non valido")
        val to = parseIntFlexible(toText) ?: return showMessage("Range finale non valido")
        if (to < from || to - from > MAX_SCAN_RANGE) {
            showMessage("Range non valido (massimo $MAX_SCAN_RANGE id)")
            return
        }
        _scanResults.value = emptyList()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            for (id in from..to) {
                if (!isActive) break
                container.session.requestRaw(id, ByteArray(0), timeoutMs = SCAN_TIMEOUT_MS)
                    .onSuccess { frame ->
                        _scanResults.value = _scanResults.value + ScanResult(
                            commandId = id,
                            errorCode = frame.errorCode,
                            payloadSize = frame.payload.size,
                            dump = frame.describePayload(),
                        )
                        container.log.info("Comando 0x%04X → risposta di %d byte".format(id, frame.payload.size))
                    }
            }
            _scanning.value = false
        }
    }

    fun clearLog() = container.log.clear()

    fun exportLog(): String = container.log.exportText()

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
        const val SCAN_TIMEOUT_MS = 400L
        const val MAX_SCAN_RANGE = 1024
    }
}
