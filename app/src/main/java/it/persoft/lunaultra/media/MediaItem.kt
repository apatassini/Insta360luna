package it.persoft.lunaultra.media

/** Foto o video: la distinzione decide sia l'icona sia dove finisce il file scaricato. */
enum class MediaKind { FOTO, VIDEO }

/**
 * Un file sulla camera.
 *
 * Il percorso è l'identità: la camera lo restituisce da `GET_FILE_LIST` ed è anche l'indirizzo
 * HTTP da cui scaricarlo, finché la sessione di controllo è aperta.
 */
data class MediaItem(
    val path: String,
    val name: String,
    val kind: MediaKind,
    val extension: String,
    val takenAtMs: Long,
    /** Le panoramiche e gli scatti a 360°: si riconoscono dal nome o dall'estensione. */
    val panoramic: Boolean = false,
    /**
     * Il proxy a bassa risoluzione che la camera salva accanto a ogni video (`.lrv`).
     * Per guardare un video sul telefono è la differenza fra due secondi e due minuti di attesa.
     */
    val proxyPath: String? = null,
    /** Il JPG gemello di un DNG: stesso scatto, ma visualizzabile. */
    val previewPath: String? = null,
    val sizeBytes: Long = 0,
) {
    val isVideo: Boolean get() = kind == MediaKind.VIDEO

    /** Ciò che il telefono sa disegnare da solo. Il DNG no, e infatti ha il gemello JPG. */
    val renderable: Boolean
        get() = isVideo || extension in RENDERABLE_IMAGE_EXTENSIONS

    /** Il percorso da usare per mostrarlo: per un DNG è il gemello, se c'è. */
    val displayPath: String get() = previewPath ?: path

    fun url(host: String): String = "http://$host$path"

    fun displayUrl(host: String): String = "http://$host$displayPath"

    fun proxyUrl(host: String): String? = proxyPath?.let { "http://$host$it" }

    companion object {
        val RENDERABLE_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "insp")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "dng", "insp", "webp")
        val VIDEO_EXTENSIONS = setOf("mp4", "mov")
    }
}
