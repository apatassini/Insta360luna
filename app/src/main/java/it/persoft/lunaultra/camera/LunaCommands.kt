package it.persoft.lunaultra.camera

import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.data.GimbalSettings
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.protocol.LunaMessages
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.protocol.LunaProtocolCodes.BatteryField
import it.persoft.lunaultra.protocol.LunaProtocolCodes.CaptureStatusField
import it.persoft.lunaultra.protocol.LunaProtocolCodes.OptionType
import it.persoft.lunaultra.protocol.LunaProtocolCodes.OptionsField
import it.persoft.lunaultra.protocol.LunaProtocolCodes.StorageField
import it.persoft.lunaultra.protocol.ProtoReader
import it.persoft.lunaultra.protocol.Ucd2Frame
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * I comandi della camera, composti sui messaggi protobuf reali del namespace
 * `insta360.messages`.
 *
 * Tutto ciò che sta sopra il gimbal (stato, batteria, registrazione, timelapse) usa numeri di
 * comando e di campo noti. Il gimbal no: il suo comando ha un nome documentato
 * (`PHONE_COMMAND_GIMBAL_CONTROL`) ma nessun numero pubblico, quindi passa da
 * [GimbalSettings.controlCode], che si popola con lo scanner della schermata Diagnostica.
 */
