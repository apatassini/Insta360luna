package it.persoft.lunaultra.stitch

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ritrovare gli stessi dettagli in due fotogrammi, **senza sapere dove cercarli**.
 *
 * È la differenza che conta rispetto a tutto quello che c'era prima, e nasce da un difetto
 * preciso: su una scena di mare e cielo la correlazione a piramide non funziona. Spostando una
 * foto di lato, mare resta mare e cielo resta cielo — la struttura è tutta verticale e non
 * cambia niente. Il punteggio è quasi identico ovunque, il massimo è rumore, e va a finire dove
 * capita: si è visto proporre sei gradi di spostamento su un fotogramma mentre tutti gli altri
 * stavano a sei centesimi.
 *
 * I punti di controllo invece funzionano benissimo, ma sono **miopi**: cercano il dettaglio in
 * un raggio di sette decimi di grado attorno a dove la geometria lo prevede. Se un ramo è fuori
 * posto di due gradi, loro lì non trovano niente, scartano quel punto, e tengono solo i punti
 * che già combaciavano. Il log finisce per dire «tutto a posto, correzione di un decimo di
 * grado» mentre l'albero è spostato di duecento pixel: si stava misurando soltanto dove il
 * problema non c'era.
 *
 * Questo confronta **impronte**, non posizioni. Ogni dettaglio riconoscibile — l'incrocio di due
 * rami, lo spigolo di uno scoglio — diventa una firma di 256 bit che dipende solo da com'è
 * fatto lui, non da dove sta. Due dettagli si abbinano se le firme si somigliano, e la distanza
 * fra loro può essere qualunque. Poi si vota: gli abbinamenti giusti concordano tutti sullo
 * stesso spostamento, quelli sbagliati cadono ognuno per conto suo, e la maggioranza vince.
 *
 * È lo stesso metodo che l'app usa già per verificare se il gimbal è tornato sul punto giusto
 * (`WaypointImageVerifier`), qui portato dal confronto fra due miniature al confronto fra due
 * fotogrammi che si sovrappongono.
 */
internal class FeatureKeypoint(
    val x: Int,
    val y: Int,
    /** La firma: 256 bit in quattro parole. */
    val a: Long,
    val b: Long,
    val c: Long,
    val d: Long,
)

/** Lo stesso dettaglio ritrovato: dove stava e dove si è ritrovato. */
internal class FeatureMatch(
    val fixedX: Int,
    val fixedY: Int,
    val movingX: Int,
    val movingY: Int,
    val distance: Int,
)

internal object FeatureMatcher {

