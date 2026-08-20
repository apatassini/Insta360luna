package it.persoft.lunaultra.camera

import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.data.GimbalSettings
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.protocol.ProtoReader
import it.persoft.lunaultra.protocol.ProtoWriter
import it.persoft.lunaultra.protocol.Ucd2Frame
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * API di alto livello sui comandi della camera. Ogni metodo compone il payload protobuf
 * secondo i numeri di campo configurati e delega l'invio alla [CameraSession].
 */
class LunaCommands(
    private val session: CameraSession,
    private val settings: StateFlow<AppSettings>,
    private val log: EventLog,
) {

    private val gimbal: GimbalSettings get() = settings.value.gimbal

    suspend fun fetchCameraInfo(): Result<CameraStatus> =
        session.request(LunaCommand.GET_CAMERA_INFO, ByteArray(0)).map { frame ->
            val reader = ProtoReader(frame.payload)
            CameraStatus(
                model = reader.stringOrNull(1),
                firmware = reader.stringOrNull(2),
                lastUpdateMs = System.currentTimeMillis(),
                rawDump = frame.describePayload(),
            )
        }

    suspend fun fetchStatus(): Result<CameraStatus> =
        session.request(LunaCommand.GET_CAMERA_STATE, ByteArray(0)).map { parseStatus(it) }

    fun parseStatus(frame: Ucd2Frame): CameraStatus {
        val fields = settings.value.statusFields
        val reader = ProtoReader(frame.payload)
        return CameraStatus(
            batteryPercent = reader.intOrNull(*fields.batteryPath.toIntArray())?.takeIf { it in 0..100 },
            charging = reader.intOrNull(*fields.chargingPath.toIntArray())?.let { it != 0 },
            recording = reader.intOrNull(*fields.recordingPath.toIntArray())?.let { it != 0 },
            captureMode = reader.intOrNull(*fields.capturePath.toIntArray())?.toString(),
            lastUpdateMs = System.currentTimeMillis(),
            rawDump = frame.describePayload(),
        )
    }

    suspend fun selectTimelapseMode(): Result<Unit> {
        val modeValue = settings.value.timelapseModeValue
        if (modeValue == 0) {
            log.warn("Valore della modalità Timelapse non configurato: cambio modalità saltato")
            return Result.success(Unit)
        }
        val payload = ProtoWriter().int32(settings.value.captureModeFieldNumber, modeValue).toByteArray()
        return session.request(LunaCommand.SET_CAPTURE_MODE, payload).map { }
    }

    suspend fun startCapture(): Result<Unit> =
        session.request(LunaCommand.START_CAPTURE, ByteArray(0)).map { }

    suspend fun stopCapture(): Result<Unit> =
        session.request(LunaCommand.STOP_CAPTURE, ByteArray(0)).map { }

    /**
     * Movimento a velocità: [panPercent] e [tiltPercent] vanno da -1 a +1 (frazione della
     * velocità massima). Inviato senza attendere risposta perché ripetuto ad alta frequenza.
     */
    suspend fun gimbalVelocity(panPercent: Float, tiltPercent: Float): Result<Unit> {
        val cfg = gimbal
        val pan = applySign(panPercent.coerceIn(-1f, 1f), cfg.invertPan)
        val tilt = applySign(tiltPercent.coerceIn(-1f, 1f), cfg.invertTilt)
        val payload = ProtoWriter()
            .sint32(cfg.panFieldNumber, (pan * 100f).roundToInt())
            .sint32(cfg.tiltFieldNumber, (tilt * 100f).roundToInt())
            .int32(cfg.speedFieldNumber, cfg.manualSpeedPercent.coerceIn(1, 100))
            .int32(cfg.modeFieldNumber, MODE_VELOCITY)
            .toByteArray()
        return session.fire(LunaCommand.GIMBAL_CONTROL, payload)
    }

    suspend fun gimbalStop(): Result<Unit> = gimbalVelocity(0f, 0f)

    /** Movimento a posizione assoluta in gradi (richiede SET_PTZ_OPTION funzionante). */
    suspend fun gimbalAbsolute(panDeg: Float, tiltDeg: Float): Result<Unit> {
        val cfg = gimbal
        val pan = panDeg.coerceIn(cfg.panMinDeg, cfg.panMaxDeg)
        val tilt = tiltDeg.coerceIn(cfg.tiltMinDeg, cfg.tiltMaxDeg)
        val payload = ProtoWriter()
            .sint32(cfg.panFieldNumber, (applySign(pan, cfg.invertPan) * cfg.angleScale).roundToInt())
            .sint32(cfg.tiltFieldNumber, (applySign(tilt, cfg.invertTilt) * cfg.angleScale).roundToInt())
            .int32(cfg.modeFieldNumber, MODE_ABSOLUTE)
            .toByteArray()
        return session.fire(LunaCommand.SET_PTZ_OPTION, payload)
    }

    suspend fun readPtz(): Result<PtzState> =
        session.request(LunaCommand.GET_PTZ_OPTION, ByteArray(0)).mapCatching { frame ->
            parsePtz(frame) ?: error("Risposta PTZ non interpretabile")
        }

    /** Estrae pan/tilt/roll da una risposta o da una notifica PTZ. */
    fun parsePtz(frame: Ucd2Frame): PtzState? {
        val fields = settings.value.statusFields
        val cfg = gimbal
        val reader = ProtoReader(frame.payload)
        val pan = reader.floatOrNull(*fields.ptzPanPath.toIntArray()) ?: return null
        val tilt = reader.floatOrNull(*fields.ptzTiltPath.toIntArray()) ?: 0f
        val roll = reader.floatOrNull(*fields.ptzRollPath.toIntArray()) ?: 0f
        val scale = if (cfg.angleScale == 0f) 1f else cfg.angleScale
        return PtzState(
            pan = applySign(pan / scale, cfg.invertPan),
            tilt = applySign(tilt / scale, cfg.invertTilt),
            roll = roll / scale,
            fromCamera = true,
            lastUpdateMs = System.currentTimeMillis(),
        )
    }

    private fun applySign(value: Float, invert: Boolean): Float = if (invert) -value else value

    companion object {
        const val MODE_VELOCITY = 0
        const val MODE_ABSOLUTE = 1
    }
}
