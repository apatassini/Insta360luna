package it.persoft.lunaultra.diagnostics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class PositionVerdict(val label: String) {
    CORRECT("POSIZIONE CORRETTA"),
    SHIFTED("INQUADRATURA SPOSTATA"),
    WRONG("INQUADRATURA NON CORRISPONDENTE"),
    UNCERTAIN("VERIFICA INCERTA"),
    NO_FEATURES("POCHI DETTAGLI PER LA VERIFICA"),
}

data class ControlPoint(
    val referenceX: Float,
    val referenceY: Float,
    val currentX: Float,
    val currentY: Float,
    val inlier: Boolean,
)

data class ImageVerification(
    val referenceFeatures: Int,
    val currentFeatures: Int,
    val candidateMatches: Int,
    val inlierMatches: Int,
    val shiftX: Float,
    val shiftY: Float,
    val confidence: Float,
    val verdict: PositionVerdict,
    val controlPoints: List<ControlPoint>,
) {
    val displacementPixels: Float get() = hypot(shiftX, shiftY)

    fun describe(): String = buildString {
        appendLine("Verifica visiva: ${verdict.label}")
        appendLine("Punti caratteristici: riferimento $referenceFeatures · immagine reale $currentFeatures")
        appendLine("Corrispondenze: $candidateMatches · consenso RANSAC: $inlierMatches")
        appendLine("Spostamento immagine: Δx %+.1f px · Δy %+.1f px · modulo %.1f px".format(shiftX, shiftY, displacementPixels))
        append("Confidenza: %.0f%%".format(confidence * 100f))
    }
}

/**
 * Verifica un waypoint con una pipeline ridotta dello stesso tipo usato nello stitching:
 * corner → descrittore binario → ratio test/cross-check → consenso geometrico RANSAC.
 *
 * La trasformazione cercata è una traslazione, non una omografia completa. La camera e la
 * lente sono le stesse e il test avviene sul medesimo waypoint: per stabilire se l'inquadratura
 * è tornata al punto giusto la traslazione è più stabile, leggibile e richiede molti meno punti.
 */
object WaypointImageVerifier {

    fun verify(referenceJpeg: ByteArray?, currentJpeg: ByteArray?): ImageVerification? {
        if (referenceJpeg == null || currentJpeg == null) return null
        val reference = BitmapFactory.decodeByteArray(referenceJpeg, 0, referenceJpeg.size) ?: return null
        val current = BitmapFactory.decodeByteArray(currentJpeg, 0, currentJpeg.size) ?: return null
        return verify(reference, current)
    }

    fun verify(reference: Bitmap, current: Bitmap): ImageVerification {
        val refGray = GrayImage.from(reference)
        val curGray = GrayImage.from(current)
        val refFeatures = describe(refGray)
        val curFeatures = describe(curGray)
        val candidates = match(refFeatures, curFeatures)

        val dense = denseTranslation(refGray, curGray, refFeatures.size, curFeatures.size)
        if (refFeatures.size < MIN_FEATURES || curFeatures.size < MIN_FEATURES) {
            return dense ?: emptyVerification(refFeatures.size, curFeatures.size, candidates.size, PositionVerdict.NO_FEATURES)
        }
        if (candidates.size < MIN_MATCHES) {
            return dense ?: emptyVerification(refFeatures.size, curFeatures.size, candidates.size, PositionVerdict.WRONG)
        }

        val consensus = translationConsensus(candidates)
        val inlierRatio = consensus.inliers.size.toFloat() / candidates.size.coerceAtLeast(1)
        val support = (consensus.inliers.size / GOOD_SUPPORT.toFloat()).coerceIn(0f, 1f)
        val confidence = (inlierRatio * 0.58f + support * 0.42f).coerceIn(0f, 1f)
        val displacement = hypot(consensus.dx, consensus.dy)
        val verdict = when {
            consensus.inliers.size < MIN_CONSENSUS -> PositionVerdict.WRONG
            confidence < MIN_CONFIDENCE -> PositionVerdict.UNCERTAIN
            displacement <= CORRECT_RADIUS_PX -> PositionVerdict.CORRECT
            displacement <= OVERLAP_RADIUS_PX -> PositionVerdict.SHIFTED
            else -> PositionVerdict.WRONG
        }
        val inliers = consensus.inliers.toSet()
        val sparse = ImageVerification(
            referenceFeatures = refFeatures.size,
            currentFeatures = curFeatures.size,
            candidateMatches = candidates.size,
            inlierMatches = consensus.inliers.size,
            shiftX = consensus.dx,
            shiftY = consensus.dy,
            confidence = confidence,
            verdict = verdict,
            controlPoints = candidates.map { match ->
                ControlPoint(
                    referenceX = match.reference.x.toFloat(),
                    referenceY = match.reference.y.toFloat(),
                    currentX = match.current.x.toFloat(),
                    currentY = match.current.y.toFloat(),
                    inlier = match in inliers,
                )
            },
        )
        return if (sparse.confidence >= MIN_CONFIDENCE || dense == null || sparse.confidence >= dense.confidence) {
            sparse
        } else {
            dense
        }
    }

