package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.CameraMode
import it.persoft.lunaultra.data.LunaVideoProfile
import it.persoft.lunaultra.data.LunaVideoProfiles
import it.persoft.lunaultra.data.VideoSettings
import it.persoft.lunaultra.ui.components.GlassPanel
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import it.persoft.lunaultra.protocol.LunaProtocolCodes

private enum class VideoControl {
    RESOLUTION, ASPECT, FPS, ISO, SHUTTER, EV, COLOR, FILTER, STRENGTH, WB, SHARPNESS,
}

private data class VideoChoice<T>(val value: T, val label: String)

private val isoChoices = listOf(0, 100, 200, 400, 800, 1600, 3200, 6400)
    .map { VideoChoice(it, if (it == 0) "Auto" else it.toString()) }

private val shutterChoices = listOf(
    VideoChoice(0.0, "Auto"),
    VideoChoice(1.0 / 8000, "1/8000"), VideoChoice(1.0 / 4000, "1/4000"),
    VideoChoice(1.0 / 2000, "1/2000"), VideoChoice(1.0 / 1000, "1/1000"),
    VideoChoice(1.0 / 500, "1/500"), VideoChoice(1.0 / 250, "1/250"),
    VideoChoice(1.0 / 120, "1/120"), VideoChoice(1.0 / 60, "1/60"),
    VideoChoice(1.0 / 30, "1/30"), VideoChoice(1.0 / 15, "1/15"),
    VideoChoice(1.0 / 8, "1/8"), VideoChoice(1.0 / 4, "1/4"),
    VideoChoice(0.5, "0,5s"), VideoChoice(1.0, "1s"),
)

private val colorChoices = listOf(
    VideoChoice(LunaProtocolCodes.ColorMode.STANDARD, "Standard"),
    VideoChoice(LunaProtocolCodes.ColorMode.I_LOG, "i-Log"),
    VideoChoice(LunaProtocolCodes.ColorMode.DOLBY_VISION, "Dolby Vision"),
)

private val filterChoices = listOf(
    VideoChoice(LunaProtocolCodes.Filter.ORIGINAL, "Originale"),
    VideoChoice(LunaProtocolCodes.Filter.LEICA_NATURAL, "Leica Natural"),
    VideoChoice(LunaProtocolCodes.Filter.LEICA_VIVID, "Leica Vivid"),
    VideoChoice(LunaProtocolCodes.Filter.LEICA_CHROME, "Leica Chrome"),
    VideoChoice(LunaProtocolCodes.Filter.POS_FILM, "Pos Film"),
    VideoChoice(LunaProtocolCodes.Filter.NEG_FILM, "Neg Film"),
    VideoChoice(LunaProtocolCodes.Filter.CC_FILM, "CC Film"),
    VideoChoice(LunaProtocolCodes.Filter.NC_FILM, "NC Film"),
    VideoChoice(LunaProtocolCodes.Filter.FRESH, "Fresh"),
    VideoChoice(LunaProtocolCodes.Filter.CINEMATIC, "Cinematic"),
)

