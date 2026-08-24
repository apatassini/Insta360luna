package it.persoft.lunaultra.stitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
                heapMb >= 256 -> 2_000
                else -> WORKING_LONG_SIDE
            }
            val canvasLongSide = when {
                heapMb >= 384 -> GENEROUS_CANVAS_LONG_SIDE
                heapMb >= 256 -> 6_000
                else -> MAX_CANVAS_LONG_SIDE
            }
            onProgress(0.02f, "Leggo gli scatti ($workingLongSide px, heap $heapMb MB)")
            val frames = loadFrames(shots, workingLongSide)
            val first = frames.first().bitmap
            val lens = PinholeLens(first.width, first.height, horizontalFovDegrees)

            var placements = shots.map { FramePlacement(it.panDegrees, it.tiltDegrees) }
            val canvas = PanoramaCanvas.covering(
                placements = placements,
                lens = lens,
                requestedPixelsPerDegree = lens.imageWidth / lens.horizontalFovDegrees,
                maximumLongSide = canvasLongSide,
            )

            onProgress(0.10f, "Allineo i fotogrammi")
            val refinement = refine(frames, placements, lens, canvas, wideSearch)
            placements = refinement.placements
            frames.forEach { it.releaseWorkingData() }

            onProgress(0.35f, "Unisco e sfumo le giunzioni")
            var bitmap = compose(frames, placements, lens, canvas, refinement.aligned)
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
            notes.add(0, "Risoluzione di lavoro $workingLongSide px, tela fino a $canvasLongSide px (heap $heapMb MB)")
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

    /** Un livello della piramide di luminanza: i dati e la sua misura. */
    private class GrayLevel(val data: FloatArray, val width: Int, val height: Int)

    private class Frame(val bitmap: Bitmap, val label: String) {
        val width get() = bitmap.width
        val height get() = bitmap.height

        private var pixelsCache: IntArray? = null
        private var grayCache: FloatArray? = null
        private var levelsCache: List<GrayLevel>? = null

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
    private fun loadFrames(shots: List<PanoramaShot>, workingLongSide: Int): List<Frame> = shots.map { shot ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(shot.file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, workingLongSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(shot.file.absolutePath, options)
            ?: throw IllegalStateException("Non riesco a leggere ${shot.file.name}")
        // Il decodificatore riduce solo per potenze di due: una foto da 4000 pixel scende a
        // 2000, non a 1600, e quel 25% in più di lato è il 56% in più di memoria — che
        // moltiplicato per cinque foto è la differenza fra unire e morire di memoria.
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
        Frame(bitmap, shot.label)
    }

    private class Refinement(
        val placements: List<FramePlacement>,
        val notes: List<String>,
        val worstCorrection: Float,
        /** Chi è stato allineato davvero: chi no, ha una posizione di fiducia, non misurata. */
        val aligned: BooleanArray,
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
            }
            var finalPan = offset.panDegrees
            var finalTilt = offset.tiltDegrees
            if (kept.size >= CONTROL_MIN_KEPT) {
                finalPan += trimmedMean(kept.map { it[0] })
                finalTilt += trimmedMean(kept.map { it[1] })
            }

            aligned[index] = true
            placements[index] = placements[index].copy(
                panCorrectionDegrees = finalPan,
                tiltCorrectionDegrees = finalTilt,
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
            notes += "%s: %d punti di controllo, %d sopra l'%d%% · corretto %+.2f° / %+.2f° · concordanza %.0f%%".format(
                frames[index].label,
                candidates,
                kept.size,
                (CONTROL_KEEP_NCC * 100).toInt(),
                finalPan,
                finalTilt,
                offset.confidence * 100f,
            )
        }
        return Refinement(placements, notes, worst, aligned)
    }

    private class ControlPointTally(val candidates: Int, val kept: List<FloatArray>)

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
    private fun controlPoints(
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

        val kept = mutableListOf<FloatArray>()
        for (candidate in picked.take(CONTROL_MAX_CANDIDATES)) {
            val world = frameToWorld(candidate[0].toFloat(), candidate[1].toFloat(), fixedPlacement, lens)
            val predicted = projectToFrame(world[0], world[1], movingPlacement, lens)
            if (!predicted.inside) continue
            val found = matchControlPoint(
                template = gray, templateWidth = fixed.width,
                sourceX = candidate[0], sourceY = candidate[1],
                target = moving.gray, targetWidth = moving.width, targetHeight = moving.height,
                centerX = predicted.x, centerY = predicted.y,
                radiusPx = searchPx,
            ) ?: continue
            val worldFound = frameToWorld(found[0], found[1], movingPlacement, lens)
            kept += floatArrayOf(
                wrapDegrees(world[0] - worldFound[0]),
                world[1] - worldFound[1],
            )
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
    ): Bitmap {
        val output = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        // Nero, non trasparente: un JPEG non ha trasparenza e diventerebbe bianco.
        output.eraseColor(0xFF000000.toInt())
        val gains = exposureGains(frames, placements, lens, canvas, aligned)

        // Chi possiede ogni pixel della tela, quantificato: il peso della sfumatura del
        // fotogramma che l'ha dipinto. Serve a decidere le maschere dei fotogrammi successivi.
        val ownerWeight = ByteArray(canvas.width * canvas.height)

        frames.forEachIndexed { index, frame ->
            currentCoroutineContext().ensureActive()
            onProgress(
                0.35f + 0.6f * index / frames.size,
                "Cucio ${frame.label} (${index + 1}/${frames.size})",
            )
            pasteFrame(output, ownerWeight, frame, placements[index], gains[index], lens, canvas)
            // Un fotogramma alla volta anche in memoria: cucito, i suoi vettori si liberano.
            frame.releaseWorkingData()
        }
        return output
    }

    /**
     * Cuce un fotogramma sulla tela, fondendo in multibanda dentro il suo rettangolo.
     *
     * Le zone dove solo uno dei due esiste vengono riempite con l'altro prima delle piramidi:
     * senza, il nero fuori campo entrerebbe nelle bande larghe e scurirebbe i bordi veri.
     */
    private fun pasteFrame(
        output: Bitmap,
        ownerWeight: ByteArray,
        frame: Frame,
        placement: FramePlacement,
        gain: Float,
        lens: PinholeLens,
        canvas: PanoramaCanvas,
    ) {
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

        // Il fotogramma nuovo, proiettato nel ritaglio. Colori impacchettati e non canali in
        // virgola mobile: la fusione lavora un canale alla volta proprio per non tenere in
        // memoria tre piani di float per due immagini insieme.
        val newColor = IntArray(count)
        val newW = FloatArray(count)
        for (by in 0 until bh) {
            val latitude = canvas.latitudeAt(row0 + by)
            for (bx in 0 until bw) {
                val longitude = canvas.longitudeAt(columns[bx])
                val point = projectToFrame(longitude, latitude, placement, lens)
                if (!point.inside) continue
                val weight = featherWeight(point.x, point.y, frame.width, frame.height)
                if (weight <= 0f) continue
                val color = sample(frame, point.x, point.y)
                val i = by * bw + bx
                newW[i] = weight
                val r = (gain * ((color shr 16) and 0xFF)).roundToInt().coerceIn(0, 255)
                val g = (gain * ((color shr 8) and 0xFF)).roundToInt().coerceIn(0, 255)
                val b = (gain * (color and 0xFF)).roundToInt().coerceIn(0, 255)
                newColor[i] = (r shl 16) or (g shl 8) or b
            }
        }

        // Quello che c'è già sulla tela, nello stesso ritaglio.
        val oldColor = IntArray(count)
        readRegion(output, columns, row0, bw, bh, oldColor)
        val oldW = FloatArray(count)
        for (by in 0 until bh) {
            val rowBase = (row0 + by) * canvas.width
            for (bx in 0 until bw) {
                oldW[by * bw + bx] = (ownerWeight[rowBase + columns[bx]].toInt() and 0xFF) / 255f
            }
        }

        val baseColor = IntArray(count)
        val mask = FloatArray(count)
        var hasOverlap = false
        for (i in 0 until count) {
            baseColor[i] = oldColor[i] and 0xFFFFFF
            when {
                newW[i] <= 0f && oldW[i] <= 0f -> Unit
                newW[i] <= 0f -> newColor[i] = baseColor[i]
                oldW[i] <= 0f -> {
                    baseColor[i] = newColor[i]
                    mask[i] = 1f
                }
                else -> {
                    hasOverlap = true
                    if (newW[i] > oldW[i]) mask[i] = 1f
                }
            }
        }

        val outColor = IntArray(count)
        if (!hasOverlap) {
            // Nessuna sovrapposizione: si dipinge e basta.
            for (i in 0 until count) {
                outColor[i] = if (newW[i] > 0f) 0xFF000000.toInt() or newColor[i] else oldColor[i]
            }
        } else {
            // I punti che nessuno copre restano neri sulla tela, ma dentro le piramidi il nero
            // sanguinerebbe nelle bande larghe e scurirebbe il bordo vero della panoramica: si
            // riempiono con il colore valido più vicino, solo per la durata della fusione.
            val valid = BooleanArray(count) { newW[it] > 0f || oldW[it] > 0f }
            fillHoles(baseColor, valid, bw, bh)
            for (i in 0 until count) if (!valid[i]) newColor[i] = baseColor[i]

            // Un canale per volta: la memoria della fusione è un piano, non tre.
            java.util.Arrays.fill(outColor, 0xFF000000.toInt())
            for (shift in intArrayOf(16, 8, 0)) {
                val baseChannel = FloatArray(count) { ((baseColor[it] shr shift) and 0xFF).toFloat() }
                val overChannel = FloatArray(count) { ((newColor[it] shr shift) and 0xFF).toFloat() }
                val blended = MultibandBlender.blend(
                    baseChannels = arrayOf(baseChannel),
                    overlayChannels = arrayOf(overChannel),
                    mask = mask,
                    width = bw,
                    height = bh,
                )[0]
                for (i in 0 until count) {
                    outColor[i] = outColor[i] or (blended[i].toChannel() shl shift)
                }
            }
            for (i in 0 until count) {
                if (newW[i] <= 0f && oldW[i] <= 0f) outColor[i] = oldColor[i]
            }
        }

        writeRegion(output, columns, row0, bw, bh, outColor)
        for (by in 0 until bh) {
            val rowBase = (row0 + by) * canvas.width
            for (bx in 0 until bw) {
                val i = by * bw + bx
                val winner = max(oldW[i], newW[i])
                val quantized = (winner * 255f).roundToInt().coerceIn(0, 255)
                val index = rowBase + columns[bx]
                if (quantized > (ownerWeight[index].toInt() and 0xFF)) {
                    ownerWeight[index] = quantized.toByte()
                }
            }
        }
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
            // Il pareggio di colore ha senso solo su una sovrapposizione vera: su un
            // fotogramma non allineato confronterebbe due pezzi di mondo diversi e
            // inventerebbe un guadagno — i colori falsati della prova a mano venivano da qui.
            if (!aligned[index]) continue

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

        const val GENEROUS_WORKING_LONG_SIDE = 2_400
        const val GENEROUS_CANVAS_LONG_SIDE = 8_000

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

