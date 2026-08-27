package it.persoft.lunaultra.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Le icone usate dall'app, in un posto solo.
 *
 * Raccoglierle qui serve a due cose: dare un nome nostro a ogni funzione (così un cambio di
 * icona è una riga, non una caccia in venti file) e tenere sotto controllo quali simboli del
 * set Material vengono davvero portati dentro.
 */
object LunaIcons {
    // modalità di scatto
    val Photo: ImageVector = Icons.Filled.PhotoCamera
    val Video: ImageVector = Icons.Filled.Videocam
    val VideoOff: ImageVector = Icons.Filled.VideocamOff
    val Timelapse: ImageVector = Icons.Filled.Timelapse
    val MotionTimelapse: ImageVector = Icons.Filled.Timer
    val MotionVideo: ImageVector = Icons.Filled.Route
    val Panorama: ImageVector = Icons.Filled.Panorama

    /** I lavori in attesa: le panoramiche scattate e non ancora unite. */
    val Jobs: ImageVector = Icons.Filled.PendingActions

    // stato camera
    val Battery: ImageVector = Icons.Filled.BatteryFull
    val Storage: ImageVector = Icons.Filled.SdStorage
    val Connected: ImageVector = Icons.Filled.Wifi
    val Disconnected: ImageVector = Icons.Filled.LinkOff
    val Warning: ImageVector = Icons.Filled.Warning
    val Info: ImageVector = Icons.Filled.Info

    // comandi
    val Play: ImageVector = Icons.Filled.PlayArrow
    val Stop: ImageVector = Icons.Filled.Stop
    val Refresh: ImageVector = Icons.Filled.Refresh
    val Close: ImageVector = Icons.Filled.Close
    val Check: ImageVector = Icons.Filled.Check
    val Add: ImageVector = Icons.Filled.Add
    val Delete: ImageVector = Icons.Filled.Delete
    val Back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack
    val More: ImageVector = Icons.Filled.MoreVert

    /** Il telefono: le foto che stanno gia` qui dentro, non quelle sulla camera. */
    val Phone: ImageVector = Icons.Filled.PhoneAndroid
    val Menu: ImageVector = Icons.Filled.Menu
    val Share: ImageVector = Icons.Filled.Share

    // galleria
    val Gallery: ImageVector = Icons.Filled.Collections
    val Download: ImageVector = Icons.Filled.Download

    /** Rimettere dentro un file scelto: il contrario di [Download]. */
    val Upload: ImageVector = Icons.Filled.FileUpload
    val Selected: ImageVector = Icons.Filled.CheckCircle
    val Unselected: ImageVector = Icons.Filled.RadioButtonUnchecked
    val SelectAll: ImageVector = Icons.Filled.SelectAll
    val PlayCircle: ImageVector = Icons.Filled.PlayCircleFilled
    val OpenExternal: ImageVector = Icons.Filled.OpenInNew
    val Star: ImageVector = Icons.Filled.Star
    val StarOutline: ImageVector = Icons.Filled.StarBorder

    // gimbal
    val Joystick: ImageVector = Icons.Filled.Gamepad
    val DPad: ImageVector = Icons.Filled.CenterFocusStrong
    val Up: ImageVector = Icons.Filled.KeyboardArrowUp
    val Down: ImageVector = Icons.Filled.KeyboardArrowDown
    val Left: ImageVector = Icons.Filled.KeyboardArrowLeft
    val Right: ImageVector = Icons.Filled.KeyboardArrowRight
    val Center: ImageVector = Icons.Filled.MyLocation

    /** Mezzo giro del pan: l'inquadratura passa dietro, come il selfie dell'app ufficiale. */
    val Selfie: ImageVector = Icons.Filled.FlipCameraAndroid
    val Speed: ImageVector = Icons.Filled.Speed
    val Axis: ImageVector = Icons.Filled.SwapVert
    val Level: ImageVector = Icons.Filled.Straighten

    // sequenza
    val Waypoint: ImageVector = Icons.Filled.AddLocation
    val Sequence: ImageVector = Icons.Filled.Route
    val Flag: ImageVector = Icons.Filled.Flag

    // pannelli
    val Settings: ImageVector = Icons.Filled.Settings
    val Tune: ImageVector = Icons.Filled.Tune
    val Diagnostics: ImageVector = Icons.Filled.BugReport

    /** La scheda grafica: i passi dell'unione che si possono spostare sulla GPU. */
    val Gpu: ImageVector = Icons.Filled.Memory

    // anteprima
    val Grid: ImageVector = Icons.Filled.GridOn
    val GridOff: ImageVector = Icons.Filled.GridOff
    val Fit: ImageVector = Icons.Filled.AspectRatio
    val Fill: ImageVector = Icons.Filled.Fullscreen
    val Show: ImageVector = Icons.Filled.Visibility
    val Hide: ImageVector = Icons.Filled.VisibilityOff
}
