package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import it.persoft.lunaultra.camera.CameraMode
import it.persoft.lunaultra.timelapse.ShootingMode
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons

/**
 * La ghiera delle modalità.
 *
 * Sette voci, due famiglie. Le prime quattro sono le modalità della camera: selezionarle la
 * mette davvero in quella modalità, perché il comando di scatto da solo non dice cosa
 * scattare — se la camera è rimasta in panoramica, «foto» fa una panoramica. Le ultime tre
 * sono le modalità guidate: percorrono i punti memorizzati muovendo il gimbal, e hanno bisogno
 * di almeno due punti.
 */
enum class CaptureMode(
    val label: String,
    val shortLabel: String,
    val icon: ImageVector,
    /** Colore della modalità: ghiera, anello dello scatto, distintivo. */
    val color: Color,
    val hint: String,
    /** In che modalità va messa la camera per questa voce. */
    val cameraMode: CameraMode,
    /** Non nullo = la modalità percorre i punti memorizzati con questo comportamento. */
    val sequenceMode: ShootingMode?,
    /** Usa il timelapse interno della camera invece della registrazione normale. */
    val cameraTimelapse: Boolean = false,
) {
    PANORAMA(
        label = "Panorama",
        shortLabel = "PANO",
        icon = LunaIcons.Panorama,
        color = Luna.Pano,
        hint = "La panoramica della camera: sferica a 360° oppure 2:1, si sceglie qui sotto.",
        cameraMode = CameraMode.PANORAMA,
        sequenceMode = null,
    ),
    FOTO(
        label = "Foto",
        shortLabel = "FOTO",
        icon = LunaIcons.Photo,
        color = Luna.Photo,
        hint = "Scatto singolo normale.",
        cameraMode = CameraMode.FOTO,
        sequenceMode = null,
    ),
    VIDEO(
        label = "Video",
        shortLabel = "VIDEO",
        icon = LunaIcons.Video,
        color = Luna.Movie,
        hint = "Registrazione normale: parte e si ferma con lo stesso tasto.",
        cameraMode = CameraMode.VIDEO,
        sequenceMode = null,
    ),
    PURE_VIDEO(
        label = "PureVideo",
        shortLabel = "PURE",
        icon = LunaIcons.Video,
        color = Luna.Movie,
        hint = "Ripresa ottimizzata per poca luce, in modalità Standard.",
        cameraMode = CameraMode.PURE_VIDEO,
        sequenceMode = null,
    ),
    SLOW_MOTION(
        label = "Slow-motion",
        shortLabel = "SLOW",
        icon = LunaIcons.Video,
        color = Luna.Movie,
        hint = "Alta velocità: 4K/2,7K fino a 120 fps e 1080p fino a 240 fps.",
        cameraMode = CameraMode.SLOW_MOTION,
        sequenceMode = null,
    ),
    TIMELAPSE(
        label = "Timelapse",
        shortLabel = "TL",
        icon = LunaIcons.Timelapse,
        color = Luna.Lapse,
        hint = "Timelapse interno della camera, a gimbal fermo.",
        cameraMode = CameraMode.TIMELAPSE,
        sequenceMode = null,
        cameraTimelapse = true,
    ),
    SEQUENZA_VIDEO(
        label = "Sequenza video",
        shortLabel = "SEQ",
        icon = LunaIcons.MotionVideo,
        color = Luna.Path,
        hint = "Registra mentre il gimbal percorre i punti memorizzati.",
        cameraMode = CameraMode.VIDEO,
        sequenceMode = ShootingMode.VIDEO,
    ),
    SEQUENZA_TL(
        label = "Sequenza timelapse",
        shortLabel = "SEQ TL",
        icon = LunaIcons.MotionTimelapse,
        color = Luna.PathLapse,
        hint = "Timelapse della camera mentre il gimbal percorre i punti.",
        cameraMode = CameraMode.TIMELAPSE,
        sequenceMode = ShootingMode.TIMELAPSE_CAMERA,
        cameraTimelapse = true,
    ),
    SEQUENZA_FOTO(
        label = "Sequenza foto",
        shortLabel = "SEQ FOTO",
        icon = LunaIcons.Waypoint,
        color = Luna.Multi,
        hint = "Si ferma a ogni scatto lungo il percorso: foto singole da unire in post.",
        cameraMode = CameraMode.FOTO,
        sequenceMode = ShootingMode.FOTO,
    ),
    ;

    /** Le modalità guidate hanno bisogno dei punti: senza, lo scatto spiega perché non parte. */
    val usesSequence: Boolean get() = sequenceMode != null

    /** La panoramica della camera è l'unica con la scelta fra sferica e 2:1. */
    val hasPanoAspect: Boolean get() = cameraMode.hasPanoAspect && !usesSequence

    companion object {
        /** La voce della ghiera che corrisponde a una scelta fatta nel pannello della sequenza. */
        fun forSequence(mode: ShootingMode): CaptureMode = when (mode) {
            ShootingMode.VIDEO -> SEQUENZA_VIDEO
            ShootingMode.TIMELAPSE_CAMERA -> SEQUENZA_TL
            ShootingMode.FOTO -> SEQUENZA_FOTO
        }

        /** La voce che corrisponde alla modalità in cui si trova la camera. */
        fun forCamera(mode: CameraMode): CaptureMode = when (mode) {
            CameraMode.FOTO -> FOTO
            CameraMode.PANORAMA -> PANORAMA
            CameraMode.VIDEO -> VIDEO
            CameraMode.PURE_VIDEO -> PURE_VIDEO
            CameraMode.SLOW_MOTION -> SLOW_MOTION
            CameraMode.TIMELAPSE -> TIMELAPSE
        }
    }
}
