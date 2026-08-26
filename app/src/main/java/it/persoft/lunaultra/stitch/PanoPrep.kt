package it.persoft.lunaultra.stitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Il lavoro preparatorio di una panoramica, messo da parte su disco.
 *
 * Allineare nove fotogrammi sono trenta secondi di calcolo, e sono trenta secondi che non
 * cambiano: le foto sono quelle, le posizioni pure. Rifarli ogni volta che si riapre un lavoro
 * per spostare il centro di due gradi e` lavoro buttato, e si sente — perche` fra il dito e
 * l'immagine ci si mette una barra di avanzamento.
 *
 * Qui c'e` il risultato di quel lavoro: dove sta ogni fotogramma sulla sfera, com'e` esposto, e
 * una copia piccola di ciascuno. Piccola davvero — l'anteprima disegna qualche centinaio di
 * pixel di lato, e campionare da una sorgente enorme per farci un francobollo e` solo un modo
 * lento di buttare via dettaglio. Il tutto sta in una cartella intitolata al lavoro, e quando il
 * lavoro se ne va se ne va anche lei: non e` un archivio, e` un appunto.
 */
@Serializable
data class PreparedFrame(
    val label: String,
    /** Il nome del file dell'immagine ridotta, dentro la stessa cartella. */
    val image: String,
    val panDegrees: Float,
    val tiltDegrees: Float,
    val rollDegrees: Float,
    val focalScale: Float,
    val gain: Float,
    val vignetteA: Float,
    val vignetteB: Float,
)

@Serializable
data class PreparedPano(
    val jobId: String,
    /** Il campo visivo orizzontale del fotogramma intero, in gradi. */
    val fovDegrees: Float,
    val spherical: Boolean,
    val frames: List<PreparedFrame>,
)

/** Dove vive l'appunto di un lavoro: una cartella per lavoro, dentro i file privati dell'app. */
object PanoPrepStore {

    private const val FOLDER = "anteprime"
    private const val PLAN = "piano.json"
    private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun directory(root: File, jobId: String): File = File(File(root, FOLDER), jobId)

    /**
     * L'immagine con cui il lavoro si presenta nell'elenco.
     *
     * Un elenco di lavori tutti uguali — «9 scatti · 82° di campo» — non dice quale sia quale.
     * Il nome del file non aiuta: chi ha scattato non lo ha mai visto. Quello che si riconosce
     * e` la scena, e la scena e` gia` li`, disegnata: e` l'anteprima appena scelta.
     */
    fun previewFile(root: File, jobId: String): File = File(directory(root, jobId), "anteprima.jpg")

    fun exists(root: File, jobId: String): Boolean =
        File(directory(root, jobId), PLAN).isFile

    fun write(directory: File, plan: PreparedPano) {
        directory.mkdirs()
        File(directory, PLAN).writeText(JSON.encodeToString(PreparedPano.serializer(), plan))
    }

    fun read(directory: File): PreparedPano? = runCatching {
        JSON.decodeFromString(PreparedPano.serializer(), File(directory, PLAN).readText())
    }.getOrNull()

    /** Butta l'appunto: si chiama quando il lavoro sparisce, perche` da solo non serve a niente. */
    fun discard(root: File, jobId: String) {
        runCatching { directory(root, jobId).deleteRecursively() }
    }

    /** E butta tutti quelli che non hanno piu` un lavoro a cui appartenere. */
    fun discardOrphans(root: File, living: Set<String>) {
        runCatching {
            File(root, FOLDER).listFiles()?.forEach { child ->
                if (child.isDirectory && child.name !in living) child.deleteRecursively()
            }
        }
    }
}

/**
 * L'anteprima che vive dell'appunto e non dei fotogrammi aperti.
 *
 * E` la gemella di quella che lo stitcher costruisce a fine allineamento, e disegna con la
 * stessa geometria — il che non e` un dettaglio: se le due divergessero, riaprire un lavoro
 * mostrerebbe una panoramica diversa da quella che si era vista chiudendolo. La differenza sta
 * tutta in dove prende i pixel: quella campiona dalle copie di lavoro ancora in memoria, questa
 * dalle miniature sul disco. Per un'anteprima da qualche centinaio di pixel sono la stessa cosa.
 */
