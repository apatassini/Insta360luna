package it.persoft.lunaultra.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.BuildConfig
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.UpdateUiState
import it.persoft.lunaultra.update.UpdateChannel
import it.persoft.lunaultra.ui.buildDateLabel
import it.persoft.lunaultra.ui.components.ButtonLabel
import it.persoft.lunaultra.ui.components.Hint
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.NumberField
import it.persoft.lunaultra.ui.components.CollapsibleSection
import it.persoft.lunaultra.ui.components.SliderRow
import it.persoft.lunaultra.ui.components.StatusChip
import it.persoft.lunaultra.ui.components.ToggleRow
import it.persoft.lunaultra.ui.formatBytes
import it.persoft.lunaultra.ui.italianLabel
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Le impostazioni di tutti i giorni: la camera, l'anteprima, il comportamento del gimbal.
 *
 * Quelle da laboratorio — scanner dei codici, sonde, log grezzo — restano in Diagnostica: sono
 * due mestieri diversi e mescolarli significa far scorrere venti righe tecniche a chi voleva
 * solo cambiare la velocità del movimento.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val status by viewModel.status.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val calibration by viewModel.gimbalCalibration.collectAsState()
    val calibrationState by viewModel.gimbalCalibrationState.collectAsState()
    val connected = connection == ConnectionState.CONNECTED
    val gimbal = settings.gimbal
    var wifiPasswordVisible by rememberSaveable { mutableStateOf(false) }

    // Il selettore di sistema: nessun permesso da chiedere, e chi sceglie il file è chi sa
    // dove l'ha messo. Il filtro è aperto perché non tutti i gestori di file riconoscono un
    // `.json` come `application/json`, e un file che non compare è peggio di uno di troppo.
    val calibrationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importCalibration(context, it) }
    }

    // Le righe di riassunto: a sezione chiusa sono l'unica cosa che si legge, quindi devono
    // dire lo stato, non ripetere il titolo.
    val cameraSummary = if (connected) {
        listOfNotNull(
            "connessa",
            status.model,
            status.batteryPercent?.let { "$it%" },
        ).joinToString(" · ")
    } else {
        connection.italianLabel().lowercase()
    }
    val previewSummary = if (preview.active) {
        "accesa · ${preview.source.name.lowercase()}"
    } else {
        "spenta"
    }
    val panoAspectLabel = if (settings.panoAspect == LunaProtocolCodes.PanoAspect.SPHERE_360) {
        "sferica 360°"
    } else {
        "panorama 2:1"
    }
    val projectionName = when (settings.stitch.projectionCode) {
        1 -> "Cilindrica"
        2 -> "Mercatore"
        else -> "Equirettangolare"
    }
    val limitLabel = if (settings.stitch.verticalLimitDegrees < 0.5f) {
        "tela intera"
    } else {
        "fino a %.0f°".format(settings.stitch.verticalLimitDegrees)
    }
    val gpuOn = listOf(
        settings.stitch.gpuRecognise to "ricognizione",
        settings.stitch.gpuPaint to "pittura",
        settings.stitch.gpuBlend to "fusione",
    ).filter { it.first }.joinToString(" · ") { it.second }
    val recipeLabel = viewModel.stitchRecipeLetter(settings.stitch) ?: "personalizzata"
    val calibrationSummary = when {
        calibrationState.running -> "misura in corso"
        calibration.isValid -> "del ${formatCalibrationDate(calibration.calibratedAtMs)}"
        else -> "mai fatta: gli spostamenti a coordinate sono stime"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        CollapsibleSection(
            title = "Camera",
            summary = cameraSummary,
            icon = if (connected) LunaIcons.Connected else LunaIcons.Disconnected,
            accent = if (connected) Luna.Ok else Luna.OnSurfaceDim,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { if (connected) viewModel.disconnect() else viewModel.connect() },
                        modifier = Modifier.weight(1f),
                    ) {
                        ButtonLabel(
                            icon = if (connected) LunaIcons.Disconnected else LunaIcons.Connected,
                            label = if (connected) "Disconnetti" else "Connetti",
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::refreshStatus,
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                    ) {
                        ButtonLabel(LunaIcons.Refresh, "Aggiorna")
                    }
                }
                LabeledValue(
                    label = "Stato",
                    value = connection.italianLabel(),
                    valueColor = if (connected) Luna.Ok else Luna.OnSurfaceDim,
                )
                LabeledValue("Batteria", status.batteryPercent?.let { "$it%" } ?: "—")
                LabeledValue("Spazio libero", status.freeSpaceBytes?.let(::formatBytes) ?: "—")
                LabeledValue("Modello", status.model ?: "—")
                LabeledValue("Seriale", status.serial ?: "—")
                LabeledValue("Firmware", status.firmware ?: "—")
            }
        }

        // Indirizzo, porta e password si toccano una volta nella vita dell'installazione, e
        // finche` la camera risponde non vogliono nemmeno essere lette: stanno in un gruppo
        // loro, chiuso, invece di occupare mezzo pannello sopra tutto il resto.
        CollapsibleSection(
            title = "Rete e password",
            summary = "${settings.host}:${settings.port} · " +
                if (settings.cameraWifiPassword.isBlank()) "password non salvata" else "password salvata",
            icon = LunaIcons.Settings,
            accent = Luna.Ok,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = "Indirizzo",
                        value = settings.host,
                        onValueChange = viewModel::setHost,
                        keyboardType = KeyboardType.Text,
                        modifier = Modifier.weight(2f),
                    )
                    NumberField(
                        label = "Porta",
                        value = settings.port.toString(),
                        onValueChange = { text -> text.toIntOrNull()?.let(viewModel::setPort) },
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = settings.cameraWifiPassword,
                    onValueChange = viewModel::setCameraWifiPassword,
                    label = { Text("Password Wi-Fi Luna") },
                    supportingText = {
                        Text(
                            if (settings.cameraWifiPassword.isBlank()) {
                                "Inseriscila una volta, oppure connettiti manualmente: l'app proverà a leggerla dalla camera."
                            } else {
                                "Salvata: la rete Luna verrà selezionata automaticamente all'avvio."
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (wifiPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { wifiPasswordVisible = !wifiPasswordVisible }) {
                            Icon(
                                imageVector = if (wifiPasswordVisible) LunaIcons.Hide else LunaIcons.Show,
                                contentDescription = if (wifiPasswordVisible) "Nascondi password" else "Mostra password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        CollapsibleSection(
            title = "Anteprima",
            summary = previewSummary,
            icon = LunaIcons.Video,
            accent = Luna.Movie,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledValue("Sorgente", preview.source.name.lowercase())
                LabeledValue("Fotogrammi decodificati", preview.framesDecoded.toString())
                LabeledValue("Byte ricevuti", formatBytes(preview.bytesReceived))
                preview.message?.let { Hint(it) }
                Button(
                    onClick = viewModel::togglePreview,
                    enabled = connected || preview.active,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = if (preview.active) LunaIcons.VideoOff else LunaIcons.Video,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(text = if (preview.active) "  Spegni l'anteprima" else "  Accendi l'anteprima")
                }
                Hint(
                    "Si prova prima il MJPEG dell'endpoint OSC; se la camera non lo offre si passa " +
                        "allo stream della sessione di controllo, che è video compresso e passa dal decoder.",
                )
            }
        }

        CollapsibleSection(
            title = "Panorama della camera",
            summary = panoAspectLabel,
            icon = LunaIcons.Panorama,
            accent = Luna.Pano,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        LunaProtocolCodes.PanoAspect.SPHERE_360 to "Sferica 360°",
                        LunaProtocolCodes.PanoAspect.RATIO_2_1 to "Panorama 2:1",
                    ).forEach { (aspect, label) ->
                        FilterChip(
                            selected = settings.panoAspect == aspect,
                            onClick = { viewModel.setPanoAspect(aspect) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.Pano.copy(alpha = 0.20f),
                                selectedLabelColor = Luna.Pano,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint(
                    "Sulla Luna Ultra la panoramica è una sola sotto-modalità della camera — " +
                        "photo_sub_mode 8 per entrambe — e la scelta fra sferica e 2:1 viaggia su " +
                        "un campo a parte, che è questo. Si può cambiare anche dal mirino, con la " +
                        "pastiglia sopra il pulsante di scatto.",
                )
            }
        }

        CollapsibleSection(
            title = "Unione foto · Proiezione",
            summary = "$projectionName · $limitLabel",
            icon = LunaIcons.Panorama,
            accent = Luna.Pano,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToggleRow(
                    title = "Scegli da dove guardarla",
                    subtitle = "Prima di cucire a piena risoluzione, l'unione si ferma e mostra " +
                        "la panoramica in piccolo: il dito sposta il centro e la deformazione " +
                        "cambia mentre lo muovi. Vale per le unioni lanciate a mano",
                    checked = settings.stitch.chooseViewpoint,
                    onCheckedChange = { on -> viewModel.updateStitch { it.copy(chooseViewpoint = on) } },
                    icon = LunaIcons.Panorama,
                )
                ToggleRow(
                    title = "Sfumatura multibanda",
                    subtitle = "Spenta, la giunzione resta a taglio netto: serve a vedere dove " +
                        "cade davvero, senza che la sfumatura la nasconda",
                    checked = settings.stitch.multiband,
                    onCheckedChange = { on -> viewModel.updateStitch { it.copy(multiband = on) } },
                    icon = LunaIcons.Panorama,
                )
                Text("Proiezione della panoramica")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(1 to "Cilindrica", 2 to "Mercatore", 0 to "Equirett.").forEach { (code, label) ->
                        FilterChip(
                            selected = settings.stitch.projectionCode == code,
                            onClick = { viewModel.updateStitch { it.copy(projectionCode = code) } },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.Pano.copy(alpha = 0.20f),
                                selectedLabelColor = Luna.Pano,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint(
                    "La cilindrica è quella delle panoramiche a fila singola: vicino " +
                        "all'orizzonte le altezze restano naturali invece di essere compresse. " +
                        "Mercatore è la via di mezzo, e conserva le forme in piccolo. " +
                        "L'equirettangolare arriva ai poli ed è l'unica che un visualizzatore " +
                        "360° sa leggere: le panoramiche sferiche la usano sempre, qualunque " +
                        "cosa sia scelta qui.",
                )
                Text("Fin dove sale e scende la tela")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(0f to "Tutto", 75f to "75°", 65f to "65°", 55f to "55°").forEach { (limit, label) ->
                        FilterChip(
                            selected = kotlin.math.abs(settings.stitch.verticalLimitDegrees - limit) < 0.5f,
                            onClick = { viewModel.updateStitch { it.copy(verticalLimitDegrees = limit) } },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.Pano.copy(alpha = 0.20f),
                                selectedLabelColor = Luna.Pano,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint(
                    "È il ritaglio che negli stitcher da tavolo si fa trascinando il bordo, ed " +
                        "è il motivo per cui lì si sceglie la cilindrica e si taglia. Vicino " +
                        "allo zenit ogni proiezione piatta deforma — una sfera non sta su un " +
                        "foglio — e gli ultimi gradi di cielo costano moltissimi pixel per " +
                        "mostrare rami stirati. Tagliandoli si guadagna due volte: sparisce la " +
                        "deformazione peggiore, e la panoramica viene più larga, perché la " +
                        "densità della tela la decide l'area totale. Con un limite la " +
                        "proiezione scelta viene rispettata invece di essere sostituita.",
                )
            }
        }

        CollapsibleSection(
            title = "Unione foto · Allineamento",
            summary = "punti al ${settings.stitch.controlQualityPercent}%" +
                (if (settings.stitch.localWarp) " · deformazione locale" else ""),
            icon = LunaIcons.Center,
            accent = Luna.Pano,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToggleRow(
                    title = "Livella l'orizzonte",
                    subtitle = "Spento, la tela resta centrata sul centro esatto delle foto. " +
                        "Acceso, cerca l'orizzonte nelle foto e ci allinea la tela: le linee " +
                        "lontane escono dritte, ma l'inquadratura si sposta",
                    checked = settings.stitch.levelHorizon,
                    onCheckedChange = { on -> viewModel.updateStitch { it.copy(levelHorizon = on) } },
                    icon = LunaIcons.Center,
                )
                SliderRow(
                    label = "Inclinazione della camera",
                    value = settings.stitch.cameraPitchDegrees,
                    onValueChange = { value ->
                        viewModel.updateStitch { it.copy(cameraPitchDegrees = value.roundToInt().toFloat()) }
                    },
                    valueRange = -40f..40f,
                    valueLabel = if (settings.stitch.cameraPitchDegrees == 0f) {
                        "misurata"
                    } else {
                        "%+.0f°".format(settings.stitch.cameraPitchDegrees)
                    },
                    icon = LunaIcons.Axis,
                )
                Hint(
                    "Di serie il riferimento è il centro esatto della foto: prevedibile, e " +
                        "non sposta l'inquadratura. L'orizzonte viene comunque misurato e, se " +
                        "cade lontano dal centro, il verdetto di fine unione lo dice — perché " +
                        "è da lì che nasce il mare a conca: una riga orizzontale piazzata come " +
                        "se fosse all'altezza dell'occhio diventa un arco, e ogni foto ne " +
                        "aggiunge uno. La livella la raddrizza; il valore a mano serve quando " +
                        "l'orizzonte non si vede.",
                )
                ToggleRow(
                    title = "Taglio sul minimo disaccordo",
                    subtitle = "La giunzione passa dove le due foto già si assomigliano, non a " +
                        "metà strada: evita di tagliare in mezzo a un oggetto vicino",
                    checked = settings.stitch.seamMinimalDifference,
                    onCheckedChange = { on -> viewModel.updateStitch { it.copy(seamMinimalDifference = on) } },
                    icon = LunaIcons.Center,
                )
                ToggleRow(
                    title = "Tieni la parte più a fuoco",
                    subtitle = "Nella sovrapposizione la stessa cosa c'è due volte, e non sempre " +
                        "a fuoco in tutte e due: su una panoramica verticale l'autofocus decide " +
                        "ogni scatto per conto suo. Acceso, il taglio guarda anche quale delle " +
                        "due si vede meglio lì, e le lascia la sua parte",
                    checked = settings.stitch.focusAwareSeam,
                    onCheckedChange = { on -> viewModel.updateStitch { it.copy(focusAwareSeam = on) } },
                    icon = LunaIcons.Photo,
                )
                ToggleRow(
                    title = "Deformazione locale",
                    subtitle = "Assorbe la parallasse: il gimbal non ruota attorno al centro " +
                        "dell'obiettivo, e ciò che è vicino si sposta più di ciò che è lontano",
                    checked = settings.stitch.localWarp,
                    onCheckedChange = { on -> viewModel.updateStitch { it.copy(localWarp = on) } },
                    icon = LunaIcons.Axis,
                )
                Text("Forza della deformazione locale")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(1 to "Leggera", 2 to "Media", 3 to "Forte").forEach { (level, label) ->
                        FilterChip(
                            selected = settings.stitch.warpStrength == level,
                            onClick = { viewModel.updateStitch { it.copy(warpStrength = level) } },
                            label = { Text(label) },
                            enabled = settings.stitch.localWarp,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.Pano.copy(alpha = 0.20f),
                                selectedLabelColor = Luna.Pano,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint(
                    "«Forte» non vuol dire solo di più: vuol dire più *locale*. Una foto vista " +
                        "di scorcio e la stessa vista frontale hanno bisogno che il dettaglio " +
                        "venga allargato progressivamente, non spostato in blocco — e solo un " +
                        "campo stretto lo sa fare. Serve anche una buona quantità di punti di " +
                        "controllo: se sono pochi, il campo non ha di che reggersi e resta fermo.",
                )
                NumberField(
                    label = "Campo visivo reale (gradi, 0 = usa la specifica)",
                    value = if (settings.stitch.fovOverrideDegrees > 0f) {
                        settings.stitch.fovOverrideDegrees.toString()
                    } else {
                        "0"
                    },
                    onValueChange = { text ->
                        text.toFloatOrNull()?.let { value ->
                            viewModel.updateStitch { it.copy(fovOverrideDegrees = value) }
                        }
                    },
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Libertà sulla focale rispetto alla specifica")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(4 to "4%", 10 to "10%", 20 to "20%", 35 to "35%").forEach { (percent, label) ->
                        FilterChip(
                            selected = settings.stitch.focalFreedomPercent == percent,
                            onClick = { viewModel.updateStitch { it.copy(focalFreedomPercent = percent) } },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.Pano.copy(alpha = 0.20f),
                                selectedLabelColor = Luna.Pano,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint(
                    "Il campo visivo nasce dai «20 mm equivalenti» dichiarati, che sono catalogo " +
                        "e non misura. Se la focale vera è più lunga, le foto combaciano al centro " +
                        "e divergono ai bordi. Nel log dell'unione c'è la riga «Campo visivo: " +
                        "dichiarato … misurato …»: se lo scarto è costante, la specifica è ottimistica.",
                )
                Text("Qualità minima dei punti di controllo")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(80, 90, 95, 100).forEach { percent ->
                        FilterChip(
                            selected = settings.stitch.controlQualityPercent == percent,
                            onClick = { viewModel.updateStitch { it.copy(controlQualityPercent = percent) } },
                            label = { Text("$percent%") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.Pano.copy(alpha = 0.20f),
                                selectedLabelColor = Luna.Pano,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text("Quantità di punti di controllo")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(1 to "Normale", 2 to "Doppia", 4 to "Quadrupla").forEach { (scale, label) ->
                        FilterChip(
                            selected = settings.stitch.controlDensity == scale,
                            onClick = { viewModel.updateStitch { it.copy(controlDensity = scale) } },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.Pano.copy(alpha = 0.20f),
                                selectedLabelColor = Luna.Pano,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint(
                    "I punti di controllo sono i dettagli ritrovati fra due foto vicine: da loro " +
                        "si misurano spostamento, rollio, focale e deformazione locale. La soglia " +
                        "è un punto di partenza, non un ultimatum: se a quella qualità i " +
                        "superstiti non bastano, scende da sola fino a trovarne abbastanza e la " +
                        "scheda di fine unione dice a quale valore si è fermata. Su un cielo di " +
                        "nuvole o in controluce nessun punto arriva al 95%, e restare senza " +
                        "significherebbe spegnere tutta la rifinitura insieme.",
                )
            }
        }

        CollapsibleSection(
            title = "Unione foto · Scheda grafica",
            summary = if (gpuOn.isEmpty()) "tutto sulla CPU" else "su GPU: $gpuOn",
            icon = LunaIcons.Gpu,
            accent = Luna.Pano,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToggleRow(
                    title = "Ricognizione su GPU",
                    subtitle = "Dove cade ogni pixel della tela e quanto pesa: solo geometria, " +
                        "niente colori. È il passo più sicuro da spostare",
                    checked = settings.stitch.gpuRecognise,
                    onCheckedChange = { on -> viewModel.updateStitch { it.copy(gpuRecognise = on) } },
                    icon = LunaIcons.Gpu,
                )
                ToggleRow(
                    title = "Pittura su GPU",
                    subtitle = "Proiezione, campionamento e fotometria: è il passo che pesa di " +
                        "più, ed è quello per cui una scheda grafica esiste",
                    checked = settings.stitch.gpuPaint,
                    onCheckedChange = { on -> viewModel.updateStitch { it.copy(gpuPaint = on) } },
                    icon = LunaIcons.Gpu,
                )
                ToggleRow(
                    title = "Fusione su GPU",
                    subtitle = "La giunzione riportata a piena risoluzione. Va con la pittura: " +
                        "stesso shader, stessa texture, stesso autocontrollo. Senza pittura " +
                        "non ha niente su cui lavorare",
                    checked = settings.stitch.gpuBlend,
                    enabled = settings.stitch.gpuPaint,
                    onCheckedChange = { on -> viewModel.updateStitch { it.copy(gpuBlend = on) } },
                    icon = LunaIcons.Gpu,
                )
                Hint(
                    "Una alla volta, per capire quale porta cosa. Sotto c'è sempre la CPU: se il " +
                        "contesto grafico non si apre, se l'originale non entra in una texture o " +
                        "se la scheda non dice le stesse cose della CPU sul riquadro di prova, " +
                        "l'unione continua com'era e nel log compare il motivo. La scheda di fine " +
                        "unione riporta sempre su cosa hanno girato i tre passi, l'autocontrollo " +
                        "con i suoi scarti, i tempi divisi fra disegno e riporto, e — dentro la " +
                        "fusione — quanto pesano la griglia ridotta, le piramidi e il riporto.",
                )
            }
        }

        CollapsibleSection(
            title = "Unione foto · Banco di prova",
            summary = if (settings.stitch.testMode) "modalità test accesa" else "ricetta $recipeLabel",
            icon = LunaIcons.Diagnostics,
            accent = Luna.Pano,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToggleRow(
                    title = "Modalità test",
                    subtitle = "3 foto a 1024 px, tutte le ricette in fila: in galleria arrivano " +
                        "Panorama_TEST_A…F, una per ricetta, da confrontare fianco a fianco",
                    checked = settings.stitch.testMode,
                    onCheckedChange = { on -> viewModel.updateStitch { it.copy(testMode = on) } },
                    icon = LunaIcons.Diagnostics,
                )
                Hint(
                    "Le ricette: A storica (solo piramide e punti, multibanda) · B storica + " +
                        "fotometria · C rollio e focale senza fotometria · D completa attuale · " +
                        "E completa con punti severi · F taglio netto, per vedere dove cadono le " +
                        "giunzioni. Trovata la migliore, si spegne il test e si regola nei gruppi " +
                        "«Proiezione», «Allineamento» e «Scheda grafica».",
                )
                // La lettera scelta nel banco di prova, applicata all'unione vera: senza
                // questo, la ricetta che aveva convinto restava chiusa nella modalità test.
                val recipe = viewModel.stitchRecipeLetter(settings.stitch)
                Text(
                    text = "Ricetta: " + (recipe?.let { "$it — la stessa della prova $it" }
                        ?: "personalizzata"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (recipe != null) Luna.Pano else Luna.OnSurfaceDim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("A", "B", "C", "D", "E", "F").forEach { letter ->
                        FilterChip(
                            selected = recipe == letter,
                            onClick = { viewModel.applyStitchRecipe(letter) },
                            label = { Text(letter) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.Pano.copy(alpha = 0.20f),
                                selectedLabelColor = Luna.Pano,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint(
                    "Sono le stesse sei ricette della modalità test, ma a piena risoluzione: " +
                        "toccata una lettera, l'unione normale fa esattamente quello che ha " +
                        "fatto quella prova. Toccando invece una manopola negli altri gruppi " +
                        "la ricetta diventa «personalizzata», ed è giusto così.",
                )
            }
        }

        CollapsibleSection(
            title = "Movimento manuale",
            summary = "levetta al ${gimbal.manualSpeedPercent}%",
            icon = LunaIcons.Joystick,
            accent = Luna.PathLapse,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SliderRow(
                    label = "Intensità della levetta",
                    value = gimbal.manualSpeedPercent.toFloat(),
                    onValueChange = { viewModel.setManualSpeed(it.roundToInt()) },
                    valueRange = 1f..100f,
                    valueLabel = "${gimbal.manualSpeedPercent}%",
                    icon = LunaIcons.Speed,
                )
                Text("Preset intensità joystick")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(1 to "Bassa 25%", 2 to "Media 50%", 3 to "Alta 75%").forEach { (level, label) ->
                        FilterChip(
                            selected = gimbal.hardwareSpeedLevel == level,
                            onClick = { viewModel.setGimbalHardwareSpeed(level) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.PathLapse.copy(alpha = 0.20f),
                                selectedLabelColor = Luna.PathLapse,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint(
                    "Sono scorciatoie del cursore, non livelli hardware: dal log e dalle prove " +
                        "L/M/V risultano equivalenti. Le sequenze usano invece la curva calibrata 1–100%.",
                )
                ToggleRow(
                    title = "Correzione visiva automatica",
                    subtitle = "Allinea Punto 1 e arrivi confrontando i punti di controllo delle miniature",
                    checked = gimbal.visualWaypointCorrection,
                    onCheckedChange = { on ->
                        viewModel.updateGimbal { it.copy(visualWaypointCorrection = on) }
                    },
                    icon = LunaIcons.Center,
                )
                ToggleRow(
                    title = "Inverti l'asse orizzontale",
                    subtitle = "Se il gimbal va a destra quando spingi a sinistra",
                    checked = gimbal.invertPan,
                    onCheckedChange = { on -> viewModel.updateGimbal { it.copy(invertPan = on) } },
                    icon = LunaIcons.Axis,
                )
                ToggleRow(
                    title = "Inverti l'asse verticale",
                    subtitle = "Stessa cosa per l'inclinazione",
                    checked = gimbal.invertTilt,
                    onCheckedChange = { on -> viewModel.updateGimbal { it.copy(invertTilt = on) } },
                    icon = LunaIcons.Axis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = "Pan max °/s",
                        value = gimbal.maxPanSpeedDegPerSec.toString(),
                        onValueChange = { text ->
                            text.toFloatOrNull()?.let { v -> viewModel.updateGimbal { it.copy(maxPanSpeedDegPerSec = v) } }
                        },
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        label = "Tilt max °/s",
                        value = gimbal.maxTiltSpeedDegPerSec.toString(),
                        onValueChange = { text ->
                            text.toFloatOrNull()?.let { v -> viewModel.updateGimbal { it.copy(maxTiltSpeedDegPerSec = v) } }
                        },
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                }
                Hint(
                    "Queste due velocità legano la durata impostata al movimento reale: si tarano " +
                        "cronometrando una rotazione completa e correggendo il numero.",
                )
                LabeledValue(
                    label = "Comando gimbal",
                    value = "226 · 0x00E2 verificato",
                    valueColor = Luna.Ok,
                )
            }
        }

        CollapsibleSection(
            title = "Calibrazione gimbal",
            summary = calibrationSummary,
            icon = LunaIcons.Center,
            accent = Luna.Pano,
            // Sette minuti di misura non si nascondono dentro una sezione chiusa: mentre
            // gira, il gruppo si apre da solo e resta aperto.
            openWhen = calibrationState.running,
            trailing = {
                when {
                    calibrationState.running -> StatusChip("in corso", Luna.Amber, pulsing = true)
                    calibration.isValid -> StatusChip("attiva", Luna.Ok)
                    else -> StatusChip("mai fatta", Luna.Warn)
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Una calibrazione che fallisce dopo sette minuti deve dire perché, e deve
                // dirlo dove si guarda: in cima, non in fondo fra i valori del profilo.
                if (!calibrationState.running) {
                    calibrationState.error?.let { CalibrationFailureNotice(it) }
                }
                if (calibrationState.running) {
                    CalibrationLiveReport(
                        state = calibrationState,
                        onCancel = viewModel::cancelGimbalCalibration,
                    )
                } else {
                    if (calibration.isValid) {
                        LabeledValue(
                            "Misurata il",
                            formatCalibrationDate(calibration.calibratedAtMs),
                            valueColor = Luna.Ok,
                        )
                        CalibrationProfileSummary(calibration)
                    } else {
                        Hint(
                            "Senza calibrazione l'app non sa quanto muove un comando, e ogni " +
                                "spostamento a coordinate è una stima. Panoramiche e percorsi " +
                                "hanno bisogno di questo profilo per finire dove dicono.",
                        )
                    }
                    CalibrationStartBlock(
                        hasProfile = calibration.isValid,
                        enabled = connected,
                        onStart = viewModel::startGimbalCalibration,
                    )

                    // La calibrazione misura l'hardware, non le preferenze: la corsa degli assi
                    // e la curva dei comandi sono le stesse ieri e domani. L'unica ragione per
                    // rifarla erano i sette minuti persi reinstallando l'app, ed è la ragione
                    // che questi due pulsanti tolgono di mezzo.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.saveCalibrationToDownloads(context) },
                            enabled = calibration.isValid,
                            modifier = Modifier.weight(1f),
                        ) {
                            ButtonLabel(LunaIcons.Download, "Salva")
                        }
                        OutlinedButton(
                            onClick = { calibrationPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f),
                        ) {
                            ButtonLabel(LunaIcons.Upload, "Carica")
                        }
                    }
                    if (calibration.isValid) {
                        OutlinedButton(
                            onClick = { viewModel.shareCalibration(context) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ButtonLabel(LunaIcons.Share, "Mandala fuori dal telefono")
                        }
                    }
                    Hint(
                        "«Salva» scrive un file JSON nei Download. «Carica» lo rimette dentro dopo " +
                            "una reinstallazione, e i sette minuti di calibrazione non si rifanno. " +
                            "Vale per questa camera: è la sua corsa e la sua curva, misurate.",
                    )
                }
            }
        }

        CollapsibleSection(
            title = "Timelapse della camera",
            summary = "modalità ${settings.timelapseMode}",
            icon = LunaIcons.Timelapse,
            accent = Luna.Lapse,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    label = "Modalità timelapse",
                    value = settings.timelapseMode.toString(),
                    onValueChange = { text -> text.toIntOrNull()?.let(viewModel::setTimelapseMode) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(
                    "È il valore di TimelapseMode inviato con i comandi di timelapse interno. " +
                        "Si cambia solo se la camera rifiuta l'avvio.",
                )
            }
        }

        CollapsibleSection(
            title = "Diagnostica",
            summary = "${logEntries.size} eventi nel log",
            icon = LunaIcons.Diagnostics,
            accent = Luna.Accent,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledValue("Eventi presenti", logEntries.size.toString())
                Button(
                    onClick = { viewModel.saveLogToDownloads(context) },
                    enabled = logEntries.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonLabel(LunaIcons.Download, "Salva il log nei Download")
                }
                // La diagnostica vera — scanner dei codici, sonde, log grezzo — resta un
                // pannello a se`, perche` e` un altro mestiere: da qui ci si arriva, ma non
                // ci si inciampa scorrendo le impostazioni di tutti i giorni.
                OutlinedButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonLabel(LunaIcons.Diagnostics, "Apri la diagnostica")
                }
                Hint(
                    "Viene creato un HTML con testo, miniature e punti di controllo incorporati. " +
                        "Il log dell'app viene azzerato soltanto dopo che il file è stato salvato correttamente.",
                )
            }
        }

        CollapsibleSection(
            title = "Aggiornamenti",
            summary = "build del ${buildDateLabel(BuildConfig.BUILT_AT_MS)}",
            icon = LunaIcons.Download,
            accent = Luna.Ok,
        ) {
            var branch by remember(settings.updateBranch) { mutableStateOf(settings.updateBranch) }
            val effective = settings.updateBranch.ifBlank { BuildConfig.GIT_BRANCH }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // La data dice tutto quello che serve: «di stamattina» o «di tre giorni fa».
                // Il commit resta nel log per chi sviluppa.
                LabeledValue("Build installata", buildDateLabel(BuildConfig.BUILT_AT_MS))
                LabeledValue("Versione", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                LabeledValue(
                    "Firma",
                    if (BuildConfig.SIGNED_BY_PERSOFT) "Persoft" else "sviluppo",
                    valueColor = if (BuildConfig.SIGNED_BY_PERSOFT) Luna.Ok else Luna.Warn,
                )

                // I due canali non sono intercambiabili sullo stesso telefono: hanno firme
                // diverse, e Android non installa l'una sopra l'altra. Il selettore sta qui
                // perché la scelta si fa una volta, non a ogni controllo.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val canale = settings.updateChannel
                    UpdateChannel.entries.forEach { scelta ->
                        if (scelta == canale) {
                            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(scelta.etichetta) }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.setUpdateChannel(scelta) },
                                modifier = Modifier.weight(1f),
                            ) { Text(scelta.etichetta) }
                        }
                    }
                }

                if (settings.updateChannel == UpdateChannel.GITHUB) {
                    LabeledValue("Branch controllato", effective)
                    OutlinedTextField(
                        value = branch,
                        onValueChange = { branch = it; viewModel.setUpdateBranch(it) },
                        label = { Text("Branch degli aggiornamenti") },
                        placeholder = { Text(BuildConfig.GIT_BRANCH) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val updateState by viewModel.update.collectAsState()
                Button(
                    onClick = viewModel::checkForUpdateNow,
                    enabled = updateState !is UpdateUiState.Checking && updateState !is UpdateUiState.Downloading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonLabel(LunaIcons.Download, "Verifica aggiornamenti")
                }
                when (val state = updateState) {
                    is UpdateUiState.Checking -> {
                        LabeledValue("Stato", "controllo su ${state.branch}…")
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateUiState.Downloading -> {
                        val fraction = state.fraction
                        LabeledValue(
                            "Scaricamento",
                            if (fraction != null) {
                                "%d%% · %.1f di %.1f MB".format(
                                    state.percent,
                                    state.downloaded / 1_048_576f,
                                    state.total / 1_048_576f,
                                )
                            } else {
                                "%.1f MB".format(state.downloaded / 1_048_576f)
                            },
                        )
                        if (fraction != null) {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    is UpdateUiState.ReadyToInstall ->
                        LabeledValue(
                            "Stato",
                            state.publishedAtMs?.let { "scaricata la build del ${buildDateLabel(it)} · installazione in corso" }
                                ?: "scaricata la build più recente · installazione in corso",
                        )
                    is UpdateUiState.UpToDate -> LabeledValue("Stato", "già all'ultima build")
                    is UpdateUiState.Failed -> LabeledValue("Stato", state.reason, valueColor = Luna.Warn)
                    UpdateUiState.Idle -> Unit
                }
                Hint(
                    if (settings.updateChannel == UpdateChannel.PERSOFT) {
                        "Il sito è il canale della versione distribuita, firmata col certificato " +
                            "Persoft: all'avvio l'app legge il manifest, confronta il numero di " +
                            "build e scarica solo se ce n'è una più recente. Da Android 12 " +
                            "l'installazione chiede conferma finché non è stata l'app stessa a " +
                            "installare la versione precedente; dopo, passa in silenzio."
                    } else {
                        "La release GitHub è il canale di sviluppo: vuoto significa il branch che " +
                            "ha prodotto questo APK, e cambiando il campo si passa al lavoro di un " +
                            "altro ramo senza reinstallare a mano. È firmata con la chiave di " +
                            "sviluppo, non con quella Persoft: per tornare al canale del sito " +
                            "servirà disinstallare una volta."
                    },
                )
            }
        }

        CollapsibleSection(
            title = "Su questa app",
            summary = "controllo non ufficiale della Insta360 Luna Ultra",
            icon = LunaIcons.Info,
            accent = Luna.Photo,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(
                    "Controllo non ufficiale della Insta360 Luna Ultra, basato sul protocollo " +
                        "ricostruito da progetti di reverse engineering indipendenti.",
                )
                Hint(
                    "Il comando del gimbal è il 226 (0x00E2), verificato su questa camera: " +
                        "movimento e sequenze funzionano. Quello che la camera non dice è dove " +
                        "il gimbal si trovi davvero, e da lì viene tutto il lavoro di taratura.",
                )
            }
        }
    }
}

private fun formatCalibrationDate(timestampMs: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date(timestampMs))
