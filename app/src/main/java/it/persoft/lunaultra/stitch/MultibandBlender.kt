package it.persoft.lunaultra.stitch

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * La fusione multibanda di Burt e Adelson: lo «spline» dei programmi seri di stitching.
 *
 * L'idea, che ha quarant'anni e non è mai stata superata: una giunzione deve essere larga per
 * i toni e stretta per i dettagli. Se si fonde tutto su una striscia stretta, ogni differenza
 * di esposizione diventa una riga visibile; se si fonde tutto su una striscia larga, ogni
 * disallineamento diventa un fantasma. La soluzione è separare l'immagine in bande di
 * frequenza — la piramide laplaciana — e dare a ogni banda la larghezza di fusione che le
 * spetta: al livello più fine la cucitura è netta come un taglio, e a ogni livello più su la
 * transizione raddoppia, fino a spalmare le differenze di tono su decine di pixel dove
 * l'occhio non le vede più.
 *
 * In pratica: piramide gaussiana della maschera (chi possiede ogni pixel), piramide laplaciana
 * delle due immagini, fusione livello per livello con la maschera di quel livello, e ricollasso.
 * Sei livelli coprono una transizione da 1 a 64 pixel — il famoso «spline 64».
 */
internal object MultibandBlender {

    /** Livelli della piramide: 2^6 = 64 pixel di transizione al livello più largo. */
    const val SPLINE_LEVELS = 6

    /**
     * Fonde [overlay] su [base] secondo [mask] (0 = tutto base, 1 = tutto overlay).
     *
     * I canali sono planari, un FloatArray da width*height per canale. Il risultato ha la
     * stessa forma. Dove la maschera è netta la fusione la ammorbidisce banda per banda: è
     * questo che elimina sia la riga sia il fantasma.
     */
    fun blend(
        baseChannels: Array<FloatArray>,
        overlayChannels: Array<FloatArray>,
        mask: FloatArray,
        width: Int,
        height: Int,
        maxLevels: Int = SPLINE_LEVELS,
    ): Array<FloatArray> {
        val levels = levelsFor(width, height, maxLevels)
        if (levels <= 1) {
            // Ritaglio troppo piccolo per una piramide: la fusione diretta è indistinguibile.
            return Array(baseChannels.size) { c ->
                FloatArray(width * height) { i ->
                    baseChannels[c][i] * (1f - mask[i]) + overlayChannels[c][i] * mask[i]
                }
            }
        }

        val maskPyr = gaussianPyramid(mask, width, height, levels)
        return Array(baseChannels.size) { c ->
            blendChannel(baseChannels[c], overlayChannels[c], maskPyr, width, height, levels)
        }
    }

    /** Quanti livelli reggono queste dimensioni: ogni livello dimezza, e sotto i 4 px non ha senso. */
    fun levelsFor(width: Int, height: Int, maxLevels: Int = SPLINE_LEVELS): Int {
        var levels = 1
        var side = min(width, height)
        while (levels < maxLevels && side >= 8) {
            levels++
            side = (side + 1) / 2
        }
        return levels
    }

    private class Pyramid(val data: List<FloatArray>, val widths: IntArray, val heights: IntArray)

    private fun gaussianPyramid(src: FloatArray, width: Int, height: Int, levels: Int): Pyramid {
        val data = ArrayList<FloatArray>(levels)
        val ws = IntArray(levels)
        val hs = IntArray(levels)
        var current = src.copyOf()
        var w = width
        var h = height
        for (level in 0 until levels) {
            data += current
            ws[level] = w
            hs[level] = h
            if (level == levels - 1) break
            val blurred = blur(current, w, h)
            val nw = (w + 1) / 2
            val nh = (h + 1) / 2
            val next = FloatArray(nw * nh)
            for (y in 0 until nh) {
                val sy = min(y * 2, h - 1)
                for (x in 0 until nw) {
                    next[y * nw + x] = blurred[sy * w + min(x * 2, w - 1)]
                }
            }
            current = next
            w = nw
            h = nh
        }
        return Pyramid(data, ws, hs)
    }

