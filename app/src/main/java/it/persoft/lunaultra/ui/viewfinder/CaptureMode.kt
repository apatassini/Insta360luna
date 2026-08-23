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
    /** Non nullo = la modalità percorre *sempre* i punti memorizzati, e senza non parte. */
    val sequenceMode: ShootingMode?,
    /**
     * Come si comporta questa modalità quando ci sono dei punti memorizzati.
     *
     * I punti non sono una modalità a parte: sono un percorso, e un percorso vale per qualunque
     * cosa si stia riprendendo. Se ci sono due punti e si preme registra, il gimbal deve andare
     * dal primo al secondo mentre registra — non restare fermo perché la voce scelta si chiama
     * «Video» invece di «Sequenza video». Nullo solo dove muovere il gimbal rovinerebbe la
     * ripresa: la panoramica interna della camera la fa la camera, ruotandosi da sé.
     */
    val pathMode: ShootingMode? = null,
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
        // La panoramica interna ruota il gimbal per conto suo: un percorso in più la rovina.
        pathMode = null,
    ),
    FOTO(
        label = "Foto",
        shortLabel = "FOTO",
        icon = LunaIcons.Photo,
        color = Luna.Photo,
        hint = "Scatto singolo normale. Con dei punti memorizzati li percorre scattando a ognuno.",
        cameraMode = CameraMode.FOTO,
        sequenceMode = null,
        pathMode = ShootingMode.FOTO,
    ),
    VIDEO(
        label = "Video",
        shortLabel = "VIDEO",
        icon = LunaIcons.Video,
        color = Luna.Movie,
        hint = "Registrazione normale. Con dei punti memorizzati registra percorrendoli.",
        cameraMode = CameraMode.VIDEO,
        sequenceMode = null,
        pathMode = ShootingMode.VIDEO,
    ),
    PURE_VIDEO(
        label = "PureVideo",
        shortLabel = "PURE",
        icon = LunaIcons.Video,
        color = Luna.Movie,
        hint = "Ripresa ottimizzata per poca luce. Con dei punti memorizzati li percorre.",
        cameraMode = CameraMode.PURE_VIDEO,
        sequenceMode = null,
        pathMode = ShootingMode.VIDEO,
    ),
    SLOW_MOTION(
        label = "Slow-motion",
        shortLabel = "SLOW",
        icon = LunaIcons.Video,
        color = Luna.Movie,
        hint = "Alta velocità: 4K/2,7K fino a 120 fps e 1080p fino a 240 fps. Segue i punti.",
        cameraMode = CameraMode.SLOW_MOTION,
        sequenceMode = null,
        pathMode = ShootingMode.VIDEO,
    ),
    TIMELAPSE(
        label = "Timelapse",
        shortLabel = "TL",
        icon = LunaIcons.Timelapse,
        color = Luna.Lapse,
        hint = "Timelapse interno della camera. Con dei punti memorizzati li percorre mentre gira.",
        cameraMode = CameraMode.TIMELAPSE,
        sequenceMode = null,
        pathMode = ShootingMode.TIMELAPSE_CAMERA,
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

    /**
     * Come va percorso il percorso, se ci sono dei punti. Nullo = questa modalità non li segue.
     *
     * Le voci «Sequenza …» restano perché dichiarano l'intenzione — con quelle senza punti lo
     * scatto spiega cosa manca invece di riprendere da fermo — ma non sono più l'unico modo di
     * usare un percorso.
     */
    val pathBehaviour: ShootingMode? get() = sequenceMode ?: pathMode

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
