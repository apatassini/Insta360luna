package it.persoft.lunaultra.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.CodeProbe
import it.persoft.lunaultra.net.LogLevel
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.NumberField
import it.persoft.lunaultra.ui.components.SectionCard

/**
 * Diagnostica: è qui che si chiude l'unico buco rimasto del protocollo, il numero del comando
 * del gimbal. Il resto della schermata serve a guardare cosa passa sul canale di controllo.
 */
@Composable
fun DiagnosticsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val probe by viewModel.probe.collectAsState()
    val sightings by viewModel.sightings.collectAsState()
    val log by viewModel.logEntries.collectAsState()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        // In cima, non in fondo: il log serve a essere mandato, e cercare il pulsante in fondo
        // a una schermata lunga significa scorrere tutto ogni volta.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.shareLog(context) },
                modifier = Modifier.weight(1f),
            ) { Text("Condividi log") }
            OutlinedButton(
                onClick = viewModel::clearLog,
                modifier = Modifier.weight(1f),
            ) { Text("Pulisci log") }
        }

        SectionCard(title = "Connessione") {
            var host by remember(settings.host) { mutableStateOf(settings.host) }
            var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; viewModel.setHost(it) },
                label = { Text("Indirizzo camera") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField(
                label = "Porta",
                value = port,
                onValueChange = { text ->
                    port = text
                    text.toIntOrNull()?.let(viewModel::setPort)
                },
                modifier = Modifier.fillMaxWidth(),
                supportingText = "Il controllo della Luna Ultra è su TCP/6666",
            )
            Text(
                text = "La sessione si apre con un frame di handshake UCD2 e si mantiene ripetendolo " +
                    "ogni ${settings.keepAliveSeconds}s.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // La sequenza che porta al comando del gimbal, in ordine. Le prove sono numerate e già
        // compilate: non c'è niente da scrivere a mano.
        SectionCard(title = "Trovare il comando del gimbal") {
            Text(
                text = "Il numero del comando che muove il gimbal non è pubblico: i due progetti " +
                    "che esistono su questa camera sono fermi sullo stesso punto. Lo cerchiamo " +
                    "qui, in due prove. Falle nell'ordine 1 → 2.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "La posizione del gimbal non si legge chiedendola: la camera la spinge da " +
                    "sola quando il gimbal si muove (di solito sul codice 8302). Per questo la " +
                    "prova 1 è mettersi in ascolto, non interrogare.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ListenCard(viewModel, sightings)

        HuntCard(viewModel)

        // Strumenti manuali, sotto: servono solo se le due prove sopra non bastano.
        MonitorCard(viewModel)

        GimbalCodeCard(viewModel)

        ProbeCard(viewModel, probe)

        ShapeCard(viewModel)

        GimbalTuningCard(viewModel)

        SectionCard(title = "Invio manuale") {
            var code by remember { mutableStateOf("") }
            var payload by remember { mutableStateOf("") }
            NumberField(
                label = "Codice comando (decimale o 0x…)",
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = payload,
                onValueChange = { payload = it },
                label = { Text("Payload esadecimale (vuoto = nessuno)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.sendRaw(code, payload) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Invia") }
        }

        SectionCard(title = "Log") {
            Text(
                text = "Ogni comando inviato e ogni risposta ricevuta, con i byte grezzi e i campi " +
                    "protobuf decodificati. Per waypoint e sequenze include anche le miniature e " +
                    "il confronto dei punti di controllo. \"Condividi\" crea un unico HTML con " +
                    "le immagini incorporate: è il file da mandare per l'analisi.",
                style = MaterialTheme.typography.bodySmall,
            )
            // Il log arriva a migliaia di righe. Disegnarle tutte in una Column significa
            // ricomporre l'intero elenco a ogni riga nuova, e durante una scansione la UI si
            // pianta: la LazyColumn disegna solo ciò che si vede, e la finestra mostra le
            // ultime righe. L'esportazione resta completa.
            val visible = remember(log) { log.takeLast(LOG_WINDOW) }
            if (log.size > LOG_WINDOW) {
                Text(
                    text = "Mostrate le ultime $LOG_WINDOW righe di ${log.size}. " +
                        "L'esportazione le contiene tutte.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(visible.asReversed()) { entry ->
                    val color = when (entry.level) {
                        LogLevel.ERROR, LogLevel.WARN -> MaterialTheme.colorScheme.error
                        LogLevel.TX -> MaterialTheme.colorScheme.tertiary
                        LogLevel.RX -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Column {
                        Text(
                            text = "${entry.time}  ${entry.level}  ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = color,
                        )
                        entry.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = color.copy(alpha = 0.7f),
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                        entry.imageJpeg?.let { bytes ->
                            val bitmap = remember(bytes) {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            bitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Miniatura diagnostica: ${entry.message}",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .padding(start = 12.dp, top = 4.dp, bottom = 6.dp)
                                        .size(144.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GimbalCodeCard(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val gimbal = settings.gimbal

    SectionCard(title = "Protocollo gimbal") {
        Text(
            text = "In uso PHONE_COMMAND_GIMBAL_CONTROL 226 (0x00E2), con assi e payload " +
                "verificati sulle catture Luna Ultra pubblicate in Insta360Linker.",
            style = MaterialTheme.typography.bodyMedium,
        )
        LabeledValue("Tick movimento", "${gimbal.commandRateHz} Hz")
        LabeledValue("Stop", "4 vettori nulli ogni 25 ms")
        LabeledValue("Notifica PTZ", gimbal.ptzNotificationCode.toString())
        Text(
            text = "Il valore predefinito ${LunaProtocolCodes.NOTIFICATION_PTZ_STATE_OBSERVED} " +
                "viene da traffico osservato durante il movimento del gimbal, compatibile con " +
                "CAMERA_NOTIFICATION_PTZ_STATE. È un indizio forte, non una certezza.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ProbeCard(viewModel: MainViewModel, probe: it.persoft.lunaultra.ui.ProbeUiState) {
    SectionCard(title = "Scanner dei codici") {
        Text(
            text = "La camera distingue \"comando inesistente\" da \"argomenti sbagliati\". " +
                "Inviando un corpo vuoto, una risposta \"argomenti sbagliati\" dice che il comando " +
                "c'è e non ha eseguito nulla: è così che si trova un codice senza rischiare di " +
                "farlo partire. Comandi distruttivi e blocco di fabbrica sono esclusi a monte.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = viewModel::calibrateProbe,
            enabled = !probe.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("1. Misura le risposte note") }

        probe.calibration?.let { calibration ->
            LabeledValue("Codice inesistente", calibration.absent)
            LabeledValue("Payload spazzatura", calibration.badPayload)
            LabeledValue("Corpo vuoto su codice reale", calibration.emptyOnReal)
            Text(
                text = calibration.reason,
                style = MaterialTheme.typography.bodySmall,
                color = if (calibration.usable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        val usable = probe.calibration?.usable == true
        CodeProbe.Range.entries.forEach { range ->
            // Durante una scansione solo la gamma in corso può essere interrotta: le altre
            // restano disabilitate, altrimenti tre pulsanti "Interrompi" identici lasciano
            // credere che ci siano tre scansioni in corso.
            val isRunning = probe.running && probe.range == range
            OutlinedButton(
                onClick = { viewModel.scanRange(range) },
                enabled = isRunning || (usable && !probe.running),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isRunning) "Interrompi"
                    else "2. Scansiona ${range.label} (${range.from}–${range.to})"
                )
            }
            Text(text = range.note, style = MaterialTheme.typography.bodySmall)
        }

        if (probe.running && probe.total > 0) {
            LinearProgressIndicator(
                progress = { probe.done.toFloat() / probe.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${probe.done} / ${probe.total}", style = MaterialTheme.typography.bodySmall)
        }

        if (probe.hits.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = "${probe.hits.size} codici hanno risposto diversamente da uno inesistente. " +
                    "In cima quelli che rispondono con dati: esistono di sicuro.",
                style = MaterialTheme.typography.bodySmall,
            )
            probe.hits.sortedByDescending { it.rank }.forEach { hit ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${hit.code}${hit.name?.let { " · $it" } ?: ""}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(text = hit.reply.describe, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * Sonda la forma del messaggio di un codice trovato dalla scansione.
 *
 * È l'unico punto dell'app che manda comandi che la camera può eseguire davvero, e per questo
 * chiede un codice esplicito invece di girare da solo su una lista.
 */
/**
 * La caccia al comando del gimbal, in un pulsante.
 *
 * Fa quello che le altre due sonde facevano a pezzi: tiene fisso il campo che la camera
 * riconosce, prova i tipi che un angolo può avere (intero, float, double, coppia di numeri in
 * un sotto-messaggio) e dopo ogni tentativo rilegge i getter. Non serve guardare la camera:
 * se un getter cambia, quel corpo ha mosso qualcosa.
 */
/**
 * PROVA 1 — Ascolto delle notifiche spontanee (sicura, solo lettura).
 *
 * La posizione del gimbal non è un getter: la camera la spinge da sola mentre il gimbal si
 * muove. Si azzera, si muove il gimbal a mano, e si guarda quale codice sale — quasi certamente
 * l'8302. Serve anche a validare l'oracolo della prova 2: se sappiamo che l'8302 è il gimbal,
 * "è arrivata una notifica 8302" diventa una prova vera che qualcosa si è mosso.
 */
@Composable
private fun ListenCard(
    viewModel: MainViewModel,
    sightings: List<it.persoft.lunaultra.ui.NotificationSighting>,
) {
    SectionCard(
        title = "1 · Ascolta il gimbal",
        trailing = {
            OutlinedButton(onClick = viewModel::clearSightings) { Text("Azzera") }
        },
    ) {
        Text(
            text = "Premi Azzera, poi muovi il gimbal a mano (o dallo schermo della camera) per " +
                "una ventina di secondi: panoramica larga e su/giù. Guarda quale codice sale. " +
                "Solo lettura, non muove niente.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Ci aspettiamo l'8302, o un codice vicino. Quello con tanti payload diversi " +
                "porta numeri che cambiano — è la posizione; uno che ripete gli stessi byte è " +
                "solo un battito. Se trovi il codice giusto, premi \"È il PTZ\".",
            style = MaterialTheme.typography.bodySmall,
        )
        if (sightings.isEmpty()) {
            Text("Nessuna notifica ricevuta finora.", style = MaterialTheme.typography.bodyMedium)
        } else {
            sightings.forEach { sighting ->
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${sighting.code} · ${sighting.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (sighting.isNamed) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        Text(
                            text = "${sighting.count} volte · ${sighting.distinctPayloads} payload distinti",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!sighting.isNamed) {
                        OutlinedButton(
                            onClick = { viewModel.updateGimbal { it.copy(ptzNotificationCode = sighting.code) } },
                        ) { Text("È il PTZ") }
                    }
                }
                if (sighting.lastDump.isNotBlank()) {
                    Text(
                        text = sighting.lastDump,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/**
 * PROVA 2 — La caccia al comando (esegue: guarda la camera).
 *
 * Tiene fisso il campo 1 = 3 (l'unico valore che il 241 prova a eseguire), cerca il campo che
 * manca nei tipi che un angolo può avere, e dopo ogni tentativo guarda se è arrivata una
 * notifica. Un getter riletto non basta — la posizione non si interroga — ma una notifica
 * spontanea sì: se arriva subito dopo un corpo preciso, quel corpo ha mosso il gimbal.
 */
@Composable
private fun HuntCard(viewModel: MainViewModel) {
    val hunt by viewModel.hunt.collectAsState()
    var code by remember { mutableStateOf("241") }
    var selector by remember { mutableStateOf("3") }

    SectionCard(title = "2 · Caccia il comando") {
        Text(
            text = "Codice e campo sono già impostati sul lead migliore: 241 con campo 1 = 3, " +
                "l'unico valore che la camera prova a eseguire. Premi e basta.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Attenzione: i corpi accettati vengono ESEGUITI. Tieni la camera libera di " +
                "muoversi e guardala. Dopo ogni tentativo controllo se arriva una notifica: se " +
                "sì, quel corpo è il comando.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = "Codice",
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "Campo 1 =",
                value = selector,
                onValueChange = { selector = it },
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = { viewModel.huntGimbal(code, selector) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (hunt.running) "Interrompi" else "Caccia il comando") }

        if (hunt.total > 0) {
            Text(
                text = "${hunt.done} di ${hunt.total} corpi provati",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        hunt.interesting.forEach { step ->
            HorizontalDivider()
            Text(
                text = step.label + if (step.rejected) " → rifiutato" else " → ACCETTATO",
                style = MaterialTheme.typography.bodySmall,
                color = if (step.rejected) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            step.notifications.forEach { (code, times) ->
                Text(
                    text = "  notifica $code ×$times",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ShapeCard(viewModel: MainViewModel) {
    val results by viewModel.shape.collectAsState()
    val selector by viewModel.selector.collectAsState()
    val running by viewModel.shapeRunning.collectAsState()
    var code by remember { mutableStateOf("241") }
    var field by remember { mutableStateOf("1") }
    var maxValue by remember { mutableStateOf("63") }
    var prefix by remember { mutableStateOf("") }

    SectionCard(title = "Forma del messaggio") {
        Text(
            text = "Prova un campo alla volta su un codice che la scansione ha trovato. Un corpo " +
                "che smette di essere rifiutato ha indovinato un campo vero.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Attenzione: ogni forma accettata viene ESEGUITA dalla camera. Falla partire " +
                "guardando la camera, e se il gimbal si muove hai trovato comando e campo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        NumberField(
            label = "Codice da sondare",
            value = code,
            onValueChange = { code = it },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = prefix,
            onValueChange = { prefix = it },
            label = { Text("Prefisso esadecimale (opzionale)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Il prefisso resta davanti a ogni forma provata: serve a tenere fermo un " +
                "campo già capito e cercare il successivo. Per il campo 1 = 3 scrivi 0803.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { viewModel.probeShape(code, prefix) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "Interrompi" else "Prova le forme") }

        HorizontalDivider()
        Text(
            text = "Se un campo cambia il TIPO di rifiuto, la camera lo sta interpretando: " +
                "probabilmente è un selettore di sotto-comando. Qui se ne provano i valori.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = "Campo",
                value = field,
                onValueChange = { field = it },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "Fino a",
                value = maxValue,
                onValueChange = { maxValue = it },
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = { viewModel.sweepSelector(code, field, maxValue) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "Interrompi" else "Prova i valori del campo") }

        selector.filter { it.valid }.forEach { result ->
            Text(
                text = "valore ${result.value} → ACCETTATO: ${result.reply.describe}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (selector.isNotEmpty() && selector.none { it.valid }) {
            Text(
                text = "${selector.size} valori provati, nessuno accettato.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        results.forEach { result ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = result.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when {
                        result.accepted -> "ACCETTATO"
                        result.unknownCode -> "rifiutato (codice)"
                        else -> "rifiutato (corpo)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.accepted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * Legge ripetutamente un getter e mostra i campi decodificati.
 *
 * È il modo per trovare la lettura della posizione: si avvia, si muove il gimbal a mano dallo
 * schermo della camera, e si guarda quale numero cambia.
 */
@Composable
private fun MonitorCard(viewModel: MainViewModel) {
    val monitor by viewModel.monitor.collectAsState()
    var codes by remember { mutableStateOf("160,162,245,239,240") }

    SectionCard(title = "Ascolto di più codici") {
        Text(
            text = "Interroga a rotazione tutti i codici indicati e conta quante volte la " +
                "risposta cambia. Avvia, poi muovi il gimbal a mano: quello che sta leggendo " +
                "la posizione sale in cima da solo. Solo lettura.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = codes,
            onValueChange = { codes = it },
            label = { Text("Codici separati da virgola") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.toggleMonitor(codes) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (monitor.running) "Ferma l'ascolto" else "Ascolta") }

        monitor.ranked.forEach { entry ->
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "codice ${entry.code}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.moves) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = "${entry.changes} cambi · ${entry.distinct} valori · ${entry.reads} letture",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (entry.moves && entry.dump.isNotBlank()) {
                Text(
                    text = entry.dump,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun GimbalTuningCard(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val gimbal = settings.gimbal

    SectionCard(title = "Taratura gimbal") {
        Text(
            text = "Il messaggio e la rotazione degli assi sono fissi. Qui restano solo " +
                "l'intensità manuale, la cadenza e la stima dei gradi percorsi.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = "Velocità manuale %",
                value = gimbal.manualSpeedPercent.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.let { n -> viewModel.updateGimbal { it.copy(manualSpeedPercent = n) } }
                },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "Comandi al secondo",
                value = gimbal.commandRateHz.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.let { n -> viewModel.updateGimbal { it.copy(commandRateHz = n) } }
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = "Pan max °/s",
                value = gimbal.maxPanSpeedDegPerSec.toString(),
                onValueChange = { text ->
                    text.toFloatOrNull()?.let { v -> viewModel.updateGimbal { it.copy(maxPanSpeedDegPerSec = v) } }
                },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "Tilt max °/s",
                value = gimbal.maxTiltSpeedDegPerSec.toString(),
                onValueChange = { text ->
                    text.toFloatOrNull()?.let { v -> viewModel.updateGimbal { it.copy(maxTiltSpeedDegPerSec = v) } }
                },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "Le velocità massime servono a convertire la sequenza in tempi di comando: " +
                "misurale cronometrando una rotazione completa e correggile qui.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Inverti pan", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gimbal.invertPan,
                onCheckedChange = { on -> viewModel.updateGimbal { it.copy(invertPan = on) } },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Inverti tilt", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gimbal.invertTilt,
                onCheckedChange = { on -> viewModel.updateGimbal { it.copy(invertTilt = on) } },
            )
        }
    }
}

/** Righe di log disegnate a schermo. L'esportazione non è limitata. */
private const val LOG_WINDOW = 300
