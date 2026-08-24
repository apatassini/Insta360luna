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
    ): Result<StitchOutcome> = withContext(Dispatchers.Default) {
        runCatching {
            require(shots.size >= 2) { "Servono almeno due scatti per unire una panoramica" }

            onProgress(0.02f, "Leggo gli scatti")
            val frames = loadFrames(shots)
            val first = frames.first().bitmap
            val lens = PinholeLens(first.width, first.height, horizontalFovDegrees)

            var placements = shots.map { FramePlacement(it.panDegrees, it.tiltDegrees) }
            val canvas = PanoramaCanvas.covering(
                placements = placements,
                lens = lens,
                requestedPixelsPerDegree = lens.imageWidth / lens.horizontalFovDegrees,
                maximumLongSide = MAX_CANVAS_LONG_SIDE,
            )

            onProgress(0.10f, "Allineo i fotogrammi")
            val refinement = refine(frames, placements, lens, canvas)
            placements = refinement.placements

            onProgress(0.35f, "Unisco e sfumo le giunzioni")
            val bitmap = compose(frames, placements, lens, canvas)
            val patchedRows = if (fillNadir) {
                onProgress(0.97f, "Chiudo il buco sotto")
                fillNadirHole(bitmap)
            } else {
                0
            }

            frames.forEach { it.bitmap.recycle() }
            onProgress(1f, "Panoramica pronta")
            StitchOutcome(
                bitmap = bitmap,
                report = StitchReport(
                    frames = frames.size,
                    canvasWidth = canvas.width,
                    canvasHeight = canvas.height,
                    coverageHorizontalDegrees = canvas.horizontalDegrees,
                    coverageVerticalDegrees = canvas.verticalDegrees,
                    refinements = refinement.notes,
                    worstCorrectionDegrees = refinement.worstCorrection,
                    nadirPatchRows = patchedRows,
                ),
            )
        }
    }

    /** Un livello della piramide di luminanza: i dati e la sua misura. */
    private class GrayLevel(val data: FloatArray, val width: Int, val height: Int)

    private class Frame(val bitmap: Bitmap, val pixels: IntArray, val label: String) {
        val width get() = bitmap.width
        val height get() = bitmap.height

        /** La luminanza, calcolata una volta: l'allineamento lavora qui sopra. */
        val gray: FloatArray by lazy {
            FloatArray(pixels.size) { i ->
                val c = pixels[i]
                0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }
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
        val grayLevels: List<GrayLevel> by lazy {
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
            levels
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
    private fun loadFrames(shots: List<PanoramaShot>): List<Frame> = shots.map { shot ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(shot.file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, WORKING_LONG_SIDE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(shot.file.absolutePath, options)
            ?: throw IllegalStateException("Non riesco a leggere ${shot.file.name}")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        Frame(bitmap, pixels, shot.label)
    }

    private class Refinement(
        val placements: List<FramePlacement>,
        val notes: List<String>,
        val worstCorrection: Float,
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
    ): Refinement {
        val placements = initial.toMutableList()
        val notes = mutableListOf<String>()
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

            placements[index] = placements[index].copy(
                panCorrectionDegrees = offset.panDegrees,
                tiltCorrectionDegrees = offset.tiltDegrees,
            )
            val magnitude = max(abs(offset.panDegrees), abs(offset.tiltDegrees))
            worst = max(worst, magnitude)
            notes += "%s: corretto %+.2f° / %+.2f° · concordanza %.0f%%".format(
                frames[index].label,
                offset.panDegrees,
                offset.tiltDegrees,
                offset.confidence * 100f,
            )
        }
        return Refinement(placements, notes, worst)
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
    ): Offset? {
        // Le direzioni campione: punti del fotogramma fermo che cadono anche nel mobile.
        val directions = ArrayList<FloatArray>()
        var y = REG_SAMPLE_STEP / 2
        while (y < fixed.height) {
            var x = REG_SAMPLE_STEP / 2
            while (x < fixed.width) {
                val world = frameToWorld(x.toFloat(), y.toFloat(), fixedPlacement, lens)
                val predicted = projectToFrame(world[0], world[1], movingPlacement, lens)
                if (predicted.inside) directions += floatArrayOf(world[0], world[1], x.toFloat(), y.toFloat())
                x += REG_SAMPLE_STEP
            }
            y += REG_SAMPLE_STEP
        }
        if (directions.size < REG_MIN_SAMPLES) return null

        var dPan = 0f
        var dTilt = 0f
        var confidence = -1f
        SEARCH_SCHEDULE.forEach { (levelIndex, range, step) ->
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
            var cp = -range
            while (cp <= range + 1e-3f) {
                var ct = -range
                while (ct <= range + 1e-3f) {
                    val candPan = (dPan + cp).coerceIn(-MAX_SEARCH_DEGREES * 2f, MAX_SEARCH_DEGREES * 2f)
                    val candTilt = (dTilt + ct).coerceIn(-MAX_SEARCH_DEGREES * 2f, MAX_SEARCH_DEGREES * 2f)
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
    ): Bitmap {
        val output = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        // Nero, non trasparente: un JPEG non ha trasparenza e diventerebbe bianco.
        output.eraseColor(0xFF000000.toInt())
        val gains = exposureGains(frames, placements, lens, canvas)

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

        // Il fotogramma nuovo, proiettato nel ritaglio.
        val newR = FloatArray(count)
        val newG = FloatArray(count)
        val newB = FloatArray(count)
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
                newR[i] = (gain * ((color shr 16) and 0xFF)).coerceIn(0f, 255f)
                newG[i] = (gain * ((color shr 8) and 0xFF)).coerceIn(0f, 255f)
                newB[i] = (gain * (color and 0xFF)).coerceIn(0f, 255f)
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

        val baseR = FloatArray(count)
        val baseG = FloatArray(count)
        val baseB = FloatArray(count)
        val mask = FloatArray(count)
        var hasOverlap = false
        for (i in 0 until count) {
            val old = oldColor[i]
            baseR[i] = ((old shr 16) and 0xFF).toFloat()
            baseG[i] = ((old shr 8) and 0xFF).toFloat()
            baseB[i] = (old and 0xFF).toFloat()
            when {
                newW[i] <= 0f && oldW[i] <= 0f -> Unit
                newW[i] <= 0f -> {
                    // Solo la tela: il nuovo si riempie con il vecchio, così le sue bande
                    // larghe non trascinano dentro il nero fuori campo.
                    newR[i] = baseR[i]; newG[i] = baseG[i]; newB[i] = baseB[i]
                }
                oldW[i] <= 0f -> {
                    baseR[i] = newR[i]; baseG[i] = newG[i]; baseB[i] = newB[i]
                    mask[i] = 1f
                }
                else -> {
                    hasOverlap = true
                    if (newW[i] > oldW[i]) mask[i] = 1f
                }
            }
        }

        // I punti che nessuno copre restano neri sulla tela, ma dentro le piramidi il nero
        // sanguinerebbe nelle bande larghe e scurirebbe il bordo vero della panoramica: si
        // riempiono con il colore valido più vicino, solo per la durata della fusione.
        val valid = BooleanArray(count) { newW[it] > 0f || oldW[it] > 0f }
        fillHoles(baseR, valid, bw, bh)
        fillHoles(baseG, valid, bw, bh)
        fillHoles(baseB, valid, bw, bh)
        for (i in 0 until count) {
            if (!valid[i]) {
                newR[i] = baseR[i]; newG[i] = baseG[i]; newB[i] = baseB[i]
            }
        }

        val outColor = IntArray(count)
        if (!hasOverlap) {
            // Nessuna sovrapposizione: si dipinge e basta.
            for (i in 0 until count) {
                outColor[i] = if (newW[i] > 0f) {
                    (0xFF shl 24) or (newR[i].toChannel() shl 16) or (newG[i].toChannel() shl 8) or newB[i].toChannel()
                } else {
                    oldColor[i]
                }
            }
        } else {
            val blended = MultibandBlender.blend(
                baseChannels = arrayOf(baseR, baseG, baseB),
                overlayChannels = arrayOf(newR, newG, newB),
                mask = mask,
                width = bw,
                height = bh,
            )
            for (i in 0 until count) {
                outColor[i] = if (newW[i] <= 0f && oldW[i] <= 0f) {
                    oldColor[i]
                } else {
                    (0xFF shl 24) or (blended[0][i].toChannel() shl 16) or
                        (blended[1][i].toChannel() shl 8) or blended[2][i].toChannel()
                }
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
     * Riempie i punti non validi con il valore valido più vicino sulla riga (e poi sulla
     * colonna, per le righe completamente vuote). Non è interpolazione fine: serve solo a non
     * far entrare il nero fuori campo nelle bande larghe della fusione.
     */
    private fun fillHoles(channel: FloatArray, valid: BooleanArray, width: Int, height: Int) {
        for (y in 0 until height) {
            val base = y * width
            var lastValue = Float.NaN
            for (x in 0 until width) {
                val i = base + x
                if (valid[i]) lastValue = channel[i] else if (!lastValue.isNaN()) channel[i] = lastValue
            }
            lastValue = Float.NaN
            for (x in width - 1 downTo 0) {
                val i = base + x
                if (valid[i]) lastValue = channel[i]
                else if (!lastValue.isNaN() && channel[i] == 0f) channel[i] = lastValue
            }
        }
        // Le righe senza nemmeno un punto valido prendono dalla riga valida sopra o sotto.
        for (x in 0 until width) {
            var lastValue = Float.NaN
            for (y in 0 until height) {
                val i = y * width + x
                if (valid[i]) lastValue = channel[i]
                else if (channel[i] == 0f && !lastValue.isNaN()) channel[i] = lastValue
            }
            lastValue = Float.NaN
            for (y in height - 1 downTo 0) {
                val i = y * width + x
                if (valid[i]) lastValue = channel[i]
                else if (channel[i] == 0f && !lastValue.isNaN()) channel[i] = lastValue
            }
        }
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
    ): FloatArray {
        val gains = FloatArray(frames.size) { 1f }
        for (index in 1 until frames.size) {
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
                movingSum += luma(sample(frames[index], m.x, m.y))
                fixedSum += luma(sample(frames[anchorIndex], f.x, f.y))
                counted++
            }
            gains[index] = if (counted < MIN_OVERLAP_SAMPLES) {
                gains[anchorIndex]
            } else {
                gains[anchorIndex] * exposureGain(fixedSum / counted, movingSum / counted)
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

        /**
         * La tabella della ricerca: livello della piramide, semiampiezza della finestra in
         * gradi, passo. Dalla nebbia al dettaglio: al livello sfocato la finestra copre tutto
         * l'errore possibile del gimbal, all'ultimo si rifinisce di un ventesimo di grado.
         */
        val SEARCH_SCHEDULE = listOf(
            Triple(3, MAX_SEARCH_DEGREES * 2f, 0.8f),
            Triple(2, 1.2f, 0.3f),
            Triple(1, 0.4f, 0.12f),
            Triple(0, 0.12f, 0.05f),
        )

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

