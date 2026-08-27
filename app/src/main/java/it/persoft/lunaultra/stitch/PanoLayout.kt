package it.persoft.lunaultra.stitch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.max

/**
 * Una foto ridotta a luminanza: quel che serve per capire **dove sta**, e niente altro.
 *
 * Il colore non aggiunge niente a questo lavoro e costa tre volte tanto; la risoluzione nemmeno,
 * perché qui non si cerca il pixel esatto — si cerca il posto, e basta il grado.
 */
class LayoutFrame(
    val label: String,
    val gray: ByteArray,
    val width: Int,
    val height: Int,
    /** Inclinazione già nota dalla gravità. Nulla quando la foto non porta la traccia inerziale. */
    val knownTiltDegrees: Float? = null,
    /** Rollio noto dalla gravità: entra nella proiezione invece di essere stimato. */
    val rollDegrees: Float = 0f,
)

/**
 * Dove è finita una foto, e con chi.
 *
 * [group] è la panoramica a cui appartiene: le foto legate fra loro da giunzioni sicure ne
 * fanno una, quelle che non si legano a nessuno restano da sole. [placed] dice se quella foto
 * sta nel gruppo più grande, cioè in quella che l'unione farà per prima.
 */
data class LayoutSpot(
    val panDegrees: Float,
    val tiltDegrees: Float,
    val placed: Boolean,
    val group: Int = 0,
)

/** Una giunzione riconosciuta: due foto, di quanto distano, e su quanti dettagli si regge. */
data class LayoutLink(
    val a: Int,
    val b: Int,
    val panDeltaDegrees: Float,
    val tiltDeltaDegrees: Float,
    val inliers: Int,
    val agreement: Float,
    /**
     * Vero quando i dettagli d'accordo erano pochi per fidarsi da soli.
     *
     * Non entra nello scheletro della panoramica — quello si costruisce solo con le certezze —
     * ma serve dopo, per non buttare via una foto che a due dettagli dalla soglia era
     * chiaramente al suo posto.
     */
    val tentative: Boolean = false,
) {
    /** Quanto pesa nella scelta dell'albero: tanti dettagli **e** tanti d'accordo fra loro. */
    val score: Float get() = inliers * agreement
}

data class PanoLayout(
    val spots: List<LayoutSpot>,
    val links: List<LayoutLink>,
    val notes: List<String>,
) {
    val allPlaced: Boolean get() = spots.all { it.placed }

    /**
     * Le panoramiche trovate, dalla più grande alla più piccola.
     *
     * Non è detto che ne esca una sola, e non è un difetto: nella cartella del telefono gli
     * scatti di momenti diversi stanno di fianco, e chi sceglie ne prende volentieri di
     * troppo. Ogni gruppo è una panoramica per conto suo — e chi le chiama può farne lavori
     * separati invece di buttarne via una.
     *
     * Le foto sole restano fuori: una foto da sola non è una panoramica.
     */
    val groups: List<List<Int>>
        get() = spots.indices
            .groupBy { spots[it].group }
            .values
            .filter { it.size >= 2 }
            .sortedByDescending { it.size }
}

/**
 * Dove vanno messe delle foto di cui non si sa niente.
 *
 * Le panoramiche scattate dall'app hanno gli angoli scritti nei tag: si parte già allineati e
 * alle immagini resta solo il residuo. Le foto che arrivano dal telefono no — e **l'ordine con
 * cui il selettore le consegna non è un'informazione**: può essere l'ordine dei tocchi, quello
 * alfabetico, quello di scansione della cartella. Nemmeno l'ora di scatto basta: dice la
 * successione, non la forma. Una panoramica su più file non è una sequenza — è una griglia, e
 * nessun ordine lineare dice che la quarta foto sta *sopra* la prima invece che a destra.
 *
 * Quindi il posto va trovato guardando le foto, e il pezzo che serve c'era già:
 * [FeatureMatcher] ritrova gli stessi dettagli in due fotogrammi senza sapere dove cercarli.
 * Ogni dettaglio riconoscibile diventa una firma di 256 bit che dipende solo da com'è fatto, non
 * da dove sta; due firme che si somigliano sono lo stesso dettaglio, e la distanza fra i due
 * punti può essere qualunque.
 *
 * Da lì il giro è corto:
 *
 * 1. **I dettagli di ogni foto**, una volta sola, su tutta la sua superficie.
 * 2. **Ogni coppia si abbina**, e ogni abbinamento dice di quanti gradi una foto è distante
 *    dall'altra. Gli abbinamenti giusti concordano tutti sullo stesso numero, quelli sbagliati
 *    cadono ognuno per conto suo: si vota, e la maggioranza è la giunzione.
 * 3. **Le giunzioni diventano un grafo**, e si tiene l'albero di peso massimo — quelle di cui
 *    ci si fida di più, che reggono tutte le altre. Le posizioni assolute si propagano da una
 *    foto sola, che è quella più legata a tutte.
 *
 * Notare cosa **non** si fa: provare tutte le posizioni possibili di ogni coppia. Sarebbe la
 * strada bruta, quella che chiede una scheda grafica per essere sopportabile; le firme danno la
 * stessa risposta senza scandire niente. È la stessa lezione dei punti di controllo, che da
 * ventisette secondi sono scesi a nove smettendo di provare tutte le posizioni.
 */
