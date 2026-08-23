package it.persoft.lunaultra.ui

import androidx.compose.ui.graphics.vector.ImageVector
import it.persoft.lunaultra.ui.theme.LunaIcons

/**
 * I pannelli che si aprono sopra il mirino.
 *
 * Non sono schede fra cui si naviga: il mirino è l'app, e questi coprono lo schermo solo finché
 * servono. È la differenza fra un'app di ripresa e un pannello di controllo con l'anteprima
 * dentro — la prima si usa guardando l'immagine.
 */
enum class Panel(val title: String, val icon: ImageVector) {
    NONE("", LunaIcons.Close),
    GALLERY("Galleria", LunaIcons.Gallery),
    SEQUENCE("Automazioni gimbal", LunaIcons.Sequence),
    PANORAMA("Panoramica a più scatti", LunaIcons.Panorama),
    SETTINGS("Impostazioni", LunaIcons.Tune),
    DIAGNOSTICS("Diagnostica", LunaIcons.Diagnostics),
}