/** Menu nativo della camera per formato video; le automazioni del gimbal non entrano qui. */
@Composable
fun VideoControlsSheet(
    settings: VideoSettings,
    mode: CameraMode,
    onProfile: (Int) -> Unit,
    onProMode: (Boolean) -> Unit,
    onIso: (Int) -> Unit,
    onShutter: (Double) -> Unit,
    onExposureBias: (Int) -> Unit,
    onWhiteBalance: (Int) -> Unit,
    onColorMode: (Int) -> Unit,
    onFilter: (Int) -> Unit,
    onFilterIntensity: (Int) -> Unit,
    onSharpness: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profiles = LunaVideoProfiles.forMode(mode)
    val selected = LunaVideoProfiles.selected(settings.profileCode, mode)
    var open by remember { mutableStateOf<VideoControl?>(null) }
    val filtersAvailable = settings.colorMode != LunaProtocolCodes.ColorMode.DOLBY_VISION &&
        selected.width <= 3840 && selected.fps <= 60
    val cinematicFilters = setOf(
        LunaProtocolCodes.Filter.POS_FILM, LunaProtocolCodes.Filter.NEG_FILM,
        LunaProtocolCodes.Filter.CC_FILM, LunaProtocolCodes.Filter.NC_FILM,
        LunaProtocolCodes.Filter.FRESH, LunaProtocolCodes.Filter.CINEMATIC,
    )

    GlassPanel(
        modifier = modifier.fillMaxWidth().widthIn(max = 460.dp),
        contentPadding = 12.dp,
        verticalSpacing = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Camera · Video", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text("MP4 · H.265", style = MaterialTheme.typography.labelSmall, color = Luna.OnSurfaceDim)
            }
            HudIconButton(
                icon = LunaIcons.Close,
                contentDescription = "Chiudi impostazioni video",
                onClick = onClose,
                size = 36.dp,
            )
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            item {
                FilterChip(
                    selected = settings.proMode,
                    onClick = { onProMode(!settings.proMode) },
                    label = { Text(if (settings.proMode) "PRO" else "AUTO") },
                )
            }
            item {
                VideoSettingChip(
                    label = "RISOLUZIONE",
                    value = selected.resolution,
                    selected = open == VideoControl.RESOLUTION,
                    onClick = { open = open.toggle(VideoControl.RESOLUTION) },
                )
            }
            item {
                VideoSettingChip(
                    label = "FORMATO",
                    value = selected.aspect,
                    selected = open == VideoControl.ASPECT,
                    onClick = { open = open.toggle(VideoControl.ASPECT) },
                )
            }
            item {
                VideoSettingChip(
                    label = "FPS",
                    value = selected.fps.toString(),
                    selected = open == VideoControl.FPS,
                    onClick = { open = open.toggle(VideoControl.FPS) },
                )
            }
        }

        if (settings.proMode) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item { VideoSettingChip("ISO", if (settings.iso == 0) "Auto" else settings.iso.toString(), open == VideoControl.ISO) { open = open.toggle(VideoControl.ISO) } }
                item { VideoSettingChip("SHUTTER", shutterLabel(settings.shutterSeconds), open == VideoControl.SHUTTER) { open = open.toggle(VideoControl.SHUTTER) } }
                item { VideoSettingChip("EV", evLabel(settings.exposureBiasThirds), open == VideoControl.EV) { open = open.toggle(VideoControl.EV) } }
                item { VideoSettingChip("WB", if (settings.whiteBalanceKelvin == 0) "Auto" else "${settings.whiteBalanceKelvin}K", open == VideoControl.WB) { open = open.toggle(VideoControl.WB) } }
                item { VideoSettingChip("COLORE", colorLabel(settings.colorMode), open == VideoControl.COLOR) { open = open.toggle(VideoControl.COLOR) } }
                if (filtersAvailable) {
                    item { VideoSettingChip("FILTRO", filterLabel(settings.filter), open == VideoControl.FILTER) { open = open.toggle(VideoControl.FILTER) } }
                }
                if (filtersAvailable && settings.filter in cinematicFilters) {
                    item { VideoSettingChip("INTENSITÀ", intensityLabel(settings.filterIntensity), open == VideoControl.STRENGTH) { open = open.toggle(VideoControl.STRENGTH) } }
                }
                item { VideoSettingChip("NITIDEZZA", sharpnessLabel(settings.sharpness), open == VideoControl.SHARPNESS) { open = open.toggle(VideoControl.SHARPNESS) } }
            }
        }

        when (open) {
            VideoControl.RESOLUTION -> VideoChoiceRow(
                values = profiles.map { it.resolution }.distinct(),
                selected = selected.resolution,
                label = { it },
                onSelect = { resolution ->
                    chooseProfile(profiles.filter { it.resolution == resolution }, selected)?.let { onProfile(it.code) }
                },
            )

            VideoControl.ASPECT -> VideoChoiceRow(
                values = profiles.filter { it.resolution == selected.resolution }.map { it.aspect }.distinct(),
                selected = selected.aspect,
                label = { it },
                onSelect = { aspect ->
                    chooseProfile(
                        profiles.filter { it.resolution == selected.resolution && it.aspect == aspect },
                        selected,
                    )?.let { onProfile(it.code) }
                },
            )

            VideoControl.FPS -> VideoChoiceRow(
                values = profiles
                    .filter { it.resolution == selected.resolution && it.aspect == selected.aspect }
                    .sortedByDescending { it.fps },
                selected = selected,
                label = { "${it.fps}" },
                onSelect = { onProfile(it.code) },
            )

            VideoControl.ISO -> ChoiceRow(isoChoices, settings.iso, onIso)
            VideoControl.SHUTTER -> ChoiceRow(shutterChoices, settings.shutterSeconds, onShutter)
            VideoControl.EV -> ChoiceRow(
                (-12..12).map { VideoChoice(it, evLabel(it)) },
                settings.exposureBiasThirds,
                onExposureBias,
            )
            VideoControl.WB -> ChoiceRow(
                listOf(VideoChoice(0, "Auto")) + (2000..10000 step 200).map { VideoChoice(it, "${it}K") },
                settings.whiteBalanceKelvin,
                onWhiteBalance,
            )
            VideoControl.COLOR -> ChoiceRow(
                if (mode == CameraMode.VIDEO) colorChoices else colorChoices.take(1),
                if (mode == CameraMode.VIDEO) settings.colorMode else LunaProtocolCodes.ColorMode.STANDARD,
                onColorMode,
            )
            VideoControl.FILTER -> ChoiceRow(filterChoices, settings.filter, onFilter)
            VideoControl.STRENGTH -> ChoiceRow(
                listOf(
                    VideoChoice(LunaProtocolCodes.FilterIntensity.LOW, "Bassa"),
                    VideoChoice(LunaProtocolCodes.FilterIntensity.MEDIUM, "Media"),
                    VideoChoice(LunaProtocolCodes.FilterIntensity.HIGH, "Alta"),
                ),
                settings.filterIntensity,
                onFilterIntensity,
            )
            VideoControl.SHARPNESS -> ChoiceRow(
                listOf("Off", "Bassa", "Media", "Alta", "Massima").mapIndexed { index, label -> VideoChoice(index, label) },
                settings.sharpness,
                onSharpness,
            )

            null -> Unit
        }

        Text(
            text = "${selected.width}×${selected.height} · ${selected.fps} fps",
            style = MaterialTheme.typography.bodySmall,
            color = Luna.OnSurfaceDim,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun <T> ChoiceRow(values: List<VideoChoice<T>>, selected: T, onSelect: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(values) { choice ->
            FilterChip(
                selected = choice.value == selected,
                onClick = { onSelect(choice.value) },
                label = { Text(choice.label) },
            )
        }
    }
}

