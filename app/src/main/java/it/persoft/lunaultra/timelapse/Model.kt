package it.persoft.lunaultra.timelapse

import kotlinx.serialization.Serializable
import java.util.Base64
import java.util.UUID

@Serializable
enum class InterpolationMode(val label: String) {
    LINEAR("Lineare"),
    SMOOTH("Smooth"),
    ;
}

/**
 * Cosa deve fare la camera mentre il gimbal percorre la sequenza.
 *
 * La differenza fra le tre non è un dettaglio di comando: cambia il senso della durata che
 * imposti. In [VIDEO] la durata è tempo reale di ripresa; in [TIMELAPSE_CAMERA] è il tempo che
 * la camera comprimerà da sé; in [FOTO] non è tempo di ripresa affatto, ma il tempo che il
 * gimbal impiega a passare da uno scatto al successivo.
 */
@Serializable
enum class ShootingMode(val label: String, val description: String) {
    VIDEO(
        "Video",
        "Registra video normale lungo tutto il percorso. Durata reale = durata della sequenza: " +
            "l'accelerazione la fai in montaggio, con pieno controllo.",
    ),
    TIMELAPSE_CAMERA(
        "Timelapse camera",
        "Usa il timelapse interno della camera, che comprime i tempi da sé. Comodo, ma il " +
            "risultato finale non dura quanto la sequenza.",
    ),
    FOTO(
        "Foto a scatti",
        "Si ferma a ogni punto di scatto, aspetta che il gimbal sia immobile e fotografa. " +
            "È la modalità per le panoramiche da unire in post produzione.",
    ),
    ;

    val movesContinuously: Boolean get() = this != FOTO
}