    private fun blendChannel(
        base: FloatArray,
        overlay: FloatArray,
        maskPyr: Pyramid,
        width: Int,
        height: Int,
        levels: Int,
    ): FloatArray {
        val basePyr = gaussianPyramid(base, width, height, levels)
        val overPyr = gaussianPyramid(overlay, width, height, levels)

        // Si parte dalla cima — le tinte larghe, fuse con la maschera più morbida — e si
        // scende ricostruendo: a ogni livello si aggiunge il dettaglio di quella banda, fuso
        // con la maschera di quella banda. Il laplaciano non si materializza mai da solo:
        // nasce e si consuma nella stessa riga.
        val top = levels - 1
        var acc = FloatArray(maskPyr.data[top].size) { i ->
            val m = maskPyr.data[top][i]
            basePyr.data[top][i] * (1f - m) + overPyr.data[top][i] * m
        }
        for (level in top - 1 downTo 0) {
            val w = maskPyr.widths[level]
            val h = maskPyr.heights[level]
            val upAcc = upsample(acc, maskPyr.widths[level + 1], maskPyr.heights[level + 1], w, h)
            val upBase = upsample(basePyr.data[level + 1], basePyr.widths[level + 1], basePyr.heights[level + 1], w, h)
            val upOver = upsample(overPyr.data[level + 1], overPyr.widths[level + 1], overPyr.heights[level + 1], w, h)
            val maskLevel = maskPyr.data[level]
            val out = FloatArray(w * h)
            for (i in out.indices) {
                val m = maskLevel[i]
                val detail = (basePyr.data[level][i] - upBase[i]) * (1f - m) +
                    (overPyr.data[level][i] - upOver[i]) * m
                out[i] = upAcc[i] + detail
            }
            acc = out
        }
        return acc
    }

    /** Sfocatura gaussiana separabile [1 4 6 4 1]/16, con i bordi che si specchiano sul posto. */
    private fun blur(src: FloatArray, width: Int, height: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (k in -2..2) {
                    val sx = (x + k).coerceIn(0, width - 1)
                    sum += KERNEL[k + 2] * src[rowBase + sx]
                }
                tmp[rowBase + x] = sum
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (k in -2..2) {
                    val sy = (y + k).coerceIn(0, height - 1)
                    sum += KERNEL[k + 2] * tmp[sy * width + x]
                }
                out[y * width + x] = sum
            }
        }
        return out
    }

    /** Riporta un livello alla dimensione del livello sotto, interpolando bilineare. */
    private fun upsample(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        val out = FloatArray(dstW * dstH)
        val scaleX = if (dstW > 1) (srcW - 1f) / (dstW - 1f) else 0f
        val scaleY = if (dstH > 1) (srcH - 1f) / (dstH - 1f) else 0f
        for (y in 0 until dstH) {
            val fy = y * scaleY
            val y0 = fy.toInt().coerceAtMost(srcH - 1)
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val ty = fy - y0
            for (x in 0 until dstW) {
                val fx = x * scaleX
                val x0 = fx.toInt().coerceAtMost(srcW - 1)
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val tx = fx - x0
                val top = src[y0 * srcW + x0] * (1f - tx) + src[y0 * srcW + x1] * tx
                val bottom = src[y1 * srcW + x0] * (1f - tx) + src[y1 * srcW + x1] * tx
                out[y * dstW + x] = top * (1f - ty) + bottom * ty
            }
        }
        return out
    }

    private val KERNEL = floatArrayOf(1f / 16f, 4f / 16f, 6f / 16f, 4f / 16f, 1f / 16f)
}

/** Un canale a 8 bit fissato nell'intervallo valido, dal float della piramide. */
internal fun Float.toChannel(): Int = roundToInt().coerceIn(0, 255)
