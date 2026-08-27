package it.persoft.lunaultra.stitch

/**
 * Il cronometro della scheda grafica, leggibile da fuori mentre la cucitura è in corso.
 *
 * `GpuStitchRenderer` il suo tempo lo tiene già, ma se lo tiene per sé: vive su un filo suo,
 * nasce e muore con la cucitura, e i suoi numeri si leggono solo alla fine, quando si scrive il
 * verdetto nel log. Chi guarda la barra di avanzamento non ha niente — e la domanda che si fa
 * mentre aspetta minuti non è «quanto ha lavorato in tutto», è «**adesso** sta lavorando?».
 *
 * Qui il contatore è uno solo per tutta l'applicazione, e va bene così: di cuciture ne va una
 * per volta, per il semplice motivo che una si prende la memoria del telefono. Il volatile basta
 * perché c'è un filo solo che scrive — quello della scheda — e uno solo che legge, quello della
 * sonda: nessuno dei due deve aspettare l'altro, e un campione letto un istante prima o dopo
 * cambia il misuratore di un pelo e niente di più.
 *
 * Quello che **non** si può sapere è quanti processori abbia la scheda e quanti ne stia usando:
 * nessuna versione di OpenGL ES lo dice. Si sa quanto tempo ha passato a disegnare, e quello si
 * mostra: la frazione di tempo in cui è stata occupata, non una conta di unità di calcolo.
 */
object GpuLoad {

    @Volatile
    private var busyNanos = 0L

    @Volatile
    private var measurable = false

    /** All'inizio di ogni cucitura: il contatore precedente non c'entra niente con questa. */
    fun reset() {
        busyNanos = 0L
        measurable = false
    }

    /** Lo scrive il filo della scheda, ogni volta che un cronometro dà il suo risultato. */
    fun report(nanos: Long) {
        busyNanos = nanos
        measurable = true
    }

    /** Nanosecondi di lavoro dichiarati finora, o `null` se il driver non ha i cronometri. */
    fun busy(): Long? = if (measurable) busyNanos else null
}
