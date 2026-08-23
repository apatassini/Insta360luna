package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.data.PhotoSettings
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.ui.components.GlassPanel
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlin.math.roundToInt

private enum class PhotoControl { TIMER, FORMAT, ZOOM, BRIGHTNESS, EV, WHITE_BALANCE }

/**
 * Regolazioni fotografiche compatte: una riga rimane discreta, il dettaglio della sola voce
 * toccata si apre sotto. Auto conserva timer e formato; Pro rende visibili le regolazioni che
 * cambiano l'immagine.
 */
@Composable
fun PhotoControlsSheet(
    settings: PhotoSettings,
    onProMode: (Boolean) -> Unit,
    onTimer: (Int) -> Unit,
    onRawCapture: (Int) -> Unit,
    onZoom: (Int) -> Unit,
    onBrightness: (Int) -> Unit,
    onExposureBias: (Int) -> Unit,
    onWhiteBalance: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf<PhotoControl?>(null) }

    GlassPanel(
        modifier = modifier.fillMaxWidth().widthIn(max = 430.dp),
        contentPadding = 12.dp,
        verticalSpacing = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Foto", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = !settings.proMode,
                    onClick = { onProMode(false); open = null },
                    label = { Text("AUTO") },
                )
                FilterChip(
                    selected = settings.proMode,
                    onClick = { onProMode(true) },
                    label = { Text("PRO") },
                )
            }
            HudIconButton(
                icon = LunaIcons.Close,
                contentDescription = "Chiudi regolazioni foto",
                onClick = onClose,
                size = 36.dp,
            )
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            item {
                SettingChip(
                    label = "TIMER",
                    value = settings.timerSeconds.takeIf { it > 0 }?.let { "${it}s" } ?: "Off",
                    selected = open == PhotoControl.TIMER,
                    onClick = { open = open.toggle(PhotoControl.TIMER) },
                )
            }
            item {
                SettingChip(
                    label = "ZOOM",
                    value = "${settings.zoomScale}×",
                    selected = open == PhotoControl.ZOOM,
                    onClick = { open = open.toggle(PhotoControl.ZOOM) },
                )
            }
            item {
                SettingChip(
                    label = "FORMATO",
                    value = if (settings.rawCaptureType == LunaProtocolCodes.RawCaptureType.DNG) "JPG+DNG" else "JPG",
                    selected = open == PhotoControl.FORMAT,
                    onClick = { open = open.toggle(PhotoControl.FORMAT) },
                )
            }
            if (settings.proMode) {
                item {
                    SettingChip(
                        label = "LUMINOSITÀ",
                        value = signed(settings.brightness),
                        selected = open == PhotoControl.BRIGHTNESS,
                        onClick = { open = open.toggle(PhotoControl.BRIGHTNESS) },
                    )
                }
                item {
                    SettingChip(
                        label = "EV",
                        value = evLabel(settings.exposureBiasThirds),
                        selected = open == PhotoControl.EV,
                        onClick = { open = open.toggle(PhotoControl.EV) },
                    )
                }
                item {
                    SettingChip(
                        label = "WB",
                        value = settings.whiteBalanceKelvin.takeIf { it > 0 }?.let { "${it}K" } ?: "Auto",
                        selected = open == PhotoControl.WHITE_BALANCE,
                        onClick = { open = open.toggle(PhotoControl.WHITE_BALANCE) },
                    )
                }
            }
        }

        when (open) {
            PhotoControl.TIMER -> ChoiceRow(
                values = listOf(0, 3, 5, 10, 20),
                selected = settings.timerSeconds,
                label = { if (it == 0) "Off" else "${it}s" },
                onSelect = onTimer,
            )

            PhotoControl.FORMAT -> ChoiceRow(
                values = listOf(LunaProtocolCodes.RawCaptureType.OFF, LunaProtocolCodes.RawCaptureType.DNG),
                selected = settings.rawCaptureType,
                label = { if (it == LunaProtocolCodes.RawCaptureType.DNG) "JPG + DNG" else "JPG" },
                onSelect = onRawCapture,
            )

            PhotoControl.ZOOM -> ChoiceRow(
                values = listOf(1, 2, 3, 6, 12),
                selected = settings.zoomScale,
                label = { "${it}×" },
                onSelect = onZoom,
            )

            PhotoControl.BRIGHTNESS -> ValueSlider(
                label = "Luminosità ${signed(settings.brightness)}",
                value = settings.brightness.toFloat(),
                range = -2f..2f,
                steps = 3,
                onChange = { onBrightness(it.roundToInt()) },
            )

            PhotoControl.EV -> ValueSlider(
                label = "Compensazione ${evLabel(settings.exposureBiasThirds)} EV",
                value = settings.exposureBiasThirds.toFloat(),
                range = -6f..6f,
                steps = 11,
                onChange = { onExposureBias(it.roundToInt()) },
            )

            PhotoControl.WHITE_BALANCE -> ChoiceRow(
                values = listOf(0, 2700, 4000, 5000, 6500, 7500),
                selected = settings.whiteBalanceKelvin,
                label = { if (it == 0) "Auto" else "${it}K" },
                onSelect = onWhiteBalance,
            )

            null -> Unit
        }
    }
}

@Composable
private fun SettingChip(label: String, value: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = Luna.OnSurfaceDim)
                Text(value, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        },
    )
}

@Composable
private fun <T> ChoiceRow(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(values) { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    var pending by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White)
        Slider(
            value = pending,
            onValueChange = { pending = it },
            onValueChangeFinished = { onChange(pending) },
            valueRange = range,
            steps = steps,
        )
    }
}

/** Numero grande ma senza una scheda che copra l'inquadratura. */
@Composable
fun PhotoCountdown(seconds: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(88.dp)
            .background(Luna.ScrimStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = seconds.toString(),
            style = MaterialTheme.typography.displayMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun PhotoControl?.toggle(value: PhotoControl): PhotoControl? = if (this == value) null else value

private fun signed(value: Int): String = when {
    value > 0 -> "+$value"
    else -> value.toString()
}

private fun evLabel(thirds: Int): String {
    if (thirds == 0) return "0"
    val value = thirds / 3f
    return if (value > 0) "+%.1f".format(value) else "%.1f".format(value)
}
