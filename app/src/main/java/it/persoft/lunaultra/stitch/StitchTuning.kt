package it.persoft.lunaultra.stitch

/**
 * La ricetta dell'unione: le manopole che decidono COME si cuce, separate dal codice che cuce.
 *
 * Esiste per due ragioni. La prima: qualità e quantità dei punti di controllo sono opinioni
 * legittime, e chi scatta le regola dalle impostazioni senza ricompilare. La seconda: la
 * modalità test prova più ricette in fila sulla stessa terna di foto, e ogni prova è solo
 * una [StitchTuning] diversa — il codice dell'unione resta uno.
 */
data class StitchTuning(
    /** Lato lungo di lavoro imposto; null lascia decidere alla memoria del telefono. */
    val workingLongSide: Int? = null,

    /**
     * Cucire campionando dagli originali a piena risoluzione. Spento, si campiona dalla
     * copia di lavoro: è la strada delle prime versioni, e della modalità test — dove la
     * tela deve restare piccola apposta.
     */
    val sampleFromOriginals: Boolean = true,

    /** La soglia di qualità dei punti di controllo: sotto, un punto è un'opinione. */
    val keepNcc: Float = 0.80f,

    /** Moltiplicatore della quantità di punti di controllo cercati (1 = come sempre). */
    val candidateScale: Float = 1f,

    /** Stimare anche rollio e scala focale dal bundle adjustment, oltre allo spostamento. */
    val rollFocal: Boolean = true,

    /**
     * Quanto la focale stimata può allontanarsi da quella dichiarata dalla specifica.
     *
     * I 20 mm equivalenti da cui nasce il campo visivo sono un numero di catalogo, non una
     * misura: se la focale vera è più lunga del 10%, le foto combaciano al centro e
     * divergono ai bordi — ed è esattamente il difetto che si vede. Fin qui il margine era
     * il 4%, e oltre quello *l'intera* correzione veniva buttata via.
     */
    val focalFreedom: Float = 0.20f,

    /** Stimare guadagni e vignettatura dai punti di controllo (spento: catena dei guadagni). */
    val photometric: Boolean = true,

    /** La sfumatura multibanda sulle giunzioni; spenta, il montaggio resta a taglio netto. */
    val multiband: Boolean = true,

    /**
     * Dove passa la giunzione: sul minimo disaccordo, non sulla mediana geometrica.
     *
     * È la differenza fra tagliare a metà strada — dove capita, anche in mezzo a una tenda —
     * e cercare il percorso lungo il quale le due foto già si assomigliano. La parallasse
     * non si annulla con nessuna rotazione, ma un taglio che passa dove le due immagini
     * concordano non si vede: è il principio di enblend, ed è quello che manca quando il
     * bambù dietro sembra continuare sopra la tenda davanti.
     */
    val seamMinimalDifference: Boolean = true,

    /**
     * La deformazione locale che assorbe quello che la rotazione non può assorbire.
     *
     * Il gimbal non ruota attorno al centro ottico dell'obiettivo: fra un'inquadratura e
     * l'altra la camera *trasla* di qualche centimetro, e gli oggetti vicini si spostano
     * più di quelli lontani. Nessuna rotazione rimette d'accordo due profondità insieme.
     * Dai punti di controllo che restano fuori posto dopo l'allineamento globale si ricava
     * un campo di spostamento morbido, e si applica al campionamento: è il «deghosting»
     * locale di Autopano.
     */
    val localWarp: Boolean = true,

    /**
     * Quanto forte deforma: 1 leggera, 2 media, 3 forte.
     *
     * Non è un capriccio di intensità: decide quanto **locale** è il campo. Forte vuol dire
     * campana stretta e limite alto, cioè la capacità di rappresentare un allargamento
     * progressivo — le stecche di bambù che vanno allargate man mano che si va verso il
     * bordo, perché di lato erano viste in scorcio e nell'altra foto sono frontali. Leggera
     * media tutto e lascia quel lavoro a metà.
     */
    val warpStrength: Int = 2,

    /**
     * La proiezione della tela finita. La sferica ignora questa scelta e resta
     * equirettangolare: è l'unica che arriva ai poli e l'unica che un visualizzatore 360°
     * sa leggere.
     */
    val projection: StitchProjection = StitchProjection.CYLINDRICAL,

    /**
     * Cercare l'orizzonte nelle foto per sapere come era inclinata la camera.
     *
     * Serve solo quando gli angoli veri non ci sono — le foto scelte a mano — perché lì si
     * dà per scontato che la camera fosse in bolla. Se invece guardava in su di dodici
     * gradi, l'orizzonte (che deve essere una riga dritta) esce come una successione di
     * archi e il mare sembra una conca, mentre il molo vicino (che dovrebbe incurvarsi)
     * resta dritto. Misurato una volta, il difetto sparisce.
     */
    val levelHorizon: Boolean = false,

    /**
     * L'inclinazione della camera imposta a mano, in gradi (positiva = guardava in su).
     * Zero significa «misurala dall'orizzonte».
     */
    val cameraPitchDegrees: Float = 0f,
)

