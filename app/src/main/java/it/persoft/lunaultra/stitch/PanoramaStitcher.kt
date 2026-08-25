package it.persoft.lunaultra.stitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

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
    /**
     * Le poche righe che dicono se l'unione è andata bene *davvero*, da mostrare in app.
     *
     * Il log completo è la sede della verità, ma si legge su un telefono e nessuno lo apre
     * per sapere se la cucitura ha tenuto. Qui ci vanno solo i numeri che cambiano il
     * giudizio: quanti punti di controllo hanno retto e a che soglia, quanto si
     * sovrappongono le foto, il campo visivo misurato, e se qualcosa non ha funzionato.
     */
    val verdict: List<String> = emptyList(),
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
    /** La ricetta: le manopole regolabili dell'unione. Il default è la ricetta completa. */
    private val tuning: StitchTuning = StitchTuning(),
    /**
     * Quanta memoria c'è davvero. Senza, si resta sulla vecchia stima prudente — e il log lo
     * dichiara, invece di far passare un'ipotesi per una misura.
     */
    private val memory: MemoryBudget = MemoryBudget.unmeasured(),
) {

    /**
     * Quanto è costato aprire gli originali a piena risoluzione.
     *
     * È l'unica parte della cucitura che resta per forza su un filo solo — il decoder JPEG
     * di Android non si spartisce — e su file da trentasette megapixel pesa. Misurarla a
     * parte evita di cercare il collo di bottiglia dove non è: se la cucitura dura cento
     * secondi e sessanta sono decodifica, parallelizzare il resto non sposta niente.
     */
    private var decodeMillis = 0L

    /**
     * I tre tempi della cucitura, tenuti separati perché sono tre lavori diversi.
     *
     * `riconoscimento` è la mappa dei pesi: dice quali pixel della tela questo fotogramma
     * può coprire. `fusione` è la giunzione multibanda, che tocca solo la sovrapposizione.
     * `pittura` è il resto, cioè la gran parte della tela. Senza tenerli separati si può
     * solo tirare a indovinare quale dei tre stia costando — ed è già successo di sbagliare.
     */
    private var recogniseMillis = 0L
    private var blendMillis = 0L
    private var paintMillis = 0L

    /**
     * Il tempo della scheda grafica, separato in disegno e riporto.
     *
     * Sono due cose diverse e conviene vederle separate: `disegno` è quello che fa la GPU,
     * `riporto` è la CPU che rimette le piastrelle sulla tela e aggiorna la mappa dei
     * possessori. Se il primo crolla e il secondo no, il collo di bottiglia si è solo
     * spostato, e la prossima mossa è metterli in pipeline invece di alternarli.
     */
    private var gpuDrawMillis = 0L
    private var gpuMergeMillis = 0L
    private var gpuUploadMillis = 0L

    /** Quello che la GPU ha da raccontare: nome della scheda, autocontrollo, o il motivo del ripiego. */
    private val gpuNotes = mutableListOf<String>()

    /** Spenta per il resto dell'unione: un ripiego non si ritenta a ogni fotogramma. */
    private var gpuOff = false

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
            val workingLongSide = tuning.workingLongSide ?: when {
                heapMb >= 384 -> GENEROUS_WORKING_LONG_SIDE
                heapMb >= 256 -> 2_400
                else -> WORKING_LONG_SIDE
            }
            onProgress(0.02f, "Leggo gli scatti ($workingLongSide px, heap $heapMb MB)")
            val frames = loadFrames(shots, workingLongSide)
            val first = frames.first().bitmap
            val lens = PinholeLens(first.width, first.height, horizontalFovDegrees)

            var placements = shots.map { FramePlacement(it.panDegrees, it.tiltDegrees) }

            // La livella. Quando gli angoli veri non ci sono — foto scelte a mano — la lista
            // arriva con tutti i tilt a zero, cioè con l'ipotesi che la camera fosse in
            // bolla. Se non lo era, l'orizzonte non esce dritto come deve: esce come un arco
            // per fotogramma, il mare sembra una conca e le linee vicine restano dritte
            // invece di incurvarsi. Misurato dall'orizzonte stesso, il difetto sparisce.
            val levelNotes = mutableListOf<String>()
            if (shots.all { abs(it.tiltDegrees) < 0.01f }) {
                val forced = tuning.cameraPitchDegrees
                if (abs(forced) > 0.05f) {
                    placements = placements.map { it.copy(tiltDegrees = forced) }
                    levelNotes += "Orizzonte: camera a %+.1f° (impostata a mano)".format(forced)
                } else {
                    // Un fotogramma per core: la misura dell'orizzonte è indipendente per
                    // ognuno, e in fila costava quanto tutte messe insieme.
                    val pitches = frames.map { frame ->
                        async(Dispatchers.Default) { estimateCameraPitch(frame, lens) }
                    }.awaitAll()
                    val measured = pitches.filterNotNull()
                    val median = if (measured.isEmpty()) null else measured.sorted()[measured.size / 2]
                    val enough = measured.size >= (frames.size + 1) / 2
                    if (tuning.levelHorizon && enough && median != null) {
                        // I fotogrammi in cui l'orizzonte non si vede prendono la mediana degli
                        // altri: meglio l'inclinazione dei vicini che uno zero di comodo.
                        placements = placements.mapIndexed { i, placement ->
                            placement.copy(tiltDegrees = pitches[i] ?: median)
                        }
                        levelNotes += "Orizzonte livellato: camera a %s (misurata su %d foto su %d)".format(
                            pitches.joinToString(" · ") { it?.let { p -> "%+.1f°".format(p) } ?: "—" },
                            measured.size,
                            frames.size,
                        )
                    } else if (enough && median != null && abs(median) >= HORIZON_NOTABLE_DEGREES) {
                        // Di serie la tela resta centrata sul centro delle foto: è la scelta
                        // prevedibile, quella che non sposta l'inquadratura. Ma se l'orizzonte
                        // è lontano dal centro vale la pena dirlo, perché è da lì che nasce
                        // l'incurvamento del mare — e basta un interruttore per raddrizzarlo.
                        levelNotes += ("L'orizzonte cade %+.1f° sotto il centro delle foto: la tela " +
                            "resta centrata sulle foto. Accendi «Livella l'orizzonte» per raddrizzarlo.")
                            .format(median)
                    }
                }
            }

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
            val fullResSampling = tuning.sampleFromOriginals && heapMb >= 384 && sourceScale > 1.05f
            val requestedDensity = lens.imageWidth / lens.horizontalFovDegrees *
                (if (fullResSampling) sourceScale else 1f)

            // La proiezione: cilindrica per le panoramiche normali — è quella delle file
            // singole, e tiene le altezze naturali vicino all'orizzonte — ma equirettangolare
            // per la sferica, perché è l'unica che arriva ai poli ed è l'unica che un
            // visualizzatore 360° sa leggere.
            val provisional = PanoramaCanvas.covering(
                placements = placements,
                lens = lens,
                requestedPixelsPerDegree = chooseDensity(placements, lens, heapMb, requestedDensity),
                maximumLongSide = CANVAS_HARD_CAP_LONG_SIDE,
                projection = projectionFor(placements, lens, fillNadir),
            )

            onProgress(0.10f, "Allineo i fotogrammi")
            val refineStartedAt = System.currentTimeMillis()
            val refinement = refine(
                frames = frames,
                initial = placements,
                lens = lens,
                canvas = provisional,
                wideSearch = wideSearch,
            )
            val refineSeconds = (System.currentTimeMillis() - refineStartedAt) / 1000f
            placements = refinement.placements
            frames.forEach { it.releaseWorkingData() }

            // La tela si rifà sulle posizioni **corrette**, non su quelle di partenza.
            //
            // Era un bug vero e con un sintomo preciso: la tela veniva dimensionata sugli
            // angoli nominali, poi l'allineamento spostava i fotogrammi, e quelli spostati
            // sporgevano oltre il bordo — dove non c'è tela non si dipinge, e quel pezzo di
            // foto spariva. Con correzioni di un grado non si notava; con una correzione di
            // venticinque gradi se ne perdeva un quarto. «Manca anche una parte della foto»
            // era esattamente questo.
            val projection = projectionFor(placements, lens, fillNadir)
            val canvas = PanoramaCanvas.covering(
                placements = placements,
                lens = lens,
                requestedPixelsPerDegree = chooseDensity(placements, lens, heapMb, requestedDensity),
                maximumLongSide = CANVAS_HARD_CAP_LONG_SIDE,
                projection = projection,
            )
            if (projection != tuning.projection && !fillNadir) {
                val reach = verticalReach(placements, lens)
                levelNotes += ("Proiezione: %s invece di %s — la panoramica arriva a %.0f° dall'orizzonte, " +
                    "dove la %s allargherebbe i pixel di %.1f volte in verticale: pixel che nelle " +
                    "foto non ci sono e che la tela dovrebbe inventarsi.").format(
                    projection.label,
                    tuning.projection.label,
                    reach,
                    tuning.projection.label.lowercase(),
                    verticalStretch(tuning.projection, reach),
                )
            }

            onProgress(0.35f, "Unisco e sfumo le giunzioni")
            val composeDetail = mutableListOf<String>()
            val composeStartedAt = System.currentTimeMillis()
            var bitmap = compose(
                frames, placements, lens, canvas, refinement.aligned,
                fullResSampling, refinement.photometric, refinement.warps, composeDetail,
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
                "Tela ${canvas.width}×${canvas.height} a %.1f px/grado · %s (%d MB in memoria, heap $heapMb MB) · "
                    .format(
                        canvas.pixelsPerDegree,
                        canvas.projection.label,
                        canvas.width.toLong() * canvas.height * 4L / (1024L * 1024L),
                    ) +
                    if (fullResSampling) {
                        "cucitura dagli originali a piena risoluzione (×%.1f rispetto ai %d px di lavoro)"
                            .format(sourceScale, workingLongSide)
                    } else {
                        "cucitura dalla copia di lavoro a $workingLongSide px"
                    },
            )
            notes += memory.describe()
            notes += composeDetail
            notes += ("Tempi: allineamento %.0f s · cucitura %.0f s — riconoscimento %.0f s · " +
                "fusione %.0f s · pittura %.0f s · apertura originali %.0f s").format(
                refineSeconds,
                composeSeconds,
                recogniseMillis / 1000f,
                blendMillis / 1000f,
                paintMillis / 1000f,
                decodeMillis / 1000f,
            )
            // Il tempo della scheda non è un tempo in più: sta già dentro riconoscimento e
            // pittura. Separarlo dice l'unica cosa che serve per la mossa successiva — se il
            // collo di bottiglia è ancora il disegno o si è spostato sul riporto.
            if (gpuDrawMillis + gpuMergeMillis + gpuUploadMillis > 0L) {
                notes += ("Di cui sulla scheda grafica: disegno %.1f s · riporto sulla tela %.1f s · " +
                    "caricamento delle sorgenti %.1f s").format(
                    gpuDrawMillis / 1000f,
                    gpuMergeMillis / 1000f,
                    gpuUploadMillis / 1000f,
                )
            }
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

            // Le copie di lavoro sono già state liberate una per una a fine cucitura; questa
            // è la rete per chi non fosse arrivato fin lì. Riciclare due volte non fa niente.
            frames.forEach { it.bitmap.recycle() }
            onProgress(1f, "Panoramica pronta")

            // La sovrapposizione **misurata** fra fotogrammi vicini, che è la cosa che decide
            // se una cucitura può venire bene. Sotto una decina di gradi non c'è abbastanza
            // materiale in comune né per allinearsi né per nascondere una giunzione: meglio
            // dirlo, perché non è un difetto del programma ma di come sono state scattate.
            val verdict = refinement.verdict.toMutableList()
            verdict.addAll(0, levelNotes)
            if (placements.size >= 2) {
                var tightest = Float.MAX_VALUE
                for (k in 1 until placements.size) {
                    val gap = abs(wrapDegrees(placements[k].effectivePan - placements[k - 1].effectivePan))
                    tightest = min(tightest, lens.horizontalFovDegrees - gap)
                }
                verdict.add(
                    0,
                    if (tightest < MIN_HEALTHY_OVERLAP_DEGREES) {
                        "⚠ Sovrapposizione minima %.0f°: poca roba in comune, la giunzione si vedrà"
                            .format(tightest)
                    } else {
                        "Sovrapposizione minima %.0f°".format(tightest)
                    },
                )
            }
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
                    verdict = verdict,
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
    /**
     * Quanto in alto e in basso arriva la panoramica, in gradi dall'orizzonte.
     *
     * È il numero che decide se una proiezione è utilizzabile: ognuna ha una latitudine oltre
     * la quale non ci arriva, e la tela non lo dice — ci arriva e basta, clampando.
     */
    private fun verticalReach(placements: List<FramePlacement>, lens: PinholeLens): Float {
        val half = lens.verticalFovDegrees / 2f
        val top = placements.maxOf { it.effectiveTilt } + half
        val bottom = placements.minOf { it.effectiveTilt } - half
        return max(abs(top), abs(bottom))
    }

    /**
     * La proiezione che la tela può davvero reggere, che non sempre è quella chiesta.
     *
     * La cilindrica è la scelta giusta per una fila sola: vicino all'orizzonte tiene le
     * altezze naturali. Ma `y = R·tan(latitudine)` esplode, e a settantacinque gradi si ferma
     * del tutto. Su una panoramica a tre file — dove si arriva a settantotto gradi — succedono
     * due cose, entrambe brutte: la tela diventa **tre volte più alta** per rappresentare
     * pixel che nessuno guarderà, e tutto quello che sta oltre il limite viene schiacciato
     * sull'ultima riga, cioè la fila di sopra e quella di sotto **spariscono**.
     *
     * Quindi la scelta di chi scatta vale finché la geometria la regge, e oltre si scende
     * alla prima che ce la fa — dicendolo nel log, perché un cambio silenzioso di proiezione
     * è esattamente il genere di cosa che poi non si spiega guardando il risultato.
     */
    /**
     * Di quanto una proiezione allarga il pixel più lontano dall'orizzonte.
     *
     * È il numero che conta davvero, e non è l'angolo: la cilindrica moltiplica la scala
     * verticale per `1/cos²`, Mercatore per `1/cos`, l'equirettangolare per niente. A
     * trentacinque gradi la cilindrica stira di una volta e mezza — si vede appena. A
     * sessantacinque stira di **cinque volte e mezza**, e Mercatore di due e mezza: quei
     * pixel la tela deve inventarseli, perché nella foto non ci sono. Il file diventa enorme
     * e il dettaglio no — è esattamente l'aria di «risoluzione altissima e immagine molle».
     */
    private fun verticalStretch(projection: StitchProjection, reachDegrees: Float): Float {
        val phi = min(reachDegrees, projection.limitDegrees - 0.5f).toRadians()
        val secant = 1f / cos(phi).coerceAtLeast(1e-3f)
        return when (projection) {
            StitchProjection.EQUIRECTANGULAR -> 1f
            StitchProjection.CYLINDRICAL -> secant * secant
            StitchProjection.MERCATOR -> secant
        }
    }

    private fun projectionFor(
        placements: List<FramePlacement>,
        lens: PinholeLens,
        fillNadir: Boolean,
    ): StitchProjection {
        // La sferica non ha scelta: è l'unica che arriva ai poli e l'unica che un
        // visualizzatore 360° sa leggere.
        if (fillNadir) return StitchProjection.EQUIRECTANGULAR
        val reach = verticalReach(placements, lens)
        if (verticalStretch(tuning.projection, reach) <= MAX_VERTICAL_STRETCH) return tuning.projection
        if (tuning.projection == StitchProjection.CYLINDRICAL &&
            verticalStretch(StitchProjection.MERCATOR, reach) <= MAX_VERTICAL_STRETCH
        ) {
            return StitchProjection.MERCATOR
        }
        return StitchProjection.EQUIRECTANGULAR
    }

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

        // Il budget dei vettori di lavoro: una parte della heap **libera adesso**, non del
        // tetto teorico.
        //
        // Prima era il 45% del tetto, e il tetto lo si paga tutto anche quando è già occupato.
        // Ora la heap si misura: con la luminanza passata ai byte e i pixel non più
        // materializzati, all'inizio di una cucitura sono in uso tredici megabyte su
        // cinquecentododici — e continuare a ragionare come se ne mancassero metà costa
        // risoluzione per niente. Sulla panoramica da nove foto la tela usciva a 62 px/grado
        // invece degli 84 che gli originali portano davvero.
        val frameBytes = lens.imageWidth.toLong() * lens.imageHeight * 4L
        val freeHeap = (memory.javaMaxBytes - memory.javaUsedBytes).coerceAtLeast(0L)
        val budget = (freeHeap * WORKING_HEAP_SHARE).toLong() - frameBytes - RESERVED_HEAP_BYTES
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

        // E poi c'è la tela stessa, che fin qui non era contata da nessuna parte.
        //
        // Il conto sopra pesa i vettori di heap Java. La tela invece è un Bitmap, e i Bitmap
        // vivono in memoria nativa: fuori da quel tetto, ma non fuori dal telefono. Quattro
        // byte per pixel su una panoramica a tre file sono quasi un gigabyte, e non contarli
        // significa scoprirlo quando il sistema chiude l'applicazione. Quanto ce ne stia non
        // si indovina: lo dice [MemoryBudget], che chiede al sistema quanta memoria è libera
        // davvero e sotto quale soglia comincia a chiudere applicazioni.
        val canvasAllowance = memory.canvasBytes
        val canvasPerDensitySquared = ownerArea * 4f
        val canvasAffordable = if (canvasPerDensitySquared > 0f) {
            kotlin.math.sqrt(canvasAllowance / canvasPerDensitySquared.toDouble()).toFloat()
        } else {
            Float.MAX_VALUE
        }

        // Mai sotto quello che i vecchi tetti fissi avrebbero concesso: il calcolo può solo
        // migliorare le cose, non peggiorarle.
        val legacyCap = when {
            heapMb >= 384 -> 8_000
            heapMb >= 256 -> 6_000
            else -> MAX_CANVAS_LONG_SIDE
        }
        val legacyFloor = min(requested, legacyCap / max(spanH, spanV))
        // Il tetto della tela non ha pavimento di cortesia: se non ci sta, non ci sta.
        return min(min(requested, max(affordable, legacyFloor)), canvasAffordable)
    }

    /**
     * Un livello della piramide di luminanza: i dati e la sua misura.
     *
     * I dati sono **byte**, non float. Una luminanza sta fra zero e duecentocinquantacinque:
     * un byte la contiene per intero, un float ne usa quattro e non aggiunge un'informazione
     * che nell'immagine di partenza non c'è. Su nove foto la differenza era fra entrare in
     * memoria e non entrarci — e in più questi vettori si leggono in lungo e in largo, quindi
     * quattro volte meno byte sono quattro volte meno letture dalla RAM.
     */
    private class GrayLevel(val data: ByteArray, val width: Int, val height: Int)

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
        private var grayCache: ByteArray? = null
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

        /**
         * La luminanza, calcolata una volta: l'allineamento lavora qui sopra.
         *
         * Si costruisce **una riga per volta** dal Bitmap, senza passare dal vettore di tutti
         * i pixel. Quel vettore pesava trenta megabyte per foto e serviva solo a fabbricare
         * questa luminanza: con nove foto erano duecentosettanta megabyte di heap tenuti in
         * ostaggio per un calcolo che si fa in transito. Il buffer di una riga si riusa, e le
         * chiamate native passano da una a un paio di migliaia — che su nove milioni di pixel
         * non si misurano.
         */
        val gray: ByteArray
            get() = grayCache ?: buildGray().also { grayCache = it }

        private fun buildGray(): ByteArray {
            val out = ByteArray(width * height)
            val row = IntArray(width)
            for (y in 0 until height) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                val base = y * width
                for (x in 0 until width) {
                    val c = row[x]
                    // Gli stessi pesi di sempre (0,299 · 0,587 · 0,114), in interi per
                    // arrivare direttamente al byte senza passare per la virgola mobile.
                    out[base + x] = (
                        (
                            299 * ((c shr 16) and 0xFF) +
                                587 * ((c shr 8) and 0xFF) +
                                114 * (c and 0xFF)
                            ) / 1000
                        ).toByte()
                }
            }
            return out
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
                val data = ByteArray(nw * nh)
                for (y in 0 until nh) {
                    val sy = min(y * 2, prev.height - 1)
                    val sy1 = min(sy + 1, prev.height - 1)
                    for (x in 0 until nw) {
                        val sx = min(x * 2, prev.width - 1)
                        val sx1 = min(sx + 1, prev.width - 1)
                        // La media dei quattro, arrotondata: la somma di quattro byte sta
                        // comoda in un intero, e il risultato torna a essere un byte.
                        val sum = (prev.data[sy * prev.width + sx].toInt() and 0xFF) +
                            (prev.data[sy * prev.width + sx1].toInt() and 0xFF) +
                            (prev.data[sy1 * prev.width + sx].toInt() and 0xFF) +
                            (prev.data[sy1 * prev.width + sx1].toInt() and 0xFF)
                        data[y * nw + x] = ((sum + 2) / 4).toByte()
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
        // A scaglioni, non tutte insieme.
        //
        // Il decodificatore riduce solo per potenze di due, quindi per ogni scatto esistono
        // per un attimo **due** Bitmap: quello appena decodificato, un po' più grande del
        // dovuto, e quello riscalato alla misura di lavoro. Con nove scatti in parallelo sono
        // diciotto immagini insieme in memoria nativa, per un lavoro che dura due secondi. A
        // quattro per volta i core restano occupati lo stesso e il picco si divide.
        shots.chunked(LOAD_BATCH).flatMap { batch ->
            batch.map { shot ->
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
        /**
         * Il campo di deformazione locale di ogni fotogramma, dove si è potuto stimarlo.
         * È quello che assorbe la parallasse: la rotazione da sola non può.
         */
        val warps: List<LocalWarp?>,
        /** Le poche righe che decidono il giudizio, per la scheda in app. */
        val verdict: List<String>,
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
    /**
     * I vicini con cui un fotogramma si confronta: i più vicini in angolo fra quelli che lo
     * precedono, e comunque solo quelli che lo toccano davvero.
     *
     * Serve in due posti — a scegliere gli ancoraggi e a decidere chi può liberare la memoria
     * — e devono dare la stessa risposta, altrimenti si libera qualcosa che serve ancora.
     */
    /**
     * Il fotogramma centrale, e poi tutti gli altri a raggiera attorno a lui.
     *
     * Fin qui si partiva dal primo scatto — quello in alto a sinistra — e si procedeva in
     * ordine di ripresa. Su una fila sola è indifferente; su una griglia no, per due ragioni.
     *
     * **La catena.** Ogni fotogramma si allinea sui vicini già sistemati, quindi il suo errore
     * è la somma degli errori lungo la strada che lo separa dal riferimento. Partendo da un
     * angolo, l'angolo opposto è a quattro passi di distanza; partendo dal centro, nessuno è
     * più lontano di due. La deriva accumulata si dimezza senza cambiare una riga di
     * matematica.
     *
     * **La cucitura.** Chi si posa per primo tiene il centro della tela, e chi viene dopo gli
     * si accosta attorno. Il centro della panoramica è quello che si guarda: è giusto che sia
     * il fotogramma che lo vede meglio a occuparlo per intero, invece di essere l'ultimo a
     * essere ricoperto dai vicini.
     *
     * Il centro non è la media aritmetica degli angoli ma il fotogramma **più vicino a tutti
     * gli altri**: su una griglia dispari è quello di mezzo, su una pari è uno dei due
     * centrali, e su una fila sola è quello di mezzo della fila — sempre la scelta giusta,
     * senza casi particolari.
     *
     * **L'ordine dopo il centro non è la distanza pura.** Sarebbe la cosa ovvia, e sarebbe
     * sbagliata: su questa griglia le file distano 34° e le colonne 49°, quindi la distanza
     * pura farebbe uscire prima quelle di sopra e di sotto e solo dopo quelle di fianco. Il
     * risultato è che il fotogramma di sinistra arriva quando in mezzo alla tela si sono già
     * allargati quelli di sopra e di sotto, e la sua cucitura deve litigare con due lati
     * invece che con uno.
     *
     * Si va invece **ad anelli**, e dentro l'anello prima la fascia centrale: centro, poi i
     * due di fianco, poi quello sopra e quello sotto, e per ultimi i quattro angoli. Così la
     * fascia orizzontale di mezzo — quella che si guarda, quella dell'orizzonte — si chiude
     * per prima e le sue uniche due giunzioni sono cuciture fra **due** fotogrammi, dove il
     * taglio sul minimo disaccordo sa lavorare. Gli angoli, che confinano da due lati e che
     * il ritaglio finale mangia comunque, arrivano quando non possono più fare danno.
     *
     * Sull'allineamento questo non cambia niente: l'ordine decide **quando** un fotogramma
     * viene misurato, non contro chi — i vicini restano sempre i più prossimi in angolo fra
     * quelli già sistemati.
     */
    private fun radialOrder(placements: List<FramePlacement>): List<Int> {
        if (placements.size <= 2) return placements.indices.toList()
        fun distance(a: Int, b: Int) = angularDistance(
            placements[a].effectivePan, placements[a].effectiveTilt,
            placements[b].effectivePan, placements[b].effectiveTilt,
        )
        val centre = placements.indices.minByOrNull { candidate ->
            placements.indices.sumOf { other -> distance(candidate, other).toDouble() }
        } ?: 0

        // Gli scarti si normalizzano sul più grande di ciascun asse, così un passo di colonna
        // e un passo di fila valgono uno tutti e due: la somma diventa il numero dell'anello,
        // uno per i quattro di fianco e di sopra, due per gli angoli. Il pizzico in più
        // sull'inclinazione rompe la parità dentro l'anello a favore della fascia centrale.
        val here = placements[centre]
        val spanPan = placements.maxOf { abs(wrapDegrees(it.effectivePan - here.effectivePan)) }
        val spanTilt = placements.maxOf { abs(it.effectiveTilt - here.effectiveTilt) }
        fun ring(index: Int): Float {
            val other = placements[index]
            val dPan = abs(wrapDegrees(other.effectivePan - here.effectivePan)) /
                spanPan.coerceAtLeast(1e-3f)
            val dTilt = abs(other.effectiveTilt - here.effectiveTilt) /
                spanTilt.coerceAtLeast(1e-3f)
            return dPan + dTilt * (1f + ROW_FIRST_BIAS)
        }
        val rest = placements.indices.filter { it != centre }.sortedBy { ring(it) }
        return listOf(centre) + rest
    }

    private fun anchorsOf(
        index: Int,
        placements: List<FramePlacement>,
        lens: PinholeLens,
        candidates: List<Int>,
    ): List<Int> = candidates
        .filter { it != index }
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
        // Il riferimento è il fotogramma centrale, non il primo scattato: da lì nessun altro
        // dista più di due passi, e la deriva della catena si dimezza.
        val order = radialOrder(initial)
        val reference = order.first()
        aligned[reference] = true
        var worst = 0f
        val photometric = mutableListOf<FloatArray>()
        val warps = arrayOfNulls<LocalWarp>(frames.size)
        val focalEstimates = mutableListOf<Float>()
        val keptCounts = mutableListOf<Int>()
        val thresholds = mutableListOf<Float>()
        var overruledCount = 0
        var starvedCount = 0

        // Una coda, non un ciclo dritto: chi non trova niente da misurare torna in fondo e
        // ci riprova quando i vicini allineati sono di più.
        //
        // In fila ogni fotogramma può confrontarsi solo con quelli che lo precedono, e su una
        // griglia questo lascia scoperti proprio i casi difficili. La fila di sopra guarda il
        // cielo: bruciato, senza dettaglio, senza niente da correlare. Chi ci prova non trova
        // una misura e — come stava succedendo — **resta dov'è**, in mezzo a vicini che nel
        // frattempo si sono spostati di nove gradi. Il risultato è una giunzione fuori posto
        // di nove gradi, garantita.
        //
        // Ma quel fotogramma confina anche con la fila di sotto, che ha l'albero e gli scogli
        // ed è piena di dettaglio: solo che quando tocca a lui la fila di sotto non è ancora
        // stata allineata. Rimandarlo in fondo alla coda costa una seconda misura e gli dà
        // vicini veri. Un rinvio a testa, non di più: se non basta, il log lo dice.
        val queue = ArrayDeque(order.drop(1))
        val postponed = HashSet<Int>()
        var processed = 0
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            currentCoroutineContext().ensureActive()
            processed++
            onProgress(
                0.10f + 0.25f * min(processed, frames.size) / frames.size,
                "Allineo ${frames[index].label}",
            )

            /** Rimanda a dopo, se c'è ancora qualcuno che potrebbe diventare un vicino. */
            fun postpone(reason: String): Boolean {
                if (postponed.add(index) && queue.isNotEmpty()) {
                    queue.addLast(index)
                    return true
                }
                notes += "${frames[index].label}: $reason"
                return false
            }

            // I vicini **già allineati** più vicini in angolo: quello di fianco e, se c'è,
            // quello della fila accanto — così le file della griglia si richiudono fra loro.
            // Alla seconda passata questo include anche i fotogrammi venuti dopo.
            val settled = aligned.indices.filter { aligned[it] }
            val anchors = anchorsOf(index, placements, lens, settled)
            if (anchors.isEmpty()) {
                postpone("nessun vicino allineato con cui confrontarsi, resta agli angoli del gimbal")
                continue
            }

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
            if (results.isEmpty() && postpone(
                    "la ricerca grossolana non ha trovato niente di affidabile: parte dagli " +
                        "angoli del gimbal e rifinisce con i soli punti di controllo",
                )
            ) {
                continue
            }

            // Senza misura non si abbandona il fotogramma: si parte da dove dice il gimbal.
            //
            // Rinunciare qui era la trappola peggiore, e ci sono cascato: se la ricerca
            // grossolana non sa dire niente si saltava il fotogramma **e con lui i punti di
            // controllo, il rollio, la focale, la deformazione locale e la fotometria** —
            // tutto spento insieme, come quando la soglia dei punti al cento per cento non
            // lasciava passare nessuno. Il risultato è una panoramica incollata a secco.
            //
            // Ma gli angoli del gimbal sono una misura meccanica buona a un grado, e i punti
            // di controllo cercano in sette decimi di grado: sono già alla scala giusta per
            // finire il lavoro da soli. Quindi si parte da zero correzione e si va avanti.
            // Due vicini concordi si mediano; discordi, vince il più sicuro di sé.
            val measured = when {
                results.isEmpty() -> Offset(0f, 0f, 0f)
                results.size == 2 &&
                    abs(results[0].panDegrees - results[1].panDegrees) < ANCHOR_AGREEMENT_DEGREES &&
                    abs(results[0].tiltDegrees - results[1].tiltDegrees) < ANCHOR_AGREEMENT_DEGREES ->
                    Offset(
                        (results[0].panDegrees + results[1].panDegrees) / 2f,
                        (results[0].tiltDegrees + results[1].tiltDegrees) / 2f,
                        max(results[0].confidence, results[1].confidence),
                    )
                else -> results.maxByOrNull { it.confidence }!!
            }

            // Un fotogramma che chiede molto più di tutti gli altri sta sbagliando.
            //
            // Il gimbal non sbaglia a caso: se ha una deriva, ce l'ha su tutti gli scatti. Se
            // otto fotogrammi su nove si sono accontentati di due decimi di grado, il nono che
            // ne chiede quattro e mezzo non ha scoperto un errore meccanico che agli altri era
            // sfuggito — ha trovato un massimo che non c'è. È il controllo che nessuna soglia
            // fissa può fare, perché non guarda un numero assoluto: guarda se questo
            // fotogramma è in compagnia degli altri.
            //
            // Attenzione a cosa **non** fa: se la deriva è sistematica e tutti chiedono tre
            // gradi, la mediana è tre e tutti passano. Qui si scarta chi è solo.
            val others = aligned.indices
                .filter { aligned[it] && it != index }
                .map { max(abs(placements[it].panCorrectionDegrees), abs(placements[it].tiltCorrectionDegrees)) }
                .sorted()
            val claim = max(abs(measured.panDegrees), abs(measured.tiltDegrees))
            val lonely = results.isNotEmpty() && others.size >= OUTLIER_MIN_WITNESSES &&
                claim > max(OUTLIER_FLOOR_DEGREES, others[others.size / 2] * OUTLIER_FACTOR)
            if (lonely) {
                notes += ("%s: la ricerca grossolana chiedeva %+.2f° / %+.2f° mentre gli altri " +
                    "si sono accontentati di %.2f°: scartata, resta agli angoli del gimbal")
                    .format(
                        frames[index].label,
                        measured.panDegrees,
                        measured.tiltDegrees,
                        others[others.size / 2],
                    )
            }
            val trusted = results.isNotEmpty() && !lonely
            val proposal = if (trusted) measured else Offset(0f, 0f, 0f)

            // La rifinitura finale la fanno i punti di controllo: con il piazzamento globale
            // già trovato dalla piramide, ogni punto cerca solo in un raggio piccolo — più
            // piccolo del passo di una foglia — e la foglia sbagliata non è più raggiungibile.
            //
            // I punti però fanno anche da giuria. La piramide, davanti a un cielo di nuvole e
            // a un mare increspato — cioè a due superfici che si somigliano ovunque — sa
            // agganciarsi con grande convinzione al posto sbagliato: si è vista proporre
            // ventidue gradi di correzione con il novantuno per cento di concordanza. Una
            // proposta così grossa non si prende per buona: si prova anche a NON applicarla,
            // e vince quella che i punti di controllo confermano. Chi ha ragione trova
            // dettagli che combaciano; chi si è agganciato alle nuvole non ne trova.
            suspend fun gather(candidate: Offset): Pair<Int, ChosenPoints> {
                val place = placements[index].copy(
                    panCorrectionDegrees = candidate.panDegrees,
                    tiltCorrectionDegrees = candidate.tiltDegrees,
                )
                var found = 0
                val all = mutableListOf<FloatArray>()
                anchors.forEach { anchor ->
                    val tally = controlPoints(
                        moving = frames[index],
                        fixed = frames[anchor],
                        movingPlacement = place,
                        fixedPlacement = placements[anchor],
                        lens = lens,
                    )
                    found += tally.candidates
                    // Ogni punto si porta dietro da quale vicino viene: la fotometria ha
                    // bisogno di sapere quale coppia di foto sta confrontando.
                    tally.kept.forEach { point -> all += point + anchor.toFloat() }
                }
                // La soglia di qualità si **adatta** invece di affamare l'allineamento.
                // Chiedere punti al cento per cento e non trovarne nemmeno uno non è
                // severità: è spegnere insieme bundle adjustment, rollio, focale,
                // deformazione locale, fotometria e misura del campo visivo — e far
                // uscire sei ricette di prova identiche fra loro. È successo davvero:
                // 0 punti tenuti su 75, e 1 su 493.
                return found to selectControlPoints(all, tuning.keepNcc, tuning.localWarp)
            }

            var offset = proposal
            var attempt = gather(proposal)
            var overruled = false
            if (max(abs(proposal.panDegrees), abs(proposal.tiltDegrees)) >= SUSPICIOUS_OFFSET_DEGREES) {
                val nominal = Offset(0f, 0f, proposal.confidence)
                val alternative = gather(nominal)
                if (alternative.second.points.size >= CONTROL_MIN_KEPT &&
                    alternative.second.points.size > attempt.second.points.size * OVERRULE_MARGIN
                ) {
                    offset = nominal
                    attempt = alternative
                    overruled = true
                }
            }
            val candidates = attempt.first
            val chosen = attempt.second
            val kept = chosen.points
            keptCounts += kept.size
            thresholds += chosen.threshold
            if (overruled) overruledCount++
            if (kept.size < CONTROL_MIN_KEPT) starvedCount++
            // I campioni per la fotometria: si scartano i punti vicini alla saturazione,
            // dove il rapporto di luminanza non dice più niente di vero.
            kept.forEach { p ->
                if (p[4] in 12f..242f && p[5] in 12f..242f) {
                    photometric += floatArrayOf(p[13], index.toFloat(), p[4], p[5], p[6], p[7])
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
                // Con la manopola spenta si torna alla ricetta storica: solo traslazione.
                val fit = if (tuning.rollFocal) fitPlacement(kept) else null
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

            // Allineato vuol dire misurato da qualcuno: dalla ricerca grossolana, oppure dai
            // punti di controllo. Se non l'ha fatto nessuno, il fotogramma sta dove dice il
            // gimbal — che è la cosa giusta — ma la scheda deve dirlo.
            aligned[index] = trusted || kept.size >= CONTROL_MIN_KEPT
            placements[index] = placements[index].copy(
                panCorrectionDegrees = finalPan,
                tiltCorrectionDegrees = finalTilt,
                rollDegrees = rollDegrees,
                focalScale = focalScale,
            )
            if (focalScale != 1f) focalEstimates += focalScale

            // Il campo di deformazione locale: con il piazzamento finale in mano si torna sui
            // punti di controllo e si guarda dove la geometria li prevede *adesso*. Quello che
            // resta fuori posto non è più errore di puntamento — è parallasse, ed è l'unica
            // cosa che si può ancora togliere.
            if (tuning.localWarp && kept.size >= LocalWarp.MIN_POINTS) {
                val residuals = ArrayList<FloatArray>(kept.size)
                for (point in kept) {
                    val predicted = projectToFrame(point[8], point[9], placements[index], lens)
                    if (!predicted.inside) continue
                    residuals += floatArrayOf(
                        predicted.x,
                        predicted.y,
                        point[10] - predicted.x,
                        point[11] - predicted.y,
                    )
                }
                // Leggera, media o forte: la campana si stringe e il limite si alza insieme.
                // Una campana stretta con un limite basso non servirebbe a niente — potrebbe
                // descrivere l'allargamento ma non arrivarci.
                val (sigmaDivisor, maxFraction) = when (tuning.warpStrength.coerceIn(1, 3)) {
                    1 -> 4f to 0.015f
                    3 -> 10f to 0.040f
                    else -> 6f to 0.025f
                }
                val limit = max(frames[index].width, frames[index].height) * maxFraction
                warps[index] = LocalWarp.from(
                    residuals, frames[index].width, frames[index].height, limit, sigmaDivisor,
                )
            }
            // Chi non farà più da vicino a nessuno libera i suoi vettori di lavoro.
            //
            // La regola di prima chiedeva la distanza dal fotogramma corrente — «più lontano
            // di due campi visivi» — e su una griglia non scattava mai, perché in una griglia
            // nessuna foto è mai davvero lontana da un'altra. Risultato: le luminanze di
            // tutte le foto restavano in memoria fino alla fine, e con nove foto la memoria
            // finiva. La domanda giusta non è «quanto è lontano da qui», è **«qualcuno più
            // avanti lo userà ancora?»**, e si risponde con la stessa funzione che sceglie i
            // vicini. Sbagliare previsione non fa danno: la luminanza è pigra e si rifà dal
            // Bitmap, che è sempre lì.
            val alignedNow = aligned.indices.filter { aligned[it] }
            val stillNeeded = HashSet<Int>()
            for (waiting in queue) stillNeeded += anchorsOf(waiting, placements, lens, alignedNow)
            for (candidate in alignedNow) {
                if (candidate !in stillNeeded) frames[candidate].releaseWorkingData()
            }

            val magnitude = max(abs(finalPan), abs(finalTilt))
            worst = max(worst, magnitude)
            if (index in postponed) {
                notes += "${frames[index].label}: al primo giro non c'era niente da misurare, " +
                    "ripreso con i vicini allineati nel frattempo"
            }
            notes += ("%s: %d punti di controllo, %d sopra l'%d%%%s · corretto %+.2f° / %+.2f° · " +
                "rollio %+.2f° · focale ×%.3f · concordanza %.0f%%%s").format(
                frames[index].label,
                candidates,
                kept.size,
                (chosen.threshold * 100).roundToInt(),
                if (chosen.lowered) {
                    " (chiesto %d%%, troppo severo: nessuno passava)".format((chosen.requested * 100).roundToInt())
                } else {
                    ""
                } + if (overruled) {
                    " · piramide sconfessata (proponeva %+.1f°, i punti dicono di no)"
                        .format(proposal.panDegrees)
                } else {
                    ""
                },
                finalPan,
                finalTilt,
                rollDegrees,
                focalScale,
                offset.confidence * 100f,
                warps[index]?.let { " · deformazione locale fino a %.1f px".format(it.worstShiftPixels) }.orEmpty(),
            )
        }

        // Il campo visivo vero, misurato invece che creduto. I 20 mm equivalenti da cui nasce
        // il numero dichiarato sono catalogo, non metrologia: se la focale stimata è
        // sistematicamente più lunga, il campo vero è più stretto, e le foto combaciano al
        // centro divergendo ai bordi — il difetto classico che nessun allineamento sistema,
        // perché non è un errore di puntamento ma di scala.
        if (focalEstimates.size >= 2) {
            val median = focalEstimates.sorted()[focalEstimates.size / 2]
            val trueFov = 2f * atan(tan(lens.horizontalFovDegrees.toRadians() / 2f) / median).toDegrees()
            notes += if (abs(median - 1f) >= FOCAL_NOTABLE_DEVIATION) {
                ("Campo visivo: dichiarato %.1f°, misurato %.1f° (focale ×%.3f su %d fotogrammi). " +
                    "Se resta costante, è la specifica a essere ottimistica.")
                    .format(lens.horizontalFovDegrees, trueFov, median, focalEstimates.size)
            } else {
                "Campo visivo: dichiarato %.1f°, misurato %.1f° — la specifica regge."
                    .format(lens.horizontalFovDegrees, trueFov)
            }
        }
        val verdict = buildList {
            if (keptCounts.isNotEmpty()) {
                val worstThreshold = thresholds.minOrNull() ?: 0f
                add(
                    "Punti di controllo: %d…%d per giunzione, soglia %d%%".format(
                        keptCounts.minOrNull() ?: 0,
                        keptCounts.maxOrNull() ?: 0,
                        (worstThreshold * 100).roundToInt(),
                    ),
                )
            }
            // Un fotogramma non misurato è la cosa più grave che possa esserci in questa
            // scheda, e stava scritta solo in fondo al log fra ottanta righe. Se i vicini si
            // sono spostati di nove gradi e lui è rimasto dov'era, la giunzione è fuori posto
            // di nove gradi — e non c'è sfumatura che la nasconda.
            // Il riferimento è già segnato come allineato, quindi si esclude da solo: non
            // serve più dare per scontato che sia il primo.
            val unmeasured = aligned.indices.filter { !aligned[it] }
            if (unmeasured.isNotEmpty()) {
                add(
                    "⚠ %d foto non misurate (%s): incollate alla posizione ipotizzata, mentre le altre si sono spostate fino a %.1f°"
                        .format(
                            unmeasured.size,
                            unmeasured.joinToString(", ") { frames[it].label },
                            worst,
                        ),
                )
            }
            if (starvedCount > 0) {
                add(
                    "⚠ %d giunzioni senza punti a sufficienza: lì l'allineamento non è verificato"
                        .format(starvedCount),
                )
            }
            if (overruledCount > 0) {
                add(
                    "⚠ %d spostamenti proposti sono stati scartati: erano agganci falsi"
                        .format(overruledCount),
                )
            }
            if (focalEstimates.size >= 2) {
                val median = focalEstimates.sorted()[focalEstimates.size / 2]
                val trueFov = 2f * atan(tan(lens.horizontalFovDegrees.toRadians() / 2f) / median).toDegrees()
                add(
                    "Campo visivo: dichiarato %.1f°, misurato %.1f°%s".format(
                        lens.horizontalFovDegrees, trueFov,
                        if (abs(median - 1f) >= FOCAL_NOTABLE_DEVIATION) " ← mettilo nelle impostazioni" else "",
                    ),
                )
            }
            val warped = warps.count { it != null }
            if (warped > 0) {
                add(
                    "Deformazione locale su %d fotogrammi, fino a %.0f px".format(
                        warped, warps.filterNotNull().maxOf { it.worstShiftPixels },
                    ),
                )
            } else if (tuning.localWarp) {
                add("Deformazione locale non applicata: punti di controllo insufficienti")
            }
        }
        return Refinement(placements, notes, worst, aligned, photometric, warps.toList(), verdict)
    }

    private class ControlPointTally(val candidates: Int, val kept: List<FloatArray>)

    /** I punti scelti e la soglia a cui si è dovuto scendere per averne abbastanza. */
    private class ChosenPoints(val points: List<FloatArray>, val threshold: Float, val requested: Float) {
        val lowered: Boolean get() = threshold < requested - 1e-4f
    }

    /**
     * Sceglie i punti di controllo partendo dalla qualità chiesta e scendendo se non bastano.
     *
     * Una soglia è un desiderio, non un dato di fatto: su una parete uniforme o in controluce
     * nemmeno un punto raggiunge il novantacinque per cento, e pretenderlo significa restare
     * senza. Restare senza non è «essere prudenti»: è spegnere il bundle adjustment, il
     * rollio, la focale, la deformazione locale, la fotometria e la misura del campo visivo
     * tutti insieme, e ritrovarsi con la sola registrazione a piramide di cui nessuno
     * controlla più il lavoro. Meglio una manciata di punti all'ottanta per cento, e dirlo.
     */
    private fun selectControlPoints(
        matched: List<FloatArray>,
        requested: Float,
        wantsWarp: Boolean,
    ): ChosenPoints {
        val target = if (wantsWarp) CONTROL_TARGET_KEPT else CONTROL_MIN_KEPT
        var threshold = requested
        while (true) {
            val points = matched.filter { it[12] >= threshold }
            if (points.size >= target || threshold <= CONTROL_FLOOR_NCC + 1e-4f) {
                return ChosenPoints(points, threshold, requested)
            }
            threshold = max(CONTROL_FLOOR_NCC, threshold - CONTROL_NCC_STEP)
        }
    }

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
        // Un rollio impossibile per una camera su gimbal vuol dire punti cattivi: lì si torna
        // alla sola traslazione. La focale invece non si butta, si **limita**: fin qui bastava
        // che la focale vera fosse più di un 4% diversa dalla specifica perché *tutta* la
        // correzione — traslazione e rollio compresi — venisse scartata in silenzio. Una
        // specifica ottimistica non è un buon motivo per rinunciare all'allineamento.
        if (abs(rollDeg) > MAX_ROLL_DEGREES) return null
        val freedom = tuning.focalFreedom.coerceIn(0f, MAX_FOCAL_FREEDOM)
        val limitedScale = scaleAdjust.coerceIn(-freedom, freedom)
        return floatArrayOf(sol[0].toFloat(), sol[1].toFloat(), rollDeg, 1f + limitedScale)
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
        val vignetteA: Float,
        val vignetteB: Float,
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

        // I guadagni si centrano sulla media, invece di essere appesi al primo fotogramma.
        //
        // Il sistema fissa a uno il guadagno del primo e stima gli altri rispetto a lui, ma
        // «rispetto a lui» significa lungo una catena di sovrapposizioni: su nove foto il
        // fotogramma in fondo è a cinque anelli di distanza e accumula tutta la deriva. Con
        // una fila di cielo bruciato e due di mare in ombra si arrivava a guadagni di 2,00
        // — cioè il limite — e moltiplicare per due una foto che ha già del bianco significa
        // **bruciarla**: il bianco non torna indietro, e il mare veniva slavato.
        //
        // Chi è la foto «giusta» non lo decide l'ordine di scatto. Togliendo la media dei
        // logaritmi il livello resta quello medio della panoramica, e la correzione più
        // grossa si dimezza: chi è chiaro viene scurito (e lo scuro si recupera) invece che
        // il contrario. È quello che fa Hugin quando pareggia le esposizioni.
        val logGains = HashMap<Int, Double>(present.size)
        present.forEach { frame ->
            logGains[frame] = if (frame == reference) 0.0 else solution[gainColumn[frame]!!]
        }
        val centre = logGains.values.average()
        var worst = 0.0
        logGains.replaceAll { _, value -> (value - centre).also { worst = max(worst, abs(it)) } }

        // Se anche così si sfora, si comprime **tutto** dello stesso fattore invece di
        // tagliare i singoli: tagliare rompe i rapporti fra le foto, e due giunzioni vicine
        // finiscono corrette in modo diverso — che è peggio di una panoramica un po' meno
        // pareggiata.
        val ceiling = kotlin.math.ln(MAX_PHOTO_GAIN.toDouble())
        if (worst > ceiling) {
            val squeeze = ceiling / worst
            logGains.replaceAll { _, value -> value * squeeze }
        }

        val gains = FloatArray(frameCount) { 1f }
        present.forEach { frame ->
            gains[frame] = kotlin.math.exp(logGains[frame]!!).toFloat()
                .coerceIn(MIN_PHOTO_GAIN, MAX_PHOTO_GAIN)
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
                            val dx = abs(gray.luminance(i + 2) - gray.luminance(i - 2))
                            val dy = abs(
                                gray.luminance(i + 2 * fixed.width) - gray.luminance(i - 2 * fixed.width),
                            )
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
            val wanted = (CONTROL_MIN_CANDIDATES * tuning.candidateScale).roundToInt()
            if (found.size >= wanted || step <= CONTROL_MIN_GRID_STEP) break
            step = (step * 2) / 3
        }

        // La ricerca di ogni punto è indipendente dalle altre: si spartiscono fra i core.
        val movingGray = moving.gray
        val candidates = picked.take((CONTROL_MAX_CANDIDATES * tuning.candidateScale).roundToInt())
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
                                gray.luminance(candidate[1] * fixed.width + candidate[0]),
                                movingGray.luminance(
                                    found[1].toInt().coerceIn(0, moving.height - 1) * moving.width +
                                        found[0].toInt().coerceIn(0, moving.width - 1),
                                ),
                                (fx * fx + fy * fy) / (halfWf * halfWf + halfHf * halfHf),
                                (mx * mx + my * my) / (halfWm * halfWm + halfHm * halfHm),
                                // La direzione nel mondo del dettaglio (vista dal fotogramma
                                // fermo, di cui ci fidiamo) e il pixel dove il fotogramma
                                // mobile lo ha davvero trovato: con il piazzamento finale in
                                // mano, la differenza fra previsione e ritrovamento è
                                // esattamente il campo di deformazione locale.
                                world[0],
                                world[1],
                                found[0],
                                found[1],
                                found[2],
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
        template: ByteArray,
        templateWidth: Int,
        sourceX: Int,
        sourceY: Int,
        target: ByteArray,
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
                val v = template.luminance((sourceY + dy) * templateWidth + sourceX + dx)
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
                    for (dx in -r..r) sum += target.luminance(rowBase + dx)
                }
                val targetMean = sum / patch.size
                var cross = 0f
                var targetNorm = 0f
                for (dy in -r..r) {
                    val rowBase = (py + dy) * targetWidth + px
                    val patchBase = (dy + r) * side + r
                    for (dx in -r..r) {
                        val d = target.luminance(rowBase + dx) - targetMean
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
        if (bestX < 0 || best < CONTROL_FLOOR_NCC) return null
        return floatArrayOf(bestX.toFloat(), bestY.toFloat(), best)
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
    private suspend fun registerPair(
        moving: Frame,
        fixed: Frame,
        movingPlacement: FramePlacement,
        fixedPlacement: FramePlacement,
        lens: PinholeLens,
        wideSearch: Boolean,
    ): Offset? = coroutineScope {
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
                if (keep) {
                    // Seno e coseno di latitudine e longitudine, tabulati qui una volta sola:
                    // le direzioni non cambiano più, mentre i candidati che le interrogano
                    // sono migliaia. Calcolarli lì dentro voleva dire rifare lo stesso conto
                    // centinaia di milioni di volte.
                    val latRad = world[1].toRadians()
                    val lonRad = world[0].toRadians()
                    directions += floatArrayOf(
                        world[0], world[1], x.toFloat(), y.toFloat(),
                        sin(latRad), cos(latRad), sin(lonRad), cos(lonRad),
                    )
                }
                x += REG_SAMPLE_STEP
            }
            y += REG_SAMPLE_STEP
        }
        if (directions.size < REG_MIN_SAMPLES) return@coroutineScope null

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

        // Con gli angoli veri i campioni sono già solo quelli della sovrapposizione prevista:
        // la risposta giusta li conserva quasi tutti, e chi li perde per strada va penalizzato.
        val expectedSamples = if (wideSearch) 0 else directions.size

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

            // La griglia dei candidati si spartisce fra i core.
            //
            // È la parte più cara dell'allineamento e girava tutta su un filo solo: al livello
            // grosso della ricerca larga sono un centinaio di posizioni di pan per una trentina
            // di tilt, e ognuna rifà la correlazione su migliaia di punti campione. Ogni
            // candidato però è indipendente da tutti gli altri — [sampledNcc] non scrive
            // niente di condiviso — quindi ognuno può stare su un core suo, e alla fine vince
            // il migliore fra i migliori di ciascuno.
            val offsets = ArrayList<Float>()
            var cp = -rangePan
            while (cp <= rangePan + 1e-3f) {
                offsets += cp
                cp += step
            }
            val workers = min(Runtime.getRuntime().availableProcessors(), MAX_STITCH_WORKERS)

            fun bestWithin(chunk: List<Float>): FloatArray {
                var bestPan = dPan
                var bestTilt = dTilt
                var bestNcc = -2f
                for (panOffset in chunk) {
                    var ct = -rangeTilt
                    while (ct <= rangeTilt + 1e-3f) {
                        val candPan = (dPan + panOffset).coerceIn(-maxPan, maxPan)
                        val candTilt = (dTilt + ct).coerceIn(-maxTilt, maxTilt)
                        val candidate = movingPlacement.copy(
                            panCorrectionDegrees = candPan,
                            tiltCorrectionDegrees = candTilt,
                        )
                        val ncc = sampledNcc(
                            directions, fixedValues, movingLevel, movingScale, candidate, lens,
                            expectedSamples = expectedSamples,
                        )
                        if (ncc > bestNcc) {
                            bestNcc = ncc
                            bestPan = candPan
                            bestTilt = candTilt
                        }
                        ct += step
                    }
                }
                return floatArrayOf(bestPan, bestTilt, bestNcc)
            }

            val best = if (workers <= 1 || offsets.size < 4) {
                bestWithin(offsets)
            } else {
                offsets.chunked((offsets.size + workers - 1) / workers)
                    .map { chunk -> async(Dispatchers.Default) { bestWithin(chunk) } }
                    .awaitAll()
                    .maxByOrNull { it[2] }!!
            }
            dPan = best[0]
            dTilt = best[1]
            confidence = best[2]
        }
        if (confidence < REG_MIN_NCC) return@coroutineScope null

        // Un ottimo che sta sul bordo della finestra non è una misura: è il bordo.
        //
        // Quando gli angoli li ha dati il gimbal, sappiamo già dove guardava la camera a meno
        // di un grado o due. Se l'unica risposta che la correlazione sa dare è «spostati di
        // nove gradi e mezzo», con la finestra che finisce a dieci, quella risposta non l'ha
        // trovata: ci è finita contro. Meglio nessuna correzione — cioè gli angoli meccanici,
        // che sono un dato — di una correzione inventata dal bordo di una ricerca.
        //
        // Nella ricerca larga il ragionamento non vale: lì la finestra è larga apposta perché
        // il passo fra le foto non lo sa nessuno, e una risposta grossa può essere giusta.
        if (!wideSearch &&
            (abs(dPan) >= maxPan * REG_WALL_FRACTION || abs(dTilt) >= maxTilt * REG_WALL_FRACTION)
        ) {
            return@coroutineScope null
        }

        // E la prova deve essere forte quanto è grossa la pretesa.
        //
        // Gli angoli del gimbal non sono un'ipotesi da battere a pari merito: sono una misura
        // meccanica, buona a un grado. Per scostarsene di mezzo grado basta poco; per
        // scostarsene di quattro serve una correlazione che non lasci dubbi. Una correzione di
        // quasi cinque gradi accettata al trentuno per cento di concordanza — cioè appena
        // sopra il minimo — non è un allineamento, è una scommessa fatta con i soldi di chi
        // guarda la panoramica.
        if (!wideSearch) {
            val claim = (max(abs(dPan), abs(dTilt)) / MAX_SEARCH_DEGREES).coerceIn(0f, 1f)
            val needed = REG_MIN_NCC + (REG_STRONG_NCC - REG_MIN_NCC) * claim
            if (confidence < needed) return@coroutineScope null
        }
        Offset(dPan, dTilt, confidence.coerceIn(0f, 1f))
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
        /**
         * Quanti campioni conserverebbe la risposta giusta. Zero significa «non pesare», ed
         * è il caso della ricerca larga, dove i campioni sono tutto il fotogramma fermo e la
         * sovrapposizione vera è per definizione una frazione ignota.
         */
        expectedSamples: Int,
    ): Float {
        var n = 0
        var sumF = 0f
        var sumM = 0f
        var sumFF = 0f
        var sumMM = 0f
        var sumFM = 0f
        // Un proiettore per candidato: le costanti del piazzamento si calcolano una volta
        // qui invece che a ogni direzione, e il risultato si posa nei suoi campi senza
        // allocare niente.
        val projector = FrameProjector(candidate, lens)
        for (i in directions.indices) {
            val direction = directions[i]
            projector.projectDirection(direction[4], direction[5], direction[6], direction[7])
            if (!projector.inside) continue
            val m = bilinear(movingLevel, projector.x * movingScale, projector.y * movingScale)
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
        val ncc = (n * sumFM - sumF * sumM) / sqrt(varF * varM)
        if (expectedSamples <= 0) return ncc

        // Il punteggio si pesa con quanta sovrapposizione resta, e questo era il difetto.
        //
        // Ogni candidato veniva giudicato **su un insieme diverso di campioni**: spostando il
        // fotogramma mobile una parte dei punti esce dall'inquadratura e non vota più. Chi si
        // spostava tanto finiva per essere valutato su poche centinaia di punti invece che su
        // migliaia — e una correlazione su pochi punti è per pura statistica più alta e più
        // ballerina di una su tanti. Il massimo si spostava così verso il bordo della finestra
        // di ricerca, dove i campioni sono meno: si trovavano nove gradi e mezzo di correzione
        // su un gimbal che sa dove stava guardando a meno di un grado.
        //
        // Moltiplicare per la frazione di campioni conservati rimette i candidati sullo stesso
        // piano: perdere un quarto dei punti costa un quarto del punteggio, e la risposta
        // giusta — che li conserva quasi tutti — torna a vincere.
        val coverage = n.toFloat() / expectedSamples
        if (coverage < REG_MIN_COVERAGE) return -2f
        return ncc * coverage
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
        val top = level.data.luminance(base) * (1f - tx) + level.data.luminance(base + 1) * tx
        val bottom = level.data.luminance(base + level.width) * (1f - tx) +
            level.data.luminance(base + level.width + 1) * tx
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
    /**
     * Il contesto grafico e il filo che ne è proprietario: le due cose vanno sempre insieme.
     *
     * Un contesto OpenGL è legato al filo che lo rende corrente, e una corutina di filo ne
     * cambia a ogni sospensione. L'unico modo sicuro è un filo solo, suo, per tutta l'unione:
     * si accende con la tela e si spegne con lei.
     */
    private class GpuSession(
        val renderer: GpuStitchRenderer,
        val dispatcher: ExecutorCoroutineDispatcher,
    )

    /**
     * Accende la scheda grafica, se qualcuna delle manopole GPU è alzata.
     *
     * Non accenderla non è mai un errore: il percorso CPU resta quello completo e testato, e
     * questo metodo restituisce null tutte le volte che qualcosa non torna — driver assente,
     * contesto rifiutato, shader che non compila. Il log dice cosa è successo.
     */
    private suspend fun openGpu(): GpuSession? {
        if (!tuning.gpuRecognise && !tuning.gpuPaint) return null
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "cucitura-gpu")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val renderer = withContext(dispatcher) { GpuStitchRenderer.create() }
        if (renderer == null) {
            dispatcher.close()
            gpuOff = true
            gpuNotes += "GPU: contesto grafico non disponibile — tutto sulla CPU"
            return null
        }
        gpuNotes += "GPU: ${renderer.hardware} · texture fino a ${renderer.maxTextureSize} px · " +
            listOfNotNull(
                "ricognizione".takeIf { tuning.gpuRecognise },
                "pittura".takeIf { tuning.gpuPaint },
            ).joinToString(" e ")
        return GpuSession(renderer, dispatcher)
    }

    private suspend fun closeGpu(gpu: GpuSession?) {
        if (gpu == null) return
        withContext(NonCancellable + gpu.dispatcher) { gpu.renderer.release() }
        gpu.dispatcher.close()
    }

    /** Spegne la scheda per il resto dell'unione, e dice perché. */
    private fun gpuGiveUp(reason: String) {
        if (gpuOff) return
        gpuOff = true
        gpuNotes += "GPU spenta: $reason — da qui in avanti dipinge la CPU"
    }

    /**
     * I numeri del fotogramma nel formato che vuole lo shader.
     *
     * Sono gli stessi che [FrameProjector] si calcola nel costruttore: se le due liste
     * divergono, [gpuVerify] se ne accorge prima che un pixel finisca sulla tela.
     */
    private fun gpuUniforms(
        frame: Frame,
        placement: FramePlacement,
        correction: FrameCorrection,
        lens: PinholeLens,
        canvas: PanoramaCanvas,
        warp: LocalWarp?,
        source: Bitmap?,
    ): GpuFrameUniforms {
        val tilt = placement.effectiveTilt.toRadians()
        val roll = placement.rollDegrees.toRadians()
        return GpuFrameUniforms(
            sourceWidth = source?.width ?: frame.width,
            sourceHeight = source?.height ?: frame.height,
            toSourceX = if (source == null) 1f else frame.fullScaleX,
            toSourceY = if (source == null) 1f else frame.fullScaleY,
            workingWidth = frame.width,
            workingHeight = frame.height,
            focalPixels = lens.focalPixels,
            focalScale = placement.focalScale,
            panDegrees = placement.effectivePan,
            cosTilt = cos(tilt),
            sinTilt = sin(tilt),
            cosRoll = cos(roll),
            sinRoll = sin(roll),
            startLongitude = canvas.startLongitudeDegrees,
            pixelsPerDegree = canvas.pixelsPerDegree,
            radius = canvas.verticalRadius,
            topY = canvas.topPixel,
            projection = canvas.projection.ordinal,
            gain = correction.gain,
            vignetteA = correction.vignetteA,
            vignetteB = correction.vignetteB,
            warpNodesX = LocalWarp.NODES_X,
            warpNodesY = LocalWarp.NODES_Y,
            warp = warp?.interleaved() ?: FloatArray(0),
        )
    }

    /**
     * L'autocontrollo: la scheda e la CPU devono dire la stessa cosa, o la scheda si spegne.
     *
     * È la rete di sicurezza, ed è quello che rende questa strada accettabile. Uno shader
     * sbagliato non è come un ciclo Kotlin sbagliato: non lancia niente, non si ferma, disegna
     * semplicemente una panoramica storta — e chi la guarda su un telefono, in vacanza, non ha
     * nessun modo di distinguerla da un problema di allineamento.
     *
     * Quindi prima di dipingere anche un solo pixel si disegna un riquadro di prova nel centro
     * del fotogramma, e lo stesso riquadro si calcola con [FrameProjector], [featherWeight] e
     * `factorAt` — le funzioni vere. Se i due non coincidono, la GPU si spegne e il log porta i
     * numeri, che è esattamente quello che serve per correggere lo shader.
     *
     * Le due strade non possono coincidere *esattamente*: l'interpolazione bilineare
     * dell'hardware lavora in virgola fissa con una manciata di bit di sotto-pixel, e su un
     * bordo netto un paio di livelli di differenza sono fisiologici. Quello che non è
     * fisiologico è una geometria diversa, e quella si vede subito: un pixel preso nel posto
     * sbagliato non differisce di due livelli, differisce di decine.
     */
    private suspend fun gpuVerify(
        gpu: GpuSession,
        uniforms: GpuFrameUniforms,
        frame: Frame,
        placement: FramePlacement,
        correction: FrameCorrection,
        lens: PinholeLens,
        canvas: PanoramaCanvas,
        warp: LocalWarp?,
        full: Bitmap?,
        withColour: Boolean,
        centreColumn: Int,
        centreRow: Int,
    ): String? {
        val side = GPU_CHECK_SIDE
        if (canvas.height < side) return null
        val col0 = centreColumn - side / 2
        val row0 = (centreRow - side / 2).coerceIn(0, canvas.height - side)
        val tile = IntArray(side * side)
        val drawn = withContext(gpu.dispatcher) {
            gpu.renderer.renderTile(
                uniforms, col0, row0, side, side, weightOnly = !withColour, into = tile,
            )
        }
        if (!drawn) return "il riquadro di prova non si è disegnato"

        val projector = FrameProjector(placement, lens, warp)
        val source = full?.let { SourceBlock(it) }
        var weightSamples = 0
        var weightSum = 0.0
        var weightWorst = 0
        var colourSamples = 0
        var colourSum = 0.0
        var colourWorst = 0
        var r = 0
        while (r < side) {
            projector.row(canvas.latitudeAt(row0 + r))
            var c = 0
            while (c < side) {
                val column = ((col0 + c) % canvas.width + canvas.width) % canvas.width
                val delta = (canvas.longitudeAt(column) - placement.effectivePan).toRadians()
                projector.project(sin(delta), cos(delta))
                val packed = tile[r * side + c]
                val gpuWeight = packed ushr 24
                val cpuWeight = if (!projector.inside) {
                    0
                } else {
                    val w = featherWeight(projector.x, projector.y, frame.width, frame.height)
                    if (w <= 0f) 0 else (w * 255f).roundToInt().coerceIn(1, 255)
                }
                weightSamples++
                val weightDelta = abs(cpuWeight - gpuWeight)
                weightSum += weightDelta.toDouble()
                weightWorst = max(weightWorst, weightDelta)
                if (withColour && cpuWeight != 0 && gpuWeight != 0) {
                    val colour = sampleColor(frame, source, projector.x, projector.y)
                    val factor = correction.factorAt(projector.x, projector.y)
                    val cr = (factor * ((colour shr 16) and 0xFF)).roundToInt().coerceIn(0, 255)
                    val cg = (factor * ((colour shr 8) and 0xFF)).roundToInt().coerceIn(0, 255)
                    val cb = (factor * (colour and 0xFF)).roundToInt().coerceIn(0, 255)
                    val gap = abs(cr - ((packed shr 16) and 0xFF)) +
                        abs(cg - ((packed shr 8) and 0xFF)) +
                        abs(cb - (packed and 0xFF))
                    colourSamples++
                    colourSum += gap / 3.0
                    colourWorst = max(colourWorst, gap / 3)
                }
                c += GPU_CHECK_STEP
            }
            r += GPU_CHECK_STEP
        }

        val weightGap = if (weightSamples == 0) 0.0 else weightSum / weightSamples
        val colourGap = if (colourSamples == 0) 0.0 else colourSum / colourSamples
        gpuNotes += "Autocontrollo ${frame.label}: peso Δ%.1f (max %d) · colore Δ%.1f (max %d) su %d/%d campioni"
            .format(weightGap, weightWorst, colourGap, colourWorst, colourSamples, weightSamples)
        if (weightGap > GPU_MAX_WEIGHT_GAP) {
            return "i pesi non coincidono (Δ%.1f, il limite è %.0f)".format(weightGap, GPU_MAX_WEIGHT_GAP)
        }
        if (withColour && colourSamples >= GPU_CHECK_MIN_COLOUR && colourGap > GPU_MAX_COLOUR_GAP) {
            return "i colori non coincidono (Δ%.1f, il limite è %.0f)".format(colourGap, GPU_MAX_COLOUR_GAP)
        }
        return null
    }

    /**
     * Disegna la finestra di un fotogramma a fasce, e consegna ogni fascia a chi la riporta.
     *
     * A fasce per due motivi. Il primo è il limite delle texture: una tela da diciassettemila
     * pixel non ci sta, e la scheda dichiara quanto è il suo massimo. Il secondo è la memoria:
     * la fascia va riletta in un vettore, e un vettore grande quanto la finestra intera —
     * cinquanta milioni di pixel — sarebbe duecento megabyte accanto alla tela.
     *
     * Il vettore della fascia si riusa per tutte: si alloca una volta sola.
     */
    private suspend fun gpuBands(
        gpu: GpuSession,
        uniforms: GpuFrameUniforms,
        c0: Int,
        row0: Int,
        bw: Int,
        bh: Int,
        weightOnly: Boolean,
        onBand: suspend (band: IntArray, bandRow: Int, rows: Int) -> Boolean,
    ): Boolean {
        val tileWidth = min(bw, gpu.renderer.maxTextureSize)
        val bandHeight = (GPU_BAND_PIXELS / tileWidth)
            .coerceIn(1, min(bh, gpu.renderer.maxTextureSize))
        val band = IntArray(bw * bandHeight)
        var by = 0
        while (by < bh) {
            currentCoroutineContext().ensureActive()
            val rows = min(bandHeight, bh - by)
            val startedAt = System.currentTimeMillis()
            val drawn = withContext(gpu.dispatcher) {
                var good = true
                var bx = 0
                while (bx < bw && good) {
                    val width = min(tileWidth, bw - bx)
                    good = gpu.renderer.renderTile(
                        uniforms, c0 + bx, row0 + by, width, rows,
                        weightOnly = weightOnly, into = band, intoOffset = bx, intoStride = bw,
                    )
                    bx += width
                }
                good
            }
            gpuDrawMillis += System.currentTimeMillis() - startedAt
            if (!drawn) return false
            if (!onBand(band, by, rows)) return false
            by += rows
        }
        return true
    }

    private suspend fun compose(
        frames: List<Frame>,
        placements: List<FramePlacement>,
        lens: PinholeLens,
        canvas: PanoramaCanvas,
        aligned: BooleanArray,
        fullResSampling: Boolean,
        photometric: List<FloatArray>,
        warps: List<LocalWarp?>,
        detail: MutableList<String>,
    ): Bitmap {
        val output = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        // Nero, non trasparente: un JPEG non ha trasparenza e diventerebbe bianco.
        output.eraseColor(0xFF000000.toInt())

        // La fotometria vera: guadagni per foto e vignettatura dell'obiettivo, stimati
        // insieme dai punti di controllo. Il ripiego è la vecchia catena delle mediane.
        val fit = if (tuning.photometric) fitPhotometric(photometric, frames.size) else null
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

        // La scheda grafica, se qualcuna delle sue manopole è alzata: un contesto per tutta
        // la tela, sul suo filo, e spento in ogni caso quando si esce di qui.
        val gpu = openGpu()
        try {
            // Dal centro verso fuori, non in ordine di scatto: il fotogramma che vede meglio
            // il centro della tela se lo prende per intero, e gli altri gli si accostano
            // attorno. Prima il centro era il quinto ad arrivare e si faceva ricoprire i bordi
            // dai quattro che venivano dopo.
            radialOrder(placements).forEachIndexed { step, index ->
                val frame = frames[index]
                currentCoroutineContext().ensureActive()
                onProgress(
                    0.35f + 0.6f * step / frames.size,
                    "Cucio ${frame.label} (${step + 1}/${frames.size})",
                )
                val startedAt = System.currentTimeMillis()
                pasteFrame(
                    output, ownerWeight, frame, placements[index], corrections[index], lens, canvas,
                    fullResSampling, warps.getOrNull(index), gpu, detail,
                    progressBase = 0.35f + 0.6f * step / frames.size,
                    progressSpan = 0.6f / frames.size,
                    progressLabel = "${frame.label} (${step + 1}/${frames.size})",
                )
                val last = detail.removeLastOrNull()
                if (last != null) detail += "$last · ${(System.currentTimeMillis() - startedAt) / 1000f} s"
                // Un fotogramma alla volta anche in memoria: cucito, i suoi vettori, il suo
                // originale a piena risoluzione **e la sua copia di lavoro** si liberano.
                //
                // La copia di lavoro se ne andava solo dopo il ritaglio, cioè nel momento
                // peggiore: nove copie da trenta megabyte tenute in vita mentre in memoria
                // c'erano già la tela e la sua copia ritagliata. Da qui in avanti nessuno la
                // guarda più, e trecento megabyte in meno sul picco sono trecento megabyte.
                frame.releaseWorkingData()
                frame.closeFullResolution()
                frame.bitmap.recycle()
            }
        } finally {
            closeGpu(gpu)
            detail += gpuNotes
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
        warp: LocalWarp?,
        gpu: GpuSession?,
        detail: MutableList<String>? = null,
        /**
         * La fetta di avanzamento che spetta a questo fotogramma. Cucirne uno può durare
         * dieci secondi: senza sotto-passi la barra resterebbe ferma per tutto il tempo, e
         * chi guarda non saprebbe se sta lavorando.
         */
        progressBase: Float = 0f,
        progressSpan: Float = 0f,
        progressLabel: String = "",
    ) {
        fun step(fraction: Float, what: String) {
            if (progressSpan > 0f) onProgress(progressBase + progressSpan * fraction, "$progressLabel · $what")
        }
        val decodeStartedAt = System.currentTimeMillis()
        val full = if (fullResSampling) frame.openFullResolution() else null
        decodeMillis += System.currentTimeMillis() - decodeStartedAt
        val margin = BBOX_MARGIN_DEGREES
        val halfH = lens.horizontalFovDegrees / 2f + margin
        val halfV = lens.verticalFovDegrees / 2f + margin
        val startLon = canvas.centerPanDegrees - canvas.horizontalDegrees / 2f

        val col0 = floor((placement.effectivePan - halfH - startLon) * canvas.pixelsPerDegree).toInt()
        val col1 = ceil((placement.effectivePan + halfH - startLon) * canvas.pixelsPerDegree).toInt()
        // Le righe se le fa dire dalla tela: con la cilindrica o Mercatore la latitudine non
        // è più lineare nelle righe, e un conto a mano taglierebbe il fotogramma.
        val row0 = floor(canvas.rowOf(placement.effectiveTilt + halfV)).toInt()
            .coerceIn(0, canvas.height - 1)
        val row1 = ceil(canvas.rowOf(placement.effectiveTilt - halfV)).toInt()
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
        // Seno e coseno di ogni colonna, una volta per tutte.
        //
        // Le colonne della finestra sono sempre le stesse per tutte le righe: calcolarne la
        // trigonometria a ogni pixel voleva dire rifare milioni di volte lo stesso conto. Qui
        // costa una tabella da qualche decina di chilobyte e si ammortizza su tutta la
        // finestra — che sono cinquanta milioni di pixel.
        val lonSin = FloatArray(bw)
        val lonCos = FloatArray(bw)
        for (bx in 0 until bw) {
            val delta = (canvas.longitudeAt(columns[bx]) - placement.effectivePan).toRadians()
            lonSin[bx] = sin(delta)
            lonCos[bx] = cos(delta)
        }

        // La scheda grafica, se le sue manopole sono alzate e se questo fotogramma le sta
        // bene. Prima di dipingere un solo pixel si passa dall'autocontrollo: uno shader che
        // sbaglia non si ferma da solo, disegna semplicemente una panoramica storta.
        var uniforms: GpuFrameUniforms? = null
        var gpuColours = false
        var gpuUploaded = false
        if (gpu != null && !gpuOff) {
            val sampled = if (fullResSampling) full else null
            val texture = sampled ?: frame.bitmap
            val limit = gpu.renderer.maxTextureSize
            val fits = texture.width <= limit && texture.height <= limit
            if (tuning.gpuPaint && !fits) {
                gpuGiveUp(
                    "la sorgente ${texture.width}×${texture.height} px non entra in una texture da $limit px",
                )
            } else {
                if (tuning.gpuPaint) {
                    val uploadStartedAt = System.currentTimeMillis()
                    gpuUploaded = withContext(gpu.dispatcher) { gpu.renderer.uploadSource(texture) }
                    gpuUploadMillis += System.currentTimeMillis() - uploadStartedAt
                    if (!gpuUploaded) gpuGiveUp("la sorgente non si è caricata sulla scheda")
                }
                if (!gpuOff) {
                    gpuColours = gpuUploaded
                    val candidate = gpuUniforms(frame, placement, correction, lens, canvas, warp, sampled)
                    val complaint = gpuVerify(
                        gpu, candidate, frame, placement, correction, lens, canvas, warp, full,
                        withColour = gpuColours,
                        centreColumn = c0 + bw / 2,
                        centreRow = (row0 + bh / 2).coerceIn(0, canvas.height - 1),
                    )
                    if (complaint != null) gpuGiveUp(complaint) else uniforms = candidate
                }
            }
        }
        // Da qui in avanti è una fotografia della decisione presa: o c'è la scheda con i suoi
        // numeri, o non c'è e si va di CPU. Non cambia più per tutto il fotogramma.
        val gpuPlan = uniforms

        step(0.05f, "cerco dove cade sulla tela")
        val recogniseStartedAt = System.currentTimeMillis()
        val newW = ByteArray(count)
        var recognisedOnGpu = false
        if (gpuPlan != null && gpu != null && tuning.gpuRecognise) {
            // Solo i pesi: la ricognizione di colori non sa che farsene, e non leggere la
            // texture è la metà del lavoro in meno.
            recognisedOnGpu = gpuBands(gpu, gpuPlan, c0, row0, bw, bh, weightOnly = true) { band, bandRow, rows ->
                val mergeStartedAt = System.currentTimeMillis()
                for (r in 0 until rows) {
                    val from = r * bw
                    val to = (bandRow + r) * bw
                    for (bx in 0 until bw) newW[to + bx] = (band[from + bx] ushr 24).toByte()
                }
                gpuMergeMillis += System.currentTimeMillis() - mergeStartedAt
                true
            }
            if (!recognisedOnGpu) gpuGiveUp("la ricognizione non è tornata dalla scheda")
        }
        if (!recognisedOnGpu) parallelRows(row0, bh, 1) { by, _ ->
            // Un proiettore per riga: appartiene a questo filo e a nessun altro, e a fronte
            // dei pixel della riga la sua costruzione non si misura.
            val projector = FrameProjector(placement, lens, warp)
            projector.row(canvas.latitudeAt(row0 + by))
            for (bx in 0 until bw) {
                projector.project(lonSin[bx], lonCos[bx])
                if (!projector.inside) continue
                val weight = featherWeight(projector.x, projector.y, frame.width, frame.height)
                if (weight <= 0f) continue
                newW[by * bw + bx] = (weight * 255f).roundToInt().coerceIn(1, 255).toByte()
            }
        }

        recogniseMillis += System.currentTimeMillis() - recogniseStartedAt

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
        var seamNote = ""
        if (hasOverlap) {
            sx0 = (ov0x - BLEND_CONTEXT_PX).coerceAtLeast(0)
            sx1 = (ov1x + BLEND_CONTEXT_PX).coerceAtMost(bw - 1)
            sy0 = (ov0y - BLEND_CONTEXT_PX).coerceAtLeast(0)
            sy1 = (ov1y + BLEND_CONTEXT_PX).coerceAtMost(bh - 1)
            step(0.25f, "fondo la giunzione (${sx1 - sx0 + 1}×${sy1 - sy0 + 1} px)")
            val blendStartedAt = System.currentTimeMillis()
            seamNote = blendSubWindow(
                output, ownerWeight, frame, placement, correction, lens, canvas,
                columns, row0, bw, newW, sx0, sx1, sy0, sy1, full, warp, lonSin, lonCos,
            )
            blendMillis += System.currentTimeMillis() - blendStartedAt
        }

        step(0.6f, "dipingo ${bw}×$bh px")
        val paintStartedAt = System.currentTimeMillis()
        var paintedOnGpu = false
        if (gpuPlan != null && gpu != null && tuning.gpuPaint && gpuColours) {
            // La scheda disegna la fascia già corretta e già sfumata; alla CPU resta il
            // riporto, che è una copia condizionata — nessuna trigonometria, nessun
            // campionamento. Le stesse righe, gli stessi lavoratori, la stessa parità della
            // mappa dei possessori: dal punto di vista della tela non cambia niente.
            paintedOnGpu = gpuBands(gpu, gpuPlan, c0, row0, bw, bh, weightOnly = false) { band, bandRow, rows ->
                val mergeStartedAt = System.currentTimeMillis()
                parallelRows(row0 + bandRow, rows, canvas.width) { r, rowPixels ->
                    val by = bandRow + r
                    val insideBlendRows = hasOverlap && by in sy0..sy1
                    var touched = false
                    var readRow = false
                    val from = r * bw
                    for (bx in 0 until bw) {
                        if (insideBlendRows && bx in sx0..sx1) continue
                        val weightByte = newW[by * bw + bx].toInt() and 0xFF
                        if (weightByte == 0) continue
                        val packed = band[from + bx]
                        if (packed ushr 24 == 0) continue
                        if (!readRow) {
                            readRow = true
                            output.getPixels(rowPixels, 0, canvas.width, 0, row0 + by, canvas.width, 1)
                        }
                        rowPixels[columns[bx]] = 0xFF000000.toInt() or (packed and 0xFFFFFF)
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
                gpuMergeMillis += System.currentTimeMillis() - mergeStartedAt
                true
            }
            if (!paintedOnGpu) gpuGiveUp("la pittura non è tornata dalla scheda")
        }
        // Il resto del fotogramma: pittura diretta riga per riga, tutte le CPU insieme.
        // Fuori dalla sotto-finestra ogni pixel nuovo cade su tela vuota per costruzione,
        // quindi non c'è niente da fondere e le righe sono indipendenti.
        if (!paintedOnGpu) parallelRows(row0, bh, canvas.width) { by, rowPixels ->
            val insideBlendRows = hasOverlap && by in sy0..sy1
            var touched = false
            var readRow = false
            val projector = FrameProjector(placement, lens, warp)
            val source = full?.let { SourceBlock(it) }
            projector.row(canvas.latitudeAt(row0 + by))
            for (bx in 0 until bw) {
                if (insideBlendRows && bx in sx0..sx1) continue
                val i = by * bw + bx
                val weightByte = newW[i].toInt() and 0xFF
                if (weightByte == 0) continue
                if (!readRow) {
                    readRow = true
                    output.getPixels(rowPixels, 0, canvas.width, 0, row0 + by, canvas.width, 1)
                }
                projector.project(lonSin[bx], lonCos[bx])
                if (!projector.inside) continue
                val color = sampleColor(frame, source, projector.x, projector.y)
                val factor = correction.factorAt(projector.x, projector.y)
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

        paintMillis += System.currentTimeMillis() - paintStartedAt

        // La memoria della scheda è poca: la sorgente di questo fotogramma se ne va subito.
        if (gpuUploaded && gpu != null) withContext(gpu.dispatcher) { gpu.renderer.dropSource() }

        detail?.add(
            "%s: finestra %d×%d px · fusione su %s%s%s · %s".format(
                frame.label, bw, bh,
                if (hasOverlap) "${sx1 - sx0 + 1}×${sy1 - sy0 + 1} px" else "niente (primo tocco di tela)",
                if (seamNote.isEmpty()) "" else " · $seamNote",
                warp?.let { " · deformazione locale fino a %.1f px".format(it.worstShiftPixels) }.orEmpty(),
                "ricognizione su ${if (recognisedOnGpu) "GPU" else "CPU"}, " +
                    "pittura su ${if (paintedOnGpu) "GPU" else "CPU"}",
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
        warp: LocalWarp?,
        /** Seno e coseno di ogni colonna della finestra, tabulati una volta da [pasteFrame]. */
        lonSin: FloatArray,
        lonCos: FloatArray,
    ): String {
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

        // L'istantanea dei possessori, presa PRIMA di qualsiasi scrittura. Senza, la riga
        // dispari di ogni coppia leggeva il peso appena scritto dalla riga sopra (stessa
        // cella a mezza risoluzione), i pesi si pareggiavano e la decisione saltava riga
        // sì / riga no: erano i trattini visti dal vivo. È a mezza risoluzione in
        // coordinate di finestra e si legge con interpolazione: pesi lisci, decisione
        // liscia, una sola linea di cucitura.
        val snapW = sbw / OWNER_SCALE + 2
        val snapH = sbh / OWNER_SCALE + 2
        val ownerSnap = FloatArray(snapW * snapH)
        for (swy in 0 until snapH) {
            val by = min(swy * OWNER_SCALE, sbh - 1)
            val row = subRow0 + by
            for (swx in 0 until snapW) {
                val bx = min(swx * OWNER_SCALE, sbw - 1)
                val col = columns[sx0 + bx]
                ownerSnap[swy * snapW + swx] =
                    (ownerWeight[ownerIndex(canvas.width, row, col)].toInt() and 0xFF) / 255f
            }
        }

        fun oldWeightAt(bx: Int, by: Int): Float =
            bilinearGrid(
                ownerSnap, snapW, snapH,
                bx.toFloat() / OWNER_SCALE, by.toFloat() / OWNER_SCALE,
            )

        // 1) Vecchio e nuovo, campionati al centro di ogni cella s×s, con quanto i due
        // discordano lì. I pesi del nuovo si ricalcolano in virgola mobile: il byte serve
        // solo a dire «qui c'è». La maschera non si decide ancora: prima serve sapere dove
        // conviene tagliare, e per saperlo serve tutta la mappa del disaccordo.
        val baseColor = IntArray(gcount)
        val newColor = IntArray(gcount)
        val mask = FloatArray(gcount)
        val valid = BooleanArray(gcount)
        val newWeightGrid = FloatArray(gcount)
        val oldWeightGrid = FloatArray(gcount)
        val difference = FloatArray(gcount)
        val bothPresent = BooleanArray(gcount)
        parallelRows(0, gh, canvas.width) { gy, rowPixels ->
            val by = min(gy * s + s / 2, sbh - 1)
            val row = subRow0 + by
            val projector = FrameProjector(placement, lens, warp)
            val source = full?.let { SourceBlock(it) }
            projector.row(canvas.latitudeAt(row))
            output.getPixels(rowPixels, 0, canvas.width, 0, row, canvas.width, 1)
            for (gx in 0 until gw) {
                val bx = min(gx * s + s / 2, sbw - 1)
                val col = columns[sx0 + bx]
                val g = gy * gw + gx
                val present = windowNewW[(sy0 + by) * bw + (sx0 + bx)].toInt() != 0
                val oldWeight = oldWeightAt(bx, by)
                val old = rowPixels[col] and 0xFFFFFF
                baseColor[g] = old
                oldWeightGrid[g] = oldWeight
                var newWeight = 0f
                if (present) {
                    projector.project(lonSin[sx0 + bx], lonCos[sx0 + bx])
                    if (projector.inside) {
                        newWeight = featherWeight(projector.x, projector.y, frame.width, frame.height)
                        val color = sampleColor(frame, source, projector.x, projector.y)
                        val factor = correction.factorAt(projector.x, projector.y)
                        val r = (factor * ((color shr 16) and 0xFF)).roundToInt().coerceIn(0, 255)
                        val gch = (factor * ((color shr 8) and 0xFF)).roundToInt().coerceIn(0, 255)
                        val b = (factor * (color and 0xFF)).roundToInt().coerceIn(0, 255)
                        newColor[g] = (r shl 16) or (gch shl 8) or b
                        if (oldWeight > 0f) {
                            bothPresent[g] = true
                            difference[g] =
                                (abs(r - ((old shr 16) and 0xFF)) +
                                    abs(gch - ((old shr 8) and 0xFF)) +
                                    abs(b - (old and 0xFF))).toFloat()
                        }
                    } else {
                        newColor[g] = old
                    }
                } else {
                    newColor[g] = old
                }
                newWeightGrid[g] = newWeight
                valid[g] = newWeight > 0f || oldWeight > 0f
            }
        }

        // Dove tagliare. La mediana geometrica taglia a metà strada, dove capita: se lì
        // passa il bordo fra una tenda vicina e un muro lontano, i due lati del taglio
        // mostrano quel bordo in due posti diversi — la parallasse — e il muro sembra
        // continuare sopra la tenda. Il taglio sul minimo disaccordo cerca invece il
        // percorso lungo il quale le due foto già si assomigliano: lì la cucitura non ha
        // niente da tradire.
        // Una cucitura sola non può descrivere una sovrapposizione su due lati.
        //
        // Il taglio sul minimo disaccordo è un percorso **monotono**: o una colonna per riga,
        // o una riga per colonna. Su una fila sola è perfetto — il vicino è uno, e sta da una
        // parte. In una griglia il fotogramma di centro confina con quello di sinistra *e* con
        // quello di sopra, e un percorso solo deve scegliere: sceglie l'orizzontale, e tutto
        // quello che sta sotto la linea diventa suo — **compresa la parte che era del vicino
        // di sinistra**. Da lì nascono le giunzioni verticali in più: nella fascia centrale se
        // ne contano quattro invece delle due che ci devono essere, perché ogni fotogramma
        // della fila di mezzo si riprende un pezzo di quello di prima.
        //
        // Quando la sovrapposizione riempie quasi tutta la finestra in **entrambe** le
        // direzioni, i vicini sono su più lati. Lì comanda il peso — ogni pixel al fotogramma
        // che in quel punto è più a casa sua, cioè più lontano dal proprio bordo — e quel
        // confine ha la forma giusta per costruzione: due tagli verticali fra i tre della
        // fila, due orizzontali fra le tre file, e basta.
        val windowHeight = if (bw > 0) windowNewW.size / bw else 0
        val manySided = sbw >= bw * SEAM_ONE_SIDED_FRACTION &&
            sbh >= windowHeight * SEAM_ONE_SIDED_FRACTION
        val seam = if (tuning.seamMinimalDifference && !manySided) {
            findSeam(difference, bothPresent, newWeightGrid, oldWeightGrid, gw, gh)
        } else {
            null
        }
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val g = gy * gw + gx
                if (newWeightGrid[g] <= 0f) continue
                if (oldWeightGrid[g] <= 0f) {
                    // Tela vuota: il nuovo non ha rivali, e il montaggio parte da lui —
                    // altrimenti il nero entrerebbe nelle bande larghe.
                    baseColor[g] = newColor[g]
                    mask[g] = 1f
                    continue
                }
                val ownsNew = if (seam != null) {
                    seam.ownsNew(gx.toFloat(), gy.toFloat())
                } else {
                    newWeightGrid[g] > oldWeightGrid[g]
                }
                if (ownsNew) mask[g] = 1f
            }
        }

        // I punti che nessuno copre, riempiti col colore valido più vicino: il nero dentro
        // le piramidi sanguinerebbe nelle bande larghe e scurirebbe il bordo vero.
        fillHoles(baseColor, valid, gw, gh)
        for (g in 0 until gcount) if (!valid[g]) newColor[g] = baseColor[g]

        // 2) La fusione sulla versione ridotta, un canale per volta. Della fusione si
        // tengono DUE correzioni — rispetto al nuovo e rispetto al vecchio — così ogni
        // pixel a piena risoluzione applica quella della propria sorgente: al confine le
        // due decisioni possono divergere di un pelo, e con una correzione sola quella
        // sbagliata scuriva a blocchi.
        val corrOver = Array(3) { FloatArray(gcount) }
        val corrBase = Array(3) { FloatArray(gcount) }
        // Manopola multibanda spenta: correzioni a zero, il montaggio resta a taglio netto —
        // è la ricetta diagnostica che mostra dove cadono le giunzioni.
        for ((channel, shift) in intArrayOf(16, 8, 0).withIndex()) {
            if (!tuning.multiband) break
            val baseChannel = FloatArray(gcount) { ((baseColor[it] shr shift) and 0xFF).toFloat() }
            val overChannel = FloatArray(gcount) { ((newColor[it] shr shift) and 0xFF).toFloat() }
            val blended = MultibandBlender.blend(
                baseChannels = arrayOf(baseChannel),
                overlayChannels = arrayOf(overChannel),
                mask = mask,
                width = gw,
                height = gh,
            )[0]
            for (g in 0 until gcount) {
                corrOver[channel][g] = blended[g] - overChannel[g]
                corrBase[channel][g] = blended[g] - baseChannel[g]
            }
        }

        // 3) Riga per riga a piena risoluzione, tutte le CPU insieme: montaggio netto più
        // la correzione della propria sorgente. Le decisioni leggono l'istantanea, mai la
        // mappa viva.
        parallelRows(subRow0, sbh, canvas.width) { by, rowPixels ->
            val row = subRow0 + by
            val projector = FrameProjector(placement, lens, warp)
            val source = full?.let { SourceBlock(it) }
            projector.row(canvas.latitudeAt(row))
            output.getPixels(rowPixels, 0, canvas.width, 0, row, canvas.width, 1)
            var touched = false
            val gyf = (by.toFloat() / s) - 0.5f + 0.5f / s
            // La correzione multibanda svanisce ai bordi della sotto-finestra.
            //
            // La fusione corregge solo dentro il rettangolo che contiene la sovrapposizione;
            // fuori non corregge niente. Se sul bordo del rettangolo la correzione vale ancora
            // qualcosa, quel salto **si vede** — ed è un rettangolo con i lati perfettamente
            // orizzontali e verticali in mezzo al cielo, che è la cosa più innaturale che una
            // panoramica possa mostrare. Con più file di foto le sotto-finestre sono tante e i
            // rettangoli anche. Sfumando la correzione a zero sul bordo il salto sparisce, e
            // quello che si perde è per costruzione la parte che serviva meno: il bordo della
            // sotto-finestra è dove la sovrapposizione finisce.
            val rowFade = edgeFade(by, sbh)
            for (bx in 0 until sbw) {
                val col = columns[sx0 + bx]
                val present = windowNewW[(sy0 + by) * bw + (sx0 + bx)].toInt() != 0
                val oldWeight = oldWeightAt(bx, by)
                if (!present && oldWeight <= 0f) continue

                val gxf = (bx.toFloat() / s) - 0.5f + 0.5f / s
                var hard = rowPixels[col] and 0xFFFFFF
                var newWeight = 0f
                var useNew = false
                if (present) {
                    projector.project(lonSin[sx0 + bx], lonCos[sx0 + bx])
                    if (projector.inside) {
                        newWeight = featherWeight(projector.x, projector.y, frame.width, frame.height)
                        // La stessa decisione della griglia ridotta, presa qui alla risoluzione
                        // vera: il taglio scelto sul minimo disaccordo, o la mediana geometrica
                        // se non c'era abbastanza sovrapposizione per sceglierlo.
                        val ownsNew = if (seam != null) {
                            seam.ownsNew(gxf, gyf)
                        } else {
                            newWeight > oldWeight
                        }
                        if (newWeight > 0f && (oldWeight <= 0f || ownsNew)) {
                            useNew = true
                            val color = sampleColor(frame, source, projector.x, projector.y)
                            val factor = correction.factorAt(projector.x, projector.y)
                            val r = (factor * ((color shr 16) and 0xFF)).roundToInt().coerceIn(0, 255)
                            val g = (factor * ((color shr 8) and 0xFF)).roundToInt().coerceIn(0, 255)
                            val b = (factor * (color and 0xFF)).roundToInt().coerceIn(0, 255)
                            hard = (r shl 16) or (g shl 8) or b
                        }
                    }
                }
                if (newWeight <= 0f && oldWeight <= 0f) continue

                // Srotolato apposta: scritto come ciclo su `intArrayOf(16, 8, 0).withIndex()`
                // allocava, per **ogni pixel**, l'array dei canali più un oggetto indice per
                // giro. Su una fusione da decine di milioni di pixel sono centinaia di
                // milioni di oggetti, e il netturbino che li raccoglie ferma tutti i fili.
                val grids = if (useNew) corrOver else corrBase
                val fade = rowFade * edgeFade(bx, sbw)
                val red = correctedChannel(hard, 16, grids[0], gw, gh, gxf, gyf, fade)
                val green = correctedChannel(hard, 8, grids[1], gw, gh, gxf, gyf, fade)
                val blue = correctedChannel(hard, 0, grids[2], gw, gh, gxf, gyf, fade)
                rowPixels[col] = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
                touched = true

                val quantized = (newWeight * 255f).roundToInt().coerceIn(0, 255)
                val ownerIdx = ownerIndex(canvas.width, row, col)
                if (quantized > (ownerWeight[ownerIdx].toInt() and 0xFF)) {
                    ownerWeight[ownerIdx] = quantized.toByte()
                }
            }
            if (touched) {
                output.setPixels(rowPixels, 0, canvas.width, 0, row, canvas.width, 1)
            }
        }
        return when {
            manySided -> "confine sul peso (vicini su più lati: un taglio solo non basterebbe)"
            seam == null && tuning.seamMinimalDifference ->
                "taglio a metà strada (la polarità della sovrapposizione non è chiara)"
            seam == null -> "taglio a metà strada"
            else -> "taglio sul minimo disaccordo, %s".format(
                if (seam.vertical) "verticale" else "orizzontale",
            )
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

    /**
     * Dove passa la giunzione fra il nuovo fotogramma e la tela già dipinta.
     *
     * Il confine è una spezzata: per ogni passo lungo la banda di sovrapposizione, la
     * posizione in cui si scavalca. Fra un passo e il successivo può spostarsi di una cella,
     * quindi il percorso è continuo per costruzione — non ci sono salti che diventerebbero
     * scalini nell'immagine.
     */
    private class Seam(
        /** Vero se la banda è alta e stretta e il taglio scende: una colonna per riga. */
        val vertical: Boolean,
        val boundary: FloatArray,
        /** Da che parte del confine sta il fotogramma nuovo. */
        val newOnHighSide: Boolean,
    ) {
        fun ownsNew(gridX: Float, gridY: Float): Boolean {
            val along = if (vertical) gridY else gridX
            val across = if (vertical) gridX else gridY
            val limit = boundaryAt(along)
            return if (newOnHighSide) across > limit else across < limit
        }

        /** Il confine fra due passi, interpolato: il taglio non fa gradini. */
        private fun boundaryAt(position: Float): Float {
            if (boundary.isEmpty()) return 0f
            val p = position.coerceIn(0f, (boundary.size - 1).toFloat())
            val i0 = p.toInt().coerceIn(0, boundary.size - 1)
            val i1 = min(i0 + 1, boundary.size - 1)
            val f = p - i0
            return boundary[i0] * (1f - f) + boundary[i1] * f
        }
    }

    /**
     * Il percorso di taglio che attraversa la sovrapposizione spendendo il meno possibile.
     *
     * È il cuore di come cuciono i programmi seri (enblend, il motore di Hugin, e lo «spline»
     * di Autopano). L'osservazione è questa: la parallasse non si può togliere — se il gimbal
     * non ruota attorno al centro ottico, vicino e lontano si spostano di diverso, e nessuna
     * rotazione li rimette d'accordo insieme. Ma si può **scegliere dove tagliare**. Se il
     * taglio passa dove le due foto già mostrano la stessa cosa — un muro uniforme, un bordo
     * che in entrambe cade nello stesso posto — allora del disaccordo non resta traccia
     * visibile; se passa in mezzo a un oggetto vicino, quell'oggetto si sdoppia o si tronca.
     *
     * Il costo di una cella è quanto le due immagini discordano lì. La programmazione dinamica
     * trova, fra tutti i percorsi continui che attraversano la banda, quello di costo minimo:
     * ogni passo può spostarsi al più di una cella rispetto al precedente, e si accumula il
     * meglio da dietro. Le celle dove uno dei due non c'è sono proibite per costo, così il
     * percorso resta dentro la sovrapposizione vera.
     */
    private fun findSeam(
        difference: FloatArray,
        bothPresent: BooleanArray,
        newWeight: FloatArray,
        oldWeight: FloatArray,
        gw: Int,
        gh: Int,
    ): Seam? {
        var shared = 0
        for (present in bothPresent) if (present) shared++
        if (shared < SEAM_MIN_CELLS) return null

        // Il taglio attraversa la banda per il verso lungo: fra due foto affiancate la
        // sovrapposizione è una striscia alta e stretta, e il taglio scende dall'alto in basso.
        val vertical = gh >= gw
        val steps = if (vertical) gh else gw
        val choices = if (vertical) gw else gh
        if (steps < 2 || choices < 2) return null

        // Da che parte sta il nuovo, e se la domanda ha una risposta sola.
        //
        // Il segnale è quanto il vantaggio del nuovo sulla tela — la differenza dei pesi di
        // sfumatura — cresce spostandosi lungo la banda. Se cresce, il nuovo sta dalla parte
        // alta; se cala, dalla parte bassa. È una covarianza, e la sua forza dice anche
        // quanto fidarsi: quando la sovrapposizione è su **due** lati (l'ultimo scatto di un
        // giro che si richiude, o una griglia dove il fotogramma tocca il vicino di fianco e
        // quello sopra) il nuovo domina in mezzo e la tela alle due estremità — la covarianza
        // si annulla, e vuol dire che un taglio solo non può separarli. Lì si torna alla
        // mediana geometrica, che quel caso lo gestisce da sempre.
        var cells = 0
        var meanAcross = 0f
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                if (!bothPresent[gy * gw + gx]) continue
                meanAcross += (if (vertical) gx else gy).toFloat()
                cells++
            }
        }
        if (cells == 0) return null
        meanAcross /= cells
        var covariance = 0f
        var strength = 0f
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val g = gy * gw + gx
                if (!bothPresent[g]) continue
                val across = (if (vertical) gx else gy).toFloat() - meanAcross
                val advantage = newWeight[g] - oldWeight[g]
                covariance += advantage * across
                strength += abs(advantage) * abs(across)
            }
        }
        if (strength <= 0f || abs(covariance) / strength < SEAM_MIN_POLARITY) return null
        val newOnHighSide = covariance > 0f

        val cost = FloatArray(steps * choices)
        for (step in 0 until steps) {
            for (k in 0 until choices) {
                val g = if (vertical) step * gw + k else k * gw + step
                cost[step * choices + k] = if (bothPresent[g]) difference[g] else SEAM_FORBIDDEN
            }
        }

        // La somma in doppia precisione: su una banda lunga migliaia di passi, le celle
        // proibite accumulano milioni e in virgola semplice le differenze di colore — che
        // sono l'unica cosa che conta — si perderebbero nell'arrotondamento.
        val best = DoubleArray(steps * choices)
        val cameFrom = IntArray(steps * choices)
        for (k in 0 until choices) best[k] = cost[k].toDouble()
        for (step in 1 until steps) {
            val previous = (step - 1) * choices
            val current = step * choices
            for (k in 0 until choices) {
                var cheapest = Double.MAX_VALUE
                var chosen = k
                for (delta in -1..1) {
                    val previousK = k + delta
                    if (previousK < 0 || previousK >= choices) continue
                    val value = best[previous + previousK]
                    if (value < cheapest) {
                        cheapest = value
                        chosen = previousK
                    }
                }
                best[current + k] = cost[current + k] + cheapest
                cameFrom[current + k] = chosen
            }
        }

        var end = 0
        var cheapest = Double.MAX_VALUE
        val lastStep = (steps - 1) * choices
        for (k in 0 until choices) {
            if (best[lastStep + k] < cheapest) {
                cheapest = best[lastStep + k]
                end = k
            }
        }
        val boundary = FloatArray(steps)
        var k = end
        for (step in steps - 1 downTo 0) {
            boundary[step] = k.toFloat()
            k = cameFrom[step * choices + k]
        }
        return Seam(vertical, boundary, newOnHighSide)
    }

    /** Il canale del montaggio netto più la correzione multibanda letta dalla griglia. */
    private fun correctedChannel(
        hard: Int,
        shift: Int,
        grid: FloatArray,
        gridWidth: Int,
        gridHeight: Int,
        gx: Float,
        gy: Float,
        /** Quanto vale la correzione qui: uno dentro, zero sul bordo della sotto-finestra. */
        weight: Float,
    ): Int = (((hard shr shift) and 0xFF) + weight * bilinearGrid(grid, gridWidth, gridHeight, gx, gy))
        .roundToInt()
        .coerceIn(0, 255)

    /**
     * Uno dentro, zero sul bordo: la rampa che spegne la correzione ai margini.
     *
     * Il coseno rialzato invece di una rampa dritta perché una rampa lineare lascia due
     * spigoli — la derivata salta all'inizio e alla fine — e l'occhio quegli spigoli li
     * trova: è lo stesso motivo per cui la sfumatura dei fotogrammi è al quadrato e non
     * lineare.
     */
    private fun edgeFade(position: Int, span: Int): Float {
        if (span <= 2) return 1f
        val margin = min(BLEND_CONTEXT_PX, span / 2)
        if (margin <= 0) return 1f
        val distance = min(position, span - 1 - position)
        if (distance >= margin) return 1f
        val t = (distance + 0.5f) / margin
        return 0.5f - 0.5f * cos(t * PI_FLOAT)
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
    private suspend fun cropBlackEdges(bitmap: Bitmap, allowColumns: Boolean): Bitmap = coroutineScope {
        val width = bitmap.width
        val height = bitmap.height
        val leftRun = IntArray(height)
        val rightRun = IntArray(height)
        // La scansione dei bordi guarda ogni pixel della tela: su una panoramica da sessanta
        // megapixel, in fila, sono secondi buttati su un core solo mentre gli altri sette
        // guardano. Le righe sono indipendenti, e ognuna scrive solo la propria casella.
        val workers = min(Runtime.getRuntime().availableProcessors(), MAX_STITCH_WORKERS)
        val band = (height + workers - 1) / workers
        (0 until height step band.coerceAtLeast(1)).map { start ->
            async(Dispatchers.Default) {
                val row = IntArray(width)
                for (y in start until min(start + band, height)) {
                    bitmap.getPixels(row, 0, width, 0, y, width, 1)
                    var left = 0
                    while (left < width && row[left] == EMPTY_PIXEL) left++
                    var right = 0
                    while (right < width - left && row[width - 1 - right] == EMPTY_PIXEL) right++
                    leftRun[y] = left
                    rightRun[y] = right
                }
            }
        }.awaitAll()

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

        if (top == 0 && bottom == height - 1 && left == 0 && right == width - 1) return@coroutineScope bitmap
        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
        bitmap.recycle()
        cropped
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
    private fun sampleColor(frame: Frame, source: SourceBlock?, x: Float, y: Float): Int =
        if (source == null) {
            sample(frame, x, y)
        } else {
            source.sample(x * frame.fullScaleX, y * frame.fullScaleY)
        }



    /** Colore interpolato fra i quattro pixel attorno: senza, i bordi diventano una scaletta. */
    private fun sample(frame: Frame, x: Float, y: Float): Int {
        val x0 = x.toInt().coerceIn(0, frame.width - 1)
        val y0 = y.toInt().coerceIn(0, frame.height - 1)
        val x1 = (x0 + 1).coerceAtMost(frame.width - 1)
        val y1 = (y0 + 1).coerceAtMost(frame.height - 1)
        val fx = x - x0
        val fy = y - y0
        return bilinearQuad(
            frame.pixels[y0 * frame.width + x0],
            frame.pixels[y0 * frame.width + x1],
            frame.pixels[y1 * frame.width + x0],
            frame.pixels[y1 * frame.width + x1],
            fx,
            fy,
        )
    }

    /**
     * L'inclinazione della camera, letta dall'orizzonte che si vede nella foto.
     *
     * È il parametro che mancava, e il difetto che produce è inconfondibile: il mare diventa
     * una conca e il marciapiede vicino resta dritto, cioè l'esatto contrario di quello che
     * deve succedere. In una panoramica equirettangolare l'orizzonte — che è un cerchio
     * massimo — è una **riga dritta**, e una linea dritta vicina alla camera, come il bordo
     * di un molo, è quella che si incurva. Se si dà per scontato che la camera fosse in
     * bolla mentre guardava in su di dodici gradi, i due ruoli si scambiano: la riga
     * orizzontale della foto, piazzata come se fosse all'altezza dell'occhio, diventa un
     * arco con il colmo al centro del fotogramma, e ogni foto ne aggiunge uno.
     *
     * Misurarlo è possibile perché l'orizzonte, dove c'è, è il gradiente orizzontale più
     * forte e più esteso della foto: cielo chiaro sopra, terra o acqua scura sotto, per
     * quasi tutte le colonne alla stessa altezza. Si cerca in ogni colonna il salto verso
     * il buio più netto, si prende la mediana delle colonne convincenti, e la si converte
     * in gradi attraverso la focale. Se le colonne convincenti sono poche o non si mettono
     * d'accordo — un interno, un muro, una foto senza orizzonte — non si inventa niente e
     * si torna a zero.
     *
     * Sulle tre foto del molo misura +11,9°, +13,7° e +15,1°: la camera guardava in su, di
     * un po' di più a ogni scatto.
     */
    private fun estimateCameraPitch(frame: Frame, lens: PinholeLens): Float? {
        val gray = frame.gray
        val width = frame.width
        val height = frame.height
        if (width < 32 || height < 32) return null

        // L'orizzonte non sta mai agli estremi: cercarlo lì significa trovare il bordo della
        // foto o una nuvola bassa.
        val top = (height * HORIZON_BAND_TOP).toInt()
        val bottom = (height * HORIZON_BAND_BOTTOM).toInt()
        if (bottom - top < 8) return null

        val rows = mutableListOf<Float>()
        var column = 0
        while (column < width) {
            var bestDrop = 0f
            var bestRow = -1
            for (row in top until bottom) {
                // Il salto verso il buio fra due bande di qualche riga: più stabile del
                // gradiente fra righe adiacenti, che sull'acqua increspata è tutto rumore.
                val above = gray.luminance((row - HORIZON_SPAN) * width + column)
                val below = gray.luminance((row + HORIZON_SPAN) * width + column)
                val drop = above - below
                if (drop > bestDrop) {
                    bestDrop = drop
                    bestRow = row
                }
            }
            if (bestDrop >= HORIZON_MIN_CONTRAST && bestRow >= 0) rows += bestRow.toFloat()
            column += HORIZON_COLUMN_STEP
        }

        val sampled = width / HORIZON_COLUMN_STEP
        if (rows.size < sampled * HORIZON_MIN_COVERAGE) return null
        val sorted = rows.sorted()
        val median = sorted[sorted.size / 2]
        // Le colonne devono essere d'accordo: un orizzonte vero è alla stessa altezza quasi
        // ovunque. Se sono sparse, quello che si è trovato non è un orizzonte.
        val spread = sorted[(sorted.size * 3) / 4] - sorted[sorted.size / 4]
        if (spread > height * HORIZON_MAX_SPREAD) return null

        val pitch = atan((median - height / 2f) / lens.focalPixels).toDegrees()
        return if (abs(pitch) > MAX_CAMERA_PITCH_DEGREES) null else pitch
    }


    private fun luma(color: Int): Float =
        0.299f * ((color shr 16) and 0xFF) + 0.587f * ((color shr 8) and 0xFF) + 0.114f * (color and 0xFF)

    private companion object {
        /** Lato lungo di lavoro degli scatti: oltre, si paga memoria per dettaglio che si butta. */
        const val WORKING_LONG_SIDE = 1600

        /** Quanti scatti si decodificano insieme: oltre, il picco di memoria non paga più. */
        const val LOAD_BATCH = 4

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

        /**
         * Quanta sovrapposizione deve restare a un candidato perché il suo punteggio conti.
         *
         * Sotto metà dei campioni previsti non si sta più misurando lo stesso pezzo di mondo:
         * si sta correlando quel che rimane, e quel che rimane dà sempre ragione a chi si è
         * spostato di più.
         */
        const val REG_MIN_COVERAGE = 0.5f

        /**
         * Quanta della heap libera possono prendersi i vettori di lavoro della cucitura.
         *
         * Non tutta: il raccoglitore di memoria ha bisogno di spazio per lavorare, e una heap
         * riempita fino all'orlo passa il tempo a raccogliere invece che a cucire. Il resto
         * della risoluzione perduta non si recupera alzando questo numero, si recupera
         * togliendo quei vettori dalla heap Java — che è un lavoro, non una manopola.
         */
        const val WORKING_HEAP_SHARE = 0.60

        /** Quello che si tiene da parte comunque, per i buffer di riga e il respiro del GC. */
        const val RESERVED_HEAP_BYTES = 48L * 1024L * 1024L

        /**
         * Quanto vicino al bordo della finestra una risposta smette di essere una misura.
         *
         * Con gli angoli del gimbal la finestra è ±10°, e un errore di puntamento vero sta
         * sotto i due. Sette gradi non sono una correzione: sono il segno che la correlazione
         * non ha trovato niente e si è appoggiata al limite di quello che le era concesso.
         */
        const val REG_WALL_FRACTION = 0.7f

        /**
         * La concordanza che serve per spostare un fotogramma di un errore di puntamento
         * intero ([MAX_SEARCH_DEGREES]). Fra zero e lì la richiesta sale in proporzione:
         * mezzo grado si concede quasi gratis, quattro no.
         */
        const val REG_STRONG_NCC = 0.60f

        /**
         * Quanti fotogrammi già misurati servono perché «gli altri» siano una compagnia e non
         * un aneddoto, e di quanto uno può staccarsi da loro prima di essere solo.
         */
        const val OUTLIER_MIN_WITNESSES = 3
        const val OUTLIER_FACTOR = 4f

        /** Sotto questo nessuno è un caso strano: è la deriva normale di un gimbal. */
        const val OUTLIER_FLOOR_DEGREES = 1.5f

        /** Due vicini che suggeriscono correzioni più lontane di così sono in disaccordo. */
        const val ANCHOR_AGREEMENT_DEGREES = 0.8f

        // ---- Punti di controllo (la rifinitura, e il metro della qualità) ----

        /** Quanti punti di controllo generare come minimo fra due fotogrammi. */
        const val CONTROL_MIN_CANDIDATES = 150

        /** Oltre questo tetto il costo cresce e la statistica no. */
        const val CONTROL_MAX_CANDIDATES = 260

        /**
         * Il pavimento assoluto della qualità di un punto: sotto, non è più nemmeno
         * un'opinione. La soglia chiesta può scendere fin qui, non oltre.
         */
        const val CONTROL_FLOOR_NCC = 0.60f

        /** Di quanto scende la soglia a ogni tentativo, quando i punti non bastano. */
        const val CONTROL_NCC_STEP = 0.05f

        /** I punti che si vorrebbero avere quando serve anche la deformazione locale. */
        const val CONTROL_TARGET_KEPT = 40

        /**
         * Oltre questa correzione la proposta della piramide è troppo grossa per crederle
         * sulla parola: si mette alla prova contro l'ipotesi «non spostare niente».
         */
        const val SUSPICIOUS_OFFSET_DEGREES = 6f

        /** Di quanto l'alternativa deve battere la proposta per sostituirla. */
        const val OVERRULE_MARGIN = 1.5f

        /** Sotto questa sovrapposizione fra due scatti non c'è materiale per una cucitura buona. */
        const val MIN_HEALTHY_OVERLAP_DEGREES = 12f

        // ---- La livella: l'orizzonte cercato nella foto per sapere come era messa la camera ----

        /** La fascia in cui può stare un orizzonte: non ai bordi, dove c'è altro. */
        const val HORIZON_BAND_TOP = 0.25f
        const val HORIZON_BAND_BOTTOM = 0.85f

        /** Il salto si misura fra due bande distanti così: sull'acqua increspata il gradiente
         * fra righe adiacenti è solo rumore. */
        const val HORIZON_SPAN = 3

        /** Sotto questo salto di luminanza non è un orizzonte, è una sfumatura. */
        const val HORIZON_MIN_CONTRAST = 18f

        /** Una colonna ogni tot: l'orizzonte è largo, non serve guardarle tutte. */
        const val HORIZON_COLUMN_STEP = 4

        /** Quante colonne devono trovarlo perché sia credibile. */
        const val HORIZON_MIN_COVERAGE = 0.55f

        /** Quanto possono essere in disaccordo le colonne, in frazione dell'altezza. */
        const val HORIZON_MAX_SPREAD = 0.06f

        /** Oltre questa inclinazione non è più una camera che guarda un paesaggio. */
        const val MAX_CAMERA_PITCH_DEGREES = 40f

        /** Sotto questo scarto fra orizzonte e centro della foto non vale la pena dire niente. */
        const val HORIZON_NOTABLE_DEGREES = 3f

        // La soglia di qualità dei punti (già 0,80 fisso) ora è in StitchTuning.keepNcc.

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

        /** Oltre questo rollio la stima non è più credibile e si torna alla sola traslazione. */
        const val MAX_ROLL_DEGREES = 4f

        /** Il tetto assoluto della libertà sulla focale, qualunque cosa dica la ricetta. */
        const val MAX_FOCAL_FREEDOM = 0.35f

        /** Sotto questo scarto la focale misurata e quella dichiarata sono la stessa cosa. */
        const val FOCAL_NOTABLE_DEVIATION = 0.02f

        /** Sotto questo numero di celle in comune, il taglio sul minimo non ha dati per scegliere. */
        const val SEAM_MIN_CELLS = 24

        /**
         * Il costo di una cella dove uno dei due non c'è: alto abbastanza da tenere il taglio
         * dentro la sovrapposizione, basso abbastanza da non far esplodere la somma.
         */
        const val SEAM_FORBIDDEN = 10_000f

        /**
         * Quanto deve essere netta la separazione fra nuovo e tela perché un taglio solo la
         * possa rappresentare. Sotto, la sovrapposizione è su due lati e si torna alla
         * mediana geometrica.
         */
        const val SEAM_MIN_POLARITY = 0.15f

        /**
         * Quanto la sovrapposizione deve riempire la finestra, in entrambe le direzioni,
         * perché si capisca che i vicini sono su più lati.
         *
         * Un vicino solo lascia una sovrapposizione stretta in una direzione: mezza finestra o
         * meno. Due vicini su due lati la riempiono quasi tutta in tutte e due. Sui numeri veri
         * di una griglia 3×3 la separazione è netta — 46%/96% e 98%/51% per chi ha un vicino
         * solo, 96%/92% e 100%/93% per chi ne ha due.
         */
        const val SEAM_ONE_SIDED_FRACTION = 0.75f

        /**
         * Di quanto un passo di fila «pesa» più di un passo di colonna nell'ordine di
         * cucitura. Piccolo apposta: non deve cambiare l'anello, solo decidere chi esce prima
         * a parità di anello — e a parità di anello si vuole la fascia centrale.
         */
        const val ROW_FIRST_BIAS = 0.05f

        /** Sotto questi campioni la fotometria globale non si fida e resta la catena. */
        const val PHOTOMETRIC_MIN_SAMPLES = 40

        /** I coefficienti di vignettatura credibili per un obiettivo vero. */
        const val VIGNETTE_LIMIT = 0.8f

        /** I guadagni di esposizione fra due scatti in automatico: fino a un raddoppio. */
        const val MIN_PHOTO_GAIN = 0.5f
        const val MAX_PHOTO_GAIN = 2.0f

        /**
         * Quanto una proiezione può allargare il pixel più lontano dall'orizzonte.
         *
         * Una volta e mezza è il punto in cui l'interpolazione non si nota ancora. Oltre, la
         * tela chiede più pixel di quanti la foto ne contenga e se li inventa: il file cresce,
         * il dettaglio no, e il risultato sembra molle proprio dove è più grande. Con questo
         * limite una fila sola resta cilindrica (a 35° stira 1,48×) e una panoramica su tre
         * file passa all'equirettangolare, che non stira affatto.
         */
        const val MAX_VERTICAL_STRETCH = 1.5f

        /** La mappa dei possessori vive a un pixel ogni [OWNER_SCALE] per dimensione. */
        const val OWNER_SCALE = 2

        /**
         * Il contesto attorno alla sovrapposizione che le piramidi vogliono vedere — ed è
         * anche la larghezza su cui la correzione si spegne arrivando al bordo.
         */
        const val BLEND_CONTEXT_PX = 96

        /** Pi greco in virgola semplice, per la rampa del coseno rialzato. */
        const val PI_FLOAT = 3.1415927f

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

        /**
         * Quanto grande è una fascia disegnata dalla scheda, in pixel.
         *
         * Due milioni sono otto megabyte di vettore, riusato per tutte le fasce di un
         * fotogramma: abbastanza da ammortizzare il costo fisso di ogni disegno, abbastanza
         * poco da non pesare accanto a una tela che di suo ne occupa quattrocentosessanta.
         * E questo vettore è heap Java, non memoria nativa come il Bitmap: è il posto dove
         * si sta più stretti.
         */
        const val GPU_BAND_PIXELS = 2 shl 20

        /** Il lato del riquadro su cui scheda e CPU si confrontano, e il passo del campione. */
        const val GPU_CHECK_SIDE = 96
        const val GPU_CHECK_STEP = 7

        /** Sotto questi campioni di colore il confronto non ha abbastanza da dire. */
        const val GPU_CHECK_MIN_COLOUR = 12

        /**
         * Le tolleranze dell'autocontrollo, in livelli su 255.
         *
         * Il peso è pura geometria e deve tornare quasi esatto: due strade che proiettano
         * uguale danno pesi uguali, e la differenza residua è solo l'arrotondamento. Il
         * colore ha in più l'interpolazione bilineare dell'hardware, che lavora in virgola
         * fissa: su un bordo netto qualche livello di scarto è normale, decine no.
         */
        const val GPU_MAX_WEIGHT_GAP = 3.0
        const val GPU_MAX_COLOUR_GAP = 6.0
    }
}

/** Il fattore di riduzione che il decodificatore accetta: potenze di due, mai sotto uno. */
fun sampleSizeFor(sourceWidth: Int, targetLongSide: Int): Int {
    if (sourceWidth <= 0 || targetLongSide <= 0) return 1
    var size = 1
    while (sourceWidth / (size * 2) >= targetLongSide) size *= 2
    return size
}

/**
 * L'interpolazione dei tre canali di un quadratino, srotolata.
 *
 * Scritta come ciclo su `intArrayOf(16, 8, 0)` allocava un array a ogni pixel: su cento
 * milioni di pixel sono cento milioni di oggetti da raccogliere, e il netturbino ferma tutti
 * i fili mentre passa. È il genere di spreco che non si vede leggendo il codice e si vede
 * benissimo nel contatore dei core.
 */
private fun bilinearQuad(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float): Int {
    val r = channelAt(c00, c10, c01, c11, 16, fx, fy)
    val g = channelAt(c00, c10, c01, c11, 8, fx, fy)
    val b = channelAt(c00, c10, c01, c11, 0, fx, fy)
    return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
}

private fun channelAt(c00: Int, c10: Int, c01: Int, c11: Int, shift: Int, fx: Float, fy: Float): Int {
    val a = (c00 shr shift) and 0xFF
    val b = (c10 shr shift) and 0xFF
    val c = (c01 shr shift) and 0xFF
    val d = (c11 shr shift) and 0xFF
    val top = a + (b - a) * fx
    val bottom = c + (d - c) * fx
    return (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
}

/**
 * Una finestrella dell'originale tenuta in heap, per non attraversare il confine nativo a
 * ogni pixel.
 *
 * Leggere dal Bitmap a piena risoluzione costava una chiamata nativa per pixel: cento
 * milioni di attraversamenti, ed erano il grosso del tempo di pittura. La via d'uscita nasce
 * da un fatto della geometria: la tela ha all'incirca la stessa densità dell'originale — 84
 * pixel per grado contro 86 — quindi colonne vicine sulla tela cadono su colonne vicine
 * nell'originale, e la scansione procede in modo ordinato. Tenere un blocco di sessantaquattro
 * per sedici pixel fa sì che quasi ogni lettura lo trovi già in mano: si attraversa il confine
 * una volta ogni sessanta pixel invece che a ognuno.
 *
 * Il blocco costa quattro chilobyte e appartiene a un filo solo, come il proiettore che gli
 * sta accanto. Quando la richiesta esce dal blocco se ne prende un altro, spostato indietro
 * di un margine perché la scansione va verso destra e conviene averne davanti.
 */
/**
 * La luminanza di un pixel tenuto in byte, letta come numero.
 *
 * Un byte in Kotlin è **con segno**: letto così com'è, un pixel chiaro diventa un numero
 * negativo e ogni confronto va a rovescio. La maschera lo riporta fra zero e
 * duecentocinquantacinque, che è quello che quel byte ha sempre voluto dire.
 */
private fun ByteArray.luminance(index: Int): Float = (this[index].toInt() and 0xFF).toFloat()

private class SourceBlock(private val bitmap: Bitmap) {
    private val width = bitmap.width
    private val height = bitmap.height
    private val usable = width >= BLOCK_WIDTH && height >= BLOCK_HEIGHT
    private val pixels = IntArray(BLOCK_WIDTH * BLOCK_HEIGHT)
    private val quad = IntArray(4)
    private var originX = -1
    private var originY = -1

    fun sample(x: Float, y: Float): Int {
        // Un pixel di margine dal bordo: il quadratino 2×2 dell'interpolazione ci sta sempre
        // dentro, e non servono casi particolari agli estremi.
        val x0 = x.toInt().coerceIn(0, width - 2)
        val y0 = y.toInt().coerceIn(0, height - 2)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        if (!usable) {
            // Un originale più piccolo del blocco: caso da nessuno, ma meglio funzionare.
            bitmap.getPixels(quad, 0, 2, x0, y0, 2, 2)
            return bilinearQuad(quad[0], quad[1], quad[2], quad[3], fx, fy)
        }
        ensureBlock(x0, y0)
        val index = (y0 - originY) * BLOCK_WIDTH + (x0 - originX)
        return bilinearQuad(
            pixels[index],
            pixels[index + 1],
            pixels[index + BLOCK_WIDTH],
            pixels[index + BLOCK_WIDTH + 1],
            fx,
            fy,
        )
    }

    /** Il blocco deve contenere anche il vicino a destra e quello sotto: li usa la bilineare. */
    private fun ensureBlock(x0: Int, y0: Int) {
        if (originX >= 0 &&
            x0 >= originX && x0 + 1 < originX + BLOCK_WIDTH &&
            y0 >= originY && y0 + 1 < originY + BLOCK_HEIGHT
        ) {
            return
        }
        originX = (x0 - BLOCK_LOOKBEHIND).coerceIn(0, width - BLOCK_WIDTH)
        originY = (y0 - BLOCK_HEIGHT / 2).coerceIn(0, height - BLOCK_HEIGHT)
        bitmap.getPixels(pixels, 0, BLOCK_WIDTH, originX, originY, BLOCK_WIDTH, BLOCK_HEIGHT)
    }

    private companion object {
        /** Largo quanto basta perché una riga di scansione ci cammini dentro a lungo. */
        const val BLOCK_WIDTH = 64

        /** Alto abbastanza da assorbire la curvatura della riga proiettata. */
        const val BLOCK_HEIGHT = 16

        /** Quanto blocco si tiene alle spalle: la scansione va avanti, non indietro. */
        const val BLOCK_LOOKBEHIND = 4
    }
}
