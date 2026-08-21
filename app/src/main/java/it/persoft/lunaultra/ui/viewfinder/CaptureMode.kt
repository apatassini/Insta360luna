package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.ui.graphics.vector.ImageVector
import it.persoft.lunaultra.timelapse.ShootingMode
import it.persoft.lunaultra.ui.theme.LunaIcons

/**
 * La ghiera delle modalità, come su una camera.
 *
 * Sei voci, due famiglie. Le prime tre comandano la camera e basta — uno scatto, una
 * registrazione, il timelapse interno — e funzionano anche senza aver memorizzato nulla. Le
 * altre tre sono le modalità guidate: percorrono i punti memorizzati muovendo il gimbal, e sono
 * il motivo per cui questa app esiste. La differenza si vede subito, perché una modalità guidata
 * senza almeno due punti resta spenta.
 */
enum class CaptureMode(
    val label: String,
    val shortLabel: String,
    val icon: ImageVector,
    val hint: String,
    /** Non nullo = la modalità percorre i punti memorizzati con questo comportamento. */
    val sequenceMode: ShootingMode?,
    /** Usa il timelapse interno della camera invece della registrazione normale. */
    val cameraTimelapse: Boolean = false,
) {
    FOTO(
        label = "Foto",
        shortLabel = "FOTO",
        icon = LunaIcons.Photo,
        hint = "Uno scatto singolo, per provare l'inquadratura.",
        sequenceMode = null,
    ),
    VIDEO(
        label = "Video",
        shortLabel = "VIDEO",
        icon = LunaIcons.Video,
        hint = "Registrazione normale: parte e si ferma con lo stesso tasto.",
        sequenceMode = null,
    ),
    TIMELAPSE(
        label = "Timelapse",
        shortLabel = "TL",
        icon = LunaIcons.Timelapse,
        hint = "Timelapse interno della camera, a gimbal fermo.",
        sequenceMode = null,
        cameraTimelapse = true,
    ),
    SEQUENZA_VIDEO(
        label = "Sequenza video",
        shortLabel = "SEQ",
        icon = LunaIcons.MotionVideo,
        hint = "Registra mentre il gimbal percorre i punti memorizzati.",
        sequenceMode = ShootingMode.VIDEO,
    ),
    SEQUENZA_TL(
        label = "Sequenza timelapse",
        shortLabel = "SEQ TL",
        icon = LunaIcons.MotionTimelapse,
        hint = "Timelapse della camera mentre il gimbal percorre i punti.",
        sequenceMode = ShootingMode.TIMELAPSE_CAMERA,
        cameraTimelapse = true,
    ),
    PANORAMA(
        label = "Panorama",
        shortLabel = "PANO",
        icon = LunaIcons.Panorama,
        hint = "Si ferma a ogni scatto lungo il percorso: foto da unire in post.",
        sequenceMode = ShootingMode.FOTO,
    ),
    ;

    /** Le modalità guidate hanno bisogno dei punti: senza, il pulsante di scatto resta spento. */
    val usesSequence: Boolean get() = sequenceMode != null

    /** Chi registra un flusso continuo mostra il cronometro; chi scatta no. */
    val isContinuous: Boolean get() = this != FOTO && sequenceMode != ShootingMode.FOTO

    companion object {
        /** La modalità che corrisponde a una scelta fatta nel pannello della sequenza. */
        fun forSequence(mode: ShootingMode): CaptureMode = when (mode) {
            ShootingMode.VIDEO -> SEQUENZA_VIDEO
            ShootingMode.TIMELAPSE_CAMERA -> SEQUENZA_TL
            ShootingMode.FOTO -> PANORAMA
        }
    }
}
