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

    /** Stimare guadagni e vignettatura dai punti di controllo (spento: catena dei guadagni). */
    val photometric: Boolean = true,

    /** La sfumatura multibanda sulle giunzioni; spenta, il montaggio resta a taglio netto. */
    val multiband: Boolean = true,
)

/** Una ricetta della modalità test: lettera per il nome del file, titolo per il log. */
data class StitchVariant(val letter: String, val title: String, val tuning: StitchTuning)

/**
 * Le ricette che la modalità test prova in fila, dalla più antica alla diagnostica.
 *
 * Tutte lavorano a [TEST_WORKING_LONG_SIDE] px e campionano dalla copia di lavoro: piccole
 * e veloci apposta, per poterle confrontare in galleria una accanto all'altra. La «storica»
 * è la ricetta delle versioni che univano bene: niente rollio/focale, niente fotometria,
 * solo piramide, punti di controllo e multibanda. Da lì in poi si riaccende una cosa alla
 * volta, così la ricetta che rompe si vede da quale lettera in poi compare il difetto.
 */
object StitchTestLab {
    const val TEST_WORKING_LONG_SIDE = 1024
    const val TEST_FRAMES = 3

    fun variants(base: StitchTuning): List<StitchVariant> {
        fun test(tuning: StitchTuning) = tuning.copy(
            workingLongSide = TEST_WORKING_LONG_SIDE,
            sampleFromOriginals = false,
        )
        return listOf(
            StitchVariant(
                "A", "Storica: solo piramide e punti, multibanda",
                test(base.copy(rollFocal = false, photometric = false, multiband = true)),
            ),
            StitchVariant(
                "B", "Storica + fotometria (guadagni e vignettatura)",
                test(base.copy(rollFocal = false, photometric = true, multiband = true)),
            ),
            StitchVariant(
                "C", "Rollio e focale, senza fotometria",
                test(base.copy(rollFocal = true, photometric = false, multiband = true)),
            ),
            StitchVariant(
                "D", "Completa: rollio/focale + fotometria (ricetta attuale)",
                test(base.copy(rollFocal = true, photometric = true, multiband = true)),
            ),
            StitchVariant(
                "E", "Completa con punti severi: qualità 95%, quantità doppia",
                test(base.copy(rollFocal = true, photometric = true, multiband = true, keepNcc = 0.95f, candidateScale = 2f)),
            ),
            StitchVariant(
                "F", "Taglio netto: come D ma senza sfumatura — mostra dove cadono le giunzioni",
                test(base.copy(rollFocal = true, photometric = true, multiband = false)),
            ),
        )
    }
}
