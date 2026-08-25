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
import it.persoft.lunaultra.ui.buildDateLabel
import it.persoft.lunaultra.ui.components.ButtonLabel
import it.persoft.lunaultra.ui.components.Hint
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.NumberField
import it.persoft.lunaultra.ui.components.SectionCard
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        SectionCard(title = "Camera", icon = LunaIcons.Connected, accent = Luna.Ok) {
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

        SectionCard(title = "Anteprima", icon = LunaIcons.Video, accent = Luna.Movie) {
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

        SectionCard(title = "Panorama della camera", icon = LunaIcons.Panorama, accent = Luna.Pano) {
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

        SectionCard(title = "Unione foto", icon = LunaIcons.Jobs, accent = Luna.Pano) {
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
                        "giunzioni. Trovata la migliore, si spegne il test e si regola qui sotto.",
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

        SectionCard(title = "Movimento manuale", icon = LunaIcons.Joystick, accent = Luna.PathLapse) {
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

        SectionCard(
            title = "Calibrazione gimbal",
            icon = LunaIcons.Center,
            accent = Luna.Pano,
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

        SectionCard(title = "Timelapse della camera", icon = LunaIcons.Timelapse, accent = Luna.Lapse) {
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

        SectionCard(title = "Log diagnostico", icon = LunaIcons.Diagnostics, accent = Luna.Accent) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledValue("Eventi presenti", logEntries.size.toString())
                Button(
                    onClick = { viewModel.saveLogToDownloads(context) },
                    enabled = logEntries.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonLabel(LunaIcons.Download, "Salva il log nei Download")
                }
                Hint(
                    "Viene creato un HTML con testo, miniature e punti di controllo incorporati. " +
                        "Il log dell'app viene azzerato soltanto dopo che il file è stato salvato correttamente.",
                )
            }
        }

        SectionCard(title = "Aggiornamenti", icon = LunaIcons.Download, accent = Luna.Ok) {
            var branch by remember(settings.updateBranch) { mutableStateOf(settings.updateBranch) }
            val effective = settings.updateBranch.ifBlank { BuildConfig.GIT_BRANCH }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // La data dice tutto quello che serve: «di stamattina» o «di tre giorni fa».
                // Il commit resta nel log per chi sviluppa.
                LabeledValue("Build installata", buildDateLabel(BuildConfig.BUILT_AT_MS))
                LabeledValue("Branch controllato", effective)
                OutlinedTextField(
                    value = branch,
                    onValueChange = { branch = it; viewModel.setUpdateBranch(it) },
                    label = { Text("Branch degli aggiornamenti") },
                    placeholder = { Text(BuildConfig.GIT_BRANCH) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                        LabeledValue("Stato", "controllo la release di ${state.branch}…")
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
                            state.publishedAtMs?.let { "scaricata la build del ${buildDateLabel(it)} · conferma l'installazione" }
                                ?: "scaricata la build più recente · conferma l'installazione",
                        )
                    is UpdateUiState.UpToDate -> LabeledValue("Stato", "già all'ultima build")
                    is UpdateUiState.Failed -> LabeledValue("Stato", state.reason, valueColor = Luna.Warn)
                    UpdateUiState.Idle -> Unit
                }
                Hint(
                    "Vuoto significa il branch che ha prodotto questo APK. All'avvio l'app legge " +
                        "la release di quel branch e propone l'aggiornamento se il commit è " +
                        "cambiato: cambiando questo campo si passa al lavoro di un altro ramo " +
                        "senza reinstallare l'APK a mano.",
                )
            }
        }

        SectionCard(title = "Su questa app", icon = LunaIcons.Info, accent = Luna.Photo) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(
                    "Controllo non ufficiale della Insta360 Luna Ultra, basato sul protocollo " +
                        "ricostruito da progetti di reverse engineering indipendenti.",
                )
                Hint(
                    "Il numero del comando del gimbal non è pubblico: finché non viene trovato, i " +
                        "comandi di movimento restano visibili ma inerti, e la sequenza non parte.",
                )
            }
        }
    }
}

private fun formatCalibrationDate(timestampMs: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date(timestampMs))