class PreparedPreview private constructor(
    private val images: List<Bitmap>,
    private val placements: List<FramePlacement>,
    private val corrections: List<PreparedFrame>,
    private val lens: PinholeLens,
    private val fillNadir: Boolean,
    private val preferred: StitchProjection,
    override val suggested: PanoramaView,
) : PanoramaPreview {

    override fun deformation(view: PanoramaView): PreviewShape {
        val turned = placements.map { it.seenFrom(view) }
        val projection = view.projection ?: projectionThatHolds(
            turned, lens, fillNadir, preferred, view.verticalLimitDegrees, MAX_STRETCH,
        )
        val reach = verticalReachOf(turned, lens, view.verticalLimitDegrees)
        return PreviewShape(
            projection = projection,
            reachDegrees = reach,
            horizontalStretch = horizontalStretchOf(projection, reach),
            verticalStretch = verticalStretchOf(projection, reach),
        )
    }

    override suspend fun paint(view: PanoramaView, longSide: Int): PreviewImage =
        withContext(Dispatchers.Default) {
            val turned = placements.map { it.seenFrom(view) }
            val projection = view.projection ?: projectionThatHolds(
                turned, lens, fillNadir, preferred, view.verticalLimitDegrees, MAX_STRETCH,
            )
            val canvas = PanoramaCanvas.covering(
                placements = turned,
                lens = lens,
                requestedPixelsPerDegree = DENSITY,
                maximumLongSide = longSide.coerceAtLeast(64),
                projection = projection,
                verticalLimitDegrees = view.verticalLimitDegrees,
            )
            val width = canvas.width
            val height = canvas.height
            val colour = IntArray(width * height)
            val weight = FloatArray(width * height)

            for ((index, placement) in turned.withIndex()) {
                currentCoroutineContext().ensureActive()
                val image = images[index]
                val correction = corrections[index]
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                val halfW = image.width / 2f
                val halfH = image.height / 2f
                val invNorm = 1f / (halfW * halfW + halfH * halfH)
                val lonSin = FloatArray(width)
                val lonCos = FloatArray(width)
                for (col in 0 until width) {
                    val delta = (canvas.longitudeAt(col) - placement.effectivePan).toRadians()
                    lonSin[col] = sin(delta)
                    lonCos[col] = cos(delta)
                }
                for (row in 0 until height) {
                    val projector = FrameProjector(placement, lens, null)
                    projector.row(canvas.latitudeAt(row))
                    val base = row * width
                    for (col in 0 until width) {
                        projector.project(lonSin[col], lonCos[col])
                        if (!projector.inside) continue
                        val here = featherWeight(projector.x, projector.y, image.width, image.height)
                        if (here <= weight[base + col]) continue
                        val x = projector.x.toInt().coerceIn(0, image.width - 1)
                        val y = projector.y.toInt().coerceIn(0, image.height - 1)
                        val sampled = pixels[y * image.width + x]
                        val dx = projector.x - halfW
                        val dy = projector.y - halfH
                        val r2 = (dx * dx + dy * dy) * invNorm
                        val vignette = (1f + correction.vignetteA * r2 + correction.vignetteB * r2 * r2)
                            .coerceAtLeast(VIGNETTE_FLOOR)
                        val factor = correction.gain / vignette
                        val r = (factor * ((sampled shr 16) and 0xFF)).roundToInt().coerceIn(0, 255)
                        val g = (factor * ((sampled shr 8) and 0xFF)).roundToInt().coerceIn(0, 255)
                        val b = (factor * (sampled and 0xFF)).roundToInt().coerceIn(0, 255)
                        weight[base + col] = here
                        colour[base + col] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                    }
                }
            }
            PreviewImage(
                bitmap = Bitmap.createBitmap(colour, width, height, Bitmap.Config.ARGB_8888),
                horizontalDegrees = canvas.horizontalDegrees,
                verticalDegrees = canvas.latitudeAt(0) - canvas.latitudeAt(height - 1),
            )
        }

    override suspend fun save(directory: File): Boolean = true

    companion object {
        /** La densità che si chiede: altissima apposta, così a decidere è il lato lungo. */
        private const val DENSITY = 10_000f

        /** Lo stesso limite di stiramento della cucitura: la regola dev'essere una sola. */
        private const val MAX_STRETCH = 2.5f

        /** Lo stesso pavimento della vignettatura del percorso vero: sotto, si spegne. */
        private const val VIGNETTE_FLOOR = 0.4f

        /**
         * Riapre un appunto. Torna null se manca qualcosa: un appunto rotto non e` un guasto,
         * e` solo un lavoro da preparare di nuovo.
         */
        fun open(directory: File, startFrom: PanoramaView): PreparedPreview? = runCatching {
            val plan = PanoPrepStore.read(directory) ?: return null
            if (plan.frames.isEmpty()) return null
            val images = plan.frames.map { frame ->
                BitmapFactory.decodeFile(File(directory, frame.image).absolutePath) ?: return null
            }
            val lens = PinholeLens(images.first().width, images.first().height, plan.fovDegrees)
            PreparedPreview(
                images = images,
                placements = plan.frames.map {
                    FramePlacement(
                        panDegrees = it.panDegrees,
                        tiltDegrees = it.tiltDegrees,
                        rollDegrees = it.rollDegrees,
                        focalScale = it.focalScale,
                    )
                },
                corrections = plan.frames,
                lens = lens,
                fillNadir = plan.spherical,
                preferred = StitchProjection.EQUIRECTANGULAR,
                suggested = startFrom,
            )
        }.getOrNull()

        /** Scrive una miniatura: piccola quanto serve all'anteprima, e non un pixel di piu`. */
        fun writeThumbnail(source: Bitmap, target: File, longSide: Int) {
            val scale = min(longSide.toFloat() / source.width, longSide.toFloat() / source.height)
                .coerceAtMost(1f)
            val width = (source.width * scale).roundToInt().coerceAtLeast(1)
            val height = (source.height * scale).roundToInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(source, width, height, true)
            FileOutputStream(target).use { out ->
                small.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
            }
            if (small !== source) small.recycle()
        }

        /** Il lato lungo delle miniature: il doppio dell'anteprima, per avere margine. */
        const val THUMBNAIL_LONG_SIDE = 720
        private const val THUMBNAIL_QUALITY = 88
    }
}
