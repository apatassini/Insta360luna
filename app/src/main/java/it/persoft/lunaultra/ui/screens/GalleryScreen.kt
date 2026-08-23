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
import it.persoft.lunaultra.ui.components.ButtonLabel
import it.persoft.lunaultra.ui.components.Hint
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** I tre filtri della griglia: quello che si cerca è quasi sempre uno dei due tipi. */
private enum class GalleryFilter(val label: String) {
    TUTTO("Tutto"),
    FOTO("Foto"),
    VIDEO("Video"),
    PREFERITI("Preferiti"),
}

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
    val favorites by viewModel.favorites.collectAsState()
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    val filter = GalleryFilter.entries[filterIndex]

    LaunchedEffect(Unit) { viewModel.refreshGallery() }

    val items = remember(gallery.items, filter, favorites) {
        when (filter) {
            GalleryFilter.TUTTO -> gallery.items
            GalleryFilter.FOTO -> gallery.items.filter { !it.isVideo }
            GalleryFilter.VIDEO -> gallery.items.filter { it.isVideo }
            GalleryFilter.PREFERITI -> gallery.items.filter { it.path in favorites }
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
                    val accent = if (entry == GalleryFilter.PREFERITI) Luna.Photo else Luna.Path
                    // Il numero sul filtro evita di doverlo premere per scoprire che è vuoto.
                    val count = when (entry) {
                        GalleryFilter.TUTTO -> gallery.items.size
                        GalleryFilter.FOTO -> gallery.photos
                        GalleryFilter.VIDEO -> gallery.videos
                        GalleryFilter.PREFERITI -> gallery.items.count { it.path in favorites }
                    }
                    FilterChip(
                        selected = filterIndex == index,
                        onClick = { filterIndex = index },
                        label = { Text(if (count > 0) "${entry.label} $count" else entry.label) },
                        leadingIcon = if (entry == GalleryFilter.PREFERITI) {
                            { Icon(LunaIcons.Star, contentDescription = null, modifier = Modifier.size(15.dp)) }
                        } else {
                            null
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.20f),
                            selectedLabelColor = accent,
                            selectedLeadingIconColor = accent,
                        ),
                    )
                }
                Box(modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.refreshGallery(force = true) }, enabled = !gallery.loading) {
                    ButtonLabel(LunaIcons.Refresh, "Aggiorna")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${gallery.items.size} file · ${gallery.photos} foto · ${gallery.videos} video",
                    style = MaterialTheme.typography.labelMedium,
                    color = Luna.OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (gallery.items.isNotEmpty()) {
                    Text(
                        text = if (gallery.selectionMode) "${gallery.selected.size} selezionati"
                        else "premi a lungo = seleziona",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (gallery.selectionMode) Luna.Accent else Luna.OnSurfaceDim,
                        maxLines = 1,
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
                        ButtonLabel(LunaIcons.Refresh, "Riprova")
                    }
                }

                // Un filtro che nasconde tutto lascerebbe una griglia vuota senza spiegazione:
                // sembra che la camera non abbia niente, mentre è solo il filtro.
                items.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = if (filter == GalleryFilter.PREFERITI) LunaIcons.StarOutline else LunaIcons.Gallery,
                        contentDescription = null,
                        tint = Luna.OnSurfaceDim,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = when (filter) {
                            GalleryFilter.PREFERITI ->
                                "Nessun preferito. Tieni premuta la stella su una foto per aggiungerla."
                            GalleryFilter.VIDEO -> "Nessun video fra i ${gallery.items.size} file sulla camera."
                            GalleryFilter.FOTO -> "Nessuna foto fra i ${gallery.items.size} file sulla camera."
                            GalleryFilter.TUTTO -> "Nessun file"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Luna.OnSurfaceDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    OutlinedButton(
                        onClick = { filterIndex = GalleryFilter.TUTTO.ordinal },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text("Mostra tutto")
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
                            favorite = item.path in favorites,
                            progress = gallery.downloads[item.path],
                            loadThumbnail = { viewModel.thumbnail(item) },
                            reloadKey = gallery.thumbnailsVersion,
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
                        ButtonLabel(LunaIcons.SelectAll, "Tutti")
                    }
                    Button(onClick = viewModel::downloadSelected, modifier = Modifier.weight(1f)) {
                        ButtonLabel(LunaIcons.Download, "Scarica ${gallery.selected.size}")
                    }
                }
            } else if (gallery.queueTotal > 0) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    val current = (gallery.queueDone + 1).coerceAtMost(gallery.queueTotal)
                    val name = gallery.downloads.keys.firstOrNull()?.substringAfterLast('/')
                    Hint(
                        buildString {
                            append("Scaricamento $current di ${gallery.queueTotal}")
                            if (name != null) append(" · $name")
                        }
                    )
                    LinearProgressIndicator(
                        progress = {
                            val file = gallery.downloads.values.firstOrNull() ?: 0f
                            ((gallery.queueDone + file) / gallery.queueTotal).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 6.dp),
                    )
                }
            } else if (filter == GalleryFilter.PREFERITI && items.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Button(onClick = viewModel::downloadFavorites, modifier = Modifier.fillMaxWidth()) {
                        ButtonLabel(LunaIcons.Download, "Scarica i ${items.size} preferiti")
                    }
                }
            }
        }

        viewer.item?.let { open ->
            MediaViewer(
                state = viewer,
                favorite = open.path in favorites,
                onClose = viewModel::closeViewer,
                onStep = viewModel::stepViewer,
                onDownload = { viewModel.download(open) },
                onToggleFavorite = { viewModel.toggleFavorite(open) },
                total = gallery.items.size,
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
    favorite: Boolean,
    progress: Float?,
    loadThumbnail: suspend () -> Bitmap?,
    reloadKey: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumbnail by produceState<Bitmap?>(initialValue = null, item.path, reloadKey) {
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
            if (favorite) TileBadge(LunaIcons.Star, Luna.Photo)
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