    /**
     * I dettagli riconoscibili di un fotogramma, al massimo uno per cella di griglia.
     *
     * Una cella per dettaglio serve a non ritrovarsi mille punti tutti sullo stesso tronco
     * ben contrastato e nessuno altrove: la griglia sparge i punti su tutta la zona utile,
     * che è quello che il voto successivo si aspetta.
     *
     * Il punteggio di «riconoscibilità» è il minimo fra il salto orizzontale e quello
     * verticale: alto solo dove il dettaglio è un angolo, cioè dove cambia in **tutte e due**
     * le direzioni. Un bordo lungo e dritto ha un salto grosso in una direzione sola, e non è
     * riconoscibile — scorrendogli lungo si somiglia sempre.
     */
    fun detect(
        gray: ByteArray,
        width: Int,
        height: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        cell: Int,
    ): List<FeatureKeypoint> {
        val margin = PATCH_RADIUS + 2
        val left = max(x0, margin)
        val top = max(y0, margin)
        val right = min(x1, width - 1 - margin)
        val bottom = min(y1, height - 1 - margin)
        if (right - left < cell || bottom - top < cell) return emptyList()

        val found = ArrayList<FeatureKeypoint>()
        var cy = top
        while (cy + cell <= bottom) {
            var cx = left
            while (cx + cell <= right) {
                var bestScore = MIN_CORNER_SCORE
                var bestX = -1
                var bestY = -1
                var y = cy
                while (y < cy + cell) {
                    var x = cx
                    while (x < cx + cell) {
                        val i = y * width + x
                        val dx = abs(luma(gray, i + 2) - luma(gray, i - 2))
                        val dy = abs(luma(gray, i + 2 * width) - luma(gray, i - 2 * width))
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
                if (bestX >= 0) found += describe(gray, width, bestX, bestY)
                cx += cell
            }
            cy += cell
        }
        return found
    }

    /**
     * Gli abbinamenti fra due liste di dettagli, con il controllo del secondo migliore.
     *
     * Un abbinamento vale solo se il migliore è **nettamente** meglio del secondo. È la difesa
     * contro i motivi ripetuti: su una chioma di rami tutti uguali il migliore e il secondo si
     * somigliano, il rapporto sfiora uno, e quell'abbinamento non viene preso. Meglio pochi
     * abbinamenti di cui fidarsi che tanti da dover poi buttare.
     */
    fun match(fixed: List<FeatureKeypoint>, moving: List<FeatureKeypoint>): List<FeatureMatch> {
        if (fixed.isEmpty() || moving.isEmpty()) return emptyList()
        val out = ArrayList<FeatureMatch>(fixed.size)
        for (f in fixed) {
            var best = Int.MAX_VALUE
            var second = Int.MAX_VALUE
            var bestPoint: FeatureKeypoint? = null
            for (m in moving) {
                val distance = java.lang.Long.bitCount(f.a xor m.a) +
                    java.lang.Long.bitCount(f.b xor m.b) +
                    java.lang.Long.bitCount(f.c xor m.c) +
                    java.lang.Long.bitCount(f.d xor m.d)
                if (distance < best) {
                    second = best
                    best = distance
                    bestPoint = m
                } else if (distance < second) {
                    second = distance
                }
            }
            val winner = bestPoint ?: continue
            if (best > MAX_HAMMING) continue
            if (second < Int.MAX_VALUE && best > second * RATIO_TEST) continue
            out += FeatureMatch(f.x, f.y, winner.x, winner.y, best)
        }
        return out
    }

    /**
     * La firma di un dettaglio: 256 confronti fra coppie di posizioni fissate una volta.
     *
     * Ogni bit dice «qui è più chiaro che là». Le coppie sono sempre le stesse — sorteggiate
     * al primo uso con un seme fisso, così due esecuzioni diverse danno la stessa firma — e la
     * luminanza si legge su un quadratino tre per tre invece che su un pixel solo: un pixel
     * singolo è rumore, e su un bit che vale uno o zero il rumore decide.
     */
    private fun describe(gray: ByteArray, width: Int, x: Int, y: Int): FeatureKeypoint {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        for (bit in 0 until DESCRIPTOR_BITS) {
            val p = PAIRS[bit * 4]
            val q = PAIRS[bit * 4 + 1]
            val r = PAIRS[bit * 4 + 2]
            val s = PAIRS[bit * 4 + 3]
            val first = block(gray, width, x + p, y + q)
            val secondValue = block(gray, width, x + r, y + s)
            if (first > secondValue) {
                val word = bit ushr 6
                val mask = 1L shl (bit and 63)
                when (word) {
                    0 -> a = a or mask
                    1 -> b = b or mask
                    2 -> c = c or mask
                    else -> d = d or mask
                }
            }
        }
        return FeatureKeypoint(x, y, a, b, c, d)
    }

    /** La luminanza media di un quadratino 3×3: un pixel solo sarebbe rumore. */
    private fun block(gray: ByteArray, width: Int, x: Int, y: Int): Int {
        var sum = 0
        for (dy in -1..1) {
            val base = (y + dy) * width + x
            sum += (gray[base - 1].toInt() and 0xFF) +
                (gray[base].toInt() and 0xFF) +
                (gray[base + 1].toInt() and 0xFF)
        }
        return sum
    }

    private fun luma(gray: ByteArray, index: Int): Float = (gray[index].toInt() and 0xFF).toFloat()

    const val PATCH_RADIUS = 15
    private const val DESCRIPTOR_BITS = 256

    /** Sotto questo salto in tutte e due le direzioni non c'è un angolo da riconoscere. */
    private const val MIN_CORNER_SCORE = 6f

    /** Oltre un quarto dei bit diversi le due firme non sono più lo stesso dettaglio. */
    private const val MAX_HAMMING = 64

    /**
     * Quanto il migliore deve battere il secondo. 0,78 è il valore classico: sotto si
     * accettano abbinamenti ambigui, sopra si butta via troppo.
     */
    private const val RATIO_TEST = 0.78f

    /**
     * Le coppie di posizioni dentro la finestra del dettaglio, sorteggiate una volta sola con
     * un seme fisso: la firma deve essere la stessa a ogni esecuzione, altrimenti due unioni
     * della stessa panoramica darebbero risultati diversi.
     */
    private val PAIRS: IntArray = IntArray(DESCRIPTOR_BITS * 4).also { table ->
        val random = Random(20260825L)
        val span = PATCH_RADIUS - 1
        for (i in table.indices) {
            table[i] = random.nextInt(2 * span + 1) - span
        }
    }
}
