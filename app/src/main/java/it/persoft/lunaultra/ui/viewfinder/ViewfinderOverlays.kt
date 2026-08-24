package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.timelapse.RunState
import it.persoft.lunaultra.ui.UpdateUiState
import it.persoft.lunaultra.ui.buildDateLabel
import it.persoft.lunaultra.stitch.StitchUiState
import it.persoft.lunaultra.ui.components.GlassPanel
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.italianLabel
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlin.math.roundToInt

/**
 * L'esito dell'unione delle foto, sul mirino e non solo nel log.
 *
 * L'unione è la cosa che può andare storta *dopo* che gli scatti sono riusciti, e quando va
 * storta il motivo è sempre concreto — la camera ha salvato meno foto di quante ne sono state
 * chieste, un file non si è scaricato. Finché quel motivo stava solo nel log, chi scattava
 * vedeva la sequenza finire bene e non sapeva che la panoramica non c'era.
 *
 * Mentre lavora mostra a che punto è, perché scaricare ventiquattro foto dal Wi-Fi della camera
 * e rimetterle sulla sfera sono minuti, non secondi. Quando ha finito si può chiudere; quando
 * fallisce resta, perché un errore che sparisce da solo è un errore che non è stato letto.
 */
@Composable
fun StitchCard(
    state: StitchUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is StitchUiState.Idle) return
    val accent = when (state) {
        is StitchUiState.Failed -> Luna.Rec
        is StitchUiState.Done, is StitchUiState.Queued -> Luna.Ok
        else -> Luna.Multi
    }
    GlassPanel(
        modifier = modifier.width(300.dp),
        background = Luna.Surface,
        contentPadding = 12.dp,
        verticalSpacing = 6.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = when (state) {
                    is StitchUiState.Failed -> LunaIcons.Warning
                    is StitchUiState.Done -> LunaIcons.Check
                    is StitchUiState.Queued -> LunaIcons.Jobs
                    else -> LunaIcons.Panorama
                },
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = when (state) {
                    is StitchUiState.Failed -> "PANORAMICA NON UNITA"
                    is StitchUiState.Done -> "PANORAMICA UNITA"
                    is StitchUiState.Queued -> "PANORAMICA IN CODA"
                    else -> "UNISCO LE FOTO"
                },
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                modifier = Modifier.weight(1f),
            )
            if (state !is StitchUiState.Working) {
                Icon(
                    imageVector = LunaIcons.Close,
                    contentDescription = "Chiudi",
                    tint = Luna.OnSurfaceDim,
                    modifier = Modifier.size(18.dp).clickable(onClick = onDismiss),
                )
            }
        }
        when (state) {
            is StitchUiState.Working -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }

            is StitchUiState.Done -> Text(
                text = "${state.fileName} · ${state.report.canvasWidth}×${state.report.canvasHeight} px " +
                    "· in DCIM › Luna Ultra",
                style = MaterialTheme.typography.bodySmall,
                color = Luna.OnSurfaceDim,
            )

            is StitchUiState.Queued -> Text(
                text = "${state.count} scatti al sicuro sul telefono. Unisci quando vuoi, " +
                    "dalla scheda dei lavori in basso a destra.",
                style = MaterialTheme.typography.bodySmall,
                color = Luna.OnSurfaceDim,
            )

            is StitchUiState.Failed -> {
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
                Text(
                    text = "Gli scatti sono comunque sulla camera.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Luna.OnSurfaceDim,
                )
            }

            is StitchUiState.Idle -> Unit
        }
    }
}

/**
 * Avanzamento della sequenza in corso, sopra l'anteprima.
 *
 * Resta visibile anche quando i comandi sono nascosti: mentre la sequenza gira, l'unica cosa
 * che serve sempre a portata di dito è fermarla.
 */
@Composable
fun RunCard(
    run: RunState,
    mode: CaptureMode,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    saving: Boolean = false,
) {
    GlassPanel(modifier = modifier.width(300.dp), contentPadding = 12.dp, verticalSpacing = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(mode.icon, contentDescription = null, tint = Luna.Accent, modifier = Modifier.size(18.dp))
            Text(
                text = run.phase.italianLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(run.overallProgress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = Luna.Accent,
            )
        }

        LinearProgressIndicator(
            progress = { run.overallProgress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )

        Text(
            text = buildString {
                append("Tratto ${run.legIndex + 1}/${run.legCount.coerceAtLeast(1)}")
                if (run.shotsPlanned > 0) append("  ·  scatto ${run.shotsTaken}/${run.shotsPlanned}")
                append("  ·  target %.1f° / %.1f°".format(run.targetPan, run.targetTilt))
            },
            style = MaterialTheme.typography.labelSmall,
            color = Luna.OnSurfaceDim,
        )

        // Quanto manca e cosa non è riuscito: le due cose che si vogliono sapere guardando
        // la camera lavorare, senza dover andare a leggere il log.
        run.secondsRemaining?.takeIf { it > 0f }?.let { remaining ->
            Text(
                text = buildString {
                    append("Mancano ${formatPathSeconds(remaining)}")
                    if (run.shotsMissed == 1) append("  ·  1 scatto perso")
                    if (run.shotsMissed > 1) append("  ·  ${run.shotsMissed} scatti persi")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (run.shotsMissed > 0) Luna.Warn else Luna.OnSurfaceDim,
            )
        }

        // «Sta salvando» lo dice la camera, non lo deduce l'app: la notifica 8202 racconta
        // otturatore, compressione e scrittura sulla scheda. È la risposta alla domanda che
        // viene guardando una sequenza che sembra ferma — non è ferma, sta scrivendo.
        val note = if (saving) "La camera sta salvando lo scatto" else run.message
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = if (saving) Luna.Accent else Luna.OnSurfaceDim,
            )
        }

        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(
                containerColor = Luna.Rec,
                contentColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(LunaIcons.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = "  STOP")
        }
    }
}

