package it.persoft.lunaultra.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.screens.DiagnosticsScreen
import it.persoft.lunaultra.ui.screens.GalleryScreen
import it.persoft.lunaultra.ui.screens.PanoramaScreen
import it.persoft.lunaultra.ui.screens.SequenceScreen
import it.persoft.lunaultra.ui.screens.SettingsScreen
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import it.persoft.lunaultra.ui.viewfinder.ViewfinderScreen

/**
 * L'app: il mirino sempre presente, e i pannelli che ci scorrono sopra quando servono.
 *
 * I messaggi compaiono sotto la riga di stato e non in fondo, dove ci sono il pulsante di scatto
 * e la ghiera: una notifica che copre lo scatto arriva sempre nel momento sbagliato.
 */
@Composable
fun LunaApp(viewModel: MainViewModel) {
    // L'ordinale invece del valore: gli enum non sono salvabili così com'è fra le rotazioni.
    var panelOrdinal by rememberSaveable { mutableIntStateOf(Panel.NONE.ordinal) }
    val panel = Panel.entries[panelOrdinal]
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    BackHandler(enabled = panel != Panel.NONE) { panelOrdinal = Panel.NONE.ordinal }

    // Uscendo dalla galleria si chiude anche il file aperto a schermo intero: riaprirla e
    // ritrovarsi dentro una foto di prima non è quello che si è chiesto.
    LaunchedEffect(panel) { if (panel != Panel.GALLERY) viewModel.closeViewer() }

    Box(modifier = Modifier.fillMaxSize()) {
        ViewfinderScreen(
            viewModel = viewModel,
            onOpenPanel = { panelOrdinal = it.ordinal },
        )

        AnimatedVisibility(
            visible = panel != Panel.NONE,
            enter = slideInVertically(initialOffsetY = { it / 6 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 6 }) + fadeOut(),
        ) {
            PanelHost(panel = panel, onClose = { panelOrdinal = Panel.NONE.ordinal }) {
                when (panel) {
                    Panel.GALLERY -> GalleryScreen(viewModel, onClose = { panelOrdinal = Panel.NONE.ordinal })
                    Panel.SEQUENCE -> SequenceScreen(viewModel)
                    Panel.PANORAMA -> PanoramaScreen(viewModel)
                    Panel.SETTINGS -> SettingsScreen(viewModel, onOpenDiagnostics = {
                        panelOrdinal = Panel.DIAGNOSTICS.ordinal
                    })
                    Panel.DIAGNOSTICS -> DiagnosticsScreen(viewModel)
                    Panel.NONE -> Unit
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 88.dp, start = 12.dp, end = 12.dp),
        )
    }
}

@Composable
private fun PanelHost(
    panel: Panel,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Luna.Bg) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            // La galleria si disegna l'intestazione da sola, tutta su una riga: qui una riga
            // col solo pulsante indietro le rubava un centimetro di schermo.
            if (panel == Panel.GALLERY) {
                content()
                return@Column
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HudIconButton(
                    icon = LunaIcons.Back,
                    contentDescription = "Torna al mirino",
                    onClick = onClose,
                    size = 42.dp,
                )
                run {
                    // L'icona dentro il suo cerchio colorato, come nelle sezioni: aprendo un
                    // pannello si riconosce dove si è dal colore prima ancora di leggere.
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(panel.accent.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = panel.icon,
                            contentDescription = null,
                            tint = panel.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(text = panel.title, style = MaterialTheme.typography.titleLarge)
                        if (panel.subtitle.isNotEmpty()) {
                            Text(
                                text = panel.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Luna.OnSurfaceDim,
                            )
                        }
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) { content() }
        }
    }
}
