package it.persoft.lunaultra.data

import it.persoft.lunaultra.protocol.LunaProtocolCodes
import kotlinx.serialization.Serializable

/**
 * Parametri del gimbal.
 *
 * A differenza del resto del protocollo, qui quasi tutto è ancora da confermare: il comando
 * `PHONE_COMMAND_GIMBAL_CONTROL` ha un nome documentato ma nessun numero pubblico, e la forma
 * del suo messaggio non è descritta da nessuna estrazione. Questi valori si correggono
 * dall'app, senza ricompilare, man mano che lo scanner e le prove sul campo li fissano.
 */
@Serializable
data class GimbalSettings(
    /**
     * Numero di `PHONE_COMMAND_GIMBAL_CONTROL`. 0 = ancora ignoto: finché resta 0 l'app
     * rifiuta di muovere il gimbal invece di sparare byte a un codice a caso.
     */
    val controlCode: Int = 0,

    /** Codice della notifica di stato PTZ; il predefinito è quello osservato in cattura. */
    val ptzNotificationCode: Int = LunaProtocolCodes.NOTIFICATION_PTZ_STATE_OBSERVED,

    val panFieldNumber: Int = 1,
    val tiltFieldNumber: Int = 2,

    /** Campi da cui leggere pan e tilt nella notifica PTZ, quando saranno identificati. */
    val ptzPanField: Int = 1,
    val ptzTiltField: Int = 2,

    /** Fattore di scala fra gradi e unità del protocollo (1 = gradi, 10 = decimi di grado). */
    val angleScale: Float = 10f,

    /** Velocità angolari stimate al 100%: servono a convertire la sequenza in tempi di comando. */
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
) {
    val isControlCodeKnown: Boolean get() = controlCode != 0
}

/** Regolazioni fotografiche essenziali mostrate nel mirino. */
@Serializable
data class PhotoSettings(
    /** Ritardo gestito dall'app prima di inviare lo scatto. */
    val timerSeconds: Int = 0,
    /** Auto azzera le regolazioni manuali senza dimenticare le ultime scelte Pro. */
    val proMode: Boolean = false,
    /** `RAW_CAPTURE_TYPE_OFF` = JPG, `RAW_CAPTURE_TYPE_DNG` = JPG + DNG. */
    val rawCaptureType: Int = LunaProtocolCodes.RawCaptureType.OFF,
    /** Scala della camera: -2…+2. */
    val brightness: Int = 0,
    /** Compensazione in terzi di stop: -6…+6 corrisponde a -2…+2 EV. */
    val exposureBiasThirds: Int = 0,
    /** Zero = automatico, altrimenti temperatura in kelvin. */
    val whiteBalanceKelvin: Int = 0,
)

@Serializable
data class AppSettings(
    val host: String = "192.168.42.1",
    val port: Int = 6666,

    /** L'app ufficiale ripete l'handshake ogni 3 secondi come keep-alive. */
    val keepAliveSeconds: Int = 3,
    val requestTimeoutMs: Long = 3_000,

    val gimbal: GimbalSettings = GimbalSettings(),

    /**
     * Proporzione della panoramica della camera: sferica 360° oppure 2:1
     * (`insta360.messages.PanoAspect`). Sulla Luna Ultra la panoramica è una sola
     * sotto-modalità e questa è la scelta che la distingue.
     */
    val panoAspect: Int = LunaProtocolCodes.PanoAspect.SPHERE_360,

    val photo: PhotoSettings = PhotoSettings(),

    /**
     * Modalità timelapse usata dai comandi `*_TIMELAPSE`
     * (`insta360.messages.TimelapseMode`).
     */
    val timelapseMode: Int = LunaProtocolCodes.TimelapseMode.STATIC_TIMELAPSE_VIDEO,
)