/**
 * Invito a connettersi, al centro dell'anteprima nera.
 *
 * È una pastiglia e non un riquadro: senza camera non c'è niente da inquadrare, ma coprire
 * mezzo schermo — e i comandi del gimbal che ci stanno sotto — per dire una cosa sola è troppo.
 * L'indirizzo sta sotto in piccolo, perché serve solo quando qualcosa non va.
 */
@Composable
fun ConnectCta(
    connection: ConnectionState,
    searchingWifi: Boolean,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = searchingWifi ||
        connection == ConnectionState.CONNECTING ||
        connection == ConnectionState.HANDSHAKE
    val failed = connection == ConnectionState.ERROR
    val shape = RoundedCornerShape(26.dp)

    Row(
        modifier = modifier
            .background(Luna.Glass, shape)
            .border(1.dp, if (failed) Luna.Warn.copy(alpha = 0.6f) else Luna.GlassBorder, shape)
            .clickable(enabled = !busy, onClick = onConnect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Luna.Accent,
            )
        } else {
            Icon(
                imageVector = if (failed) LunaIcons.Warning else LunaIcons.Connected,
                contentDescription = null,
                tint = if (failed) Luna.Warn else Luna.Accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = when {
                searchingWifi -> "Ricerca Luna Ultra…"
                connection == ConnectionState.CONNECTING ||
                    connection == ConnectionState.HANDSHAKE -> "Connessione…"
                failed -> "Riprova a connetterti"
                else -> "Connetti alla camera"
            },
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

/**
 * Perché l'anteprima è nera.
 *
 * Quando la camera è connessa ma non arriva un fotogramma, la differenza fra «spenta»,
 * «in avvio» e «rifiutata dalla camera» è tutta l'informazione che c'è, e va scritta.
 */
@Composable
fun PreviewStatusNote(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Luna.GlassSoft, MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * L'elenco completo delle modalità, aperto dalla ghiera.
 *
 * La ghiera in fondo mostra i nomi e basta, che è quello che serve mentre si riprende; quando
 * invece si sta ancora decidendo, qui c'è anche cosa fa ognuna e se è utilizzabile — una
 * modalità guidata senza punti memorizzati non parte, e conviene saperlo prima di sceglierla.
 */
/**
 * Lo stato dell'aggiornamento, sopra l'inquadratura.
 *
 * Sta al centro perché è l'unica cosa che conta finché dura, e sparisce da solo quando non c'è
 * più niente da dire. Lo scaricamento mostra la percentuale se il server ha dichiarato la
 * dimensione, altrimenti i megabyte arrivati: una percentuale su un totale ignoto sarebbe
 * inventata, e una barra che si muove a caso è peggio di nessuna barra.
 */
@Composable
fun UpdateNotice(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is UpdateUiState.Idle) return
    GlassPanel(modifier = modifier.width(300.dp), contentPadding = 14.dp, verticalSpacing = 8.dp) {
        Text(
            text = when (state) {
                is UpdateUiState.Checking -> "CONTROLLO AGGIORNAMENTI"
                is UpdateUiState.Downloading -> "SCARICAMENTO IN CORSO"
                is UpdateUiState.ReadyToInstall -> "AGGIORNAMENTO PRONTO"
                is UpdateUiState.UpToDate -> "APP AGGIORNATA"
                is UpdateUiState.Failed -> "AGGIORNAMENTO NON VERIFICABILE"
                UpdateUiState.Idle -> ""
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (state is UpdateUiState.Failed) Luna.Warn else Luna.Ok,
        )
        when (state) {
            is UpdateUiState.Checking -> {
                Text(
                    "Cerco la release di ${state.branch}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            is UpdateUiState.Downloading -> {
                val fraction = state.fraction
                Text(
                    text = if (fraction != null) {
                        "%d%% · %.1f di %.1f MB".format(
                            state.percent,
                            state.downloaded / 1_048_576f,
                            state.total / 1_048_576f,
                        )
                    } else {
                        "%.1f MB scaricati".format(state.downloaded / 1_048_576f)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            is UpdateUiState.ReadyToInstall -> Text(
                buildString {
                    val date = state.publishedAtMs?.let { buildDateLabel(it) }
                    append(if (date != null) "Build del $date." else "Build nuova pronta.")
                    append(" Android chiede conferma per installare.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )

            is UpdateUiState.UpToDate -> Text(
                "Sei sull'ultima build di ${state.branch}.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )

            is UpdateUiState.Failed -> Text(
                "${state.reason}. La camera si usa lo stesso: l'aggiornamento non blocca niente.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )

            UpdateUiState.Idle -> Unit
        }
        if (state is UpdateUiState.UpToDate || state is UpdateUiState.Failed ||
            state is UpdateUiState.ReadyToInstall
        ) {
            Text(
                text = "Chiudi",
                style = MaterialTheme.typography.labelMedium,
                color = Luna.OnSurfaceDim,
                modifier = Modifier.clickable(onClick = onDismiss),
            )
        }
    }
}

@Composable
fun ModeSheet(
    selected: CaptureMode,
    sequenceReady: Boolean,
    onSelect: (CaptureMode) -> Unit,
    onOpenPanorama: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Pieno, non traslucido: qui si legge, e leggere una descrizione di tre righe sopra
    // un'anteprima che si muove è faticoso quanto basta a non farla leggere a nessuno. Il vetro
    // va bene per i comandi che stanno *sopra* l'inquadratura mentre la si guarda; un elenco da
    // scorrere la copre comunque, tanto vale che la copra bene.
    GlassPanel(
        modifier = modifier.width(330.dp),
        background = Luna.Surface,
        contentPadding = 12.dp,
        verticalSpacing = 6.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Modalità",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Icon(
                imageVector = LunaIcons.Close,
                contentDescription = "Chiudi",
                tint = Luna.OnSurfaceDim,
                modifier = Modifier.size(20.dp).clickable(onClick = onDismiss),
            )
        }
        // La panoramica a più scatti sta qui, con le modalità foto, e non sepolta fra le
        // automazioni del gimbal: chi la cerca la cerca fra i modi di scattare, non fra i
        // percorsi. È l'unica voce che apre un pannello invece di cambiare modalità, perché
        // prima di scattare bisogna dirle quanti gradi coprire. Ma il via si dà dal mirino come
        // per ogni altra modalità: qui si sceglie, e l'ingranaggio apre le opzioni.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = if (selected == CaptureMode.PANORAMICA_APP) {
                        CaptureMode.PANORAMICA_APP.color.copy(alpha = 0.18f)
                    } else {
                        Luna.Multi.copy(alpha = 0.08f)
                    },
                    shape = RoundedCornerShape(14.dp),
                )
                .clickable {
                    onSelect(CaptureMode.PANORAMICA_APP)
                    onDismiss()
                }
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(34.dp).background(Luna.Multi.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = LunaIcons.Panorama,
                    contentDescription = null,
                    tint = Luna.Multi,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Panoramica a più scatti",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected == CaptureMode.PANORAMICA_APP) Luna.Multi else Color.White,
                )
                Text(
                    text = "Scegli qui le opzioni, poi premi scatto sul mirino: percorre la " +
                        "griglia e unisce le foto da sé.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Luna.OnSurfaceDim,
                )
            }
            if (selected == CaptureMode.PANORAMICA_APP) {
                Icon(
                    imageVector = LunaIcons.Check,
                    contentDescription = null,
                    tint = Luna.Multi,
                    modifier = Modifier.size(18.dp),
                )
            }
            HudIconButton(
                icon = LunaIcons.Tune,
                contentDescription = "Opzioni della panoramica",
                onClick = {
                    onSelect(CaptureMode.PANORAMICA_APP)
                    onOpenPanorama()
                    onDismiss()
                },
                size = 36.dp,
            )
        }

        CaptureMode.entries.filterNot { it.plansPanorama }.forEach { mode ->
            val usable = !mode.usesSequence || sequenceReady
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (mode == selected) mode.color.copy(alpha = 0.14f) else Color.Transparent,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable {
                        onSelect(mode)
                        onDismiss()
                    }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(mode.color.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = mode.icon,
                        contentDescription = null,
                        tint = mode.color,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (mode == selected) mode.color else Color.White,
                    )
                    Text(
                        text = if (usable) mode.hint else "Servono almeno due punti memorizzati",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (usable) Luna.OnSurfaceDim else Luna.Warn,
                    )
                }
                if (mode == selected) {
                    Icon(
                        imageVector = LunaIcons.Check,
                        contentDescription = null,
                        tint = mode.color,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