private fun shutterLabel(seconds: Double): String =
    shutterChoices.minByOrNull { kotlin.math.abs(it.value - seconds) }?.label ?: "Auto"

private fun evLabel(thirds: Int): String {
    if (thirds == 0) return "0"
    val value = thirds / 3.0
    return if (thirds > 0) "+%.1f".format(value) else "%.1f".format(value)
}

private fun colorLabel(value: Int) = colorChoices.firstOrNull { it.value == value }?.label ?: "Standard"
private fun filterLabel(value: Int) = filterChoices.firstOrNull { it.value == value }?.label ?: "Originale"
private fun intensityLabel(value: Int) = when (value) {
    LunaProtocolCodes.FilterIntensity.LOW -> "Bassa"
    LunaProtocolCodes.FilterIntensity.HIGH -> "Alta"
    else -> "Media"
}
private fun sharpnessLabel(value: Int) = listOf("Off", "Bassa", "Media", "Alta", "Massima").getOrElse(value) { "Media" }

private fun chooseProfile(candidates: List<LunaVideoProfile>, current: LunaVideoProfile): LunaVideoProfile? =
    candidates.firstOrNull { it.aspect == current.aspect && it.fps == current.fps }
        ?: candidates.firstOrNull { it.aspect == current.aspect && it.fps == 30 }
        ?: candidates.firstOrNull { it.fps == current.fps }
        ?: candidates.firstOrNull { it.fps == 30 }
        ?: candidates.maxByOrNull { it.fps }

@Composable
private fun VideoSettingChip(label: String, value: String, selected: Boolean, onClick: () -> Unit) {
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
private fun <T> VideoChoiceRow(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
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

private fun VideoControl?.toggle(value: VideoControl): VideoControl? = if (this == value) null else value