object PanoLayoutFinder {

    /**
     * Il risultato, più quanto ci è voluto: il tempo è un numero da guardare, non da supporre.
     */
    class Timing(val detectMillis: Long, val matchMillis: Long, val features: Int, val pairs: Int)

    suspend fun solve(
        frames: List<LayoutFrame>,
        horizontalFovDegrees: Float,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        onTiming: (Timing) -> Unit = {},
    ): PanoLayout {
        if (frames.size < 2) {
            return PanoLayout(
                spots = frames.map { LayoutSpot(0f, it.knownTiltDegrees ?: 0f, placed = true) },
                links = emptyList(),
                notes = emptyList(),
            )
        }
        val notes = mutableListOf<String>()
        val lenses = frames.map { PinholeLens(it.width, it.height, horizontalFovDegrees) }
        val placements = frames.map {
            FramePlacement(
                panDegrees = 0f,
                tiltDegrees = it.knownTiltDegrees ?: 0f,
                rollDegrees = it.rollDegrees,
            )
        }

        // I dettagli di ogni foto, una volta sola. Sono gli stessi per tutte le coppie in cui
        // quella foto compare: ricalcolarli per coppia sarebbe otto volte il lavoro su nove
        // foto, e non una virgola di risultato in più.
        val detectStartedAt = System.currentTimeMillis()
        val points = coroutineScope {
            frames.mapIndexed { index, frame ->
                async(Dispatchers.Default) {
                    onProgress(0.1f + 0.3f * index / frames.size, "Cerco i dettagli di ${frame.label}")
                    FeatureMatcher.detect(
                        frame.gray, frame.width, frame.height,
                        0, 0, frame.width - 1, frame.height - 1,
                        cellFor(frame),
                    )
                }
            }.awaitAll()
        }
        val detectMillis = System.currentTimeMillis() - detectStartedAt

        val couples = mutableListOf<Pair<Int, Int>>()
        for (a in frames.indices) for (b in a + 1 until frames.size) couples += a to b

        val matchStartedAt = System.currentTimeMillis()
        val nearMisses = java.util.Collections.synchronizedList(mutableListOf<Triple<Int, Int, Int>>())
        val found = coroutineScope {
            couples.mapIndexed { step, (a, b) ->
                async(Dispatchers.Default) {
                    onProgress(
                        0.4f + 0.5f * step / couples.size,
                        "Provo ${frames[a].label} con ${frames[b].label}",
                    )
                    link(a, b, points[a], points[b], placements, lenses, horizontalFovDegrees) { x, y, count ->
                        if (count >= NEAR_MISS_INLIERS) nearMisses += Triple(x, y, count)
                    }
                }
            }.awaitAll()
        }
        val matchMillis = System.currentTimeMillis() - matchStartedAt
        onTiming(Timing(detectMillis, matchMillis, points.sumOf { it.size }, couples.size))

        val links = found.filterNotNull().sortedByDescending { it.score }
        val spots = propagate(frames, links, notes)
        notes += describe(frames, links)
        notes += crossCheck(spots, links)
        describeNearMisses(frames, nearMisses)?.let { notes += it }
        return PanoLayout(spots, links, notes)
    }

