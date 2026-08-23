package it.persoft.lunaultra.stitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * **Raffinatura.** La posizione nominale non è esatta — la prova di andata e ritorno della
 * calibrazione dice che il modello sbaglia fino a un paio di gradi — e un paio di gradi su un
 * fotogramma largo sono decine di pixel, che si vedono. Allora ogni fotogramma, dopo il primo,
 * viene confrontato con quello che c'è già sulla tela nella zona in cui si sovrappongono, e si
 * cerca lo spostamento che li fa combaciare meglio. La ricerca parte grossolana e si stringe,
 * così costa poco e non si perde in un minimo locale.
 *
 * **Fusione.** Anche dopo la raffinatura la giunzione non è perfetta, e non può esserlo: due
 * scatti presi ruotando la camera hanno centri di proiezione diversi, e la parallasse fra
 * oggetti vicini e lontani non si annulla con nessuna rotazione. Quindi il confine non si fa
 * vedere invece di fingere che non ci sia: ogni pixel pesa in proporzione a quanto è lontano
 * dal bordo del suo fotogramma, e nella sovrapposizione i due si mescolano gradualmente. Con
 * una correzione di luminosità prima, perché altrimenti a vedersi non è il confine ma il salto
 * di tono ai suoi due lati.
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

    private class Frame(val bitmap: Bitmap, val pixels: IntArray, val label: String) {
        val width get() = bitmap.width
        val height get() = bitmap.height
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
     * Corregge la posizione di ogni fotogramma confrontandolo con quelli già sistemati.
     *
     * Il primo fotogramma resta dov'è: è il riferimento, e non ha senso spostare tutto rispetto
     * a niente. Ognuno dei successivi si confronta con il vicino già sistemato più prossimo, e
     * il confronto avviene su un campione di punti della zona di sovrapposizione — non su tutti,
     * perché sarebbero milioni e la risposta non cambierebbe.
     *
     * La ricerca è a passi che si dimezzano: prima si guarda lontano con passo grosso, poi si
     * stringe attorno al migliore. Trova il minimo senza provare tutte le combinazioni, e non
     * scivola in un minimo locale vicino come farebbe una discesa a passo fisso.
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

            // Il vicino già sistemato più vicino in angolo: è quello con cui si sovrappone.
            val anchorIndex = (0 until index).minByOrNull {
                angularDistance(
                    placements[it].effectivePan,
                    placements[it].effectiveTilt,
                    placements[index].panDegrees,
                    placements[index].tiltDegrees,
                )
            } ?: continue

            val best = searchOffset(
                moving = frames[index],
                fixed = frames[anchorIndex],
                movingPlacement = placements[index],
                fixedPlacement = placements[anchorIndex],
                lens = lens,
                canvas = canvas,
            )
            if (best == null) {
                notes += "${frames[index].label}: sovrapposizione troppo povera, resta dov'era"
                continue
            }
            placements[index] = placements[index].copy(
                panCorrectionDegrees = best.panDegrees,
                tiltCorrectionDegrees = best.tiltDegrees,
            )
            val magnitude = max(abs(best.panDegrees), abs(best.tiltDegrees))
            worst = max(worst, magnitude)
            notes += "%s: corretto di %+.2f° in orizzontale e %+.2f° in verticale".format(
                frames[index].label,
                best.panDegrees,
                best.tiltDegrees,
            )
        }
        return Refinement(placements, notes, worst)
    }

    private class Offset(val panDegrees: Float, val tiltDegrees: Float)

    /**
     * Lo spostamento che fa combaciare due fotogrammi, cercato a passi che si stringono.
     *
     * Si campionano dei punti della zona in cui i due si sovrappongono e si somma la differenza
     * assoluta fra i colori. Meno è, meglio combaciano. La somma delle differenze è una misura
     * grezza rispetto a una correlazione normalizzata, ma la luminosità qui viene già
     * pareggiata prima, e in cambio costa una frazione: su decine di migliaia di prove è la
     * differenza fra un secondo e un minuto.
     */
    private fun searchOffset(
        moving: Frame,
        fixed: Frame,
        movingPlacement: FramePlacement,
        fixedPlacement: FramePlacement,
        lens: PinholeLens,
        canvas: PanoramaCanvas,
    ): Offset? {
        val samples = overlapSamples(movingPlacement, fixedPlacement, lens, canvas)
        if (samples.size < MIN_OVERLAP_SAMPLES) return null

        var bestPan = 0f
        var bestTilt = 0f
        var bestScore = score(moving, fixed, samples, movingPlacement, fixedPlacement, lens, 0f, 0f)
            ?: return null
        var step = MAX_SEARCH_DEGREES
        while (step >= MIN_SEARCH_DEGREES) {
            var improved = false
            for (dp in -1..1) {
                for (dt in -1..1) {
                    if (dp == 0 && dt == 0) continue
                    val pan = bestPan + dp * step
                    val tilt = bestTilt + dt * step
                    if (abs(pan) > MAX_SEARCH_DEGREES * 2f || abs(tilt) > MAX_SEARCH_DEGREES * 2f) continue
                    val candidate = score(
                        moving, fixed, samples, movingPlacement, fixedPlacement, lens, pan, tilt,
                    ) ?: continue
                    if (candidate < bestScore) {
                        bestScore = candidate
                        bestPan = pan
                        bestTilt = tilt
                        improved = true
                    }
                }
            }
            if (!improved) step /= 2f
        }
        return Offset(bestPan, bestTilt)
    }

    /** Direzioni campione dentro la sovrapposizione fra due fotogrammi, in gradi. */
    private fun overlapSamples(
        a: FramePlacement,
        b: FramePlacement,
        lens: PinholeLens,
        canvas: PanoramaCanvas,
    ): List<FloatArray> {
        val samples = mutableListOf<FloatArray>()
        val stepLon = canvas.horizontalDegrees / SAMPLE_GRID
        val stepLat = canvas.verticalDegrees / SAMPLE_GRID
        var lat = canvas.centerTiltDegrees - canvas.verticalDegrees / 2f
        while (lat <= canvas.centerTiltDegrees + canvas.verticalDegrees / 2f) {
            var lon = canvas.centerPanDegrees - canvas.horizontalDegrees / 2f
            while (lon <= canvas.centerPanDegrees + canvas.horizontalDegrees / 2f) {
                val inA = projectToFrame(lon, lat, a, lens)
                val inB = projectToFrame(lon, lat, b, lens)
                if (inA.inside && inB.inside) samples += floatArrayOf(lon, lat)
                lon += stepLon
            }
            lat += stepLat
        }
        return samples
    }

    /** Quanto male combaciano, con il fotogramma mobile spostato di [panDelta] e [tiltDelta]. */
    private fun score(
        moving: Frame,
        fixed: Frame,
        samples: List<FloatArray>,
        movingPlacement: FramePlacement,
        fixedPlacement: FramePlacement,
        lens: PinholeLens,
        panDelta: Float,
        tiltDelta: Float,
    ): Float? {
        val shifted = movingPlacement.copy(
            panCorrectionDegrees = movingPlacement.panCorrectionDegrees + panDelta,
            tiltCorrectionDegrees = movingPlacement.tiltCorrectionDegrees + tiltDelta,
        )
        var total = 0f
        var counted = 0
        samples.forEach { sample ->
            val lon = sample[0]
            val lat = sample[1]
            val m = projectToFrame(lon, lat, shifted, lens)
            if (!m.inside) return@forEach
            val f = projectToFrame(lon, lat, fixedPlacement, lens)
            if (!f.inside) return@forEach
            val mc = sample(moving, m.x, m.y)
            val fc = sample(fixed, f.x, f.y)
            total += abs(luma(mc) - luma(fc))
            counted++
        }
        if (counted < MIN_OVERLAP_SAMPLES) return null
        return total / counted
    }

    /**
     * Dipinge la tela, un nastro di righe per volta.
     *
     * A nastri e non tutta insieme per una ragione di memoria: sommare i contributi pesati
     * richiede tre accumulatori a virgola mobile più i pesi per ogni pixel, cioè quattro volte
     * quello che occupa il risultato. Su una tela grande sarebbero centinaia di megabyte e il
     * telefono chiuderebbe l'app. Un nastro per volta costa quanto il nastro, e il risultato è
     * identico perché ogni pixel dipende solo da sé.
     */
    private suspend fun compose(
        frames: List<Frame>,
        placements: List<FramePlacement>,
        lens: PinholeLens,
        canvas: PanoramaCanvas,
    ): Bitmap {
        val output = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        val gains = exposureGains(frames, placements, lens, canvas)
        val bandRows = min(BAND_ROWS, canvas.height)
        val accumulator = FloatArray(canvas.width * bandRows * 3)
        val weights = FloatArray(canvas.width * bandRows)
        val row = IntArray(canvas.width * bandRows)

        var top = 0
        while (top < canvas.height) {
            currentCoroutineContext().ensureActive()
            val rows = min(bandRows, canvas.height - top)
            java.util.Arrays.fill(accumulator, 0f)
            java.util.Arrays.fill(weights, 0f)

            frames.forEachIndexed { index, frame ->
                val placement = placements[index]
                val gain = gains[index]
                for (y in 0 until rows) {
                    val latitude = canvas.latitudeAt(top + y)
                    for (x in 0 until canvas.width) {
                        val point = projectToFrame(canvas.longitudeAt(x), latitude, placement, lens)
                        if (!point.inside) continue
                        val weight = featherWeight(point.x, point.y, frame.width, frame.height)
                        if (weight <= 0f) continue
                        val color = sample(frame, point.x, point.y)
                        val base = (y * canvas.width + x) * 3
                        accumulator[base] += weight * gain * ((color shr 16) and 0xFF)
                        accumulator[base + 1] += weight * gain * ((color shr 8) and 0xFF)
                        accumulator[base + 2] += weight * gain * (color and 0xFF)
                        weights[y * canvas.width + x] += weight
                    }
                }
            }

            for (i in 0 until canvas.width * rows) {
                val weight = weights[i]
                row[i] = if (weight <= 0f) {
                    // Nessun fotogramma copre questo punto: la tela è rettangolare ma la
                    // panoramica non lo è, e gli angoli restano vuoti. Nero, non trasparente:
                    // un JPEG non ha trasparenza e la trasparenza diventerebbe bianco.
                    0xFF000000.toInt()
                } else {
                    val base = i * 3
                    val r = (accumulator[base] / weight).roundToInt().coerceIn(0, 255)
                    val g = (accumulator[base + 1] / weight).roundToInt().coerceIn(0, 255)
                    val b = (accumulator[base + 2] / weight).roundToInt().coerceIn(0, 255)
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            output.setPixels(row, 0, canvas.width, 0, top, canvas.width, rows)
            top += rows
            onProgress(0.35f + 0.6f * top / canvas.height, "Unisco e sfumo le giunzioni")
        }
        return output
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
            val samples = overlapSamples(placements[index], placements[anchorIndex], lens, canvas)
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

        /** Righe dipinte per volta: la memoria degli accumulatori è quattro volte il nastro. */
        const val BAND_ROWS = 192

        /** Quanto lontano si cerca l'allineamento: due gradi è il peggio che la calibrazione dà. */
        const val MAX_SEARCH_DEGREES = 2.5f

        /** Sotto questo passo la correzione è più fine di un pixel: cercare oltre è rumore. */
        const val MIN_SEARCH_DEGREES = 0.02f

        /** Punti campione lungo ogni lato della tela per trovare le sovrapposizioni. */
        const val SAMPLE_GRID = 90

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