/** Una ricetta della modalità test: lettera per il nome del file, titolo per il log. */
data class StitchVariant(val letter: String, val title: String, val tuning: StitchTuning)

/**
 * Le ricette che la modalità test prova in fila, ognuna diversa dalla precedente per **una
 * cosa sola**.
 *
 * La prima scala di prove faceva variare fotometria, rollio e sfumatura, e le sei uscite si
 * somigliavano tutte: la risposta giusta a una domanda sbagliata. Se le ricette differiscono
 * e il risultato no, il colpevole sta in quello che **condividono** — la geometria e il punto
 * in cui cade il taglio. Questa scala fa variare quelli.
 *
 * Tutte lavorano a [TEST_WORKING_LONG_SIDE] px e campionano dalla copia di lavoro: piccole e
 * veloci apposta, per confrontarle in galleria una accanto all'altra.
 */
object StitchTestLab {
    const val TEST_WORKING_LONG_SIDE = 1024
    const val TEST_FRAMES = 3

    fun variants(base: StitchTuning): List<StitchVariant> {
        fun test(tuning: StitchTuning) = tuning.copy(
            workingLongSide = TEST_WORKING_LONG_SIDE,
            sampleFromOriginals = false,
        )
        // Il punto di partenza: com'era prima di questa tornata — taglio sulla mediana
        // geometrica, nessuna deformazione locale, focale quasi bloccata sulla specifica.
        val old = base.copy(
            seamMinimalDifference = false,
            localWarp = false,
            focalFreedom = 0.04f,
            levelHorizon = false,
        )
        // Ogni gradino accende **una** cosa in più del precedente, così la lettera in cui il
        // difetto sparisce dice da sola chi era il colpevole.
        val levelled = old.copy(levelHorizon = true)
        val modern = levelled.copy(seamMinimalDifference = true, focalFreedom = 0.20f)
        // Con la deformazione servono punti fitti: un campo stretto senza punti che lo
        // reggano resta fermo, e la prova direbbe «non serve a niente» per il motivo sbagliato.
        val dense = modern.copy(localWarp = true, candidateScale = 2f)
        return listOf(
            StitchVariant(
                "A", "Com'era: camera assunta in bolla, taglio a metà strada, nessuna deformazione",
                test(old),
            ),
            StitchVariant(
                "B", "Livella l'orizzonte: l'inclinazione vera della camera, misurata",
                test(levelled),
            ),
            StitchVariant(
                "C", "Taglio sul minimo disaccordo + focale libera",
                test(modern),
            ),
            StitchVariant(
                "D", "Deformazione locale media",
                test(dense.copy(warpStrength = 2)),
            ),
            StitchVariant(
                "E", "Deformazione locale forte",
                test(dense.copy(warpStrength = 3)),
            ),
            StitchVariant(
                "F", "Come E a taglio netto: mostra nuda la giunzione",
                test(dense.copy(warpStrength = 3, multiband = false)),
            ),
        )
    }
}