    /**
     * Quanto è distante una foto dall'altra, se lo sono davvero.
     *
     * Ogni abbinamento di dettagli dice uno spostamento in gradi. Si cerca il numero su cui il
     * maggior numero di abbinamenti va d'accordo — la casella più affollata — e poi si fa la
     * media di chi ci sta dentro, che è la misura fine. Chi resta fuori era rumore: due dettagli
     * che si somigliano senza essere lo stesso dettaglio, cosa che su un fogliame capita
     * eccome.
     *
     * Due foto che non si sovrappongono per niente producono lo stesso qualche abbinamento, per
     * pura somiglianza. Quello che non producono è **accordo**: cadono ognuno per conto suo, la
     * casella più affollata resta quasi vuota, e la coppia viene scartata. È questa la
     * differenza fra sapere e credere.
     */
    private fun link(
        a: Int,
        b: Int,
        pointsA: List<FeatureKeypoint>,
        pointsB: List<FeatureKeypoint>,
        placements: List<FramePlacement>,
        lenses: List<PinholeLens>,
        horizontalFovDegrees: Float,
        onNearMiss: (Int, Int, Int) -> Unit = { _, _, _ -> },
    ): LayoutLink? {
        if (pointsA.size < MIN_INLIERS || pointsB.size < MIN_INLIERS) return null
        val matches = FeatureMatcher.match(pointsA, pointsB)
        if (matches.size < MIN_INLIERS) return null

        // Oltre una volta e mezza il campo visivo due foto non possono sovrapporsi: uno
        // spostamento più grande di così non è una giunzione, è un abbaglio.
        val reachPan = horizontalFovDegrees * MAX_REACH
        val reachTilt = max(lenses[a].verticalFovDegrees, lenses[b].verticalFovDegrees) * MAX_REACH

        val panShift = FloatArray(matches.size)
        val tiltShift = FloatArray(matches.size)
        val usable = BooleanArray(matches.size)
        for (i in matches.indices) {
            val m = matches[i]
            val here = frameToWorld(m.fixedX.toFloat(), m.fixedY.toFloat(), placements[a], lenses[a])
            val there = frameToWorld(m.movingX.toFloat(), m.movingY.toFloat(), placements[b], lenses[b])
            // Di quanto va spostata la seconda perché quel dettaglio caschi dove lo vede la prima.
            // Di quanto la seconda foto sta più in là della prima: se lo stesso punto la
            // prima lo vede a destra e la seconda al centro, la seconda è girata a destra.
            panShift[i] = wrapDegrees(here[0] - there[0])
            tiltShift[i] = here[1] - there[1]
            usable[i] = abs(panShift[i]) <= reachPan && abs(tiltShift[i]) <= reachTilt
        }

        var bestCount = 0
        var bestPan = 0f
        var bestTilt = 0f
        for (i in matches.indices) {
            if (!usable[i]) continue
            var count = 0
            for (j in matches.indices) {
                if (!usable[j]) continue
                if (abs(panShift[j] - panShift[i]) <= VOTE_DEGREES &&
                    abs(tiltShift[j] - tiltShift[i]) <= VOTE_DEGREES
                ) {
                    count++
                }
            }
            if (count > bestCount) {
                bestCount = count
                bestPan = panShift[i]
                bestTilt = tiltShift[i]
            }
        }
        if (bestCount < SECOND_CHANCE_INLIERS) {
            // Quanti dettagli erano andati d'accordo, anche se non abbastanza: è la
            // differenza fra «queste due foto non si toccano» e «per un pelo».
            onNearMiss(a, b, bestCount)
            return null
        }

        var sumPan = 0f
        var sumTilt = 0f
        var inliers = 0
        for (i in matches.indices) {
            if (!usable[i]) continue
            if (abs(panShift[i] - bestPan) <= VOTE_DEGREES && abs(tiltShift[i] - bestTilt) <= VOTE_DEGREES) {
                sumPan += panShift[i]
                sumTilt += tiltShift[i]
                inliers++
            }
        }
        if (inliers < SECOND_CHANCE_INLIERS) {
            onNearMiss(a, b, inliers)
            return null
        }
        // Quanta parte degli abbinamenti deve essere d'accordo. Una giunzione che regge lo
        // scheletro deve stringere; una da ripescaggio no — li` il numero che conta e` quanti
        // dettagli sono andati d'accordo, non quanti se ne sono buttati.
        val agreement = inliers.toFloat() / matches.size
        val floor = if (inliers >= MIN_INLIERS) MIN_AGREEMENT else TENTATIVE_AGREEMENT
        if (agreement < floor) {
            onNearMiss(a, b, inliers)
            return null
        }
        if (inliers < MIN_INLIERS) onNearMiss(a, b, inliers)
        return LayoutLink(
            a, b, sumPan / inliers, sumTilt / inliers, inliers, agreement,
            tentative = inliers < MIN_INLIERS,
        )
    }

