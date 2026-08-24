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
 * gradi — e la correzione si misura sui **punti di coerenza**, come fanno Autopano e i suoi
 * eredi: dettagli con carattere trovati nel vicino già sistemato, ritrovati per correlazione
 * nel fotogramma da sistemare, e trasformati ciascuno in un voto in gradi. La mediana dei voti
 * scarta gli accoppiamenti sbagliati, i concordi decidono. Dove i dettagli mancano (cielo,
 * muri lisci) resta la vecchia ricerca sulle differenze di colore, come rete.
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

    private class Frame(val bitmap: Bitmap, val pixels: IntArray, val label: String) {
        val width get() = bitmap.width
        val height get() = bitmap.height

        /** La luminanza, calcolata una volta: i punti di coerenza si cercano qui sopra. */
        val gray: FloatArray by lazy {
            FloatArray(pixels.size) { i ->
                val c = pixels[i]
                0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }
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
     * Corregge la posizione di ogni fotogramma con i punti di coerenza, alla maniera di Autopano.
     *
     * Per ogni fotogramma si prendono i vicini già sistemati (fino a due: quello di fianco e
     * quello della fila accanto, così le file si richiudono fra loro), si trovano nei vicini i
     * dettagli con più carattere — angoli, non bordi lisci — e ogni dettaglio si va a cercare
     * nel fotogramma da sistemare, dove la geometria dice che dovrebbe stare. La differenza fra
     * il previsto e il trovato, in gradi, è un voto sulla correzione.
     *
     * I voti si contano alla maniera robusta: mediana, poi si tengono solo i concordi — un
     * accoppiamento sbagliato su un motivo ripetuto vota per una correzione assurda, e la
     * mediana lo ignora. Se i punti concordi sono pochi (un muro liscio, il cielo) si torna
     * alla vecchia ricerca sulle differenze di colore, che non ha bisogno di dettagli.
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
            onProgress(0.10f + 0.25f * index / frames.size, "Cerco i punti di coerenza di ${frames[index].label}")

            // I vicini già sistemati più vicini in angolo: sono quelli con cui si sovrappone.
            val anchors = (0 until index)
                .sortedBy {
                    angularDistance(
                        placements[it].effectivePan,
                        placements[it].effectiveTilt,
                        placements[index].panDegrees,
                        placements[index].tiltDegrees,
                    )
                }
                .filter {
                    angularDistance(
                        placements[it].effectivePan,
                        placements[it].effectiveTilt,
                        placements[index].panDegrees,
                        placements[index].tiltDegrees,
                    ) < max(lens.horizontalFovDegrees, lens.verticalFovDegrees)
                }
                .take(MAX_ANCHORS)
            if (anchors.isEmpty()) continue

            val votes = mutableListOf<FloatArray>()
            anchors.forEach { anchor ->
                votes += matchFeatures(
                    moving = frames[index],
                    movingPlacement = placements[index],
                    fixed = frames[anchor],
                    fixedPlacement = placements[anchor],
                    lens = lens,
                )
            }

            var offset: Offset? = null
            var how = ""
            if (votes.size >= MIN_MATCHES) {
                val medPan = median(votes.map { it[0] })
                val medTilt = median(votes.map { it[1] })
                val inliers = votes.filter {
                    abs(it[0] - medPan) <= INLIER_TOLERANCE_DEGREES &&
                        abs(it[1] - medTilt) <= INLIER_TOLERANCE_DEGREES
                }
                if (inliers.size >= MIN_INLIERS) {
                    offset = Offset(
                        inliers.map { it[0] }.average().toFloat(),
                        inliers.map { it[1] }.average().toFloat(),
                    )
                    how = "${votes.size} punti di coerenza, ${inliers.size} concordi"
                }
            }
            if (offset == null) {
                // Pochi dettagli o troppo discordi: la vecchia ricerca sulle differenze di
                // colore non ha bisogno di angoli, e qui fa da rete.
                offset = searchOffset(
                    moving = frames[index],
                    fixed = frames[anchors.first()],
                    movingPlacement = placements[index],
                    fixedPlacement = placements[anchors.first()],
                    lens = lens,
                    canvas = canvas,
                )
                how = "pochi punti di coerenza (${votes.size}): ricerca sul colore"
            }
            if (offset == null) {
                notes += "${frames[index].label}: sovrapposizione troppo povera, resta dov'era"
                continue
            }
            placements[index] = placements[index].copy(
                panCorrectionDegrees = offset.panDegrees,
                tiltCorrectionDegrees = offset.tiltDegrees,
            )
            val magnitude = max(abs(offset.panDegrees), abs(offset.tiltDegrees))
            worst = max(worst, magnitude)
            notes += "%s: %s · corretto %+.2f° / %+.2f°".format(
                frames[index].label,
                how,
                offset.panDegrees,
                offset.tiltDegrees,
            )
        }
        return Refinement(placements, notes, worst)
    }

    /**
     * I punti di coerenza fra due fotogrammi: dettagli del vicino ritrovati in quello da
     * sistemare, ciascuno con il suo voto di correzione in gradi.
     *
     * Il dettaglio si sceglie dove l'immagine ha carattere in *entrambe* le direzioni — un
     * angolo, non un bordo: un bordo si riconosce solo di traverso e lungo di sé scivola. Il
     * confronto è una correlazione normalizzata, indifferente a esposizioni diverse, e un
     * accoppiamento ambiguo — un secondo posto quasi buono altrove — si butta: sui motivi
     * ripetuti l'errore sicuro vale meno di nessuna risposta.
     */
    private fun matchFeatures(
        moving: Frame,
        movingPlacement: FramePlacement,
        fixed: Frame,
        fixedPlacement: FramePlacement,
        lens: PinholeLens,
    ): List<FloatArray> {
        val fixedGray = fixed.gray
        val movingGray = moving.gray
        val margin = PATCH_RADIUS + 2
        val searchPx = (lens.focalPixels * (MAX_SEARCH_DEGREES * 2f).toRadians())
            .coerceAtMost(min(moving.width, moving.height) / 3f)

        // Candidati: il punto con più carattere dentro ogni cella di una griglia.
        val candidates = mutableListOf<IntArray>()
        var cy = margin
        while (cy < fixed.height - margin) {
            var cx = margin
            while (cx < fixed.width - margin) {
                var bestScore = 0f
                var bestX = -1
                var bestY = -1
                var y = cy
                val yEnd = min(cy + GRID_STEP, fixed.height - margin)
                val xEnd = min(cx + GRID_STEP, fixed.width - margin)
                while (y < yEnd) {
                    var x = cx
                    while (x < xEnd) {
                        val i = y * fixed.width + x
                        val dx = abs(fixedGray[i + 2] - fixedGray[i - 2])
                        val dy = abs(fixedGray[i + 2 * fixed.width] - fixedGray[i - 2 * fixed.width])
                        val score = min(dx, dy)
                        if (score > bestScore) {
                            bestScore = score
                            bestX = x
                            bestY = y
                        }
                        x += 2
                    }
                    y += 2
                }
                if (bestScore >= MIN_TEXTURE && bestX >= 0) candidates += intArrayOf(bestX, bestY, (bestScore * 16f).toInt())
                cx += GRID_STEP
            }
            cy += GRID_STEP
        }
        candidates.sortByDescending { it[2] }

        val matches = mutableListOf<FloatArray>()
        for (candidate in candidates) {
            if (matches.size >= MAX_FEATURES) break
            val world = frameToWorld(candidate[0].toFloat(), candidate[1].toFloat(), fixedPlacement, lens)
            val predicted = projectToFrame(world[0], world[1], movingPlacement, lens)
            if (!predicted.inside) continue

            val found = trackPatch(
                src = fixedGray, srcWidth = fixed.width,
                sourceX = candidate[0], sourceY = candidate[1],
                dst = movingGray, dstWidth = moving.width, dstHeight = moving.height,
                centerX = predicted.x, centerY = predicted.y,
                radius = searchPx,
            ) ?: continue

            val worldFound = frameToWorld(found[0], found[1], movingPlacement, lens)
            val dPan = wrapDegrees(world[0] - worldFound[0])
            val dTilt = world[1] - worldFound[1]
            if (abs(dPan) > MAX_SEARCH_DEGREES * 2f || abs(dTilt) > MAX_SEARCH_DEGREES * 2f) continue
            matches += floatArrayOf(dPan, dTilt)
        }
        return matches
    }

    /**
     * Ritrova il ritaglio attorno a (sourceX, sourceY) di [src] dentro [dst], partendo dal
     * punto previsto e guardando in un raggio. Prima una passata rada su un ritaglio sfoltito,
     * poi la rifinitura piena attorno al migliore. Correlazione a media e varianza tolte: due
     * esposizioni diverse dello stesso dettaglio danno lo stesso punteggio.
     */
    private fun trackPatch(
        src: FloatArray,
        srcWidth: Int,
        sourceX: Int,
        sourceY: Int,
        dst: FloatArray,
        dstWidth: Int,
        dstHeight: Int,
        centerX: Float,
        centerY: Float,
        radius: Float,
    ): FloatArray? {
        val r = PATCH_RADIUS
        val side = 2 * r + 1

        // Il modello, con media e norma pronte (versione piena e versione sfoltita).
        val template = FloatArray(side * side)
        var mean = 0f
        for (dy in -r..r) {
            for (dx in -r..r) {
                val v = src[(sourceY + dy) * srcWidth + sourceX + dx]
                template[(dy + r) * side + dx + r] = v
                mean += v
            }
        }
        mean /= template.size
        var norm = 0f
        for (i in template.indices) {
            template[i] -= mean
            norm += template[i] * template[i]
        }
        if (norm < MIN_PATCH_VARIANCE) return null
        norm = sqrt(norm)

        fun zncc(px: Int, py: Int, step: Int): Float {
            var sum = 0f
            var count = 0
            var dy = -r
            while (dy <= r) {
                var dx = -r
                while (dx <= r) {
                    sum += dst[(py + dy) * dstWidth + px + dx]
                    count++
                    dx += step
                }
                dy += step
            }
            val dstMean = sum / count
            var cross = 0f
            var dstNorm = 0f
            var tNorm = 0f
            dy = -r
            while (dy <= r) {
                var dx = -r
                while (dx <= r) {
                    val d = dst[(py + dy) * dstWidth + px + dx] - dstMean
                    val t = template[(dy + r) * side + dx + r]
                    cross += d * t
                    dstNorm += d * d
                    tNorm += t * t
                    dx += step
                }
                dy += step
            }
            if (dstNorm < MIN_PATCH_VARIANCE) return -1f
            return cross / (sqrt(dstNorm) * sqrt(tNorm))
        }

        val minX = (centerX - radius).toInt().coerceAtLeast(r)
        val maxX = (centerX + radius).toInt().coerceAtMost(dstWidth - 1 - r)
        val minY = (centerY - radius).toInt().coerceAtLeast(r)
        val maxY = (centerY + radius).toInt().coerceAtMost(dstHeight - 1 - r)
        if (minX > maxX || minY > maxY) return null

        var bestX = -1
        var bestY = -1
        var best = -1f
        var second = -1f
        var y = minY
        while (y <= maxY) {
            var x = minX
            while (x <= maxX) {
                val score = zncc(x, y, COARSE_PATCH_STEP)
                if (score > best) {
                    if (abs(x - bestX) > DISTINCT_MIN_DISTANCE_PX || abs(y - bestY) > DISTINCT_MIN_DISTANCE_PX) second = best
                    best = score
                    bestX = x
                    bestY = y
                } else if (score > second &&
                    (abs(x - bestX) > DISTINCT_MIN_DISTANCE_PX || abs(y - bestY) > DISTINCT_MIN_DISTANCE_PX)
                ) {
                    second = score
                }
                x += COARSE_SEARCH_STEP
            }
            y += COARSE_SEARCH_STEP
        }
        if (bestX < 0 || best < MIN_NCC) return null
        // Ambiguo: un secondo posto quasi identico lontano dal primo è un motivo ripetuto.
        if (second > best * DISTINCT_RATIO) return null

        var fineX = bestX
        var fineY = bestY
        var fineBest = -1f
        for (py in (bestY - COARSE_SEARCH_STEP)..(bestY + COARSE_SEARCH_STEP)) {
            if (py < r || py > dstHeight - 1 - r) continue
            for (px in (bestX - COARSE_SEARCH_STEP)..(bestX + COARSE_SEARCH_STEP)) {
                if (px < r || px > dstWidth - 1 - r) continue
                val score = zncc(px, py, 1)
                if (score > fineBest) {
                    fineBest = score
                    fineX = px
                    fineY = py
                }
            }
        }
        if (fineBest < MIN_NCC) return null
        return floatArrayOf(fineX.toFloat(), fineY.toFloat())
    }

    /** La mediana: il voto che i bari non spostano. */
    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
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


        /** Quanto lontano si cerca l'allineamento: due gradi è il peggio che la calibrazione dà. */
        /**
         * Metà dell'errore massimo recuperabile: la ricerca può arrivare al doppio di questo.
         * Sulla prima panoramica riuscita le correzioni vere sono state fino a 3,7°: il gimbal
         * naviga a stima, e qualche grado di deriva è la norma, non l'eccezione.
         */
        const val MAX_SEARCH_DEGREES = 4f

        /** Sotto questo passo la correzione è più fine di un pixel: cercare oltre è rumore. */
        // ---- Punti di coerenza (l'allineamento alla Autopano) ----

        /** Vicini con cui confrontarsi: quello di fianco e quello della fila accanto. */
        const val MAX_ANCHORS = 2

        /** Sotto questi voti la statistica non regge e si passa alla ricerca sul colore. */
        const val MIN_MATCHES = 10
        const val MIN_INLIERS = 6

        /** Un voto più lontano di così dalla mediana è un accoppiamento sbagliato, non rumore. */
        const val INLIER_TOLERANCE_DEGREES = 0.5f

        /** Una cella della griglia dei candidati: un punto con carattere per cella. */
        const val GRID_STEP = 28

        /** Mezzo lato del ritaglio confrontato: 15×15 pixel, abbastanza carattere, poco costo. */
        const val PATCH_RADIUS = 7

        const val MAX_FEATURES = 120

        /** Sotto questo gradiente in entrambe le direzioni non è un angolo, è una superficie. */
        const val MIN_TEXTURE = 5f

        /** Una correlazione più bassa non è un ritrovamento, è una coincidenza. */
        const val MIN_NCC = 0.55f

        /** Un ritaglio quasi uniforme non ha niente da correlare. */
        const val MIN_PATCH_VARIANCE = 40f

        /** Passata rada: ogni 3 pixel, sul ritaglio sfoltito a metà. */
        const val COARSE_SEARCH_STEP = 3
        const val COARSE_PATCH_STEP = 2

        /** Un secondo posto oltre questa frazione del primo, lontano da lui, è ambiguità. */
        const val DISTINCT_RATIO = 0.92f
        const val DISTINCT_MIN_DISTANCE_PX = 6

        /**
         * Il ritaglio di cucitura sborda dal campo del fotogramma di questo margine: le
         * correzioni di allineamento arrivano a qualche grado, e un ritaglio giusto giusto
         * taglierebbe proprio la striscia dove si fonde.
         */
        const val BBOX_MARGIN_DEGREES = 3f

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

