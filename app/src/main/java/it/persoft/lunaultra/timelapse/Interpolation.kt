package it.persoft.lunaultra.timelapse

/** Funzioni di interpolazione usate per il movimento fra waypoint. */
object Interpolation {

    /**
     * La punta di velocità della vecchia `smoothStep`, che non era scegliibile.
     *
     * `t²(3−2t)` ha derivata `6t(1−t)`: zero agli estremi e **1,5** al centro. Un numero
     * elegante ma non negoziabile — e su un movimento lungo si vede, perché a metà tratto la
     * camera va una volta e mezzo la velocità media.
     */
    const val PUNTA_SMOOTHSTEP = 1.5f

    /** Costante e uguale a sé stessa dall'inizio alla fine: nessuna punta, ma strappi ai due capi. */
    const val PUNTA_MINIMA = 1f

    /** Rampe che si toccano a metà: il profilo diventa un triangolo e la punta raddoppia. */
    const val PUNTA_MASSIMA = 2f

    /** smooth(t) = t² * (3 - 2t): derivata nulla agli estremi, quindi partenza e arresto morbidi. */
    fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * Quanta parte del tratto se ne va in accelerazione, data la punta voluta.
     *
     * Il profilo di velocità è un trapezio con gli spigoli smussati: sale da zero alla velocità
     * di crociera lungo una frazione `a` del tratto, resta costante, e scende specularmente. La
     * media dev'essere uno — il tratto va percorso tutto nel tempo dato — e siccome una rampa
     * smussata vale in media metà della crociera, viene `1 = V·(1−a)`, cioè `V = 1/(1−a)`.
     *
     * Quindi la punta e la rampa sono la stessa informazione detta in due modi, e conviene far
     * scegliere la punta: è il numero che si vede guardando il video.
     */
    fun rampaPerPunta(punta: Float): Float =
        1f - 1f / punta.coerceIn(PUNTA_MINIMA, PUNTA_MASSIMA)

    /**
     * Avanzamento normalizzato con partenza e arresto morbidi e punta scelta.
     *
     * Con punta 1 è la retta; con 1,5 ha la stessa punta della vecchia `smoothStep`; con 2 le
     * due rampe si toccano e non c'è più crociera.
     */
    fun morbida(t: Float, punta: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val a = rampaPerPunta(punta)
        if (a <= 0f) return x
        val velocita = 1f / (1f - a)
        if (x <= a) {
            // Integrale della rampa smussata: a·V·[(x/a)³ − (x/a)⁴/2].
            val u = x / a
            return velocita * a * (u * u * u - u * u * u * u / 2f)
        }
        if (x >= 1f - a) return 1f - morbida(1f - x, punta)
        return velocita * a / 2f + velocita * (x - a)
    }

    fun apply(mode: InterpolationMode, t: Float, punta: Float = PUNTA_SMOOTHSTEP): Float =
        when (mode) {
            InterpolationMode.LINEAR -> t.coerceIn(0f, 1f)
            InterpolationMode.SMOOTH -> morbida(t, punta)
        }

    fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t

    /**
     * Posizione lungo un tratto.
     * @param t avanzamento normalizzato del tratto, 0..1
     * @param punta quante volte la velocità media si arriva a toccare a metà tratto
     */
    fun position(
        start: Float,
        end: Float,
        t: Float,
        mode: InterpolationMode,
        punta: Float = PUNTA_SMOOTHSTEP,
    ): Float = lerp(start, end, apply(mode, t, punta))

    /**
     * Velocità relativa nel punto t: uno significa «la velocità media del tratto».
     *
     * Serve a modulare il comando quando si pilota il gimbal a velocità invece che a posizione,
     * e serve a mostrare a chi imposta il movimento quanto andrà veloce nel punto peggiore.
     */
    fun speedFactor(mode: InterpolationMode, t: Float, punta: Float = PUNTA_SMOOTHSTEP): Float {
        val x = t.coerceIn(0f, 1f)
        if (mode == InterpolationMode.LINEAR) return 1f
        val a = rampaPerPunta(punta)
        if (a <= 0f) return 1f
        val velocita = 1f / (1f - a)
        return when {
            x <= a -> velocita * smoothStep(x / a)
            x >= 1f - a -> velocita * smoothStep((1f - x) / a)
            else -> velocita
        }
    }
}
