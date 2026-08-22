package it.persoft.lunaultra.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.media.MediaItem
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.Hint
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** I tre filtri della griglia: quello che si cerca è quasi sempre uno dei due tipi. */
private enum class GalleryFilter(val label: String) { TUTTO("Tutto"), FOTO("Foto"), VIDEO("Video") }

/**
 * La galleria della camera.
 *
 * Le miniature arrivano una alla volta e in ritardo, perché ognuna costa una richiesta alla
 * camera: la casella compare subito con il tipo di file scritto sopra e si riempie quando
 * l'immagine arriva. Aspettare che siano pronte tutte per mostrare la griglia significherebbe
 * fissare uno schermo vuoto per mezzo minuto.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(viewModel: MainViewModel) {
    val gallery by viewModel.gallery.collectAsState()
    val viewer by viewModel.viewer.collectAsState()
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    val filter = GalleryFilter.entries[filterIndex]

    LaunchedEffect(Unit) { viewModel.refreshGallery() }

    val items = remember(gallery.items, filter) {
        when (filter) {
            GalleryFilter.TUTTO -> gallery.items
            GalleryFilter.FOTO -> gallery.items.filter { !it.isVideo }
            GalleryFilter.VIDEO -> gallery.items.filter { it.isVideo }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GalleryFilter.entries.forEachIndexed { index, entry ->
                    FilterChip(
                        selected = filterIndex == index,
                        onClick = { filterIndex = index },
                        label = { Text(entry.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Luna.Path.copy(alpha = 0.20f),
                            selectedLabelColor = Luna.Path,
                        ),
                    )
                }
                Box(modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.refreshGallery(force = true) }, enabled = !gallery.loading) {
                    Icon(LunaIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  Aggiorna")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${gallery.items.size} file  ·  ${gallery.photos} foto  ·  ${gallery.videos} video",
                    style = MaterialTheme.typography.labelMedium,
                    color = Luna.OnSurfaceDim,
                )
                if (gallery.items.isNotEmpty()) {
                    Text(
                        text = if (gallery.selectionMode) "${gallery.selected.size} selezionati" else "tieni premuto per selezionare",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (gallery.selectionMode) Luna.Accent else Luna.OnSurfaceDim,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                gallery.loading && gallery.items.isEmpty() -> LoadingBox("Lettura della libreria dalla camera…")

                gallery.items.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = LunaIcons.Gallery,
                        contentDescription = null,
                        tint = Luna.OnSurfaceDim,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = gallery.error ?: "Nessun file",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Luna.OnSurfaceDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    OutlinedButton(
                        onClick = { viewModel.refreshGallery(force = true) },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Icon(LunaIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("  Riprova")
                    }
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items, key = { it.path }) { item ->
                        MediaTile(
                            item = item,
                            selected = item.path in gallery.selected,
                            selectionMode = gallery.selectionMode,
                            progress = gallery.downloads[item.path],
                            loadThumbnail = { viewModel.thumbnail(item) },
                            onClick = {
                                if (gallery.selectionMode) viewModel.toggleSelection(item)
                                else viewModel.openViewer(item)
                            },
                            onLongClick = { viewModel.toggleSelection(item) },
                        )
                    }
                }
            }
            }

            if (gallery.selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Luna.SurfaceHigh)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = viewModel::clearSelection) { Text("Annulla") }
                    OutlinedButton(onClick = viewModel::selectAll) {
                        Icon(LunaIcons.SelectAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("  Tutti")
                    }
                    Button(onClick = viewModel::downloadSelected, modifier = Modifier.weight(1f)) {
                        Icon(LunaIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Scarica ${gallery.selected.size}")
                    }
                }
            } else if (gallery.downloads.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Hint("Scaricamento in corso: ${gallery.downloads.size} file")
                    LinearProgressIndicator(
                        progress = { gallery.downloads.values.average().toFloat() },
                        modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 6.dp),
                    )
                }
            }
        }

        if (viewer.item != null) {
            MediaViewer(
                state = viewer,
                onClose = viewModel::closeViewer,
                onStep = viewModel::stepViewer,
                onDownload = { viewer.item?.let(viewModel::download) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LoadingBox(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Luna.Accent)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Luna.OnSurfaceDim,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    item: MediaItem,
    selected: Boolean,
    selectionMode: Boolean,
    progress: Float?,
    loadThumbnail: suspend () -> Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumbnail by produceState<Bitmap?>(initialValue = null, item.path) {
        value = runCatching { loadThumbnail() }.getOrNull()
    }
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(Luna.SurfaceHigh, shape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Luna.Accent else Luna.GlassBorder,
                shape = shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val bitmap = thumbnail
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = if (item.isVideo) LunaIcons.Video else LunaIcons.Photo,
                    contentDescription = null,
                    tint = Luna.OnSurfaceDim,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = item.extension.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Luna.OnSurfaceDim,
                )
            }
        }

        // Distintivi in basso: dicono cos'è il file quando la miniatura non basta.
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.isVideo) TileBadge(LunaIcons.PlayCircle, Luna.Movie)
            if (item.panoramic) TileBadge(LunaIcons.Panorama, Luna.Pano)
            if (item.extension == "dng") {
                Text(
                    text = "RAW",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .background(Luna.Glass, CircleShape)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }

        Text(
            text = item.name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(Luna.GlassSoft)
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )

        if (selectionMode) {
            Icon(
                imageVector = if (selected) LunaIcons.Selected else LunaIcons.Unselected,
                contentDescription = if (selected) "Selezionato" else "Non selezionato",
                tint = if (selected) Luna.Accent else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp),
            )
        }

        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp),
            )
        }
    }
}

@Composable
private fun TileBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Box(
        modifier = Modifier.size(18.dp).background(Luna.Glass, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
    }
}

/** Data leggibile del file, dal nome quando la camera la scrive lì (quasi sempre). */
fun mediaDateLabel(takenAtMs: Long): String =
    if (takenAtMs <= 0L) "data sconosciuta"
    else SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(takenAtMs))
