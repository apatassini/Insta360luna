package it.persoft.lunaultra.timelapse

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class InterpolationMode(val label: String) {
    LINEAR("Lineare"),
    SMOOTH("Smooth"),
    ;
}

@Serializable
data class Waypoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val pan: Float,
    val tilt: Float,
    /** Durata del tratto verso il waypoint successivo, in secondi. Ignorata per l'ultimo punto. */
    val durationToNextSeconds: Float = 30f,
)

@Serializable
data class TimelapseSequence(
    val waypoints: List<Waypoint> = emptyList(),
    val intervalSeconds: Float = 2f,
    val totalDurationSeconds: Float = 60f,
    val interpolation: InterpolationMode = InterpolationMode.SMOOTH,
    /** Se true le durate dei tratti derivano da [totalDurationSeconds] divisa equamente. */
    val useTotalDuration: Boolean = true,
    val controlRecording: Boolean = true,
    val setTimelapseMode: Boolean = true,
) {
    val legCount: Int get() = (waypoints.size - 1).coerceAtLeast(0)

    val isRunnable: Boolean get() = waypoints.size >= 2

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

    /** Numero di scatti previsti, informativo (dipende dall'intervallo impostato in camera). */
    fun estimatedShots(): Int {
        val interval = intervalSeconds.coerceAtLeast(0.1f)
        return (effectiveTotalSeconds() / interval).toInt().coerceAtLeast(0)
    }

    companion object {
        const val MIN_LEG_SECONDS = 1f
    }
}