    /** Miniatura reale con i punti verdi del consenso e gli scarti rossi. */
    fun annotatedCurrentJpeg(currentJpeg: ByteArray?, verification: ImageVerification?): ByteArray? {
        if (currentJpeg == null || verification == null) return currentJpeg
        val decoded = BitmapFactory.decodeByteArray(currentJpeg, 0, currentJpeg.size) ?: return currentJpeg
        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val inlierPaint = pointPaint(Color.rgb(65, 235, 145))
        val outlierPaint = pointPaint(Color.rgb(255, 92, 92))
        verification.controlPoints.forEach { point ->
            canvas.drawCircle(
                point.currentX,
                point.currentY,
                if (point.inlier) 4.5f else 3f,
                if (point.inlier) inlierPaint else outlierPaint,
            )
        }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            output.toByteArray()
        }
    }

    private fun pointPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private fun emptyVerification(
        referenceFeatures: Int,
        currentFeatures: Int,
        candidates: Int,
        verdict: PositionVerdict,
    ) = ImageVerification(
        referenceFeatures = referenceFeatures,
        currentFeatures = currentFeatures,
        candidateMatches = candidates,
        inlierMatches = 0,
        shiftX = 0f,
        shiftY = 0f,
        confidence = 0f,
        verdict = verdict,
        controlPoints = emptyList(),
    )

    private data class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
        operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

        companion object {
            fun from(bitmap: Bitmap): GrayImage {
                val width = bitmap.width
                val height = bitmap.height
                val colors = IntArray(width * height)
                bitmap.getPixels(colors, 0, width, 0, 0, width, height)
                val gray = IntArray(colors.size)
                colors.forEachIndexed { index, color ->
                    gray[index] = (Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) shr 8
                }
                return GrayImage(width, height, gray)
            }
        }
    }

    private data class Feature(val x: Int, val y: Int, val bits: LongArray)
    private data class Match(val reference: Feature, val current: Feature, val distance: Int)
    private data class Consensus(val dx: Float, val dy: Float, val inliers: List<Match>)
    private data class Corner(val x: Int, val y: Int, val score: Double)

    /**
     * Fallback per cielo, pareti e superfici con pochi angoli: confronta tutta la luminanza
     * ridotta tramite correlazione normalizzata. Le nuvole deboli che non superano la soglia
     * Harris restano comunque una tessitura sufficiente; un'immagine davvero piatta viene
     * rifiutata misurandone la deviazione standard.
     */
    private fun denseTranslation(
        reference: GrayImage,
        current: GrayImage,
        referenceFeatureCount: Int,
        currentFeatureCount: Int,
    ): ImageVerification? {
        val ref = DenseImage.from(reference)
        val cur = DenseImage.from(current)
        if (ref.width != cur.width || ref.height != cur.height) return null
        if (min(ref.standardDeviation, cur.standardDeviation) < MIN_DENSE_STD_DEV) return null

        var bestScore = -1f
        var secondScore = -1f
        var bestDx = 0
        var bestDy = 0
        for (dy in -MAX_DENSE_SHIFT..MAX_DENSE_SHIFT) {
            for (dx in -MAX_DENSE_SHIFT..MAX_DENSE_SHIFT) {
                val score = denseCorrelation(ref, cur, dx, dy)
                if (score > bestScore) {
                    secondScore = bestScore
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                } else if (score > secondScore && abs(dx - bestDx) + abs(dy - bestDy) > 2) {
                    secondScore = score
                }
            }
        }
        if (bestScore < MIN_DENSE_CORRELATION) return null

        // Un motivo che si ripete — le doghe di un soffitto, una tapparella, una staccionata —
        // combacia altrettanto bene spostato di un passo del motivo. La correlazione trova
        // allora due picchi quasi uguali in posti diversi, e sceglierne uno è tirare a
        // indovinare: la risposta esce "identica, 100%" mentre la camera si è mossa eccome.
        // È così che una ricerca del fine corsa si è fermata al primo impulso, con la camera
        // in piena corsa. Quando i due picchi non si distinguono, qui non si risponde.
        if (secondScore > 0f && secondScore > bestScore * MAX_DENSE_PEAK_RATIO) return null

        val scoreConfidence = ((bestScore - MIN_DENSE_CORRELATION) /
            (1f - MIN_DENSE_CORRELATION)).coerceIn(0f, 1f)
        val separation = ((bestScore - secondScore).coerceAtLeast(0f) * 3.5f).coerceIn(0f, 1f)
        // La separazione fra i picchi pesa quanto il punteggio: un picco alto ma non isolato
        // vale meno di un picco medio e solo.
        val confidence = (scoreConfidence * 0.5f + separation * 0.5f).coerceIn(0f, 1f)
        val scaleX = reference.width.toFloat() / ref.width
        val scaleY = reference.height.toFloat() / ref.height
        val shiftX = bestDx * scaleX
        val shiftY = bestDy * scaleY
        val displacement = hypot(shiftX, shiftY)
        val verdict = when {
            confidence < MIN_DENSE_CONFIDENCE -> PositionVerdict.UNCERTAIN
            displacement <= CORRECT_RADIUS_PX -> PositionVerdict.CORRECT
            displacement <= DENSE_OVERLAP_RADIUS_PX -> PositionVerdict.SHIFTED
            else -> PositionVerdict.WRONG
        }
        val points = denseControlPoints(reference.width, reference.height, shiftX, shiftY)
        return ImageVerification(
            referenceFeatures = referenceFeatureCount,
            currentFeatures = currentFeatureCount,
            candidateMatches = points.size,
            inlierMatches = points.size,
            shiftX = shiftX,
            shiftY = shiftY,
            confidence = confidence,
            verdict = verdict,
            controlPoints = points,
        )
    }

    private data class DenseImage(
        val width: Int,
        val height: Int,
        val pixels: FloatArray,
        val standardDeviation: Float,
    ) {
        operator fun get(x: Int, y: Int): Float = pixels[y * width + x]

        companion object {
            fun from(source: GrayImage): DenseImage {
                val width = DENSE_SIZE
                val height = DENSE_SIZE
                val pixels = FloatArray(width * height)
                var sum = 0f
                var sumSquares = 0f
                for (y in 0 until height) for (x in 0 until width) {
                    val sourceX = (x * source.width / width).coerceIn(0, source.width - 1)
                    val sourceY = (y * source.height / height).coerceIn(0, source.height - 1)
                    val value = source[sourceX, sourceY].toFloat()
                    pixels[y * width + x] = value
                    sum += value
                    sumSquares += value * value
                }
                val count = pixels.size.coerceAtLeast(1)
                val mean = sum / count
                val variance = (sumSquares / count - mean * mean).coerceAtLeast(0f)
                return DenseImage(width, height, pixels, sqrt(variance))
            }
        }
    }

    private fun denseCorrelation(reference: DenseImage, current: DenseImage, dx: Int, dy: Int): Float {
        val startX = max(DENSE_BORDER, max(DENSE_BORDER, -dx + DENSE_BORDER))
        val endX = min(reference.width - DENSE_BORDER, current.width - dx - DENSE_BORDER)
        val startY = max(DENSE_BORDER, max(DENSE_BORDER, -dy + DENSE_BORDER))
        val endY = min(reference.height - DENSE_BORDER, current.height - dy - DENSE_BORDER)
        if (endX - startX < DENSE_MIN_OVERLAP || endY - startY < DENSE_MIN_OVERLAP) return -1f

        var sumA = 0.0
        var sumB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        var sumAB = 0.0
        var count = 0
        for (y in startY until endY) for (x in startX until endX) {
            val a = reference[x, y].toDouble()
            val b = current[x + dx, y + dy].toDouble()
            sumA += a
            sumB += b
            sumAA += a * a
            sumBB += b * b
            sumAB += a * b
            count++
        }
        if (count <= 0) return -1f
        val covariance = sumAB - sumA * sumB / count
        val varianceA = sumAA - sumA * sumA / count
        val varianceB = sumBB - sumB * sumB / count
        val denominator = sqrt((varianceA * varianceB).coerceAtLeast(0.0))
        if (denominator < 1.0e-6) return -1f
        val overlap = count.toFloat() / ((reference.width - DENSE_BORDER * 2) *
            (reference.height - DENSE_BORDER * 2)).coerceAtLeast(1)
        return (covariance / denominator).toFloat().coerceIn(-1f, 1f) * (0.82f + overlap * 0.18f)
    }

    private fun denseControlPoints(width: Int, height: Int, shiftX: Float, shiftY: Float): List<ControlPoint> {
        val points = ArrayList<ControlPoint>()
        for (row in 1..4) for (column in 1..5) {
            val x = width * column / 6f
            val y = height * row / 5f
            val currentX = x + shiftX
            val currentY = y + shiftY
            if (currentX in 0f..width.toFloat() && currentY in 0f..height.toFloat()) {
                points += ControlPoint(x, y, currentX, currentY, true)
            }
        }
        return points
    }

    private fun describe(image: GrayImage): List<Feature> = detectCorners(image).map { corner ->
        val bits = LongArray(DESCRIPTOR_LONGS)
        BRIEF_PAIRS.forEachIndexed { index, pair ->
            val a = image[corner.x + pair[0], corner.y + pair[1]]
            val b = image[corner.x + pair[2], corner.y + pair[3]]
            if (a < b) bits[index ushr 6] = bits[index ushr 6] or (1L shl (index and 63))
        }
        Feature(corner.x, corner.y, bits)
    }

    private fun detectCorners(image: GrayImage): List<Corner> {
        if (image.width < PATCH_RADIUS * 2 + 3 || image.height < PATCH_RADIUS * 2 + 3) return emptyList()
        val candidates = ArrayList<Corner>()
        val border = PATCH_RADIUS + 2
        var y = border
        while (y < image.height - border) {
            var x = border
            while (x < image.width - border) {
                var xx = 0.0
                var yy = 0.0
                var xy = 0.0
                for (oy in -1..1) for (ox in -1..1) {
                    val gx = (image[x + ox + 1, y + oy] - image[x + ox - 1, y + oy]).toDouble()
                    val gy = (image[x + ox, y + oy + 1] - image[x + ox, y + oy - 1]).toDouble()
                    xx += gx * gx
                    yy += gy * gy
                    xy += gx * gy
                }
                val det = xx * yy - xy * xy
                val trace = xx + yy
                val score = det - HARRIS_K * trace * trace
                if (score > HARRIS_MIN_SCORE) candidates += Corner(x, y, score)
                x += CORNER_SCAN_STEP
            }
            y += CORNER_SCAN_STEP
        }

        val selected = ArrayList<Corner>(MAX_FEATURES)
        candidates.sortedByDescending(Corner::score).forEach { candidate ->
            if (selected.size >= MAX_FEATURES) return@forEach
            if (selected.none { other ->
                    val dx = candidate.x - other.x
                    val dy = candidate.y - other.y
                    dx * dx + dy * dy < NON_MAX_RADIUS * NON_MAX_RADIUS
                }
            ) selected += candidate
        }
        return selected
    }

    private fun match(reference: List<Feature>, current: List<Feature>): List<Match> {
        if (reference.isEmpty() || current.size < 2) return emptyList()
        val forward = ArrayList<Match>()
        reference.forEach { ref ->
            var best: Feature? = null
            var bestDistance = Int.MAX_VALUE
            var secondDistance = Int.MAX_VALUE
            current.forEach { candidate ->
                val distance = hamming(ref.bits, candidate.bits)
                if (distance < bestDistance) {
                    secondDistance = bestDistance
                    bestDistance = distance
                    best = candidate
                } else if (distance < secondDistance) {
                    secondDistance = distance
                }
            }
            val winner = best
            if (winner != null && bestDistance <= MAX_HAMMING &&
                bestDistance.toFloat() < secondDistance * RATIO_TEST
            ) forward += Match(ref, winner, bestDistance)
        }

        // Cross-check: il punto corrente deve scegliere a sua volta lo stesso riferimento.
        return forward.filter { match ->
            reference.minByOrNull { hamming(it.bits, match.current.bits) } === match.reference
        }
    }

    private fun hamming(a: LongArray, b: LongArray): Int {
        var distance = 0
        for (index in a.indices) distance += java.lang.Long.bitCount(a[index] xor b[index])
        return distance
    }

    private fun translationConsensus(matches: List<Match>): Consensus {
        var best = emptyList<Match>()
        matches.forEach { hypothesis ->
            val dx = hypothesis.current.x - hypothesis.reference.x
            val dy = hypothesis.current.y - hypothesis.reference.y
            val inliers = matches.filter { candidate ->
                val ex = (candidate.current.x - candidate.reference.x) - dx
                val ey = (candidate.current.y - candidate.reference.y) - dy
                ex * ex + ey * ey <= RANSAC_RADIUS_PX * RANSAC_RADIUS_PX
            }
            if (inliers.size > best.size) best = inliers
        }
        if (best.isEmpty()) return Consensus(0f, 0f, emptyList())
        val dxs = best.map { (it.current.x - it.reference.x).toFloat() }.sorted()
        val dys = best.map { (it.current.y - it.reference.y).toFloat() }.sorted()
        val dx = median(dxs)
        val dy = median(dys)
        val refined = matches.filter { candidate ->
            val ex = (candidate.current.x - candidate.reference.x) - dx
            val ey = (candidate.current.y - candidate.reference.y) - dy
            ex * ex + ey * ey <= RANSAC_RADIUS_PX * RANSAC_RADIUS_PX
        }
        return Consensus(dx, dy, refined)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2f
    }

    private val BRIEF_PAIRS: List<IntArray> = Random(0x360L).let { random ->
        List(DESCRIPTOR_BITS) {
            intArrayOf(
                random.nextInt(PATCH_RADIUS * 2 + 1) - PATCH_RADIUS,
                random.nextInt(PATCH_RADIUS * 2 + 1) - PATCH_RADIUS,
                random.nextInt(PATCH_RADIUS * 2 + 1) - PATCH_RADIUS,
                random.nextInt(PATCH_RADIUS * 2 + 1) - PATCH_RADIUS,
            )
        }
    }

    private const val DESCRIPTOR_BITS = 256
    private const val DESCRIPTOR_LONGS = DESCRIPTOR_BITS / 64
    private const val PATCH_RADIUS = 12
    private const val CORNER_SCAN_STEP = 2
    private const val NON_MAX_RADIUS = 8
    private const val MAX_FEATURES = 180
    private const val MIN_FEATURES = 10
    private const val MIN_MATCHES = 5

    /**
     * Quanti punti devono sopravvivere al consenso geometrico perché la misura conti.
     *
     * Non può essere lo stesso numero delle corrispondenze candidate: RANSAC ne scarta
     * sempre qualcuna, quindi chiedere cinque superstiti su cinque candidati vuol dire
     * chiedere che nessuno sbagli mai — e una scena viva, dove l'acqua si muove e la luce
     * cambia, un candidato sbagliato ce l'ha sempre. Tre punti che concordano sulla stessa
     * traslazione entro [RANSAC_RADIUS_PX] non sono una coincidenza; il resto lo dice la
     * confidenza, che pesa insieme la frazione di concordi e quanti sono in assoluto.
     */
    private const val MIN_CONSENSUS = 3
    private const val GOOD_SUPPORT = 14
    private const val MAX_HAMMING = 82
    private const val RATIO_TEST = 0.78f
    private const val RANSAC_RADIUS_PX = 5
    private const val CORRECT_RADIUS_PX = 6f
    private const val OVERLAP_RADIUS_PX = 28f
    private const val MIN_CONFIDENCE = 0.48f
    private const val HARRIS_K = 0.04
    private const val HARRIS_MIN_SCORE = 5.0e7
    private const val DENSE_SIZE = 64
    private const val DENSE_BORDER = 2
    private const val DENSE_MIN_OVERLAP = 28
    private const val MAX_DENSE_SHIFT = 22

    /**
     * Quanto il secondo picco può avvicinarsi al primo prima che la misura diventi un'ipotesi.
     * Su un motivo ripetitivo i due picchi sono gemelli e il rapporto sfiora 1.
     */
    private const val MAX_DENSE_PEAK_RATIO = 0.92f
    private const val MIN_DENSE_STD_DEV = 1.25f
    private const val MIN_DENSE_CORRELATION = 0.38f
    private const val MIN_DENSE_CONFIDENCE = 0.20f
    private const val DENSE_OVERLAP_RADIUS_PX = 96f
}