@Serializable
data class Waypoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val pan: Float,
    val tilt: Float,
    /** Durata del tratto verso il waypoint successivo, in secondi. Ignorata per l'ultimo punto. */
    val durationToNextSeconds: Float = 30f,
    /** 1 = vecchia stima; 2 = assi corretti e integrazione sul tempo reale. */
    val positionModelVersion: Int = LEGACY_POSITION_MODEL_VERSION,
    /** Inquadratura 256×256 vista quando il punto è stato memorizzato, JPEG in Base64. */
    val previewJpegBase64: String? = null,
    /** Punto assoluto generato dalla griglia panorama; non richiede una miniatura manuale. */
    val generatedByPanoramaPlanner: Boolean = false,
) {
    val needsRecapture: Boolean get() = positionModelVersion < CURRENT_POSITION_MODEL_VERSION

    fun previewJpeg(): ByteArray? = previewJpegBase64?.let { encoded ->
        runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    companion object {
        const val LEGACY_POSITION_MODEL_VERSION = 1
        const val CURRENT_POSITION_MODEL_VERSION = 2

        fun encodePreview(jpeg: ByteArray?): String? = jpeg?.let(Base64.getEncoder()::encodeToString)
    }
}

@Serializable
data class TimelapseSequence(
    val waypoints: List<Waypoint> = emptyList(),
    val mode: ShootingMode = ShootingMode.VIDEO,
    val intervalSeconds: Float = 2f,
    val totalDurationSeconds: Float = 60f,
    val interpolation: InterpolationMode = InterpolationMode.SMOOTH,

    /**
     * Quante volte la velocita' media si arriva a toccare a meta' tratto.
     *
     * Con l'interpolazione morbida la camera parte da ferma, accelera e rallenta: da qualche
     * parte deve recuperare il tempo perso ai due capi, e lo fa in mezzo. La vecchia smoothstep
     * arrivava a **1,5 volte** la media, che su un movimento lungo si vede — sembra che a meta'
     * strada qualcuno acceleri.
     *
     * Uno significa velocita' costante, cioe' la retta. Due significa rampe che si toccano a
     * meta', senza nessun tratto a velocita' costante. Uno e due sono gli estremi, e 1,2 e' il
     * compromesso: partenza e arresto ancora morbidi, ma il centro va appena il 20% piu' della
     * media invece del 50%.
     */
    val easingPeak: Float = 1.2f,
    /** Se true le durate dei tratti derivano da [totalDurationSeconds] divisa equamente. */
    val useTotalDuration: Boolean = true,
    val controlRecording: Boolean = true,
    /** Se true invia a monte durata e intervallo alla camera con SET_TIMELAPSE_OPTIONS. */
    val configureCameraTimelapse: Boolean = true,

    /** Secondi registrati e fermi sul primo punto, prima di iniziare il movimento. */
    val startHoldSeconds: Float = 1f,

    /** Secondi registrati e fermi sull'ultimo punto, prima di fermare la ripresa. */
    val endHoldSeconds: Float = 1f,

    /** Scatti per tratto in modalità [ShootingMode.FOTO], estremi inclusi. */
    val shotsPerLeg: Int = 6,

    /**
     * Pausa fra l'arrivo in posizione e lo scatto. Il gimbal ha un'inerzia: fotografare subito
     * dopo un movimento produce scatti mossi, che in una panoramica si vedono all'unione.
     */
    val settleSeconds: Float = 1.5f,

    /** Copertura finale richiesta: comprende anche il campo visivo del singolo fotogramma. */
    val panoramaHorizontalDegrees: Float = 180f,
    val panoramaVerticalDegrees: Float = 0f,
    val panoramaOverlapPercent: Int = 30,

    /**
     * Scatti a 12 MP invece che a piena risoluzione.
     *
     * Serve alle panoramiche di taratura, dove le foto non si guardano: si misurano. L'unione
     * lavora comunque a 3200 px sul lato lungo, quindi 4000×3000 sono già più di quanto le serva
     * per riconoscere i dettagli e ricavare gli angoli — mentre da scaricare sono un terzo, e il
     * Wi-Fi della camera è la parte lenta di tutto il procedimento.
     *
     * Il rapporto fotografico non cambia (4:3 come a piena risoluzione), quindi non cambia il
     * campo visivo: la geometria misurata è la stessa, solo con meno pixel dentro.
     */
    val panoramaLowResolution: Boolean = false,
    /**
     * Unire gli scatti appena la panoramica finisce, senza doverlo chiedere.
     *
     * Acceso di suo: chi scatta una panoramica la vuole unita, e le foto separate restano
     * comunque sulla camera. L'unione avviene sul telefono e non tocca niente sulla scheda.
     */
    val autoStitchPanorama: Boolean = true,
    /**
     * Scatto sferico: tutta la corsa che il gimbal ha, con sovrapposizione fissa al 20%.
     *
     * Non è una copertura fra le altre: i gradi non li sceglie chi scatta, li detta la corsa
     * misurata dalla calibrazione. E la sovrapposizione non si tocca — al 20% i fotogrammi si
     * accavallano abbastanza da unirsi bene anche ai poli, dove i meridiani si stringono e due
     * scatti affiancati si sovrappongono molto meno di quanto dicano i gradi.
     */
    val panoramaSpherical: Boolean = false,
    /**
     * Il ritmo veloce: usare il buffer della camera invece di aspettarla a ogni scatto.
     *
     * La camera tiene in coda fino a quattro scatti mentre scrive — misurato da chi la usa —
     * quindi fermarsi ad aspettare la scrittura dopo ogni foto è pagare cinque secondi sedici
     * volte quando si possono pagare una volta ogni tre. Acceso: si scatta, si protegge la posa
     * (che è l'unico momento in cui il gimbal deve stare fermo), ci si sposta mentre la camera
     * scrive, e la si aspetta solo a buffer pieno. Spento: uno scatto alla volta, con l'attesa
     * completa fra uno e l'altro.
     *
     * Il fallimento «sedici scatti, zero file» che aveva fatto spegnere tutto questo aveva
     * un'altra causa, trovata e corretta: l'anteprima spenta durante la sequenza, che impedisce
     * alla camera di chiudere qualunque cattura. Se gli scatti venissero mossi o la camera si
     * incantasse, questo resta l'interruttore da spegnere.
     */
    val moveWhileSaving: Boolean = true,
    val panoramaAspect: PhotoFrameAspect = PhotoFrameAspect.FOUR_THREE,
) {
    val legCount: Int get() = (waypoints.size - 1).coerceAtLeast(0)

    val isRunnable: Boolean get() = waypoints.size >= 2

    val hasLegacyWaypoints: Boolean get() = waypoints.any(Waypoint::needsRecapture)

    val hasUnverifiedManualWaypoints: Boolean
        get() = waypoints.any { !it.generatedByPanoramaPlanner && it.previewJpegBase64 == null }

    /** Durata effettiva di ogni tratto, coerente con la modalità scelta. */
    fun legDurations(): List<Float> {
        if (legCount == 0) return emptyList()
        return if (useTotalDuration) {
            val each = (totalDurationSeconds / legCount).coerceAtLeast(MIN_LEG_SECONDS)
            List(legCount) { each }
        } else {
            waypoints.dropLast(1).map { it.durationToNextSeconds.coerceAtLeast(MIN_LEG_SECONDS) }
        }
    }

    fun effectiveTotalSeconds(): Float = legDurations().sum()

    /** Durata della ripresa continua: pause sui bordi più il movimento impostato. */
    fun estimatedRecordingSeconds(): Float =
        effectiveTotalSeconds() + startHoldSeconds.coerceAtLeast(0f) + endHoldSeconds.coerceAtLeast(0f)

    /** Scatti effettivi per tratto: almeno due, altrimenti non è un percorso. */
    fun effectiveShotsPerLeg(): Int = shotsPerLeg.coerceAtLeast(2)

    /**
     * Numero totale di scatti in modalità foto. Il punto di arrivo di un tratto coincide con la
     * partenza del successivo, quindi si conta una volta sola.
     */
    fun totalShots(): Int {
        if (legCount == 0) return 0
        return legCount * (effectiveShotsPerLeg() - 1) + 1
    }

    /**
     * Quanto dura davvero la sequenza in modalità foto: al movimento va aggiunto il tempo di
     * assestamento e di scatto, che su una panoramica lunga cambia il totale in modo netto.
     */
    fun estimatedPhotoSeconds(): Float =
        effectiveTotalSeconds() + totalShots() * (settleSeconds + ESTIMATED_SHOT_SECONDS)

    /** Numero di scatti previsti dal timelapse interno della camera, informativo. */
    fun estimatedShots(): Int {
        val interval = intervalSeconds.coerceAtLeast(0.1f)
        return (effectiveTotalSeconds() / interval).toInt().coerceAtLeast(0)
    }

    companion object {
        const val MIN_LEG_SECONDS = 1f

        /** Stima prudente del tempo di uno scatto, dal comando al salvataggio. */
        const val ESTIMATED_SHOT_SECONDS = 1.5f
    }
}
