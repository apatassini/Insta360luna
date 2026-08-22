package it.persoft.lunaultra.timelapse

import kotlinx.serialization.Serializable
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
) {
    val needsRecapture: Boolean get() = positionModelVersion < CURRENT_POSITION_MODEL_VERSION

    companion object {
        const val LEGACY_POSITION_MODEL_VERSION = 1
        const val CURRENT_POSITION_MODEL_VERSION = 2
    }
}

@Serializable
data class TimelapseSequence(
    val waypoints: List<Waypoint> = emptyList(),
    val mode: ShootingMode = ShootingMode.VIDEO,
    val intervalSeconds: Float = 2f,
    val totalDurationSeconds: Float = 60f,
    val interpolation: InterpolationMode = InterpolationMode.SMOOTH,
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
) {
    val legCount: Int get() = (waypoints.size - 1).coerceAtLeast(0)

    val isRunnable: Boolean get() = waypoints.size >= 2

    val hasLegacyWaypoints: Boolean get() = waypoints.any(Waypoint::needsRecapture)

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
