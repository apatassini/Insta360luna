package it.persoft.lunaultra.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.BuildConfig
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.Hint
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.NumberField
import it.persoft.lunaultra.ui.components.SectionCard
import it.persoft.lunaultra.ui.components.SliderRow
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
                        Icon(
                            imageVector = if (connected) LunaIcons.Disconnected else LunaIcons.Connected,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(text = if (connected) "  Disconnetti" else "  Connetti")
                    }
                    OutlinedButton(
                        onClick = viewModel::refreshStatus,
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(LunaIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Aggiorna")
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

        SectionCard(title = "Calibrazione gimbal", icon = LunaIcons.Center, accent = Luna.Pano) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Una calibrazione che fallisce dopo sette minuti deve dire perché, e deve
                // dirlo dove si guarda: in cima, non in fondo fra i valori del profilo.
                if (!calibrationState.running) {
                    calibrationState.error?.let { reason ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Luna.Rec, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "CALIBRAZIONE NON SALVATA",
                                style = MaterialTheme.typography.labelMedium,
                                color = Luna.Rec,
                            )
                            Text(reason, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Il profilo precedente è rimasto com'era. La Diagnostica ha il " +
                                    "log completo di questo tentativo, miniature comprese.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Luna.OnSurfaceDim,
                            )
                        }
                    }
                }
                if (calibrationState.running) {
                    val annotatedBitmap = remember(calibrationState.annotatedJpeg) {
                        calibrationState.annotatedJpeg?.let { bytes ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    }
                    Text(calibrationState.message)
                    LinearProgressIndicator(
                        progress = { calibrationState.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LabeledValue(
                        "Avanzamento",
                        "${(calibrationState.progress * 100).roundToInt()}% · ${calibrationState.phaseLabel}",
                    )
                    if (calibrationState.completedSteps > 0) {
                        LabeledValue(
                            "Curva comandi",
                            "${calibrationState.completedSteps}/${calibrationState.totalSteps} misure",
                        )
                    }
                    if (calibrationState.pausedForPreview) {
                        Hint(
                            "Calibrazione in pausa, non interrotta. Il servizio mantiene camera, " +
                                "Wi-Fi e misure; riaprendo l'app riprende dal passaggio corrente.",
                        )
                    }
                    LabeledValue(
                        "Passaggio corrente",
                        "${calibrationState.intensityPercent}% · ${calibrationState.axisLabel} · ${calibrationState.directionLabel}",
                    )
                    LabeledValue("Durata impulso", "${calibrationState.pulseMs} ms")
                    LabeledValue(
                        "Coordinate teoriche",
                        "pan %.2f° · tilt %.2f°".format(
                            calibrationState.theoreticalPan,
                            calibrationState.theoreticalTilt,
                        ),
                    )
                    Text(calibrationState.verificationLabel)
                    LabeledValue(
                        "Spostamento immagine",
                        "Δx %+.1f px · Δy %+.1f px".format(calibrationState.shiftX, calibrationState.shiftY),
                    )
                    LabeledValue(
                        "Punti di controllo corretti",
                        "${calibrationState.inlierMatches}/${calibrationState.candidateMatches} · ${calibrationState.controlPointsPercent}%",
                        valueColor = when {
                            calibrationState.controlPointsPercent >= 70 -> Luna.Ok
                            calibrationState.controlPointsPercent >= 45 -> Luna.Warn
                            else -> Luna.Rec
                        },
                    )
                    LabeledValue(
                        "Posizionamento corretto",
                        "${calibrationState.positioningPercent}%",
                        valueColor = when {
                            calibrationState.positioningPercent >= 75 -> Luna.Ok
                            calibrationState.positioningPercent >= 50 -> Luna.Warn
                            else -> Luna.Rec
                        },
                    )
                    LinearProgressIndicator(
                        progress = { calibrationState.positioningPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LabeledValue(
                        "Campioni validi",
                        "${calibrationState.validSamples}/${calibrationState.completedSteps.coerceAtLeast(1)}",
                    )
                    annotatedBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Punti di controllo della calibrazione",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, Luna.GlassBorder, RoundedCornerShape(14.dp)),
                        )
                        Hint("Verde = punti coerenti con il movimento · rosso = corrispondenze scartate.")
                    }
                    OutlinedButton(
                        onClick = viewModel::cancelGimbalCalibration,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Interrompi calibrazione")
                    }
                } else {
                    if (calibration.isValid) {
                        LabeledValue(
                            "Profilo attivo",
                            formatCalibrationDate(calibration.calibratedAtMs),
                            valueColor = Luna.Ok,
                        )
                        LabeledValue("Qualità misure", "${calibration.qualityPercent}% · ${calibration.validSamples}/${calibration.totalSamples}")
                        LabeledValue("Ritardo / assestamento", "${calibration.responseOverheadMs} ms / ${calibration.settleMs} ms")
                        LabeledValue(
                            "Fine corsa orizzontale",
                            "%.0f°…%+.0f° · %.1f s al %d%%".format(
                                calibration.panLimits.minimumDeg,
                                calibration.panLimits.maximumDeg,
                                calibration.panLimits.travelSecondsAtSweepIntensity,
                                calibration.panLimits.sweepIntensityPercent,
                            ),
                            valueColor = Luna.Ok,
                        )
                        LabeledValue(
                            "Fine corsa verticale",
                            "%.0f°…%+.0f° · %.1f s al %d%%".format(
                                calibration.tiltLimits.minimumDeg,
                                calibration.tiltLimits.maximumDeg,
                                calibration.tiltLimits.travelSecondsAtSweepIntensity,
                                calibration.tiltLimits.sweepIntensityPercent,
                            ),
                            valueColor = Luna.Ok,
                        )
                        calibration.responsePoints.filter {
                            it.intensityPercent in setOf(1, 10, 50, 100)
                        }.sortedBy { it.intensityPercent }.forEach { point ->
                            LabeledValue(
                                "Intensità ${point.intensityPercent}%",
                                "pan %+.0f px/s · tilt %+.0f px/s".format(
                                    point.panImagePixelsPerSecond,
                                    point.tiltImagePixelsPerSecond,
                                ),
                            )
                        }
                    } else {
                        LabeledValue("Profilo attivo", "Non ancora calibrato")
                    }
                    Button(
                        onClick = viewModel::startGimbalCalibration,
                        enabled = connected,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(LunaIcons.Center, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(if (calibration.isValid) "  Riesegui calibrazione completa" else "  Avvia calibrazione completa")
                    }
                }
                Hint(
                    "Durata indicativa 4–7 minuti. Lascia la camera completamente libera di " +
                        "ruotare: prima cerca sinistra/destra/basso/alto con impulsi al 40%, poi " +
                        "torna allo zero frontale. Inquadra una scena ferma e ricca di dettagli. Vengono misurate le " +
                        "intensità 1%, 5% e poi ogni 10% fino al 100%, sui due assi; il profilo precedente resta valido se la " +
                        "nuova prova viene interrotta o non è affidabile.",
                )
                if (calibration.isValid) {
                    Hint(
                        "Il file gimbal_calibration.json viene caricato a ogni avvio e usato " +
                            "automaticamente per tempi, direzioni e correzione visiva dei waypoint.",
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
                    Icon(LunaIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Salva log nella cartella Download")
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
                LabeledValue("Build installata", "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA.take(12)}")
                LabeledValue("Branch controllato", effective)
                OutlinedTextField(
                    value = branch,
                    onValueChange = { branch = it; viewModel.setUpdateBranch(it) },
                    label = { Text("Branch degli aggiornamenti") },
                    placeholder = { Text(BuildConfig.GIT_BRANCH) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