    /**
     * Dalle giunzioni alle posizioni: l'albero di peso massimo, e poi si propaga.
     *
     * Si prendono le giunzioni dalla più solida alla meno solida e si tiene solo quella che
     * unisce due gruppi ancora separati. Il risultato è che ogni foto è appesa alla catena di
     * giunzioni più robusta che la lega alle altre, non a una qualunque che per caso la
     * riguardava — ed è anche il modo in cui una coppia dubbia viene decisa dalle sicure:
     * quando arriva il suo turno, i due gruppi sono già uniti e lei non serve più.
     */
    private fun propagate(
        frames: List<LayoutFrame>,
        links: List<LayoutLink>,
        notes: MutableList<String>,
    ): List<LayoutSpot> {
        val parent = IntArray(frames.size) { it }
        fun root(of: Int): Int {
            var node = of
            while (parent[node] != node) {
                parent[node] = parent[parent[node]]
                node = parent[node]
            }
            return node
        }
        val tree = mutableListOf<LayoutLink>()
        links.filter { !it.tentative }.forEach { link ->
            val ra = root(link.a)
            val rb = root(link.b)
            if (ra == rb) return@forEach
            parent[ra] = rb
            tree += link
        }

        val neighbours = Array(frames.size) { mutableListOf<LayoutLink>() }
        tree.forEach { neighbours[it.a] += it; neighbours[it.b] += it }

        // I gruppi: chi si tiene per mano con chi.
        //
        // Non tutte le foto scelte fanno parte della stessa panoramica, ed è normale — nella
        // cartella del telefono stanno di fianco scatti di momenti diversi, e chi le sceglie
        // ne prende qualcuna di troppo. Un tempo si mettevano tutte in fila lo stesso, e due
        // scatti che non c'entravano niente allargavano la tela di ottanta gradi per stare in
        // un angolo. Adesso ogni gruppo di foto legate fra loro è una panoramica possibile, e
        // si tiene **la più grande**: è quella che si voleva.
        val group = IntArray(frames.size) { -1 }
        var groups = 0
        frames.indices.forEach { start ->
            if (group[start] >= 0) return@forEach
            val walk = ArrayDeque<Int>().apply { add(start) }
            group[start] = groups
            while (walk.isNotEmpty()) {
                val node = walk.removeFirst()
                neighbours[node].forEach { link ->
                    val other = if (link.a == node) link.b else link.a
                    if (group[other] >= 0) return@forEach
                    group[other] = groups
                    walk.add(other)
                }
            }
            groups++
        }
        val sizes = IntArray(groups)
        group.forEach { sizes[it]++ }
        val chosen = (0 until groups).maxByOrNull { sizes[it] } ?: 0

        val pan = FloatArray(frames.size)
        val tilt = FloatArray(frames.size)
        val placed = BooleanArray(frames.size)

        // Si parte dalla foto più legata del gruppo scelto: quella con più dettagli in comune.
        val anchor = frames.indices.filter { group[it] == chosen }.maxByOrNull { index ->
            neighbours[index].sumOf { it.score.toDouble() }
        } ?: 0
        pan[anchor] = 0f
        tilt[anchor] = frames[anchor].knownTiltDegrees ?: 0f
        placed[anchor] = true
        val queue = ArrayDeque<Int>().apply { add(anchor) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            neighbours[node].forEach { link ->
                val other = if (link.a == node) link.b else link.a
                if (placed[other]) return@forEach
                // Il segno. La giunzione dice «di quanto la seconda sta più in là della
                // prima»: andando dalla prima alla seconda si somma, tornando indietro si
                // sottrae. Sbagliarlo qui specchia mezza panoramica, ed è il genere di errore
                // che il test con la griglia finta prende al primo colpo.
                val sign = if (link.a == node) 1f else -1f
                pan[other] = pan[node] + sign * link.panDeltaDegrees
                tilt[other] = frames[other].knownTiltDegrees
                    ?: (tilt[node] + sign * link.tiltDeltaDegrees)
                placed[other] = true
                queue.add(other)
            }
        }

        // Il ripescaggio.
        //
        // Lo scheletro si costruisce con le certezze, e deve restare così: una giunzione
        // debole nell'ossatura sposta un ramo intero. Ma una foto che resta fuori per **due
        // dettagli** sotto la soglia non è una foto di un altro momento — è la stessa
        // panoramica vista da un punto dove il fogliame si ripete e le firme si somigliano
        // meno. Buttarla via costa più di quanto costi tenerla nel posto che indica.
        //
        // Quindi, a scheletro finito: chi è rimasto fuori si attacca alla foto già piazzata
        // con cui ha più dettagli in comune, e lo si scrive. Il giro si ripete finché non si
        // ripesca più nessuno, così una foto ripescata può a sua volta reggerne un'altra.
        val rescued = mutableListOf<Pair<Int, LayoutLink>>()
        while (true) {
            val best = links.filter { link ->
                if (!link.tentative || placed[link.a] == placed[link.b]) return@filter false
                // Solo le foto **sole** si ripescano. Un gruppo di due o più sta in piedi da
                // sé ed è una panoramica per conto suo: attaccarcelo con una giunzione debole
                // vorrebbe dire fondere due panoramiche su un indizio, che è esattamente
                // l'errore che lo scheletro fatto di sole certezze serve a evitare.
                val stranger = if (placed[link.a]) link.b else link.a
                sizes[group[stranger]] == 1
            }.maxByOrNull { it.inliers } ?: break
            val known = if (placed[best.a]) best.a else best.b
            val other = if (known == best.a) best.b else best.a
            val sign = if (best.a == known) 1f else -1f
            pan[other] = pan[known] + sign * best.panDeltaDegrees
            tilt[other] = frames[other].knownTiltDegrees
                ?: (tilt[known] + sign * best.tiltDeltaDegrees)
            placed[other] = true
            group[other] = group[known]
            neighbours[other] += best
            neighbours[known] += best
            rescued += other to best
        }
        if (rescued.isNotEmpty()) {
            notes += "Riprese con riserva: " + rescued.joinToString(" · ") { (index, link) ->
                "%s (%d dettagli con %s, ne servivano %d)".format(
                    frames[index].label,
                    link.inliers,
                    frames[if (link.a == index) link.b else link.a].label,
                    MIN_INLIERS,
                )
            } + ". L'unione le cerca larghe."
        }

        val left = frames.indices.filter { !placed[it] }
        if (left.isNotEmpty()) {
            val others = (0 until groups).filter { it != chosen && sizes[it] > 1 }
            notes += buildString {
                append("Fuori da questa panoramica: ")
                append(left.joinToString(" · ") { frames[it].label })
                append(". ")
                append(
                    if (others.isEmpty()) {
                        "Non hanno dettagli in comune con le altre — sono scatti di un altro momento."
                    } else {
                        "Farebbero ${others.size} panoramica/he per conto loro; qui si tiene la più grande."
                    },
                )
            }
        }

        // Ricentrata: il pan sempre, perché è tutto relativo e nessuno sa dov'è il nord.
        // L'inclinazione solo se nessuna foto la sa dalla gravità — quando la sanno, quello è
        // l'orizzonte vero e spostarlo butterebbe via l'unica misura assoluta che c'è.
        val inside = frames.indices.filter { placed[it] }
        val meanPan = if (inside.isEmpty()) 0f else inside.map { pan[it] }.average().toFloat()
        val meanTilt = when {
            frames.any { it.knownTiltDegrees != null } -> 0f
            inside.isEmpty() -> 0f
            else -> inside.map { tilt[it] }.average().toFloat()
        }
        return frames.indices.map { index ->
            LayoutSpot(pan[index] - meanPan, tilt[index] - meanTilt, placed[index], group[index])
        }
    }

