package it.persoft.lunaultra.stitch

import kotlinx.serialization.Serializable

/**
 * Una panoramica scattata e non ancora unita: il lavoro aspetta sul telefono.
 *
 * Unire venti scatti sono minuti di calcolo, e chi è in giro a fotografare non ha voglia di
 * stare a guardare una barra: gli scatti si scaricano subito — quello va fatto lì, finché la
 * camera è a portata di Wi-Fi — e l'unione si lancia quando si vuole, anche la sera, anche il
 * giorno dopo. Le foto aspettano in `DCIM › Luna Ultra › Panoramiche`, ognuna con il suo
 * passaporto negli EXIF, quindi il job sa rimetterle insieme senza indovinare niente.
 */
@Serializable
data class PanoJob(
    /** L'identità del panorama, la stessa scritta nei tag EXIF delle foto. */
    val id: String,
    val createdAtMs: Long,
    /** I percorsi assoluti degli scatti scaricati, in ordine. */
    val files: List<String>,
    val fovDegrees: Float,
    val spherical: Boolean = false,

    /**
     * Il punto di vista scelto guardando l'anteprima, se qualcuno l'ha scelto.
     *
     * Sta qui e non nella cucitura perche` sono due mestieri diversi in due momenti diversi.
     * Guardare e decidere si fa in un minuto, con le foto ridotte e il telefono in mano;
     * cucire sono minuti di calcolo che si lanciano quando si vuole — la sera, tutti i job
     * insieme, con il telefono in carica. Legarli l'uno all'altro obbligava a stare a guardare
     * una barra subito dopo aver deciso, che e` esattamente quello che i job servono a evitare.
     *
     * Campi sciolti e non un oggetto: cosi` un elenco salvato da una versione che non li aveva
     * si rilegge senza saltare per aria, e i valori mancanti valgono zero — che vuol dire «non
     * scelto», cioe` il comportamento di prima.
     */
    val viewPanDegrees: Float = 0f,
    val viewTiltDegrees: Float = 0f,
    val viewRollDegrees: Float = 0f,
    /** La proiezione voluta come ordinale, o -1 per lasciar decidere alla copertura. */
    val viewProjectionCode: Int = -1,
    val viewVerticalLimitDegrees: Float = 0f,
    /**
     * Vero quando gli scatti sono copie di foto che stanno già sul telefono.
     *
     * Cambia una cosa sola, ed è importante: **l'originale esiste altrove**. Per una
     * panoramica scaricata dalla camera questi file sono l'unica copia locale e buttarli
     * significa perdere gli scatti; qui sono un doppione della galleria, e tenerli dopo che la
     * panoramica è fatta vuol dire solo occupare due volte lo stesso spazio.
     */
    val fromPhone: Boolean = false,
    /**
     * Vero quando i file di questo lavoro sono nostri, e possiamo buttarli.
     *
     * Lo sono gli scatti scaricati dalla camera e le copie fatte quando una foto non era
     * leggibile dov'era. **Non** lo sono le foto della galleria lette dove stanno: quelle sono
     * dell'utente, il lavoro le legge e basta, e nessuna impostazione le deve poter cancellare.
     */
    val filesAreOurs: Boolean = true,
    /** Il rettangolo del ritaglio, in frazioni della tela. Zero-zero-uno-uno = tela intera. */
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    /** Vero solo quando qualcuno ha davvero guardato e salvato: zero non basta a dirlo. */
    val viewChosen: Boolean = false,
) {
    /** Il punto di vista salvato, o null se per questo job nessuno l'ha ancora scelto. */
    val view: PanoramaView?
        get() = if (!viewChosen) {
            null
        } else {
            PanoramaView(
                panDegrees = viewPanDegrees,
                tiltDegrees = viewTiltDegrees,
                rollDegrees = viewRollDegrees,
                projection = StitchProjection.entries.getOrNull(viewProjectionCode),
                verticalLimitDegrees = viewVerticalLimitDegrees,
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropRight = cropRight,
                cropBottom = cropBottom,
            )
        }

    /** Lo stesso job, con il punto di vista appena scelto. */
    fun withView(view: PanoramaView): PanoJob = copy(
        viewPanDegrees = view.panDegrees,
        viewTiltDegrees = view.tiltDegrees,
        viewRollDegrees = view.rollDegrees,
        viewProjectionCode = view.projection?.ordinal ?: -1,
        viewVerticalLimitDegrees = view.verticalLimitDegrees,
        cropLeft = view.cropLeft,
        cropTop = view.cropTop,
        cropRight = view.cropRight,
        cropBottom = view.cropBottom,
        viewChosen = true,
    )
}

/** L'elenco persistente dei job: sopravvive alla chiusura dell'app, che è il suo mestiere. */
@Serializable
data class PanoJobList(
    val jobs: List<PanoJob> = emptyList(),
)
