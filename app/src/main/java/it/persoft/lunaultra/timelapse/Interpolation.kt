package it.persoft.lunaultra.timelapse

/** Funzioni di interpolazione usate per il movimento fra waypoint. */
object Interpolation {

    /** smooth(t) = t² * (3 - 2t): derivata nulla agli estremi, quindi partenza e arresto morbidi. */
    fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    fun apply(mode: InterpolationMode, t: Float): Float = when (mode) {
        InterpolationMode.LINEAR -> t.coerceIn(0f, 1f)
        InterpolationMode.SMOOTH -> smoothStep(t)
    }

    fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t

    /**
     * Posizione lungo un tratto.
     * @param t avanzamento normalizzato del tratto, 0..1
     */
    fun position(start: Float, end: Float, t: Float, mode: InterpolationMode): Float =
        lerp(start, end, apply(mode, t))

    /**
     * Derivata normalizzata (velocità relativa) nel punto t: serve a modulare la velocità
     * quando si pilota il gimbal a velocità invece che a posizione assoluta.
     * Per SMOOTH: d/dt [t²(3-2t)] = 6t(1-t).
     */
    fun speedFactor(mode: InterpolationMode, t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (mode) {
            InterpolationMode.LINEAR -> 1f
            InterpolationMode.SMOOTH -> 6f * x * (1f - x)
        }
    }
}