    /**
     * Le coppie che ci sono andate vicino: quante ne servivano e quante ne avevano.
     *
     * Una coppia scartata in silenzio non dice niente, e la differenza fra «queste due foto
     * non si toccano» e «per un pelo» è tutto quello che serve sapere per capire se la soglia
     * è giusta o se le foto si sovrappongono troppo poco. Chi legge il verdetto deve poterlo
     * distinguere senza avere il codice davanti.
     */
    private fun describeNearMisses(
        frames: List<LayoutFrame>,
        misses: List<Triple<Int, Int, Int>>,
    ): String? {
        if (misses.isEmpty()) return null
        val worth = misses.sortedByDescending { it.third }.take(MAX_DESCRIBED)
        return "Coppie vicine alla soglia (ne servono %d): ".format(MIN_INLIERS) +
            worth.joinToString(" · ") { (a, b, count) ->
                "%s/%s %d dettagli".format(frames[a].label, frames[b].label, count)
            }
    }

    /**
     * La controprova: le giunzioni **non** usate dicono la stessa cosa di quelle usate?
     *
     * L'albero si regge su n−1 giunzioni; tutte le altre sono state trovate lo stesso e non
     * hanno votato. Sono quindi testimoni indipendenti: se le posizioni sono giuste, ognuna di
     * loro deve ritrovarsi d'accordo con quello che l'albero ha deciso. Una che sbaglia è
     * normale — due foto che si somigliano per caso capitano; molte che sbagliano vogliono dire
     * che dentro l'albero c'è una giunzione inventata, e che un pezzo di panoramica è finito
     * nel posto sbagliato.
     *
     * Non si corregge niente: si **dice**. Chi legge il verdetto deve sapere quanto vale il
     * risultato, e l'unione dopo cerca comunque larga.
     */
    private fun crossCheck(spots: List<LayoutSpot>, links: List<LayoutLink>): String {
        var agree = 0
        var disagree = 0
        links.forEach { link ->
            val panSeen = spots[link.b].panDegrees - spots[link.a].panDegrees
            val tiltSeen = spots[link.b].tiltDegrees - spots[link.a].tiltDegrees
            val off = max(
                abs(panSeen - link.panDeltaDegrees),
                abs(tiltSeen - link.tiltDeltaDegrees),
            )
            if (off <= CHECK_DEGREES) agree++ else disagree++
        }
        return if (disagree == 0) {
            "Controprova: tutte le %d giunzioni concordano con le posizioni scelte.".format(agree)
        } else {
            "Controprova: %d giunzioni su %d concordano, %d no (oltre %.0f°)%s".format(
                agree, agree + disagree, disagree, CHECK_DEGREES,
                if (disagree > agree) " — le posizioni non sono affidabili." else ".",
            )
        }
    }

