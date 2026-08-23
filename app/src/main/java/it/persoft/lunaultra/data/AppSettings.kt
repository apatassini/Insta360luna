package it.persoft.lunaultra.data

import it.persoft.lunaultra.protocol.LunaProtocolCodes
import kotlinx.serialization.Serializable

/**
 * Parametri del gimbal.
 *
 * Il movimento usa il comando e il payload verificati dalle catture Luna Ultra di
 * Insta360Linker. Restano configurabili soltanto le inversioni e i parametri di dead reckoning.
 */
@Serializable
data class GimbalSettings(
    /** Codice della notifica di stato PTZ; il predefinito è quello osservato in cattura. */
    val ptzNotificationCode: Int = LunaProtocolCodes.NOTIFICATION_PTZ_STATE_OBSERVED,

    /** Campi da cui leggere pan e tilt nella notifica PTZ, quando saranno identificati. */
    val ptzPanField: Int = 1,
    val ptzTiltField: Int = 2,

    /**
     * La notifica 8302 è reale, ma i suoi campi non sono ancora confermati come angoli.
     * Tenerla disattivata impedisce a valori sperimentali di spostare i waypoint memorizzati.
     */
    val useExperimentalPtzPosition: Boolean = false,

    /** Fattore di scala fra gradi e unità del protocollo (1 = gradi, 10 = decimi di grado). */
    val angleScale: Float = 10f,

    /** Velocità angolari stimate al 100%: servono a convertire la sequenza in tempi di comando. */
    val maxPanSpeedDegPerSec: Float = 30f,
    val maxTiltSpeedDegPerSec: Float = 20f,

    val manualSpeedPercent: Int = 40,
    /** Insta360Linker usa un tick di 25 ms; 40 Hz mantiene lo stesso ritmo. */
    val commandRateHz: Int = 40,
    /** Preset UI: 0=personalizzato, 1=25%, 2=50%, 3=75%; non è inviato alla camera. */
    val hardwareSpeedLevel: Int = 3,
    /** Usa le miniature dei waypoint per correggere visivamente partenza e arrivi. */
    val visualWaypointCorrection: Boolean = true,
    val invertPan: Boolean = false,
    val invertTilt: Boolean = false,
    /**
     * Azione nativa del gimbal che gira di 180° per il selfie, se è stata trovata.
     *
     * 0 significa "non la conosciamo": il selfie viene allora eseguito ruotando il pan di 180°
     * con il profilo di calibrazione. Il valore si scopre con la carta *Azioni del gimbal*
     * della Diagnostica e non viene indovinato qui.
     */
    val selfieActionCode: Int = 0,

    /** Rotazione applicata dal comando selfie quando l'azione nativa non è nota. */
    val selfieTurnDeg: Float = 180f,

    /** Intervallo controllabile ufficiale; una calibrazione valida lo misura e lo sostituisce. */
    val panMinDeg: Float = -57f,
    val panMaxDeg: Float = 235f,
    val tiltMinDeg: Float = -57f,
    val tiltMaxDeg: Float = 120f,
)

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
    /** Arresti verificati dalla Luna: 1×, 2×, 3×, 6× e 12×. */
    val zoomScale: Int = 1,
)

/** Impostazioni video verificate sulla Luna Ultra e applicate alla modalità attiva. */
@Serializable
data class VideoSettings(
    /** `VideoResolution`: 24 corrisponde a 3840×2160 @ 30 fps. */
    val profileCode: Int = 24,
    val proMode: Boolean = false,
    /** Zero significa ISO automatico. */
    val iso: Int = 0,
    /** Secondi reali; zero significa otturatore automatico. */
    val shutterSeconds: Double = 0.0,
    /** Compensazione in terzi di stop, da -12 a +12 (±4 EV). */
    val exposureBiasThirds: Int = 0,
    /** Zero = automatico; altrimenti 2000…10000 K. */
    val whiteBalanceKelvin: Int = 0,
    /** Standard=1, i-Log=2, Dolby Vision=5 sulla Luna Ultra. */
    val colorMode: Int = LunaProtocolCodes.ColorMode.STANDARD,
    /** Il campo `gamma_mode` è il filtro/Look, non una curva gamma. */
    val filter: Int = LunaProtocolCodes.Filter.ORIGINAL,
    val filterIntensity: Int = LunaProtocolCodes.FilterIntensity.MEDIUM,
    val sharpness: Int = 1,
)

@Serializable
data class AppSettings(
    val host: String = "192.168.42.1",
    val port: Int = 6666,

    /**
     * Credenziale dell'access point della camera. Android non espone alle app le password
     * delle reti già salvate: dopo una prima connessione manuale la leggiamo dalla camera con
     * `GET_OPTIONS(WIFI_INFO)` e la conserviamo per le connessioni automatiche successive.
     */
    val cameraWifiPassword: String = "",

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

    val video: VideoSettings = VideoSettings(),

    /**
     * Modalità timelapse usata dai comandi `*_TIMELAPSE`
     * (`insta360.messages.TimelapseMode`).
     */
    val timelapseMode: Int = LunaProtocolCodes.TimelapseMode.STATIC_TIMELAPSE_VIDEO,
)
