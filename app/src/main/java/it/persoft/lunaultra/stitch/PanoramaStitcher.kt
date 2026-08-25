package it.persoft.lunaultra.stitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Uno scatto della panoramica: il file sul telefono e dove guardava la camera. */
data class PanoramaShot(
    val file: File,
    val panDegrees: Float,
    val tiltDegrees: Float,
    val label: String,
)

/** Cosa è successo durante l'unione, per chi guarda e per il log. */
data class StitchReport(
    val frames: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val coverageHorizontalDegrees: Float,
    val coverageVerticalDegrees: Float,
    val refinements: List<String>,
    val worstCorrectionDegrees: Float,
    /** Righe inventate in fondo per chiudere il buco sotto: zero se non ce n'era bisogno. */
    val nadirPatchRows: Int = 0,
)

data class StitchOutcome(val bitmap: Bitmap, val report: StitchReport)

/**
 * L'unione delle foto di una panoramica, fatta sul telefono.
 *
 * Il procedimento ha tre parti, e la prima è quella che rende diverso questo da uno stitcher
 * generico. Uno stitcher generico non sa dove sono state scattate le foto e deve dedurlo
 * cercando corrispondenze fra tutte le coppie: è la parte fragile, quella che fallisce davanti
 * a un muro uniforme o a un motivo ripetuto. Qui la posizione di ogni scatto è nota in gradi,
 * perché è l'app che ha mosso il gimbal, e il campo visivo dell'obiettivo è noto anche lui.
 * Quindi si parte già allineati, e alle immagini resta solo il residuo.
 *
 * **Deformazione.** Ogni fotogramma è una proiezione piana di un pezzo di sfera: i bordi sono
 * stirati rispetto al centro tanto più quanto il campo è largo. Ogni pixel della tela finita
 * viene riportato indietro attraverso la focale fino al pixel del fotogramma che lo guarda, e
 * campionato con interpolazione bilineare. È qui che le foto vengono "riformate in base
 * all'obiettivo": non è un ritocco, è la geometria della lente applicata al contrario.
 *
 * **Raffinatura.** La posizione nominale non è esatta — il gimbal naviga a stima e sbaglia di
 * gradi — e la correzione si misura registrando ogni fotogramma sul vicino già sistemato,
 * dalla nebbia al dettaglio: correlazione normalizzata sulla piramide di luminanza, prima a un
 * ottavo della risoluzione con la finestra larga quanto tutto l'errore possibile, poi sempre
 * più fine con finestre sempre più strette. Al livello sfocato i motivi ripetuti — le foglie
 * di una palma a ventaglio — sono una massa unica e non ingannano; al livello fine resta solo
 * la rifinitura. È l'ordine con cui lavorano i programmi seri, Autopano compreso.
 *
 * **Fusione.** Multibanda, lo «spline» di Autopano ([MultibandBlender]): ogni pixel appartiene
 * al fotogramma che lì è più a casa sua, il dettaglio fino cambia mano in un taglio netto —
 * così un oggetto mosso viene tagliato, non stampato due volte — e i toni larghi si spalmano
 * su decine di pixel, dove l'occhio non li vede. La parallasse fra vicino e lontano non si
 * annulla con nessuna rotazione: si nasconde, ed è questo il modo in cui la nascondono tutti.
 */
