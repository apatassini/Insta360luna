package it.persoft.lunaultra.media

import java.util.Calendar
import java.util.Locale

/**
 * Dall'elenco dei percorsi restituito dalla camera alla libreria mostrata a schermo.
 *
 * È tutto codice puro, senza Android e senza rete, perché le regole qui dentro sono le uniche
 * che possono sbagliare in silenzio: un video accoppiato al proxy sbagliato mostra l'anteprima
 * di un'altra ripresa, e non se ne accorge nessuno finché non la si guarda.
 */
object MediaIndex {

    /** I proxy a bassa risoluzione dei video. Non sono file da mostrare, sono anteprime. */
    private const val PROXY_EXTENSION = "lrv"

    /** File di servizio delle foto in movimento: non vanno in galleria. */
    private const val LIVE_SUFFIX = ".live.mp4"

    private val NAME_TIMESTAMP = Regex(
        "(?:VID|LRV|IMG|LIV|PIC|PANO)_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Chiave che lega un video al suo proxy. Regge due forme di nome: quella storica
     * (`VID_<data>_<ora>_<NN>_<clip>`) e quella dal firmware 1.0.238, che ha perso il segmento
     * del sottoflusso (`VID_<data>_<ora>_<clip>`).
     */
    private val LEGACY_PROXY_KEY = Regex("^(?:VID|LRV|LIV)_(\\d{8}_\\d{6})_\\d{2}_(\\d+)\\.\\w+$", RegexOption.IGNORE_CASE)
    private val PROXY_KEY = Regex("^(?:VID|LRV|LIV)_(.+)\\.\\w+$", RegexOption.IGNORE_CASE)

    fun fromPaths(paths: List<String>): List<MediaItem> {
        val entries = paths.mapNotNull(::entryOf)

        val proxyByKey = entries
            .filter { it.extension == PROXY_EXTENSION }
            .mapNotNull { entry -> proxyKey(entry.name)?.let { it to entry } }
            .toMap()

        // Coppie RAW+JPEG: la camera salva IMG_x.dng accanto a IMG_x.jpg, che è lo stesso
        // scatto e serve da anteprima al DNG, che il telefono non sa disegnare.
        val jpgByBase = entries
            .filter { it.extension == "jpg" || it.extension == "jpeg" }
            .associateBy { baseName(it.name) }

        val seen = mutableSetOf<String>()
        val items = mutableListOf<MediaItem>()
        for (entry in entries) {
            if (entry.extension == PROXY_EXTENSION) continue
            if (!seen.add(entry.path)) continue

            val video = entry.extension in MediaItem.VIDEO_EXTENSIONS
            items += MediaItem(
                path = entry.path,
                name = entry.name,
                kind = if (video) MediaKind.VIDEO else MediaKind.FOTO,
                extension = entry.extension,
                takenAtMs = entry.takenAtMs,
                panoramic = !video &&
                    (entry.name.startsWith("PANO_", ignoreCase = true) || entry.extension == "insp"),
                proxyPath = proxyKey(entry.name)?.let { proxyByKey[it]?.path },
                previewPath = if (entry.extension == "dng") jpgByBase[baseName(entry.name)]?.path else null,
            )
        }

        // I più recenti in cima: è l'ordine in cui si cercano le riprese appena fatte.
        return items.sortedWith(compareByDescending<MediaItem> { it.takenAtMs }.thenByDescending { it.name })
    }

    /** L'istante di scatto scritto nel nome del file, se c'è. */
    fun parseNameTimestamp(name: String): Long? {
        val match = NAME_TIMESTAMP.find(name) ?: return null
        val (year, month, day, hour, minute, second) = match.destructured
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year.toInt(), month.toInt() - 1, day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
        return calendar.timeInMillis
    }

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }

    private fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(0, dot) else name
    }

    private fun proxyKey(name: String): String? {
        LEGACY_PROXY_KEY.find(name)?.let { return "${it.groupValues[1]}_${it.groupValues[2]}" }
        return PROXY_KEY.find(name)?.groupValues?.get(1)
    }

    private fun entryOf(path: String): Entry? {
        val name = path.substringAfterLast('/')
        if (name.isEmpty()) return null
        if (name.endsWith(LIVE_SUFFIX, ignoreCase = true)) return null
        val extension = extensionOf(name)
        val known = extension in MediaItem.IMAGE_EXTENSIONS ||
            extension in MediaItem.VIDEO_EXTENSIONS ||
            extension == PROXY_EXTENSION
        if (!known) return null
        return Entry(
            path = path,
            name = name,
            extension = extension,
            takenAtMs = parseNameTimestamp(name) ?: 0L,
        )
    }

    private data class Entry(
        val path: String,
        val name: String,
        val extension: String,
        val takenAtMs: Long,
    )
}
