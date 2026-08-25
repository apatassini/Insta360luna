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
     * Azione nativa che commuta fra fronte e selfie. Misurata sulla Luna Ultra: è la 3.
     *
     * Resta configurabile perché è un numero trovato provando, non letto in un `.proto`: se
     * un firmware la sposta, si cambia da Diagnostica senza ricompilare. Zero disattiva
     * l'azione nativa e fa ruotare il pan di 180° con il profilo di calibrazione — più lento
     * e meno preciso, ma funziona anche senza conoscere il numero.
     */
    val selfieActionCode: Int = 3,

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
    /**
     * L'enum `PhotoSize` dichiarato dalla camera (-1 = non ancora letto). Non è un desiderio:
     * è ciò che la camera ha risposto all'ultima lettura, e l'interruttore Ultra/Standard
     * mostra questo.
     */
    val photoSizeCode: Int = -1,
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

/**
 * Le manopole dell'unione foto, regolabili dalle impostazioni.
 *
 * La modalità test è il banco di prova: 3 foto a 1024 px, tutte le ricette in fila (A, B,
 * C…), ognuna salvata in galleria col suo nome — si confrontano e si sceglie la migliore,
 * senza pagare ogni volta i minuti dell'unione a piena risoluzione.
 */
@Serializable
data class StitchSettings(
    val testMode: Boolean = false,
    /** Qualità minima dei punti di controllo, in percento: 80 è lo standard, 100 i soli perfetti. */
    val controlQualityPercent: Int = 80,
    /** Quantità di punti di controllo: 1 = normale, 2 = doppia, 4 = quadrupla. */
    val controlDensity: Int = 1,

    /**
     * Far passare la giunzione dove le due foto già si assomigliano, invece che a metà
     * strada. È quello che impedisce a un taglio di cadere in mezzo a un oggetto vicino.
     */
    val seamMinimalDifference: Boolean = true,

    /**
     * La deformazione locale che assorbe la parallasse: il gimbal non ruota attorno al
     * centro ottico, e quello che è vicino si sposta più di quello che è lontano.
     */
    val localWarp: Boolean = true,

    /**
     * Di quanto la focale misurata può discostarsi da quella dichiarata, in percento. I
     * 20 mm equivalenti sono un dato di catalogo: se la focale vera è più lunga, le foto
     * combaciano al centro e divergono ai bordi.
     */
    val focalFreedomPercent: Int = 20,
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

    /**
     * Branch di cui cercare gli aggiornamenti, se diverso da quello che ha prodotto l'APK.
     *
     * Vuoto significa "quello da cui vengo": è il caso normale. Serve a saltare da un ramo
     * all'altro dal telefono, senza dover reinstallare a mano l'APK del ramo nuovo — che è
     * esattamente quello che toccava fare quando il ramo era scritto nel codice.
     */
    val updateBranch: String = "",

    /**
     * A unione riuscita, cancellare gli scatti temporanei e il job? Spento di serie: con le
     * foto che restano, lo stesso job si può rilanciare quante volte serve — è il banco di
     * prova dell'unione, senza dover riscattare niente.
     */
    val deleteJobAfterStitch: Boolean = false,

    /** Le manopole dell'unione foto: modalità test e punti di controllo. */
    val stitch: StitchSettings = StitchSettings(),

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
