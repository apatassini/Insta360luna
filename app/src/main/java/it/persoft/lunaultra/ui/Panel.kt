package it.persoft.lunaultra.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons

/**
 * I pannelli che si aprono sopra il mirino.
 *
 * Non sono schede fra cui si naviga: il mirino è l'app, e questi coprono lo schermo solo finché
 * servono. È la differenza fra un'app di ripresa e un pannello di controllo con l'anteprima
 * dentro — la prima si usa guardando l'immagine.
 */
enum class Panel(
    val title: String,
    val icon: ImageVector,
    /** Una riga che dice a cosa serve il pannello: si legge una volta e poi non serve più. */
    val subtitle: String = "",
    /** Il colore del pannello, lo stesso che porta la sua modalità nel mirino. */
    val accent: Color = Luna.Accent,
) {
    NONE("", LunaIcons.Close),
    GALLERY("Galleria", LunaIcons.Gallery, "Foto e video sulla camera", Luna.Photo),
    SEQUENCE(
        "Automazioni gimbal",
        LunaIcons.Sequence,
        "Punti memorizzati, percorsi e tempi",
        Luna.Path,
    ),
    PANORAMA(
        "Panoramica",
        LunaIcons.Panorama,
        "Quanti gradi coprire, e con che obiettivo",
        Luna.Pano,
    ),
    SETTINGS("Impostazioni", LunaIcons.Tune, "Camera, gimbal, calibrazione, aggiornamenti", Luna.Accent),
    DIAGNOSTICS("Diagnostica", LunaIcons.Diagnostics, "Protocollo, log e prove sul campo", Luna.Amber),
}