class PanoramaStitcher(
    private val onProgress: (Float, String) -> Unit = { _, _ -> },
) {

    suspend fun stitch(
        shots: List<PanoramaShot>,
        horizontalFovDegrees: Float,
        /** Riempi il buco sotto: serve agli scatti sferici, dove il gimbal non arriva al nadir. */
        fillNadir: Boolean = false,
        /**
         * Ricerca larga: gli angoli dati sono un'ipotesi, non una misura.
         *
         * È il caso dell'unione manuale, dove il passo fra le foto è tirato a indovinare: la
         * finestra di ricerca copre quasi tutto il campo visivo invece dei gradi di deriva di
         * un gimbal calibrato. Costa di più, e per le foto fatte a mano è l'unico modo.
         */
        wideSearch: Boolean = false,
    ): Result<StitchOutcome> = withContext(Dispatchers.Default) {
        runCatching {
            require(shots.size >= 2) { "Servono almeno due scatti per unire una panoramica" }

            // La qualità si adatta al telefono, non al minimo comune: la heap che Android
            // concede all'app decide la risoluzione di lavoro e la grandezza della tela. I
            // Bitmap vivono in memoria nativa, fuori dal tetto; i vettori di lavoro in heap
            // Java, e vivono un fotogramma alla volta.
            val heapMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt()
            val workingLongSide = when {
                heapMb >= 384 -> GENEROUS_WORKING_LONG_SIDE
                heapMb >= 256 -> 2_400
                else -> WORKING_LONG_SIDE
            }
            onProgress(0.02f, "Leggo gli scatti ($workingLongSide px, heap $heapMb MB)")
            val frames = loadFrames(shots, workingLongSide)
            val first = frames.first().bitmap
            val lens = PinholeLens(first.width, first.height, horizontalFovDegrees)

            var placements = shots.map { FramePlacement(it.panDegrees, it.tiltDegrees) }
            // La cucitura campiona dagli originali a piena risoluzione (uno per volta, in
            // memoria nativa): la copia di lavoro serve solo all'allineamento. Quindi la
            // tela può chiedere la densità dei pixel veri, non quella della copia ridotta.
            val sourceScale = frames.minOf { frame ->
                if (frame.sourceLongSide > 0) {
                    frame.sourceLongSide.toFloat() / max(frame.width, frame.height)
                } else {
                    1f
                }
            }.coerceAtLeast(1f)
            val fullResSampling = heapMb >= 384 && sourceScale > 1.05f
            val requestedDensity = lens.imageWidth / lens.horizontalFovDegrees *
                (if (fullResSampling) sourceScale else 1f)
            // La densità della tela non è più un numero fisso: si calcola dal budget di
            // memoria vero, sapendo quanto costerà la cucitura di ogni fotogramma con la
            // fusione ristretta alla sola sovrapposizione. È quello che decide quanto
            // grande esce la panoramica.
            val density = chooseDensity(placements, lens, heapMb, requestedDensity)
            val canvas = PanoramaCanvas.covering(
                placements = placements,
                lens = lens,
                requestedPixelsPerDegree = density,
                maximumLongSide = CANVAS_HARD_CAP_LONG_SIDE,
            )

            onProgress(0.10f, "Allineo i fotogrammi")
            val refineStartedAt = System.currentTimeMillis()
            val refinement = refine(frames, placements, lens, canvas, wideSearch)
            val refineSeconds = (System.currentTimeMillis() - refineStartedAt) / 1000f
            placements = refinement.placements
            frames.forEach { it.releaseWorkingData() }

            onProgress(0.35f, "Unisco e sfumo le giunzioni")
            val composeDetail = mutableListOf<String>()
            val composeStartedAt = System.currentTimeMillis()
            var bitmap = compose(
                frames, placements, lens, canvas, refinement.aligned,
                fullResSampling, refinement.photometric, composeDetail,
            )
            val composeSeconds = (System.currentTimeMillis() - composeStartedAt) / 1000f
            val patchedRows = if (fillNadir) {
                onProgress(0.97f, "Chiudo il buco sotto")
                fillNadirHole(bitmap)
            } else {
                0
            }

            // I bordi neri non sono la panoramica: sono la tela rettangolare attorno a una
            // copertura che non lo è. Si ritagliano, tranne che sulla sferica: lì la tela 2:1
            // È il formato, e chi la guarda a 360° la vuole intera.
            val notes = refinement.notes.toMutableList()
            notes.add(
                0,
                "Tela ${canvas.width}×${canvas.height} a %.1f px/grado (heap $heapMb MB) · ".format(canvas.pixelsPerDegree) +
                    if (fullResSampling) {
                        "cucitura dagli originali a piena risoluzione (×%.1f rispetto ai %d px di lavoro)"
                            .format(sourceScale, workingLongSide)
                    } else {
                        "cucitura dalla copia di lavoro a $workingLongSide px"
                    },
            )
            notes += composeDetail
            notes += "Tempi: allineamento %.0f s · cucitura %.0f s".format(refineSeconds, composeSeconds)
            if (!fillNadir) {
                onProgress(0.98f, "Ritaglio il nero ai bordi")
                val before = "${bitmap.width}×${bitmap.height}"
                bitmap = cropBlackEdges(
                    bitmap,
                    allowColumns = canvas.horizontalDegrees < PanoramaCanvas.FULL_TURN_DEGREES - 0.5f,
                )
                if ("${bitmap.width}×${bitmap.height}" != before) {
                    notes += "Ritaglio: da $before a ${bitmap.width}×${bitmap.height}, via il nero ai bordi"
                }
            }

            frames.forEach { it.bitmap.recycle() }
            onProgress(1f, "Panoramica pronta")
            StitchOutcome(
                bitmap = bitmap,
                report = StitchReport(
                    frames = frames.size,
                    canvasWidth = bitmap.width,
                    canvasHeight = bitmap.height,
                    coverageHorizontalDegrees = canvas.horizontalDegrees,
                    coverageVerticalDegrees = canvas.verticalDegrees,
                    refinements = notes,
                    worstCorrectionDegrees = refinement.worstCorrection,
                    nadirPatchRows = patchedRows,
                ),
            )
        }
    }

    /**
     * Quanti pixel per grado può permettersi la tela, dai conti e non da un numero fisso.
     *
     * La memoria della cucitura è prevedibile prima di cominciare: per ogni fotogramma
     * servono i pesi dell'intera finestra (4 byte a pixel) e la fusione multibanda solo sul
     * rettangolo che racchiude le sovrapposizioni con i fotogrammi già cuciti (~64 byte a
     * pixel), più la mappa dei possessori sull'intera tela (1 byte a pixel). Tutte queste
     * aree crescono col quadrato della densità: si prende il fotogramma più caro, si divide
     * il budget, e la radice è la densità. Le sovrapposizioni si stimano dagli angoli
     * pianificati, allargati di [OVERLAP_SLACK_DEGREES] perché la rifinitura li sposterà.
     */
    private fun chooseDensity(
        placements: List<FramePlacement>,
        lens: PinholeLens,
        heapMb: Int,
        requested: Float,
    ): Float {
        val wH = lens.horizontalFovDegrees + 2f * BBOX_MARGIN_DEGREES
        val wV = lens.verticalFovDegrees + 2f * BBOX_MARGIN_DEGREES
        val slackH = wH / 2f + OVERLAP_SLACK_DEGREES
        val slackV = wV / 2f + OVERLAP_SLACK_DEGREES

        // Il fotogramma più caro: finestra piena + rettangolo delle sue sovrapposizioni.
        var worstBlendArea = 0f
        for (k in placements.indices) {
            var o0 = Float.MAX_VALUE
            var o1 = -Float.MAX_VALUE
            var o2 = Float.MAX_VALUE
            var o3 = -Float.MAX_VALUE
            for (j in 0 until k) {
                val dx = slackH * 2f - abs(placements[k].effectivePan - placements[j].effectivePan)
                val dy = slackV * 2f - abs(placements[k].effectiveTilt - placements[j].effectiveTilt)
                if (dx <= 0f || dy <= 0f) continue
                val ix0 = max(placements[k].effectivePan - slackH, placements[j].effectivePan - slackH)
                val ix1 = min(placements[k].effectivePan + slackH, placements[j].effectivePan + slackH)
                val iy0 = max(placements[k].effectiveTilt - slackV, placements[j].effectiveTilt - slackV)
                val iy1 = min(placements[k].effectiveTilt + slackV, placements[j].effectiveTilt + slackV)
                if (ix0 < o0) o0 = ix0
                if (ix1 > o1) o1 = ix1
                if (iy0 < o2) o2 = iy0
                if (iy1 > o3) o3 = iy1
            }
            val area = if (o1 > o0) (o1 - o0) * (o3 - o2) else 0f
            if (area > worstBlendArea) worstBlendArea = area
        }

        val spanH = min(
            placements.maxOf { it.effectivePan } - placements.minOf { it.effectivePan } + wH,
            PanoramaCanvas.FULL_TURN_DEGREES,
        )
        val spanV = min(
            placements.maxOf { it.effectiveTilt } - placements.minOf { it.effectiveTilt } + wV,
            180f,
        )
        val ownerArea = spanH * spanV

        // Il budget: metà scarsa della heap, meno i pixel del fotogramma in lavorazione.
        val frameBytes = lens.imageWidth.toLong() * lens.imageHeight * 4L
        val budget = (heapMb.toLong() * 1024L * 1024L * 45L / 100L) - frameBytes - 32L * 1024L * 1024L
        // I pesi del fotogramma sono un byte a pixel; la fusione a scala ridotta pesa una
        // frazione; la mappa dei possessori un byte ogni quattro pixel di tela.
        val perDensitySquared = wH * wV * 1f +
            worstBlendArea * BLEND_PREDICTED_BYTES_PER_PX +
            ownerArea / (OWNER_SCALE * OWNER_SCALE)
        val affordable = if (budget > 0 && perDensitySquared > 0f) {
            kotlin.math.sqrt(budget / perDensitySquared.toDouble()).toFloat()
        } else {
            0f
        }

        // Mai sotto quello che i vecchi tetti fissi avrebbero concesso: il calcolo può solo
        // migliorare le cose, non peggiorarle.
        val legacyCap = when {
            heapMb >= 384 -> 8_000
            heapMb >= 256 -> 6_000
            else -> MAX_CANVAS_LONG_SIDE
        }
        val legacyFloor = min(requested, legacyCap / max(spanH, spanV))
        return min(requested, max(affordable, legacyFloor))
    }

    /** Un livello della piramide di luminanza: i dati e la sua misura. */
    private class GrayLevel(val data: FloatArray, val width: Int, val height: Int)

    private class Frame(
        val bitmap: Bitmap,
        val label: String,
        val file: java.io.File? = null,
        /** Il lato lungo dell'originale su disco: dice quanta risoluzione esiste davvero. */
        val sourceLongSide: Int = 0,
    ) {
        val width get() = bitmap.width
        val height get() = bitmap.height

        private var pixelsCache: IntArray? = null
        private var grayCache: FloatArray? = null
        private var levelsCache: List<GrayLevel>? = null

        private var fullCache: Bitmap? = null
        var fullScaleX = 1f
            private set
        var fullScaleY = 1f
            private set

        /**
         * L'originale a piena risoluzione, aperto solo mentre lo si cuce.
         *
         * L'allineamento lavora sulla copia ridotta — gli basta e avanza — ma la cucitura
         * campiona da qui: è la differenza fra una panoramica grande coi pixel veri e una
         * gonfiata. Vive in memoria nativa (fuori dalla heap), uno per volta, e si chiude
         * appena il fotogramma è cucito. Se la decodifica fallisce si resta sulla ridotta.
         */
        fun openFullResolution(): Bitmap? {
            fullCache?.let { return it }
            val source = file ?: return null
            val decoded = runCatching {
                val raw = BitmapFactory.decodeFile(source.absolutePath) ?: return null
                it.persoft.lunaultra.media.applyExifOrientation(
                    raw,
                    androidx.exifinterface.media.ExifInterface(source),
                )
            }.getOrNull() ?: return null
            fullScaleX = decoded.width.toFloat() / width
            fullScaleY = decoded.height.toFloat() / height
            fullCache = decoded
            return decoded
        }

        fun closeFullResolution() {
            fullCache?.recycle()
            fullCache = null
        }

        /**
         * I pixel come vettore, per il campionamento veloce della fusione.
         *
         * Il Bitmap tiene i suoi pixel in memoria nativa, fuori dal tetto che Android impone
         * alla heap dell'app: lì possono stare tutti insieme. Questo vettore invece è heap
         * Java, e per questo vive solo finché serve — un fotogramma alla volta.
         */
        val pixels: IntArray
            get() = pixelsCache ?: IntArray(width * height).also {
                bitmap.getPixels(it, 0, width, 0, 0, width, height)
                pixelsCache = it
            }

        /** La luminanza, calcolata una volta: l'allineamento lavora qui sopra. */
        val gray: FloatArray
            get() = grayCache ?: run {
                val source = pixels
                FloatArray(source.size) { i ->
                    val c = source[i]
                    0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                }.also { grayCache = it }
            }

        /**
         * Libera i vettori di heap Java. Il Bitmap resta: è lui la copia buona, in memoria
         * nativa, e tutto qui dentro si ricostruisce da lui quando serve di nuovo.
         */
        fun releaseWorkingData() {
            pixelsCache = null
            grayCache = null
            levelsCache = null
        }

        /**
         * La piramide della luminanza: ogni livello dimezza.
         *
         * È la difesa contro i motivi ripetuti. A piena risoluzione le foglie di una palma a
         * ventaglio sono un pettine: spostandosi di una foglia l'immagine combacia di nuovo, e
         * qualunque confronto locale può agganciarsi alla foglia sbagliata con grande
         * convinzione. A un ottavo della risoluzione il pettine è una massa unica, e resta solo
         * il combaciamento vero. La ricerca parte da lì e si raffina scendendo.
         */
        val grayLevels: List<GrayLevel>
            get() = levelsCache ?: buildLevels().also { levelsCache = it }

        private fun buildLevels(): List<GrayLevel> {
            val levels = ArrayList<GrayLevel>(PYRAMID_LEVELS)
            levels += GrayLevel(gray, width, height)
            while (levels.size < PYRAMID_LEVELS) {
                val prev = levels.last()
                val nw = (prev.width + 1) / 2
                val nh = (prev.height + 1) / 2
                val data = FloatArray(nw * nh)
                for (y in 0 until nh) {
                    val sy = min(y * 2, prev.height - 1)
                    val sy1 = min(sy + 1, prev.height - 1)
                    for (x in 0 until nw) {
                        val sx = min(x * 2, prev.width - 1)
                        val sx1 = min(sx + 1, prev.width - 1)
                        data[y * nw + x] = (
                            prev.data[sy * prev.width + sx] + prev.data[sy * prev.width + sx1] +
                                prev.data[sy1 * prev.width + sx] + prev.data[sy1 * prev.width + sx1]
                            ) / 4f
                    }
                }
                levels += GrayLevel(data, nw, nh)
            }
            return levels
        }
    }

    /**
     * Legge gli scatti già ridotti alla misura di lavoro.
     *
     * Una foto da otto megapixel per sei scatti non entra in memoria insieme alla tela, e non
     * servirebbe: la tela ha un tetto, e campionare da un originale molto più fitto del
     * risultato è tempo speso per buttare via dettaglio subito dopo. Il ridimensionamento lo fa
     * il decodificatore, che salta i pixel invece di leggerli e scartarli.
     */
    private suspend fun loadFrames(
        shots: List<PanoramaShot>,
        workingLongSide: Int,
    ): List<Frame> = coroutineScope {
        shots.map { shot ->
            async(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(shot.file.absolutePath, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, workingLongSide)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val raw = BitmapFactory.decodeFile(shot.file.absolutePath, options)
                    ?: throw IllegalStateException("Non riesco a leggere ${shot.file.name}")
                // Le foto del telefono in verticale arrivano coricate con l'EXIF che dice di
                // girarle: il decodificatore lo ignora, e un fotogramma sdraiato manda a
                // monte l'unione.
                val decoded = runCatching {
                    it.persoft.lunaultra.media.applyExifOrientation(
                        raw,
                        androidx.exifinterface.media.ExifInterface(shot.file),
                    )
                }.getOrDefault(raw)
                // Il decodificatore riduce solo per potenze di due: una foto da 4000 pixel
                // scende a 2000, non a 1600, e quel 25% in più di lato è il 56% in più di
                // memoria — moltiplicato per gli scatti è la differenza fra unire e morire.
                val longSide = max(decoded.width, decoded.height)
                val bitmap = if (longSide > workingLongSide) {
                    val scale = workingLongSide.toFloat() / longSide
                    val scaled = Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).roundToInt().coerceAtLeast(1),
                        (decoded.height * scale).roundToInt().coerceAtLeast(1),
                        true,
                    )
                    decoded.recycle()
                    scaled
                } else {
                    decoded
                }
                Frame(bitmap, shot.label, shot.file, max(bounds.outWidth, bounds.outHeight))
            }
        }.awaitAll()
    }

    private class Refinement(
        val placements: List<FramePlacement>,
        val notes: List<String>,
        val worstCorrection: Float,
        /** Chi è stato allineato davvero: chi no, ha una posizione di fiducia, non misurata. */
        val aligned: BooleanArray,
        /**
         * I campioni fotometrici dai punti di controllo:
         * [indice fisso, indice mobile, luma fisso, luma mobile, r² fisso, r² mobile].
         * Lo stesso punto del mondo visto da due foto a raggi diversi: è quello che
         * permette di separare l'esposizione dalla vignettatura.
         */
        val photometric: List<FloatArray>,
    )

    /**
     * Corregge la posizione di ogni fotogramma con la registrazione a piramide.
     *
     * Ogni fotogramma, dopo il primo, viene confrontato con il vicino già sistemato nella zona
     * in cui i due si sovrappongono. Il confronto è una correlazione normalizzata su un
     * campione di punti — indifferente all'esposizione — e la ricerca è dalla nebbia al
     * dettaglio: prima su una versione a un ottavo, dove un errore di otto gradi si vede e i
     * motivi ripetuti non ingannano, poi via via più fine su versioni più nitide, con
     * finestre sempre più strette. È il modo dei programmi seri, ed è il contrario di una
     * ricerca locale a piena risoluzione, che su una palma a ventaglio si aggancia alla foglia
     * sbagliata con grande convinzione — misurato, e si vedeva.
     */
    private suspend fun refine(
        frames: List<Frame>,
        initial: List<FramePlacement>,
        lens: PinholeLens,
        canvas: PanoramaCanvas,
        wideSearch: Boolean,
    ): Refinement {
        val placements = initial.toMutableList()
        val notes = mutableListOf<String>()
        val aligned = BooleanArray(frames.size)
        aligned[0] = true
        var worst = 0f
        val photometric = mutableListOf<FloatArray>()

        for (index in 1 until frames.size) {
            currentCoroutineContext().ensureActive()
            onProgress(0.10f + 0.25f * index / frames.size, "Allineo ${frames[index].label}")

            // I vicini già sistemati più vicini in angolo: quello di fianco e, se c'è, quello
            // della fila accanto — così le file della griglia si richiudono fra loro.
            val anchors = (0 until index)
                .map { anchor ->
                    anchor to angularDistance(
                        placements[anchor].effectivePan,
                        placements[anchor].effectiveTilt,
                        placements[index].panDegrees,
                        placements[index].tiltDegrees,
                    )
                }
                .filter { (_, distance) -> distance < max(lens.horizontalFovDegrees, lens.verticalFovDegrees) }
                .sortedBy { (_, distance) -> distance }
                .take(MAX_ANCHORS)
                .map { (anchor, _) -> anchor }
            if (anchors.isEmpty()) continue

            val results = anchors.mapNotNull { anchor ->
                registerPair(
                    moving = frames[index],
                    fixed = frames[anchor],
                    movingPlacement = placements[index],
                    fixedPlacement = placements[anchor],
                    lens = lens,
                    wideSearch = wideSearch,
                )
            }
            if (results.isEmpty()) {
                notes += "${frames[index].label}: sovrapposizione troppo povera, resta dov'era"
                continue
            }

            // Due vicini concordi si mediano; discordi, vince il più sicuro di sé.
            val offset = if (results.size == 2 &&
                abs(results[0].panDegrees - results[1].panDegrees) < ANCHOR_AGREEMENT_DEGREES &&
                abs(results[0].tiltDegrees - results[1].tiltDegrees) < ANCHOR_AGREEMENT_DEGREES
            ) {
                Offset(
                    (results[0].panDegrees + results[1].panDegrees) / 2f,
                    (results[0].tiltDegrees + results[1].tiltDegrees) / 2f,
                    max(results[0].confidence, results[1].confidence),
                )
            } else {
                results.maxByOrNull { it.confidence }!!
            }

            // La rifinitura finale la fanno i punti di controllo: con il piazzamento globale
            // già trovato dalla piramide, ogni punto cerca solo in un raggio piccolo — più
            // piccolo del passo di una foglia — e la foglia sbagliata non è più raggiungibile.
            val corrected = placements[index].copy(
                panCorrectionDegrees = offset.panDegrees,
                tiltCorrectionDegrees = offset.tiltDegrees,
            )
            var candidates = 0
            val kept = mutableListOf<FloatArray>()
            anchors.forEach { anchor ->
                val tally = controlPoints(
                    moving = frames[index],
                    fixed = frames[anchor],
                    movingPlacement = corrected,
                    fixedPlacement = placements[anchor],
                    lens = lens,
                )
                candidates += tally.candidates
                kept += tally.kept
                // I campioni per la fotometria: si scartano i punti vicini alla saturazione,
                // dove il rapporto di luminanza non dice più niente di vero.
                tally.kept.forEach { p ->
                    if (p[4] in 12f..242f && p[5] in 12f..242f) {
                        photometric += floatArrayOf(anchor.toFloat(), index.toFloat(), p[4], p[5], p[6], p[7])
                    }
                }
            }
            var finalPan = offset.panDegrees
            var finalTilt = offset.tiltDegrees
            var rollDegrees = 0f
            var focalScale = 1f
            if (kept.size >= CONTROL_MIN_KEPT) {
                // Il piccolo bundle adjustment: dai punti di controllo si stimano insieme
                // spostamento, rollio e scala della focale — è quello che fanno gli
                // stitcher seri, ed è quello che raddrizza una foto scattata storta.
                val fit = fitPlacement(kept)
                if (fit != null) {
                    finalPan += fit[0]
                    finalTilt += fit[1]
                    rollDegrees = fit[2]
                    focalScale = fit[3]
                } else {
                    finalPan += trimmedMean(kept.map { it[0] })
                    finalTilt += trimmedMean(kept.map { it[1] })
                }
            }

            aligned[index] = true
            placements[index] = placements[index].copy(
                panCorrectionDegrees = finalPan,
                tiltCorrectionDegrees = finalTilt,
                rollDegrees = rollDegrees,
                focalScale = focalScale,
            )
            // I fotogrammi ormai lontani non faranno più da vicini a nessuno: i loro vettori
            // di lavoro si liberano, così una griglia grande non accumula piramidi.
            for (past in 0 until index) {
                val distance = angularDistance(
                    placements[past].effectivePan,
                    placements[past].effectiveTilt,
                    placements[index].effectivePan,
                    placements[index].effectiveTilt,
                )
                if (distance > 2f * max(lens.horizontalFovDegrees, lens.verticalFovDegrees)) {
                    frames[past].releaseWorkingData()
                }
            }

            val magnitude = max(abs(finalPan), abs(finalTilt))
            worst = max(worst, magnitude)
            notes += ("%s: %d punti di controllo, %d sopra l'%d%% · corretto %+.2f° / %+.2f° · " +
                "rollio %+.2f° · focale ×%.3f · concordanza %.0f%%").format(
                frames[index].label,
                candidates,
                kept.size,
                (CONTROL_KEEP_NCC * 100).toInt(),
                finalPan,
                finalTilt,
                rollDegrees,
                focalScale,
                offset.confidence * 100f,
            )
        }
        return Refinement(placements, notes, worst, aligned, photometric)
    }

    private class ControlPointTally(val candidates: Int, val kept: List<FloatArray>)

    /**
     * Il piccolo bundle adjustment di un fotogramma, come lo fanno gli stitcher seri.
     *
     * Ogni punto di controllo porta il suo residuo (rLon, rLat) e la sua posizione (u, v)
     * rispetto al centro del fotogramma mobile. Un puro spostamento muove tutti i punti
     * dello stesso vettore; una rotazione attorno all'asse ottico li muove tangenzialmente
     * (−v, +u)·R; un errore di focale radialmente (−u, −v)·d. Si risolvono insieme ai
     * minimi quadrati — è la linearizzazione per piccoli angoli del Levenberg–Marquardt di
     * Hugin/Autopano — con una passata di potatura dei fuori posto. Restituisce
     * [dPan, dTilt, rollio in gradi, scala focale], o null se il sistema non è affidabile.
     */
    private fun fitPlacement(points: List<FloatArray>): FloatArray? {
        var current = points
        var solution: DoubleArray? = null
        repeat(2) { round ->
            val sol = solvePlacementLeastSquares(current) ?: return@repeat
            solution = sol
            if (round == 0) {
                val residuals = current.map { p -> placementResidual(p, sol) }
                val median = residuals.sorted()[residuals.size / 2]
                val filtered = current.filterIndexed { i, _ -> residuals[i] <= 2.5f * median + 1e-4f }
                if (filtered.size >= CONTROL_MIN_KEPT) current = filtered
            }
        }
        val sol = solution ?: return null
        val rollDeg = Math.toDegrees(sol[2]).toFloat()
        val scaleAdjust = sol[3].toFloat()
        // Valori fuori dal credibile per una camera su gimbal: meglio la sola traslazione
        // che una rotazione inventata da punti cattivi.
        if (abs(rollDeg) > MAX_ROLL_DEGREES || abs(scaleAdjust) > MAX_FOCAL_ADJUST) return null
        return floatArrayOf(sol[0].toFloat(), sol[1].toFloat(), rollDeg, 1f + scaleAdjust)
    }

    /** Le equazioni normali 4×4 del modello, risolte con eliminazione di Gauss. */
    private fun solvePlacementLeastSquares(points: List<FloatArray>): DoubleArray? {
        val n = DoubleArray(16)
        val t = DoubleArray(4)
        val row = DoubleArray(4)
        for (p in points) {
            val u = p[2].toDouble()
            val v = p[3].toDouble()
            // Equazione della longitudine: rLon = a − v·R − u·d
            row[0] = 1.0; row[1] = 0.0; row[2] = -v; row[3] = -u
            accumulate(n, t, row, p[0].toDouble())
            // Equazione della latitudine: rLat = b + u·R − v·d
            row[0] = 0.0; row[1] = 1.0; row[2] = u; row[3] = -v
            accumulate(n, t, row, p[1].toDouble())
        }
        return solve4x4(n, t)
    }

    private fun accumulate(n: DoubleArray, t: DoubleArray, row: DoubleArray, y: Double) {
        for (i in 0 until 4) {
            t[i] += row[i] * y
            for (j in 0 until 4) n[i * 4 + j] += row[i] * row[j]
        }
    }

    private fun solve4x4(matrix: DoubleArray, vector: DoubleArray): DoubleArray? {
        val a = matrix.copyOf()
        val b = vector.copyOf()
        for (col in 0 until 4) {
            var pivot = col
            for (r in col + 1 until 4) {
                if (abs(a[r * 4 + col]) > abs(a[pivot * 4 + col])) pivot = r
            }
            if (abs(a[pivot * 4 + col]) < 1e-9) return null
            if (pivot != col) {
                for (j in 0 until 4) {
                    val tmp = a[col * 4 + j]; a[col * 4 + j] = a[pivot * 4 + j]; a[pivot * 4 + j] = tmp
                }
                val tmp = b[col]; b[col] = b[pivot]; b[pivot] = tmp
            }
            val diag = a[col * 4 + col]
            for (r in 0 until 4) {
                if (r == col) continue
                val factor = a[r * 4 + col] / diag
                for (j in 0 until 4) a[r * 4 + j] -= factor * a[col * 4 + j]
                b[r] -= factor * b[col]
            }
        }
        return DoubleArray(4) { b[it] / a[it * 4 + it] }
    }

    /**
     * La correzione fotometrica di un fotogramma: guadagno e compensazione di vignettatura.
     *
     * `fattore(x, y) = guadagno / V(r)` con `V(r) = 1 + a·r² + b·r⁴` e `r` normalizzato al
     * semidiagonale. È il modello classico dell'ottimizzazione fotometrica di Autopano:
     * l'esposizione varia da scatto a scatto, la caduta di luce ai bordi è dell'obiettivo
     * ed è uguale per tutti.
     */
    private class FrameCorrection(
        val gain: Float,
        private val vignetteA: Float,
        private val vignetteB: Float,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        private val halfW = frameWidth / 2f
        private val halfH = frameHeight / 2f
        private val invNorm = 1f / (halfW * halfW + halfH * halfH)

        fun factorAt(x: Float, y: Float): Float {
            val dx = x - halfW
            val dy = y - halfH
            val r2 = (dx * dx + dy * dy) * invNorm
            val v = (1f + vignetteA * r2 + vignetteB * r2 * r2).coerceAtLeast(0.4f)
            return gain / v
        }
    }

    private class PhotometricFit(val gains: FloatArray, val vignetteA: Float, val vignetteB: Float)

    /**
     * La stima fotometrica globale, dai campioni dei punti di controllo.
     *
     * Lo stesso punto del mondo visto da due foto: la differenza dei logaritmi delle
     * luminanze è la somma della differenza di esposizione e della differenza di
     * vignettatura ai due raggi. Con tanti punti sparsi si risolvono insieme, ai minimi
     * quadrati, i guadagni di ogni foto (la prima fa da riferimento) e i due coefficienti
     * della caduta ai bordi. È il pezzo che mancava: la catena dei guadagni vedeva solo le
     * mediane e la vignettatura le sembrava esposizione — da lì le bande scure alle
     * giunzioni.
     */
    private fun fitPhotometric(samples: List<FloatArray>, frameCount: Int): PhotometricFit? {
        if (samples.size < PHOTOMETRIC_MIN_SAMPLES) return null
        val present = samples.flatMap { listOf(it[0].toInt(), it[1].toInt()) }.distinct().sorted()
        if (present.size < 2) return null
        val reference = present.first()
        val gainColumn = HashMap<Int, Int>()
        var next = 0
        present.forEach { frame -> if (frame != reference) gainColumn[frame] = next++ }
        val size = next + 2
        val colA = next
        val colB = next + 1

        val n = DoubleArray(size * size)
        val t = DoubleArray(size)
        val row = DoubleArray(size)
        for (s in samples) {
            java.util.Arrays.fill(row, 0.0)
            val fixed = s[0].toInt()
            val movingIdx = s[1].toInt()
            val y = kotlin.math.ln(s[3].toDouble().coerceAtLeast(1.0)) -
                kotlin.math.ln(s[2].toDouble().coerceAtLeast(1.0))
            // y = logG_f − logG_m + a·(r²m − r²f) + b·(r⁴m − r⁴f)
            gainColumn[fixed]?.let { row[it] = 1.0 }
            gainColumn[movingIdx]?.let { row[it] = -1.0 }
            val r2f = s[4].toDouble()
            val r2m = s[5].toDouble()
            row[colA] = r2m - r2f
            row[colB] = r2m * r2m - r2f * r2f
            for (i in 0 until size) {
                if (row[i] == 0.0) continue
                t[i] += row[i] * y
                for (j in 0 until size) n[i * size + j] += row[i] * row[j]
            }
        }
        val solution = solveLinearSystem(size, n, t) ?: return null

        val a = solution[colA].toFloat()
        val b = solution[colB].toFloat()
        if (a !in -VIGNETTE_LIMIT..VIGNETTE_LIMIT || b !in -VIGNETTE_LIMIT..VIGNETTE_LIMIT) return null

        val gains = FloatArray(frameCount) { 1f }
        present.forEach { frame ->
            val logGain = if (frame == reference) 0.0 else solution[gainColumn[frame]!!]
            gains[frame] = kotlin.math.exp(logGain).toFloat().coerceIn(MIN_PHOTO_GAIN, MAX_PHOTO_GAIN)
        }
        // Le foto senza campioni (non allineate) ereditano il guadagno della più vicina
        // fra quelle stimate: meglio di una pezza a guadagno pieno.
        for (frame in 0 until frameCount) {
            if (frame in present) continue
            val nearest = present.minByOrNull { abs(it - frame) } ?: continue
            gains[frame] = gains[nearest]
        }
        return PhotometricFit(gains, a, b)
    }

    /** Eliminazione di Gauss con pivot, per sistemi piccoli di taglia qualunque. */
    private fun solveLinearSystem(size: Int, matrix: DoubleArray, vector: DoubleArray): DoubleArray? {
        val a = matrix.copyOf()
        val b = vector.copyOf()
        for (col in 0 until size) {
            var pivot = col
            for (r in col + 1 until size) {
                if (abs(a[r * size + col]) > abs(a[pivot * size + col])) pivot = r
            }
            if (abs(a[pivot * size + col]) < 1e-9) return null
            if (pivot != col) {
                for (j in 0 until size) {
                    val tmp = a[col * size + j]
                    a[col * size + j] = a[pivot * size + j]
                    a[pivot * size + j] = tmp
                }
                val tmp = b[col]
                b[col] = b[pivot]
                b[pivot] = tmp
            }
            val diag = a[col * size + col]
            for (r in 0 until size) {
                if (r == col) continue
                val factor = a[r * size + col] / diag
                for (j in 0 until size) a[r * size + j] -= factor * a[col * size + j]
                b[r] -= factor * b[col]
            }
        }
        return DoubleArray(size) { b[it] / a[it * size + it] }
    }

    private fun placementResidual(p: FloatArray, sol: DoubleArray): Float {
        val u = p[2].toDouble()
        val v = p[3].toDouble()
        val eLon = p[0] - (sol[0] - v * sol[2] - u * sol[3])
        val eLat = p[1] - (sol[1] + u * sol[2] - v * sol[3])
        return sqrt(eLon * eLon + eLat * eLat).toFloat()
    }

    /**
     * I punti di controllo fra due fotogrammi, alla maniera di Autopano: tanti, e filtrati
     * per qualità.
     *
     * Nel fotogramma fermo si scelgono almeno centocinquanta dettagli con carattere in
     * entrambe le direzioni, sparsi su tutta la sovrapposizione. Ognuno si va a ritrovare
     * nell'altro fotogramma per correlazione piena, in un raggio piccolo attorno a dove il
     * piazzamento globale lo prevede. Si tengono solo i ritrovamenti sopra la soglia di
     * qualità: un punto sotto l'ottanta per cento non è un punto di controllo, è un'opinione.
     * Ogni superstite porta il suo residuo in gradi, e la media potata dei residui è la
     * rifinitura.
     */
    private suspend fun controlPoints(
        moving: Frame,
        fixed: Frame,
        movingPlacement: FramePlacement,
        fixedPlacement: FramePlacement,
        lens: PinholeLens,
    ): ControlPointTally {
        val gray = fixed.gray
        val margin = CONTROL_PATCH_RADIUS + 2
        val searchPx = (lens.focalPixels * CONTROL_SEARCH_DEGREES.toRadians()).coerceAtLeast(6f)

        // Prima i candidati: il punto con più carattere in ogni cella della sovrapposizione.
        // Il passo della griglia si stringe finché i candidati non bastano.
        var step = CONTROL_GRID_STEP
        var picked: List<IntArray> = emptyList()
        while (true) {
            val found = mutableListOf<IntArray>()
            var cy = margin
            while (cy < fixed.height - margin - step) {
                var cx = margin
                while (cx < fixed.width - margin - step) {
                    var bestScore = 0f
                    var bestX = -1
                    var bestY = -1
                    var yy = cy
                    while (yy < cy + step) {
                        var xx = cx
                        while (xx < cx + step) {
                            val i = yy * fixed.width + xx
                            val dx = abs(gray[i + 2] - gray[i - 2])
                            val dy = abs(gray[i + 2 * fixed.width] - gray[i - 2 * fixed.width])
                            val score = min(dx, dy)
                            if (score > bestScore) {
                                bestScore = score
                                bestX = xx
                                bestY = yy
                            }
                            xx += 2
                        }
                        yy += 2
                    }
                    if (bestScore >= CONTROL_MIN_TEXTURE && bestX >= 0) {
                        val world = frameToWorld(bestX.toFloat(), bestY.toFloat(), fixedPlacement, lens)
                        if (projectToFrame(world[0], world[1], movingPlacement, lens).inside) {
                            found += intArrayOf(bestX, bestY)
                        }
                    }
                    cx += step
                }
                cy += step
            }
            picked = found
            if (found.size >= CONTROL_MIN_CANDIDATES || step <= CONTROL_MIN_GRID_STEP) break
            step = (step * 2) / 3
        }

        // La ricerca di ogni punto è indipendente dalle altre: si spartiscono fra i core.
        val movingGray = moving.gray
        val candidates = picked.take(CONTROL_MAX_CANDIDATES)
        val kept = coroutineScope {
            candidates.chunked((candidates.size / MAX_STITCH_WORKERS + 1).coerceAtLeast(8))
                .map { chunk ->
                    async(Dispatchers.Default) {
                        val local = mutableListOf<FloatArray>()
                        for (candidate in chunk) {
                            val world = frameToWorld(candidate[0].toFloat(), candidate[1].toFloat(), fixedPlacement, lens)
                            val predicted = projectToFrame(world[0], world[1], movingPlacement, lens)
                            if (!predicted.inside) continue
                            val found = matchControlPoint(
                                template = gray, templateWidth = fixed.width,
                                sourceX = candidate[0], sourceY = candidate[1],
                                target = movingGray, targetWidth = moving.width, targetHeight = moving.height,
                                centerX = predicted.x, centerY = predicted.y,
                                radiusPx = searchPx,
                            ) ?: continue
                            val worldFound = frameToWorld(found[0], found[1], movingPlacement, lens)
                            // Oltre al residuo, la posizione del punto nel fotogramma mobile
                            // (in gradi dal centro) — distingue spostamento, rotazione e
                            // focale — e le due luminanze con i raggi normalizzati, che sono
                            // il cibo della stima fotometrica: guadagni e vignettatura.
                            val halfWf = fixed.width / 2f
                            val halfHf = fixed.height / 2f
                            val halfWm = moving.width / 2f
                            val halfHm = moving.height / 2f
                            val fx = candidate[0] - halfWf
                            val fy = candidate[1] - halfHf
                            val mx = found[0] - halfWm
                            val my = found[1] - halfHm
                            local += floatArrayOf(
                                wrapDegrees(world[0] - worldFound[0]),
                                world[1] - worldFound[1],
                                ((found[0] - lens.imageWidth / 2f) / lens.focalPixels).toDegrees(),
                                ((lens.imageHeight / 2f - found[1]) / lens.focalPixels).toDegrees(),
                                gray[candidate[1] * fixed.width + candidate[0]],
                                movingGray[
                                    found[1].toInt().coerceIn(0, moving.height - 1) * moving.width +
                                        found[0].toInt().coerceIn(0, moving.width - 1),
                                ],
                                (fx * fx + fy * fy) / (halfWf * halfWf + halfHf * halfHf),
                                (mx * mx + my * my) / (halfWm * halfWm + halfHm * halfHm),
                            )
                        }
                        local
                    }
                }
                .awaitAll()
                .flatten()
        }
        return ControlPointTally(picked.size, kept)
    }

    /**
     * Ritrova un ritaglio nel fotogramma mobile, e risponde solo se la qualità supera la
     * soglia. Correlazione a media e varianza tolte, ricerca piena pixel per pixel: il raggio
     * è piccolo e la completezza costa poco.
     */
    private fun matchControlPoint(
        template: FloatArray,
        templateWidth: Int,
        sourceX: Int,
        sourceY: Int,
        target: FloatArray,
        targetWidth: Int,
        targetHeight: Int,
        centerX: Float,
        centerY: Float,
        radiusPx: Float,
    ): FloatArray? {
        val r = CONTROL_PATCH_RADIUS
        val side = 2 * r + 1
        val patch = FloatArray(side * side)
        var mean = 0f
        for (dy in -r..r) {
            for (dx in -r..r) {
                val v = template[(sourceY + dy) * templateWidth + sourceX + dx]
                patch[(dy + r) * side + dx + r] = v
                mean += v
            }
        }
        mean /= patch.size
        var norm = 0f
        for (i in patch.indices) {
            patch[i] -= mean
            norm += patch[i] * patch[i]
        }
        if (norm < CONTROL_MIN_VARIANCE) return null

        val minX = (centerX - radiusPx).toInt().coerceAtLeast(r)
        val maxX = (centerX + radiusPx).toInt().coerceAtMost(targetWidth - 1 - r)
        val minY = (centerY - radiusPx).toInt().coerceAtLeast(r)
        val maxY = (centerY + radiusPx).toInt().coerceAtMost(targetHeight - 1 - r)
        if (minX > maxX || minY > maxY) return null

        var bestX = -1
        var bestY = -1
        var best = -1f
        for (py in minY..maxY) {
            for (px in minX..maxX) {
                var sum = 0f
                for (dy in -r..r) {
                    val rowBase = (py + dy) * targetWidth + px
                    for (dx in -r..r) sum += target[rowBase + dx]
                }
                val targetMean = sum / patch.size
                var cross = 0f
                var targetNorm = 0f
                for (dy in -r..r) {
                    val rowBase = (py + dy) * targetWidth + px
                    val patchBase = (dy + r) * side + r
                    for (dx in -r..r) {
                        val d = target[rowBase + dx] - targetMean
                        cross += d * patch[patchBase + dx]
                        targetNorm += d * d
                    }
                }
                if (targetNorm < CONTROL_MIN_VARIANCE) continue
                val ncc = cross / sqrt(targetNorm * norm)
                if (ncc > best) {
                    best = ncc
                    bestX = px
                    bestY = py
                }
            }
        }
        if (bestX < 0 || best < CONTROL_KEEP_NCC) return null
        return floatArrayOf(bestX.toFloat(), bestY.toFloat())
    }

    /** La media dei residui senza le code: il dieci per cento più estremo per lato non vota. */
    private fun trimmedMean(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val trim = sorted.size / 10
        val slice = sorted.subList(trim, sorted.size - trim)
        return if (slice.isEmpty()) sorted[sorted.size / 2] else slice.average().toFloat()
    }

    private class Offset(val panDegrees: Float, val tiltDegrees: Float, val confidence: Float)

    /**
     * Lo spostamento che fa combaciare [moving] con [fixed], dalla nebbia al dettaglio.
     *
     * I punti campione nascono su una griglia del fotogramma fermo e diventano direzioni nel
     * mondo; a ogni livello della piramide si cerca, in una finestra attorno al risultato del
     * livello prima, lo spostamento che massimizza la correlazione fra i due fotogrammi su quei
     * punti. Al livello più sfocato la finestra è larga sedici gradi; all'ultimo, un decimo.
     */
    private fun registerPair(
        moving: Frame,
        fixed: Frame,
        movingPlacement: FramePlacement,
        fixedPlacement: FramePlacement,
        lens: PinholeLens,
        wideSearch: Boolean,
    ): Offset? {
        // Con la ricerca larga il vero combaciamento può stare ovunque: si tengono tutti i
        // punti del fermo, e sarà ogni candidato a dire quali cadono nel mobile. Con gli
        // angoli misurati, invece, la previsione è affidabile e filtra da subito.
        val directions = ArrayList<FloatArray>()
        var y = REG_SAMPLE_STEP / 2
        while (y < fixed.height) {
            var x = REG_SAMPLE_STEP / 2
            while (x < fixed.width) {
                val world = frameToWorld(x.toFloat(), y.toFloat(), fixedPlacement, lens)
                val keep = wideSearch ||
                    projectToFrame(world[0], world[1], movingPlacement, lens).inside
                if (keep) directions += floatArrayOf(world[0], world[1], x.toFloat(), y.toFloat())
                x += REG_SAMPLE_STEP
            }
            y += REG_SAMPLE_STEP
        }
        if (directions.size < REG_MIN_SAMPLES) return null

        // La finestra della prima passata: la deriva di un gimbal calibrato, oppure — a mano
        // libera — quasi tutto il campo visivo, perché il passo vero nessuno lo sa.
        val coarsePan = if (wideSearch) min(60f, lens.horizontalFovDegrees * 0.75f) else MAX_SEARCH_DEGREES * 2f
        val coarseTilt = if (wideSearch) 20f else MAX_SEARCH_DEGREES * 2f
        val maxPan = coarsePan + 2f
        val maxTilt = coarseTilt + 2f
        val schedule = listOf(
            floatArrayOf(3f, coarsePan, coarseTilt, if (wideSearch) 1.2f else 0.8f),
            floatArrayOf(2f, 1.2f, 1.2f, 0.3f),
            floatArrayOf(1f, 0.4f, 0.4f, 0.12f),
            floatArrayOf(0f, 0.12f, 0.12f, 0.05f),
        )

        var dPan = 0f
        var dTilt = 0f
        var confidence = -1f
        schedule.forEach { spec ->
            val levelIndex = spec[0].toInt()
            val rangePan = spec[1]
            val rangeTilt = spec[2]
            val step = spec[3]
            val fixedLevel = fixed.grayLevels[min(levelIndex, fixed.grayLevels.size - 1)]
            val movingLevel = moving.grayLevels[min(levelIndex, moving.grayLevels.size - 1)]
            val fixedScale = fixedLevel.width.toFloat() / fixed.width
            val movingScale = movingLevel.width.toFloat() / moving.width

            // I valori del fermo a questo livello, una volta sola.
            val fixedValues = FloatArray(directions.size)
            for (i in directions.indices) {
                fixedValues[i] = bilinear(
                    fixedLevel,
                    directions[i][2] * fixedScale,
                    directions[i][3] * fixedScale,
                )
            }

            var bestPan = dPan
            var bestTilt = dTilt
            var bestNcc = -2f
            var cp = -rangePan
            while (cp <= rangePan + 1e-3f) {
                var ct = -rangeTilt
                while (ct <= rangeTilt + 1e-3f) {
                    val candPan = (dPan + cp).coerceIn(-maxPan, maxPan)
                    val candTilt = (dTilt + ct).coerceIn(-maxTilt, maxTilt)
                    val candidate = movingPlacement.copy(
                        panCorrectionDegrees = candPan,
                        tiltCorrectionDegrees = candTilt,
                    )
                    val ncc = sampledNcc(directions, fixedValues, movingLevel, movingScale, candidate, lens)
                    if (ncc > bestNcc) {
                        bestNcc = ncc
                        bestPan = candPan
                        bestTilt = candTilt
                    }
                    ct += step
                }
                cp += step
            }
            dPan = bestPan
            dTilt = bestTilt
            confidence = bestNcc
        }
        if (confidence < REG_MIN_NCC) return null
        return Offset(dPan, dTilt, confidence.coerceIn(0f, 1f))
    }

    /**
     * La correlazione normalizzata fra i due fotogrammi sui punti campione, con il mobile
     * spostato della correzione candidata. Normalizzata su media e varianza di *questo*
     * sottoinsieme: due esposizioni diverse danno lo stesso punteggio.
     */
    private fun sampledNcc(
        directions: List<FloatArray>,
        fixedValues: FloatArray,
        movingLevel: GrayLevel,
        movingScale: Float,
        candidate: FramePlacement,
        lens: PinholeLens,
    ): Float {
        var n = 0
        var sumF = 0f
        var sumM = 0f
        var sumFF = 0f
        var sumMM = 0f
        var sumFM = 0f
        for (i in directions.indices) {
            val direction = directions[i]
            val point = projectToFrame(direction[0], direction[1], candidate, lens)
            if (!point.inside) continue
            val m = bilinear(movingLevel, point.x * movingScale, point.y * movingScale)
            val f = fixedValues[i]
            n++
            sumF += f
            sumM += m
            sumFF += f * f
            sumMM += m * m
            sumFM += f * m
        }
        if (n < REG_MIN_SAMPLES / 2) return -2f
        val varF = n * sumFF - sumF * sumF
        val varM = n * sumMM - sumM * sumM
        if (varF < 1f || varM < 1f) return -2f
        return (n * sumFM - sumF * sumM) / sqrt(varF * varM)
    }

    /** Direzioni campione nella sovrapposizione fra due fotogrammi, per il pareggio di esposizione. */
    private fun overlapSamples(
        a: FramePlacement,
        b: FramePlacement,
        lens: PinholeLens,
    ): List<FloatArray> {
        val samples = mutableListOf<FloatArray>()
        var y = REG_SAMPLE_STEP
        while (y < lens.imageHeight) {
            var x = REG_SAMPLE_STEP
            while (x < lens.imageWidth) {
                val world = frameToWorld(x.toFloat(), y.toFloat(), a, lens)
                if (projectToFrame(world[0], world[1], b, lens).inside) {
                    samples += floatArrayOf(world[0], world[1])
                }
                x += REG_SAMPLE_STEP * 2
            }
            y += REG_SAMPLE_STEP * 2
        }
        return samples
    }

    private fun bilinear(level: GrayLevel, x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, level.width - 1.001f)
        val cy = y.coerceIn(0f, level.height - 1.001f)
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val tx = cx - x0
        val ty = cy - y0
        val base = y0 * level.width + x0
        val top = level.data[base] * (1f - tx) + level.data[base + 1] * tx
        val bottom = level.data[base + level.width] * (1f - tx) + level.data[base + level.width + 1] * tx
        return top * (1f - ty) + bottom * ty
    }

    /**
     * Dipinge la tela con la fusione multibanda: la cucitura di Autopano, non una dissolvenza.
     *
     * Un fotogramma alla volta, in ordine di scatto: il nuovo viene cucito su quello che c'è
     * già. La maschera dice chi possiede ogni pixel — il fotogramma che lì è più «a casa sua»,
     * cioè più lontano dal proprio bordo — ed è netta come un taglio. Poi la fusione la
     * ammorbidisce banda per banda ([MultibandBlender]): il dettaglio fino cambia mano in un
     * pixel, così un oggetto mosso fra due scatti viene tagliato e non stampato due volte; i
     * toni larghi cambiano mano in decine di pixel, così una differenza di esposizione si
     * spalma dove l'occhio non la vede. È la coppia che una striscia sola, larga o stretta che
     * sia, non può dare.
     *
     * Il lavoro pesante avviene solo nel rettangolo del fotogramma nuovo, non su tutta la
     * tela: la memoria resta quella di un ritaglio.
     */
    private suspend fun compose(
        frames: List<Frame>,
        placements: List<FramePlacement>,
        lens: PinholeLens,
        canvas: PanoramaCanvas,
        aligned: BooleanArray,
        fullResSampling: Boolean,
        photometric: List<FloatArray>,
        detail: MutableList<String>,
    ): Bitmap {
        val output = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        // Nero, non trasparente: un JPEG non ha trasparenza e diventerebbe bianco.
        output.eraseColor(0xFF000000.toInt())

        // La fotometria vera: guadagni per foto e vignettatura dell'obiettivo, stimati
        // insieme dai punti di controllo. Il ripiego è la vecchia catena delle mediane.
        val fit = fitPhotometric(photometric, frames.size)
        val gains = fit?.gains ?: exposureGains(frames, placements, lens, canvas, aligned)
        detail += if (fit != null) {
            "Fotometria: guadagni " + gains.joinToString(" · ") { "%.2f".format(it) } +
                " · vignettatura a=%.3f b=%.3f (%d campioni)".format(fit.vignetteA, fit.vignetteB, photometric.size)
        } else {
            "Fotometria di ripiego (catena delle mediane): guadagni " +
                gains.mapIndexed { i, g -> "%.2f%s".format(g, if (!aligned[i]) "≈" else "") }
                    .joinToString(" · ")
        }
        val corrections = frames.mapIndexed { i, frame ->
            FrameCorrection(gains[i], fit?.vignetteA ?: 0f, fit?.vignetteB ?: 0f, frame.width, frame.height)
        }

        // Chi possiede ogni pixel della tela, quantificato: il peso della sfumatura del
        // fotogramma che l'ha dipinto. Serve a decidere le maschere dei fotogrammi
        // successivi. A mezza risoluzione: la decisione di possesso non ha bisogno del
        // pixel esatto, e su una tela grande questa mappa era il vettore più pesante.
        val ownerWidth = (canvas.width + OWNER_SCALE - 1) / OWNER_SCALE
        val ownerHeight = (canvas.height + OWNER_SCALE - 1) / OWNER_SCALE
        val ownerWeight = ByteArray(ownerWidth * ownerHeight)

        frames.forEachIndexed { index, frame ->
            currentCoroutineContext().ensureActive()
            onProgress(
                0.35f + 0.6f * index / frames.size,
                "Cucio ${frame.label} (${index + 1}/${frames.size})",
            )
            val startedAt = System.currentTimeMillis()
            pasteFrame(
                output, ownerWeight, frame, placements[index], corrections[index], lens, canvas,
                fullResSampling, detail,
            )
            val last = detail.removeLastOrNull()
            if (last != null) detail += "$last · ${(System.currentTimeMillis() - startedAt) / 1000f} s"
            // Un fotogramma alla volta anche in memoria: cucito, i suoi vettori e il suo
            // originale a piena risoluzione si liberano.
            frame.releaseWorkingData()
            frame.closeFullResolution()
        }
        return output
    }

    /**
     * Cuce un fotogramma sulla tela, fondendo in multibanda dentro il suo rettangolo.
     *
     * Le zone dove solo uno dei due esiste vengono riempite con l'altro prima delle piramidi:
     * senza, il nero fuori campo entrerebbe nelle bande larghe e scurirebbe i bordi veri.
     */
    private suspend fun pasteFrame(
        output: Bitmap,
        ownerWeight: ByteArray,
        frame: Frame,
        placement: FramePlacement,
        correction: FrameCorrection,
        lens: PinholeLens,
        canvas: PanoramaCanvas,
        fullResSampling: Boolean,
        detail: MutableList<String>? = null,
    ) {
        val full = if (fullResSampling) frame.openFullResolution() else null
        val margin = BBOX_MARGIN_DEGREES
        val halfH = lens.horizontalFovDegrees / 2f + margin
        val halfV = lens.verticalFovDegrees / 2f + margin
        val startLon = canvas.centerPanDegrees - canvas.horizontalDegrees / 2f
        val topLat = canvas.centerTiltDegrees + canvas.verticalDegrees / 2f

        val col0 = floor((placement.effectivePan - halfH - startLon) * canvas.pixelsPerDegree).toInt()
        val col1 = ceil((placement.effectivePan + halfH - startLon) * canvas.pixelsPerDegree).toInt()
        val row0 = floor((topLat - (placement.effectiveTilt + halfV)) * canvas.pixelsPerDegree).toInt()
            .coerceIn(0, canvas.height - 1)
        val row1 = ceil((topLat - (placement.effectiveTilt - halfV)) * canvas.pixelsPerDegree).toInt()
            .coerceIn(0, canvas.height - 1)

        // Le colonne possono sbordare dalla tela. Se la tela chiude il giro si passa
        // dall'altra parte (modulo); altrimenti si tagliano e basta.
        val wraps = canvas.horizontalDegrees >= PanoramaCanvas.FULL_TURN_DEGREES - 0.5f
        val c0 = if (wraps) col0 else col0.coerceIn(0, canvas.width - 1)
        val c1 = if (wraps) col1 else col1.coerceIn(0, canvas.width - 1)
        val bw = (c1 - c0 + 1).coerceAtMost(canvas.width)
        val bh = row1 - row0 + 1
        if (bw <= 0 || bh <= 0) return

        val count = bw * bh
        val columns = IntArray(bw) { bx -> ((c0 + bx) % canvas.width + canvas.width) % canvas.width }

        // Ricognizione: i pesi del fotogramma nuovo — l'unico vettore a tutta finestra,
        // quattro byte a pixel — e il perimetro della sovrapposizione con la tela già
        // dipinta. La fusione multibanda costa cara e serve solo lì: tutto il resto del
        // fotogramma si dipinge diretto, una riga alla volta, senza vettori giganti. È
        // questo che permette alla tela di crescere: prima la fusione lavorava sull'intera
        // finestra e la memoria imponeva panoramiche piccole.
        val newW = ByteArray(count)
        parallelRows(row0, bh, 1) { by, _ ->
            val latitude = canvas.latitudeAt(row0 + by)
            for (bx in 0 until bw) {
                val longitude = canvas.longitudeAt(columns[bx])
                val point = projectToFrame(longitude, latitude, placement, lens)
                if (!point.inside) continue
                val weight = featherWeight(point.x, point.y, frame.width, frame.height)
                if (weight <= 0f) continue
                newW[by * bw + bx] = (weight * 255f).roundToInt().coerceIn(1, 255).toByte()
            }
        }

        // Il perimetro della sovrapposizione, dai pesi appena calcolati: una scansione
        // leggera, senza trigonometria.
        var ov0x = Int.MAX_VALUE
        var ov1x = -1
        var ov0y = Int.MAX_VALUE
        var ov1y = -1
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                if (newW[by * bw + bx].toInt() == 0) continue
                if (ownerWeight[ownerIndex(canvas.width, row0 + by, columns[bx])].toInt() != 0) {
                    if (bx < ov0x) ov0x = bx
                    if (bx > ov1x) ov1x = bx
                    if (by < ov0y) ov0y = by
                    if (by > ov1y) ov1y = by
                }
            }
        }

        // La sotto-finestra della fusione, con il contesto che serve alle piramidi.
        val hasOverlap = ov1x >= 0
        var sx0 = 0
        var sx1 = -1
        var sy0 = 0
        var sy1 = -1
        if (hasOverlap) {
            sx0 = (ov0x - BLEND_CONTEXT_PX).coerceAtLeast(0)
            sx1 = (ov1x + BLEND_CONTEXT_PX).coerceAtMost(bw - 1)
            sy0 = (ov0y - BLEND_CONTEXT_PX).coerceAtLeast(0)
            sy1 = (ov1y + BLEND_CONTEXT_PX).coerceAtMost(bh - 1)
            blendSubWindow(
                output, ownerWeight, frame, placement, correction, lens, canvas,
                columns, row0, bw, newW, sx0, sx1, sy0, sy1, full,
            )
        }

        // Il resto del fotogramma: pittura diretta riga per riga, tutte le CPU insieme.
        // Fuori dalla sotto-finestra ogni pixel nuovo cade su tela vuota per costruzione,
        // quindi non c'è niente da fondere e le righe sono indipendenti.
        parallelRows(row0, bh, canvas.width) { by, rowPixels ->
            val insideBlendRows = hasOverlap && by in sy0..sy1
            var touched = false
            var readRow = false
            var latitude = 0f
            for (bx in 0 until bw) {
                if (insideBlendRows && bx in sx0..sx1) continue
                val i = by * bw + bx
                val weightByte = newW[i].toInt() and 0xFF
                if (weightByte == 0) continue
                if (!readRow) {
                    readRow = true
                    latitude = canvas.latitudeAt(row0 + by)
                    output.getPixels(rowPixels, 0, canvas.width, 0, row0 + by, canvas.width, 1)
                }
                val longitude = canvas.longitudeAt(columns[bx])
                val point = projectToFrame(longitude, latitude, placement, lens)
                if (!point.inside) continue
                val color = sampleColor(frame, full, point.x, point.y)
                val factor = correction.factorAt(point.x, point.y)
                val r = (factor * ((color shr 16) and 0xFF)).roundToInt().coerceIn(0, 255)
                val g = (factor * ((color shr 8) and 0xFF)).roundToInt().coerceIn(0, 255)
                val b = (factor * (color and 0xFF)).roundToInt().coerceIn(0, 255)
                rowPixels[columns[bx]] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                touched = true
                val index = ownerIndex(canvas.width, row0 + by, columns[bx])
                if (weightByte > (ownerWeight[index].toInt() and 0xFF)) {
                    ownerWeight[index] = weightByte.toByte()
                }
            }
            if (touched) {
                output.setPixels(rowPixels, 0, canvas.width, 0, row0 + by, canvas.width, 1)
            }
        }

        detail?.add(
            "%s: finestra %d×%d px · fusione su %s".format(
                frame.label, bw, bh,
                if (hasOverlap) "${sx1 - sx0 + 1}×${sy1 - sy0 + 1} px" else "niente (primo tocco di tela)",
            ),
        )
    }

    /**
     * La fusione multibanda, ristretta alla sotto-finestra dove vecchio e nuovo si toccano —
     * e calcolata su una versione ridotta quando la finestra è grande.
     *
     * L'idea che rende possibile la tela grande: della fusione multibanda serve solo la
     * **correzione** — la differenza fra la fusione vera e il montaggio a taglio netto — ed
     * è per natura a bassa frequenza, perché le bande fini cambiano mano in un pixel. Quindi
     * si costruiscono vecchio, nuovo e maschera a passo [s], si fonde lì, si sottrae il
     * montaggio netto ridotto, e la differenza si riapplica riga per riga al montaggio netto
     * a piena risoluzione. Con `s = 1` è la fusione esatta di prima; con `s` maggiore la
     * memoria cala col quadrato e il dettaglio fine resta pieno, perché viene dal montaggio,
     * non dalla piramide.
     */
    private suspend fun blendSubWindow(
        output: Bitmap,
        ownerWeight: ByteArray,
        frame: Frame,
        placement: FramePlacement,
        correction: FrameCorrection,
        lens: PinholeLens,
        canvas: PanoramaCanvas,
        columns: IntArray,
        row0: Int,
        bw: Int,
        windowNewW: ByteArray,
        sx0: Int,
        sx1: Int,
        sy0: Int,
        sy1: Int,
        full: Bitmap?,
    ) {
        val sbw = sx1 - sx0 + 1
        val sbh = sy1 - sy0 + 1
        val subRow0 = row0 + sy0

        // La scala della correzione: piena finché la memoria ci sta, poi a passi.
        val fullCost = sbw.toLong() * sbh * BLEND_BYTES_PER_PX.toLong()
        val s = when {
            fullCost <= BLEND_HEAP_BUDGET_BYTES -> 1
            fullCost / 4 <= BLEND_HEAP_BUDGET_BYTES -> 2
            fullCost / 16 <= BLEND_HEAP_BUDGET_BYTES -> 4
            else -> 8
        }
        val gw = (sbw + s - 1) / s
        val gh = (sbh + s - 1) / s
        val gcount = gw * gh

        // 1) Vecchio, nuovo e maschera, campionati al centro di ogni cella s×s.
        val baseColor = IntArray(gcount)
        val newColor = IntArray(gcount)
        val mask = FloatArray(gcount)
        val valid = BooleanArray(gcount)
        parallelRows(0, gh, canvas.width) { gy, rowPixels ->
            val by = min(gy * s + s / 2, sbh - 1)
            val row = subRow0 + by
            val latitude = canvas.latitudeAt(row)
            output.getPixels(rowPixels, 0, canvas.width, 0, row, canvas.width, 1)
            for (gx in 0 until gw) {
                val bx = min(gx * s + s / 2, sbw - 1)
                val col = columns[sx0 + bx]
                val g = gy * gw + gx
                val newWeight = (windowNewW[(sy0 + by) * bw + (sx0 + bx)].toInt() and 0xFF) / 255f
                val oldWeight = (ownerWeight[ownerIndex(canvas.width, row, col)].toInt() and 0xFF) / 255f
                val old = rowPixels[col] and 0xFFFFFF
                baseColor[g] = old
                valid[g] = newWeight > 0f || oldWeight > 0f
                if (newWeight > 0f) {
                    val longitude = canvas.longitudeAt(col)
                    val point = projectToFrame(longitude, latitude, placement, lens)
                    if (point.inside) {
                        val color = sampleColor(frame, full, point.x, point.y)
                        val factor = correction.factorAt(point.x, point.y)
                        val r = (factor * ((color shr 16) and 0xFF)).roundToInt().coerceIn(0, 255)
                        val gch = (factor * ((color shr 8) and 0xFF)).roundToInt().coerceIn(0, 255)
                        val b = (factor * (color and 0xFF)).roundToInt().coerceIn(0, 255)
                        newColor[g] = (r shl 16) or (gch shl 8) or b
                        if (oldWeight <= 0f) {
                            baseColor[g] = newColor[g]
                            mask[g] = 1f
                        } else if (newWeight > oldWeight) {
                            mask[g] = 1f
                        }
                    } else {
                        newColor[g] = old
                    }
                } else {
                    newColor[g] = old
                }
            }
        }

        // I punti che nessuno copre, riempiti col colore valido più vicino: il nero dentro
        // le piramidi sanguinerebbe nelle bande larghe e scurirebbe il bordo vero.
        fillHoles(baseColor, valid, gw, gh)
        for (g in 0 until gcount) if (!valid[g]) newColor[g] = baseColor[g]

        // 2) La fusione sulla versione ridotta, un canale per volta; della fusione si tiene
        // solo la correzione rispetto al montaggio netto ridotto.
        val correctionGrid = Array(3) { FloatArray(gcount) }
        for ((channel, shift) in intArrayOf(16, 8, 0).withIndex()) {
            val baseChannel = FloatArray(gcount) { ((baseColor[it] shr shift) and 0xFF).toFloat() }
            val overChannel = FloatArray(gcount) { ((newColor[it] shr shift) and 0xFF).toFloat() }
            val blended = MultibandBlender.blend(
                baseChannels = arrayOf(baseChannel),
                overlayChannels = arrayOf(overChannel),
                mask = mask,
                width = gw,
                height = gh,
            )[0]
            val target = correctionGrid[channel]
            for (g in 0 until gcount) {
                val hard = if (mask[g] > 0.5f) overChannel[g] else baseChannel[g]
                target[g] = blended[g] - hard
            }
        }

        // 3) Riga per riga a piena risoluzione, tutte le CPU insieme: montaggio netto più
        // correzione interpolata.
        parallelRows(subRow0, sbh, canvas.width) { by, rowPixels ->
            val row = subRow0 + by
            val latitude = canvas.latitudeAt(row)
            output.getPixels(rowPixels, 0, canvas.width, 0, row, canvas.width, 1)
            var touched = false
            val gyf = (by.toFloat() / s) - 0.5f + 0.5f / s
            for (bx in 0 until sbw) {
                val col = columns[sx0 + bx]
                val weightByte = windowNewW[(sy0 + by) * bw + (sx0 + bx)].toInt() and 0xFF
                val ownerIdx = ownerIndex(canvas.width, row, col)
                val oldByte = ownerWeight[ownerIdx].toInt() and 0xFF
                if (weightByte == 0 && oldByte == 0) continue

                var hard = rowPixels[col] and 0xFFFFFF
                if (weightByte > 0 && (oldByte == 0 || weightByte > oldByte)) {
                    val longitude = canvas.longitudeAt(col)
                    val point = projectToFrame(longitude, latitude, placement, lens)
                    if (point.inside) {
                        val color = sampleColor(frame, full, point.x, point.y)
                        val factor = correction.factorAt(point.x, point.y)
                        val r = (factor * ((color shr 16) and 0xFF)).roundToInt().coerceIn(0, 255)
                        val g = (factor * ((color shr 8) and 0xFF)).roundToInt().coerceIn(0, 255)
                        val b = (factor * (color and 0xFF)).roundToInt().coerceIn(0, 255)
                        hard = (r shl 16) or (g shl 8) or b
                    }
                }

                val gxf = (bx.toFloat() / s) - 0.5f + 0.5f / s
                var outPixel = 0xFF000000.toInt()
                for ((channel, shift) in intArrayOf(16, 8, 0).withIndex()) {
                    val value = ((hard shr shift) and 0xFF) +
                        bilinearGrid(correctionGrid[channel], gw, gh, gxf, gyf)
                    outPixel = outPixel or (value.roundToInt().coerceIn(0, 255) shl shift)
                }
                rowPixels[col] = outPixel
                touched = true

                val winner = max(oldByte, weightByte)
                if (winner > oldByte) {
                    ownerWeight[ownerIdx] = winner.toByte()
                }
            }
            if (touched) {
                output.setPixels(rowPixels, 0, canvas.width, 0, row, canvas.width, 1)
            }
        }
    }

    /**
     * Distribuisce le righe su tutti i core, a coppie allineate alla mappa dei possessori.
     *
     * Ogni riga scrive pixel suoi sulla tela, ma due righe adiacenti condividono la riga
     * della mappa dei possessori (che vive a mezza risoluzione): lavorando a coppie
     * allineate alla parità assoluta, ogni lavoratore ha le sue righe di mappa in
     * esclusiva e non serve nessun lucchetto. A ogni lavoratore il suo buffer di riga.
     */
    private suspend fun parallelRows(
        firstAbsoluteRow: Int,
        rowCount: Int,
        bufferSize: Int,
        body: (Int, IntArray) -> Unit,
    ) = coroutineScope {
        if (rowCount <= 0) return@coroutineScope
        val workers = min(Runtime.getRuntime().availableProcessors(), MAX_STITCH_WORKERS)
        if (workers <= 1 || rowCount < 8) {
            val buffer = IntArray(bufferSize)
            for (r in 0 until rowCount) body(r, buffer)
            return@coroutineScope
        }
        val firstPair = firstAbsoluteRow / OWNER_SCALE
        val lastPair = (firstAbsoluteRow + rowCount - 1) / OWNER_SCALE
        val next = java.util.concurrent.atomic.AtomicInteger(firstPair)
        repeat(workers) {
            launch(Dispatchers.Default) {
                val buffer = IntArray(bufferSize)
                while (true) {
                    val pair = next.getAndIncrement()
                    if (pair > lastPair) break
                    val from = max(pair * OWNER_SCALE, firstAbsoluteRow)
                    val to = min(pair * OWNER_SCALE + OWNER_SCALE - 1, firstAbsoluteRow + rowCount - 1)
                    for (row in from..to) body(row - firstAbsoluteRow, buffer)
                }
            }
        }
    }

    /** Interpolazione bilineare su una griglia ridotta, ai bordi si ferma. */
    private fun bilinearGrid(grid: FloatArray, gridWidth: Int, gridHeight: Int, x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (gridWidth - 1).toFloat())
        val cy = y.coerceIn(0f, (gridHeight - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(x0 + 1, gridWidth - 1)
        val y1 = min(y0 + 1, gridHeight - 1)
        val tx = cx - x0
        val ty = cy - y0
        val top = grid[y0 * gridWidth + x0] * (1f - tx) + grid[y0 * gridWidth + x1] * tx
        val bottom = grid[y1 * gridWidth + x0] * (1f - tx) + grid[y1 * gridWidth + x1] * tx
        return top * (1f - ty) + bottom * ty
    }

    /** L'indice nella mappa dei possessori, tenuta a mezza risoluzione per pesare la metà². */
    private fun ownerIndex(canvasWidth: Int, row: Int, col: Int): Int {
        val ownerWidth = (canvasWidth + OWNER_SCALE - 1) / OWNER_SCALE
        return (row / OWNER_SCALE) * ownerWidth + (col / OWNER_SCALE)
    }

    /**
     * Riempie i punti non validi con il colore valido più vicino sulla riga (e poi sulla
     * colonna, per le righe completamente vuote). Non è interpolazione: copia il vicino, e
     * serve solo a non far entrare il nero fuori campo nelle bande larghe della fusione.
     */
    private fun fillHoles(colors: IntArray, valid: BooleanArray, width: Int, height: Int) {
        val filled = BooleanArray(colors.size)
        for (y in 0 until height) {
            val base = y * width
            var last = -1
            for (x in 0 until width) {
                val i = base + x
                if (valid[i]) {
                    last = colors[i]
                    filled[i] = true
                } else if (last >= 0) {
                    colors[i] = last
                    filled[i] = true
                }
            }
            last = -1
            for (x in width - 1 downTo 0) {
                val i = base + x
                if (valid[i]) last = colors[i]
                else if (!filled[i] && last >= 0) {
                    colors[i] = last
                    filled[i] = true
                }
            }
        }
        // Le righe senza nemmeno un punto valido prendono dalla riga piena sopra o sotto.
        for (x in 0 until width) {
            var last = -1
            for (y in 0 until height) {
                val i = y * width + x
                if (filled[i]) last = colors[i] else if (last >= 0) { colors[i] = last; filled[i] = true }
            }
            last = -1
            for (y in height - 1 downTo 0) {
                val i = y * width + x
                if (filled[i]) last = colors[i] else if (last >= 0) { colors[i] = last; filled[i] = true }
            }
        }
    }

    /**
     * Ritaglia il nero ai bordi tenendo la parte visibile più grande possibile.
     *
     * La copertura vera di una panoramica non è mai rettangolare: fotogrammi corretti in
     * altezza lasciano cunei neri in alto e in basso, e i bordi proiettati sono stirati. Si
     * misurano le corse di nero da sinistra e da destra su ogni riga — il nero sta sempre al
     * bordo, per costruzione — e poi si rosicchia dal lato che ne ha di più, un filo alla
     * volta, finché ogni lato del rettangolo è quasi pulito. Non è il rettangolo massimo
     * teorico, ma gli somiglia, e non taglia mai il centro.
     *
     * Sulle tele che chiudono il giro le colonne non si toccano: destra e sinistra sono lo
     * stesso meridiano, e ritagliarle romperebbe la continuità.
     */
    private fun cropBlackEdges(bitmap: Bitmap, allowColumns: Boolean): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        val leftRun = IntArray(height)
        val rightRun = IntArray(height)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var left = 0
            while (left < width && row[left] == EMPTY_PIXEL) left++
            var right = 0
            while (right < width - left && row[width - 1 - right] == EMPTY_PIXEL) right++
            leftRun[y] = left
            rightRun[y] = right
        }

        var top = 0
        var bottom = height - 1
        var left = 0
        var right = width - 1
        val minWidth = (width * MIN_CROP_KEEP).toInt().coerceAtLeast(16)
        val minHeight = (height * MIN_CROP_KEEP).toInt().coerceAtLeast(16)

        fun rowEmptiness(y: Int): Float {
            val span = right - left + 1
            val fromLeft = (leftRun[y] - left).coerceIn(0, span)
            val fromRight = (rightRun[y] - (width - 1 - right)).coerceIn(0, span)
            return (fromLeft + fromRight).coerceAtMost(span).toFloat() / span
        }

        fun columnEmptiness(atLeft: Boolean): Float {
            var count = 0
            for (y in top..bottom) {
                val empty = if (atLeft) leftRun[y] > left else rightRun[y] > width - 1 - right
                if (empty) count++
            }
            return count.toFloat() / (bottom - top + 1)
        }

        while (true) {
            var worst = EDGE_EMPTY_TOLERANCE
            var side = -1
            if (bottom - top + 1 > minHeight) {
                rowEmptiness(top).let { if (it > worst) { worst = it; side = 0 } }
                rowEmptiness(bottom).let { if (it > worst) { worst = it; side = 1 } }
            }
            if (allowColumns && right - left + 1 > minWidth) {
                columnEmptiness(atLeft = true).let { if (it > worst) { worst = it; side = 2 } }
                columnEmptiness(atLeft = false).let { if (it > worst) { worst = it; side = 3 } }
            }
            when (side) {
                0 -> top++
                1 -> bottom--
                2 -> left++
                3 -> right--
                else -> break
            }
        }

        if (top == 0 && bottom == height - 1 && left == 0 && right == width - 1) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
        bitmap.recycle()
        return cropped
    }

    /** Legge un ritaglio che può avvolgersi oltre il bordo destro della tela. */
    private fun readRegion(bitmap: Bitmap, columns: IntArray, row0: Int, bw: Int, bh: Int, out: IntArray) {
        val rowPixels = IntArray(bitmap.width)
        for (by in 0 until bh) {
            bitmap.getPixels(rowPixels, 0, bitmap.width, 0, row0 + by, bitmap.width, 1)
            for (bx in 0 until bw) out[by * bw + bx] = rowPixels[columns[bx]]
        }
    }

    /** Scrive un ritaglio che può avvolgersi oltre il bordo destro della tela. */
    private fun writeRegion(bitmap: Bitmap, columns: IntArray, row0: Int, bw: Int, bh: Int, data: IntArray) {
        val rowPixels = IntArray(bitmap.width)
        for (by in 0 until bh) {
            bitmap.getPixels(rowPixels, 0, bitmap.width, 0, row0 + by, bitmap.width, 1)
            for (bx in 0 until bw) rowPixels[columns[bx]] = data[by * bw + bx]
            bitmap.setPixels(rowPixels, 0, bitmap.width, 0, row0 + by, bitmap.width, 1)
        }
    }

    /**
     * Chiude il buco sotto, dove il gimbal non arriva.
     *
     * Sotto una certa inclinazione non c'è più corsa: il tilt si ferma, e quello che sta sotto
     * la camera non viene fotografato da nessuno scatto. In una panoramica normale non importa,
     * perché quella zona è fuori inquadratura; in uno scatto sferico è il pavimento, ed è il
     * primo posto dove guarda chi apre l'immagine in un visore.
     *
     * Quello che si mette lì non è misurato: è inventato, e va detto. Il criterio è che sembri
     * la continuazione di quello che c'è sopra invece di un buco nero. Ogni colonna eredita il
     * colore dell'ultimo pixel buono che ha sopra, e più si scende più quel colore si mescola
     * con la media dell'intero anello: al polo tutte le colonne convergono sullo stesso colore,
     * che è l'unica cosa geometricamente sensata — un polo è un punto solo, e non può avere
     * trecento colori diversi. Senza quella convergenza si otterrebbe una raggiera, che è
     * proprio l'artefatto che si riconosce a colpo d'occhio nelle panoramiche rattoppate male.
     *
     * Restituisce quante righe ha inventato, così il rapporto può dirlo invece di far finta che
     * la sfera fosse completa.
     */
    private fun fillNadirHole(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)

        // L'ultima riga piena è il bordo del buco: sotto, si inventa.
        var boundary = -1
        for (y in height - 1 downTo 0) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            if (row.count { it == EMPTY_PIXEL } <= width * MAX_EMPTY_FRACTION) {
                boundary = y
                break
            }
        }
        if (boundary < 0 || boundary >= height - 1) return 0

        bitmap.getPixels(row, 0, width, 0, boundary, width, 1)
        val ring = row.copyOf()
        var r = 0L
        var g = 0L
        var b = 0L
        var counted = 0
        ring.forEach { color ->
            if (color == EMPTY_PIXEL) return@forEach
            r += (color shr 16) and 0xFF
            g += (color shr 8) and 0xFF
            b += color and 0xFF
            counted++
        }
        if (counted == 0) return 0
        val meanR = (r / counted).toInt()
        val meanG = (g / counted).toInt()
        val meanB = (b / counted).toInt()

        val patched = height - 1 - boundary
        for (y in boundary + 1 until height) {
            // Verso il fondo il colore della colonna cede il posto alla media dell'anello.
            val toward = ((y - boundary).toFloat() / patched).coerceIn(0f, 1f)
            for (x in 0 until width) {
                val source = ring[x].takeIf { it != EMPTY_PIXEL } ?: EMPTY_PIXEL
                val sr = if (source == EMPTY_PIXEL) meanR else (source shr 16) and 0xFF
                val sg = if (source == EMPTY_PIXEL) meanG else (source shr 8) and 0xFF
                val sb = if (source == EMPTY_PIXEL) meanB else source and 0xFF
                val mixR = (sr + (meanR - sr) * toward).roundToInt().coerceIn(0, 255)
                val mixG = (sg + (meanG - sg) * toward).roundToInt().coerceIn(0, 255)
                val mixB = (sb + (meanB - sb) * toward).roundToInt().coerceIn(0, 255)
                row[x] = (0xFF shl 24) or (mixR shl 16) or (mixG shl 8) or mixB
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return patched
    }

    /**
     * Il fattore di luminosità di ogni fotogramma, riferito al primo.
     *
     * A esposizione automatica due scatti dello stesso posto non hanno la stessa luminosità: la
     * camera rimisura fra uno e l'altro, e su una panoramica che va dal cielo all'ombra la
     * differenza è netta. Ogni fotogramma si confronta con il primo sulla loro sovrapposizione,
     * e chi non tocca il primo eredita il fattore del vicino da cui è stato allineato — così la
     * correzione si propaga lungo la catena invece di fermarsi al primo anello.
     */
    private fun exposureGains(
        frames: List<Frame>,
        placements: List<FramePlacement>,
        lens: PinholeLens,
        canvas: PanoramaCanvas,
        aligned: BooleanArray,
    ): FloatArray {
        val gains = FloatArray(frames.size) { 1f }
        for (index in 1 until frames.size) {
            // Anche i fotogrammi non allineati entrano nella catena: il loro guadagno è una
            // stima su angoli ipotizzati — meno precisa, e il tetto della catena la tiene a
            // freno — ma lasciarli a 1.0 li faceva spiccare come una pezza di un altro
            // colore proprio dove l'unione era già zoppa. Misurato dal vivo: la foto «resta
            // dov'era» usciva visibilmente più chiara del resto.

            val anchorIndex = (0 until index).minByOrNull {
                angularDistance(
                    placements[it].effectivePan,
                    placements[it].effectiveTilt,
                    placements[index].effectivePan,
                    placements[index].effectiveTilt,
                )
            } ?: continue
            val samples = overlapSamples(placements[index], placements[anchorIndex], lens)
            if (samples.size < MIN_OVERLAP_SAMPLES) {
                gains[index] = gains[anchorIndex]
                continue
            }
            var movingSum = 0f
            var fixedSum = 0f
            var counted = 0
            samples.forEach { sample ->
                val lon = sample[0]
                val lat = sample[1]
                val m = projectToFrame(lon, lat, placements[index], lens)
                val f = projectToFrame(lon, lat, placements[anchorIndex], lens)
                if (!m.inside || !f.inside) return@forEach
                movingSum += luma(frames[index].bitmap.getPixel(m.x.toInt(), m.y.toInt()))
                fixedSum += luma(frames[anchorIndex].bitmap.getPixel(f.x.toInt(), f.y.toInt()))
                counted++
            }
            gains[index] = if (counted < MIN_OVERLAP_SAMPLES) {
                gains[anchorIndex]
            } else {
                // Il tetto vale anche sulla catena: ogni anello è limitato, ma i limiti si
                // moltiplicano, e dieci fotogrammi possono scurire l'ultimo del trenta per
                // cento un passo alla volta.
                (gains[anchorIndex] * exposureGain(fixedSum / counted, movingSum / counted))
                    .coerceIn(MIN_CHAIN_GAIN, MAX_CHAIN_GAIN)
            }
        }
        return gains
    }

    /**
     * Il colore di un punto del fotogramma: dall'originale a piena risoluzione quando è
     * aperto, altrimenti dalla copia di lavoro. Le coordinate arrivano nello spazio della
     * copia di lavoro e si riportano all'originale con la sua scala.
     */
    private fun sampleColor(frame: Frame, full: Bitmap?, x: Float, y: Float): Int =
        if (full == null) {
            sample(frame, x, y)
        } else {
            sampleBitmap(full, x * frame.fullScaleX, y * frame.fullScaleY)
        }

    /**
     * Interpolazione bilineare leggendo direttamente dal Bitmap nativo: nessun vettore in
     * heap, e le letture sono sicure da più fili insieme.
     */
    private fun sampleBitmap(bitmap: Bitmap, x: Float, y: Float): Int {
        val x0 = x.toInt().coerceIn(0, bitmap.width - 1)
        val y0 = y.toInt().coerceIn(0, bitmap.height - 1)
        val x1 = (x0 + 1).coerceAtMost(bitmap.width - 1)
        val y1 = (y0 + 1).coerceAtMost(bitmap.height - 1)
        val fx = x - x0
        val fy = y - y0
        val c00 = bitmap.getPixel(x0, y0)
        val c10 = bitmap.getPixel(x1, y0)
        val c01 = bitmap.getPixel(x0, y1)
        val c11 = bitmap.getPixel(x1, y1)
        var result = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (c00 shr shift) and 0xFF
            val b = (c10 shr shift) and 0xFF
            val c = (c01 shr shift) and 0xFF
            val d = (c11 shr shift) and 0xFF
            val top = a + (b - a) * fx
            val bottom = c + (d - c) * fx
            result = result or ((top + (bottom - top) * fy).roundToInt().coerceIn(0, 255) shl shift)
        }
        return result
    }

    /** Colore interpolato fra i quattro pixel attorno: senza, i bordi diventano una scaletta. */
    private fun sample(frame: Frame, x: Float, y: Float): Int {
        val x0 = x.toInt().coerceIn(0, frame.width - 1)
        val y0 = y.toInt().coerceIn(0, frame.height - 1)
        val x1 = (x0 + 1).coerceAtMost(frame.width - 1)
        val y1 = (y0 + 1).coerceAtMost(frame.height - 1)
        val fx = x - x0
        val fy = y - y0
        val c00 = frame.pixels[y0 * frame.width + x0]
        val c10 = frame.pixels[y0 * frame.width + x1]
        val c01 = frame.pixels[y1 * frame.width + x0]
        val c11 = frame.pixels[y1 * frame.width + x1]
        var result = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (c00 shr shift) and 0xFF
            val b = (c10 shr shift) and 0xFF
            val c = (c01 shr shift) and 0xFF
            val d = (c11 shr shift) and 0xFF
            val top = a + (b - a) * fx
            val bottom = c + (d - c) * fx
            val value = (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
            result = result or (value shl shift)
        }
        return result
    }

    private fun luma(color: Int): Float =
        0.299f * ((color shr 16) and 0xFF) + 0.587f * ((color shr 8) and 0xFF) + 0.114f * (color and 0xFF)

    private companion object {
        /** Lato lungo di lavoro degli scatti: oltre, si paga memoria per dettaglio che si butta. */
        const val WORKING_LONG_SIDE = 1600

        /** Tetto del lato lungo della tela, perché il risultato entri nella memoria di un telefono. */
        const val MAX_CANVAS_LONG_SIDE = 5000


        /** Quanto lontano si cerca l'allineamento: due gradi è il peggio che la calibrazione dà. */
        /**
         * Metà dell'errore massimo recuperabile: la ricerca può arrivare al doppio di questo.
         * Sulla prima panoramica riuscita le correzioni vere sono state fino a 3,7°: il gimbal
         * naviga a stima, e qualche grado di deriva è la norma, non l'eccezione.
         */
        const val MAX_SEARCH_DEGREES = 4f

        /** Sotto questo passo la correzione è più fine di un pixel: cercare oltre è rumore. */
        // ---- Registrazione a piramide (l'allineamento) ----

        /** Vicini con cui confrontarsi: quello di fianco e quello della fila accanto. */
        const val MAX_ANCHORS = 2

        /** Livelli della piramide di luminanza: pieno, metà, quarto, ottavo. */
        const val PYRAMID_LEVELS = 4

        /** Griglia dei punti campione sul fotogramma fermo, a piena risoluzione. */
        const val REG_SAMPLE_STEP = 20

        /** Sotto questi campioni nella sovrapposizione la statistica non regge. */
        const val REG_MIN_SAMPLES = 80

        /** Una correlazione finale più bassa non è un allineamento, è un caso. */
        const val REG_MIN_NCC = 0.30f

        /** Due vicini che suggeriscono correzioni più lontane di così sono in disaccordo. */
        const val ANCHOR_AGREEMENT_DEGREES = 0.8f

        // ---- Punti di controllo (la rifinitura, e il metro della qualità) ----

        /** Quanti punti di controllo generare come minimo fra due fotogrammi. */
        const val CONTROL_MIN_CANDIDATES = 150

        /** Oltre questo tetto il costo cresce e la statistica no. */
        const val CONTROL_MAX_CANDIDATES = 260

        /** Si tengono solo i punti sopra questa qualità: sotto, è un'opinione. */
        const val CONTROL_KEEP_NCC = 0.80f

        /** Sotto questi superstiti la rifinitura non si applica e resta la piramide. */
        const val CONTROL_MIN_KEPT = 12

        /** La cella di partenza della griglia dei candidati; si stringe se i punti non bastano. */
        const val CONTROL_GRID_STEP = 40
        const val CONTROL_MIN_GRID_STEP = 14

        /** Mezzo lato del ritaglio confrontato: 13×13 pixel. */
        const val CONTROL_PATCH_RADIUS = 6

        /** Raggio di ricerca attorno alla previsione: sotto il passo di una foglia di palma. */
        const val CONTROL_SEARCH_DEGREES = 0.7f

        /** Sotto questo gradiente in entrambe le direzioni non è un angolo, è una superficie. */
        const val CONTROL_MIN_TEXTURE = 5f

        /** Un ritaglio quasi uniforme non ha niente da correlare. */
        const val CONTROL_MIN_VARIANCE = 40f


        /**
         * Il ritaglio di cucitura sborda dal campo del fotogramma di questo margine: le
         * correzioni di allineamento arrivano a qualche grado, e un ritaglio giusto giusto
         * taglierebbe proprio la striscia dove si fonde.
         */
        const val BBOX_MARGIN_DEGREES = 3f


        /** Sotto questi punti in comune il confronto non dice niente di affidabile. */
        const val MIN_OVERLAP_SAMPLES = 40

        /** Il nero pieno che [compose] lascia dove nessun fotogramma copre. */
        const val EMPTY_PIXEL = 0xFF000000.toInt()

        /** Sotto questa frazione di nero, una riga o colonna di bordo è pulita abbastanza. */
        const val EDGE_EMPTY_TOLERANCE = 0.01f

        /** Il ritaglio non mangia mai più di così: metà per dimensione resta sempre. */
        const val MIN_CROP_KEEP = 0.5f

        /** Su un telefono con heap larga si lavora più fitti: qualità, non prudenza. */
        /** La catena dei guadagni non può allontanarsi più di così dal fotogramma di partenza. */
        const val MIN_CHAIN_GAIN = 0.75f
        const val MAX_CHAIN_GAIN = 1.35f

        const val GENEROUS_WORKING_LONG_SIDE = 3_200

        /**
         * Il costo per pixel della fusione multibanda a piena scala: vettori interi, canali
         * in virgola mobile e piramidi. Decide quando [blendSubWindow] passa alla scala
         * ridotta.
         */
        const val BLEND_BYTES_PER_PX = 64f

        /** Il budget di heap concesso a una singola fusione, prima di ridurre la scala. */
        const val BLEND_HEAP_BUDGET_BYTES = 64L * 1024L * 1024L

        /**
         * Il costo per pixel *previsto* della fusione per [chooseDensity]: con la scala
         * ridotta al passo massimo restano le correzioni e le griglie piccole, non le
         * piramidi piene. Prudente per eccesso.
         */
        const val BLEND_PREDICTED_BYTES_PER_PX = 3f

        /** Quanti fili lavorano insieme alla cucitura: tutti i core, con un tetto sano. */
        const val MAX_STITCH_WORKERS = 8

        /** Oltre questi valori la stima non è più credibile e si torna alla sola traslazione. */
        const val MAX_ROLL_DEGREES = 4f
        const val MAX_FOCAL_ADJUST = 0.04f

        /** Sotto questi campioni la fotometria globale non si fida e resta la catena. */
        const val PHOTOMETRIC_MIN_SAMPLES = 40

        /** I coefficienti di vignettatura credibili per un obiettivo vero. */
        const val VIGNETTE_LIMIT = 0.8f

        /** I guadagni di esposizione fra due scatti in automatico: fino a un raddoppio. */
        const val MIN_PHOTO_GAIN = 0.5f
        const val MAX_PHOTO_GAIN = 2.0f

        /** La mappa dei possessori vive a un pixel ogni [OWNER_SCALE] per dimensione. */
        const val OWNER_SCALE = 2

        /** Il contesto attorno alla sovrapposizione che le piramidi vogliono vedere. */
        const val BLEND_CONTEXT_PX = 96

        /** Di quanto la rifinitura può spostare i fotogrammi: le sovrapposizioni si stimano larghe. */
        const val OVERLAP_SLACK_DEGREES = 12f

        /**
         * Il tetto assoluto della tela, per il buon senso: oltre, il solo salvataggio JPEG
         * dura minuti. Alto abbastanza da non tagliare mai prima della memoria: su una fila
         * da 180° tagliava a 65 px/grado quando gli originali ne portavano 115.
         */
        const val CANVAS_HARD_CAP_LONG_SIDE = 24_000

        /** Una riga con più buchi di così è già dentro il foro, non sul suo bordo. */
        const val MAX_EMPTY_FRACTION = 0.10f
    }
}

/** Il fattore di riduzione che il decodificatore accetta: potenze di due, mai sotto uno. */
fun sampleSizeFor(sourceWidth: Int, targetLongSide: Int): Int {
    if (sourceWidth <= 0 || targetLongSide <= 0) return 1
    var size = 1
    while (sourceWidth / (size * 2) >= targetLongSide) size *= 2
    return size
}

