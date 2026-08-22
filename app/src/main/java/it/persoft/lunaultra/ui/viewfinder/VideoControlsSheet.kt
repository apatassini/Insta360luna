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

private enum class VideoControl { RESOLUTION, ASPECT, FPS }

/** Menu nativo della camera per formato video; le automazioni del gimbal non entrano qui. */
@Composable
fun VideoControlsSheet(
    settings: VideoSettings,
    mode: CameraMode,
    onProfile: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profiles = LunaVideoProfiles.forMode(mode)
    val selected = LunaVideoProfiles.selected(settings.profileCode, mode)
    var open by remember { mutableStateOf<VideoControl?>(null) }

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
