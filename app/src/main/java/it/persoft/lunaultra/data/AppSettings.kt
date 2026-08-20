package it.persoft.lunaultra.data

import it.persoft.lunaultra.protocol.Ucd2Layout
import kotlinx.serialization.Serializable

@Serializable
enum class GimbalDriveMode {
    /** Comandi di velocità ripetuti (funziona anche senza lettura della posizione). */
    VELOCITY,

    /** Comandi di posizione assoluta: richiede SET_PTZ_OPTION funzionante. */
    ABSOLUTE,
}

/** Parametri del layout binario dei frame, modificabili dalla schermata Diagnostica. */
@Serializable
data class LayoutSettings(
    val headerSize: Int = 16,
    val lengthOffset: Int = 0,
    val lengthSize: Int = 4,
    val lengthIncludesHeader: Boolean = true,
    val versionOffset: Int = 4,
    val version: Int = 2,
    val typeOffset: Int = 5,
    val sequenceOffset: Int = 6,
    val sequenceSize: Int = 2,
    val commandOffset: Int = 8,
    val commandSize: Int = 4,
    val errorOffset: Int = 12,
    val errorSize: Int = 4,
    val littleEndian: Boolean = true,
    val validateVersion: Boolean = true,
) {
    fun toLayout(): Ucd2Layout = Ucd2Layout(
        headerSize = headerSize,
        lengthOffset = lengthOffset,
        lengthSize = lengthSize,
        lengthIncludesHeader = lengthIncludesHeader,
        versionOffset = versionOffset,
        version = version,
        typeOffset = typeOffset,
        sequenceOffset = sequenceOffset,
        sequenceSize = sequenceSize,
        commandOffset = commandOffset,
        commandSize = commandSize,
        errorOffset = errorOffset,
        errorSize = errorSize,
        littleEndian = littleEndian,
        validateVersion = validateVersion,
    )

    companion object {
        fun from(layout: Ucd2Layout) = LayoutSettings(
            headerSize = layout.headerSize,
            lengthOffset = layout.lengthOffset,
            lengthSize = layout.lengthSize,
            lengthIncludesHeader = layout.lengthIncludesHeader,
            versionOffset = layout.versionOffset,
            version = layout.version,
            typeOffset = layout.typeOffset,
            sequenceOffset = layout.sequenceOffset,
            sequenceSize = layout.sequenceSize,
            commandOffset = layout.commandOffset,
            commandSize = layout.commandSize,
            errorOffset = layout.errorOffset,
            errorSize = layout.errorSize,
            littleEndian = layout.littleEndian,
            validateVersion = layout.validateVersion,
        )
    }
}

/**
 * Numeri di campo protobuf usati per comporre i payload dei comandi gimbal.
 * Sono ipotesi: si correggono qui senza toccare il codice quando la cattura li conferma.
 */
@Serializable
data class GimbalSettings(
    val driveMode: GimbalDriveMode = GimbalDriveMode.VELOCITY,
    val panFieldNumber: Int = 1,
    val tiltFieldNumber: Int = 2,
    val speedFieldNumber: Int = 3,
    val modeFieldNumber: Int = 4,
    /** Fattore di scala fra gradi e unità del protocollo (1 = gradi, 10 = decimi di grado). */
    val angleScale: Float = 10f,
    val maxPanSpeedDegPerSec: Float = 30f,
    val maxTiltSpeedDegPerSec: Float = 20f,
    val manualSpeedPercent: Int = 40,
    val commandRateHz: Int = 10,
    val invertPan: Boolean = false,
    val invertTilt: Boolean = false,
    val panMinDeg: Float = -170f,
    val panMaxDeg: Float = 170f,
    val tiltMinDeg: Float = -90f,
    val tiltMaxDeg: Float = 90f,
)

/** Percorsi dei campi da cui estrarre lo stato camera dalla risposta protobuf. */
@Serializable
data class StatusFieldSettings(
    val batteryPath: List<Int> = listOf(1),
    val chargingPath: List<Int> = listOf(2),
    val recordingPath: List<Int> = listOf(3),
    val capturePath: List<Int> = listOf(4),
    val ptzPanPath: List<Int> = listOf(1),
    val ptzTiltPath: List<Int> = listOf(2),
    val ptzRollPath: List<Int> = listOf(3),
)

@Serializable
data class AppSettings(
    val host: String = "192.168.42.1",
    val port: Int = 6666,
    val handshakeEnabled: Boolean = true,
    val keepAliveSeconds: Int = 2,
    val requestTimeoutMs: Long = 3_000,
    val layout: LayoutSettings = LayoutSettings(),
    /** Chiave = [it.persoft.lunaultra.camera.LunaCommand.key], valore = id numerico del comando. */
    val commandIds: Map<String, Int> = emptyMap(),
    val notificationIds: Map<String, Int> = emptyMap(),
    val gimbal: GimbalSettings = GimbalSettings(),
    val statusFields: StatusFieldSettings = StatusFieldSettings(),
    /** Valore protobuf della modalità Timelapse (0 = sconosciuto: il cambio modalità viene saltato). */
    val timelapseModeValue: Int = 0,
    /** Numero di campo usato nel payload di SET_CAPTURE_MODE. */
    val captureModeFieldNumber: Int = 1,
)