class LunaCommands(
    private val session: CameraSession,
    private val settings: StateFlow<AppSettings>,
    private val log: EventLog,
) {

    private val gimbal: GimbalSettings get() = settings.value.gimbal

    /** Nella risposta `GetOptionsResp` il messaggio `Options` sta nel campo 2. */
    private fun optionsReader(frame: Ucd2Frame): ProtoReader = ProtoReader(frame.payload)

    suspend fun fetchCameraInfo(): Result<CameraStatus> =
        session.request(
            LunaProtocolCodes.GET_OPTIONS,
            LunaMessages.getOptions(
                OptionType.CAMERA_TYPE,
                OptionType.SERIAL_NUMBER,
                OptionType.FIRMWARE_REVISION,
            ),
        ).map { frame ->
            val reader = optionsReader(frame)
            CameraStatus(
                model = reader.stringOrNull(OPTIONS, OptionsField.CAMERA_TYPE),
                serial = reader.stringOrNull(OPTIONS, OptionsField.SERIAL_NUMBER),
                firmware = reader.stringOrNull(OPTIONS, OptionsField.FIRMWARE_REVISION),
                lastUpdateMs = System.currentTimeMillis(),
                rawDump = frame.describePayload(),
            )
        }

    /**
     * Stato corrente: batteria e storage da `GET_OPTIONS`, registrazione da
     * `GET_CURRENT_CAPTURE_STATUS`. Sono due comandi distinti perché lo stato di cattura non
     * è un'opzione memorizzata.
     */
    suspend fun fetchStatus(): Result<CameraStatus> {
        val options = session.request(
            LunaProtocolCodes.GET_OPTIONS,
            LunaMessages.getOptions(OptionType.BATTERY_STATUS, OptionType.STORAGE_STATE),
        )
        val optionsFrame = options.getOrElse { return Result.failure(it) }
        val reader = optionsReader(optionsFrame)

        val level = reader.intOrNull(OPTIONS, OptionsField.BATTERY_STATUS, BatteryField.BATTERY_LEVEL)
        val scale = reader.intOrNull(OPTIONS, OptionsField.BATTERY_STATUS, BatteryField.BATTERY_SCALE)
        val free = reader.intOrNull(OPTIONS, OptionsField.STORAGE_STATE, StorageField.FREE_SPACE)
        val total = reader.intOrNull(OPTIONS, OptionsField.STORAGE_STATE, StorageField.TOTAL_SPACE)

        val capture = fetchCaptureState()

        return Result.success(
            CameraStatus(
                batteryPercent = batteryPercent(level, scale),
                recording = capture?.let { LunaProtocolCodes.CaptureState.isRecording(it.state) },
                captureMode = capture?.let { LunaProtocolCodes.CaptureState.name(it.state) },
                captureSeconds = capture?.seconds,
                freeSpaceBytes = free?.toLong(),
                totalSpaceBytes = total?.toLong(),
                lastUpdateMs = System.currentTimeMillis(),
                rawDump = optionsFrame.describePayload(),
            )
        )
    }

    /**
     * `battery_scale` è il fondo scala del livello. Quando la camera non lo manda vale 100,
     * ma non lo si può dare per scontato: uno scale a 255 renderebbe la percentuale assurda.
     */
    private fun batteryPercent(level: Int?, scale: Int?): Int? {
        if (level == null) return null
        val fullScale = scale?.takeIf { it > 0 } ?: 100
        return ((level.toFloat() / fullScale) * 100f).roundToInt().coerceIn(0, 100)
    }

    private suspend fun fetchCaptureState(): CaptureSnapshot? =
        session.request(LunaProtocolCodes.GET_CURRENT_CAPTURE_STATUS).map { frame ->
            parseCaptureStatus(frame.payload, nested = true)
        }.getOrNull()

    /**
     * Legge `CameraCaptureStatus`. Nella risposta a `GET_CURRENT_CAPTURE_STATUS` è annidato nel
     * campo 1 di `GetCurrentCaptureStatusResp`; nella notifica 8208 arriva invece nudo.
     */
    fun parseCaptureStatus(payload: ByteArray, nested: Boolean): CaptureSnapshot {
        val reader = ProtoReader(payload)
        val path = if (nested) intArrayOf(1) else IntArray(0)
        val state = reader.intOrNull(*(path + CaptureStatusField.STATE)) ?: 0
        val seconds = reader.intOrNull(*(path + CaptureStatusField.CAPTURE_TIME))
        return CaptureSnapshot(state = state, seconds = seconds)
    }

    fun statusFromNotification(frame: Ucd2Frame): CameraStatus? = when (frame.code) {
        LunaProtocolCodes.NOTIFICATION_CURRENT_CAPTURE_STATUS -> {
            val snapshot = parseCaptureStatus(frame.payload, nested = false)
            CameraStatus(
                recording = LunaProtocolCodes.CaptureState.isRecording(snapshot.state),
                captureMode = LunaProtocolCodes.CaptureState.name(snapshot.state),
                captureSeconds = snapshot.seconds,
                lastUpdateMs = System.currentTimeMillis(),
            )
        }

        LunaProtocolCodes.NOTIFICATION_BATTERY_UPDATE -> {
            val reader = ProtoReader(frame.payload)
            val level = reader.intOrNull(BatteryField.BATTERY_LEVEL)
            val scale = reader.intOrNull(BatteryField.BATTERY_SCALE)
            batteryPercent(level, scale)?.let {
                CameraStatus(batteryPercent = it, lastUpdateMs = System.currentTimeMillis())
            }
        }

        else -> null
    }

    // ---- Registrazione ----

    /** `StartCapture { CaptureMode mode = 1 }` */
    suspend fun startCapture(): Result<Unit> =
        session.request(
            LunaProtocolCodes.START_CAPTURE,
            LunaMessages.startCapture(LunaProtocolCodes.CaptureMode.NORMAL),
        ).map { }

    /** `StopCapture { ExtraMetadata extra_metadata = 1; CaptureMode mode = 2 }` */
    suspend fun stopCapture(): Result<Unit> =
        session.request(
            LunaProtocolCodes.STOP_CAPTURE,
            LunaMessages.stopCapture(LunaProtocolCodes.CaptureMode.NORMAL),
        ).map { }

    /** `TakePicture { CaptureMode mode = 1 }` — uno scatto singolo. */
    suspend fun takePicture(): Result<Unit> =
        session.request(
            LunaProtocolCodes.TAKE_PICTURE,
            LunaMessages.takePicture(LunaProtocolCodes.CaptureMode.NORMAL),
            timeoutMs = PHOTO_TIMEOUT_MS,
        ).map { }

    // ---- Anteprima dal vivo ----

    /**
     * Chiede alla camera di aprire lo stream di anteprima. Il video non arriva come risposta:
     * viene spinto sui frame media della stessa sessione, che [CameraSession.videoFrames]
     * ripubblica.
     */
    suspend fun startLiveStream(): Result<Unit> =
        session.request(LunaProtocolCodes.START_LIVE_STREAM, LunaMessages.startLiveStream()).map { }

    suspend fun stopLiveStream(): Result<Unit> =
        session.request(LunaProtocolCodes.STOP_LIVE_STREAM).map { }

    // ---- Timelapse ----

    /**
     * `SetTimelapseOptions { TimelapseOptions timelapse_options = 1; TimelapseMode mode = 2 }`
     *
     * `duration` è la durata totale in secondi (0 = illimitata), `lapseTime` l'intervallo fra
     * uno scatto e il successivo, sempre in secondi.
     */
    suspend fun setTimelapseOptions(durationSeconds: Int, intervalSeconds: Int): Result<Unit> {
        val body = LunaMessages.setTimelapseOptions(
            durationSeconds = durationSeconds,
            intervalSeconds = intervalSeconds,
            mode = settings.value.timelapseMode,
        )
        return session.request(LunaProtocolCodes.SET_TIMELAPSE_OPTIONS, body).map { }
    }

    /** `StartTimelapse { TimelapseMode mode = 1 }` */
    suspend fun startTimelapse(): Result<Unit> =
        session.request(
            LunaProtocolCodes.START_TIMELAPSE,
            LunaMessages.timelapseMode(settings.value.timelapseMode),
        ).map { }

    /** `StopTimelapse { TimelapseMode mode = 1 }` */
    suspend fun stopTimelapse(): Result<Unit> =
        session.request(
            LunaProtocolCodes.STOP_TIMELAPSE,
            LunaMessages.timelapseMode(settings.value.timelapseMode),
        ).map { }

    /**
     * Avvia la ripresa secondo la modalità scelta. Il timelapse della camera e la registrazione
     * normale sono due comandi diversi: con il movimento del gimbal gestito dall'app, la
     * registrazione normale è spesso la scelta giusta (il timelapse interno accelera i tempi
     * e rende difficile far coincidere durata reale e durata della sequenza).
     */
    suspend fun startRecording(useCameraTimelapse: Boolean): Result<Unit> =
        if (useCameraTimelapse) startTimelapse() else startCapture()

    suspend fun stopRecording(useCameraTimelapse: Boolean): Result<Unit> =
        if (useCameraTimelapse) stopTimelapse() else stopCapture()

    // ---- Gimbal ----

    /**
     * Movimento a velocità: [panPercent] e [tiltPercent] vanno da -1 a +1.
     *
     * La forma del messaggio non è documentata. I numeri di campo sono quelli configurati in
     * [GimbalSettings] e l'ipotesi di partenza (asse, direzione, velocità come varint piccoli)
     * è quella suggerita dallo scanner: vanno confermati guardando la camera.
     */
    suspend fun gimbalVelocity(panPercent: Float, tiltPercent: Float): Result<Unit> {
        val cfg = gimbal
        val code = cfg.controlCode
        if (code == 0) return Result.failure(UnknownGimbalCodeException())
        val pan = applySign(panPercent.coerceIn(-1f, 1f), cfg.invertPan)
        val tilt = applySign(tiltPercent.coerceIn(-1f, 1f), cfg.invertTilt)
        val payload = LunaMessages.gimbalVelocity(
            panField = cfg.panFieldNumber,
            panValue = (pan * cfg.manualSpeedPercent).roundToInt(),
            tiltField = cfg.tiltFieldNumber,
            tiltValue = (tilt * cfg.manualSpeedPercent).roundToInt(),
        )
        return session.fire(code, payload)
    }

    suspend fun gimbalStop(): Result<Unit> = gimbalVelocity(0f, 0f)

    /**
     * Legge lo stato PTZ da una notifica.
     *
     * L'unico riscontro pubblico su questo blocco è il traffico osservato sul codice 8302,
     * compatibile con `CAMERA_NOTIFICATION_PTZ_STATE`: nove varint, di cui due commutano
     * quando il gimbal tocca i finecorsa. Da soli non danno gli angoli, quindi finché i campi
     * non sono confermati questa funzione restituisce ciò che riesce a leggere e nulla più.
     */
    fun parsePtz(frame: Ucd2Frame): PtzState? {
        val cfg = gimbal
        val reader = ProtoReader(frame.payload)
        val pan = reader.floatOrNull(cfg.ptzPanField) ?: return null
        val tilt = reader.floatOrNull(cfg.ptzTiltField) ?: 0f
        val scale = if (cfg.angleScale == 0f) 1f else cfg.angleScale
        return PtzState(
            pan = applySign(pan / scale, cfg.invertPan),
            tilt = applySign(tilt / scale, cfg.invertTilt),
            fromCamera = true,
            lastUpdateMs = System.currentTimeMillis(),
        )
    }

    private fun applySign(value: Float, invert: Boolean): Float = if (invert) -value else value

    class UnknownGimbalCodeException : IllegalStateException(
        "Il codice di PHONE_COMMAND_GIMBAL_CONTROL non è noto: trovalo con lo scanner in Diagnostica"
    )

    data class CaptureSnapshot(val state: Int, val seconds: Int?)

    companion object {
        private const val OPTIONS = LunaMessages.FIELD_OPTIONS_VALUE

        /** Uno scatto può richiedere più tempo di un comando qualsiasi: HDR, posa lunga, salvataggio. */
        private const val PHOTO_TIMEOUT_MS = 15_000L
    }
}