    /**
     * La griglia dei dettagli: una cella ogni tot pixel, un dettaglio per cella.
     *
     * Serve a non ritrovarsi mille punti tutti sullo stesso tronco ben contrastato e nessuno
     * altrove. Su una miniatura di seicento pixel una cella di venti ne dà al massimo un
     * migliaio, sparsi ovunque: abbastanza da votare, pochi da abbinare in fretta.
     */
    private fun cellFor(frame: LayoutFrame): Int =
        (max(frame.width, frame.height) / CELLS_ACROSS).coerceAtLeast(MIN_CELL)

    private fun describe(frames: List<LayoutFrame>, links: List<LayoutLink>): String {
        if (links.isEmpty()) {
            return "Nessuna giunzione riconosciuta: le foto non hanno dettagli in comune."
        }
        return "Giunzioni riconosciute (%d)".format(links.size) + ": " +
            links.take(MAX_DESCRIBED).joinToString(" · ") { link ->
                "%s→%s %+.1f°/%+.1f° (%d dettagli, %.0f%% d'accordo)".format(
                    frames[link.a].label,
                    frames[link.b].label,
                    link.panDeltaDegrees,
                    link.tiltDeltaDegrees,
                    link.inliers,
                    link.agreement * 100,
                )
            }
    }

    /** Quanti dettagli devono andare d'accordo perché una giunzione regga lo scheletro. */
    const val MIN_INLIERS = 10

    /**
     * E quanti bastano per il ripescaggio, a scheletro già in piedi.
     *
     * Sei. Sotto, l'accordo di qualche dettaglio capita per caso su qualunque fogliame; sopra,
     * su una panoramica vera si perdevano foto che stavano al loro posto — una spazzata di
     * quattro scatti ne ha persa una per due dettagli, e quella foto era il quarto del
     * panorama, non un altro momento.
     */
    const val SECOND_CHANCE_INLIERS = 6

    /** E che parte degli abbinamenti devono essere: sotto, è somiglianza casuale. */
    const val MIN_AGREEMENT = 0.12f

    /** Per le giunzioni da ripescaggio basta la metà: non reggono niente, aggiungono soltanto. */
    const val TENTATIVE_AGREEMENT = 0.06f

    /** Due spostamenti entro questi gradi sono lo stesso spostamento. */
    const val VOTE_DEGREES = 1.5f

    /** Oltre questa parte di campo visivo due foto non possono sovrapporsi. */
    const val MAX_REACH = 1.5f

    /** Di quanto una giunzione può discostarsi dalle posizioni scelte restando d'accordo. */
    const val CHECK_DEGREES = 4f

    /** Sotto questi dettagli d'accordo una coppia non è nemmeno «vicina»: è rumore. */
    private const val NEAR_MISS_INLIERS = 4

    private const val CELLS_ACROSS = 30
    private const val MIN_CELL = 12
    private const val MAX_DESCRIBED = 12
}
