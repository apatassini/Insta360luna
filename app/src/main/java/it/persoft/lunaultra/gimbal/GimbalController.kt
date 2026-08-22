package it.persoft.lunaultra.gimbal

import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.camera.PtzState
import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.data.GimbalCalibrationProfile
import it.persoft.lunaultra.net.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sign

/**
 * Pilota il gimbal e mantiene una stima della posizione corrente.
 *
 * Sulla Luna Ultra la posizione reale non è leggibile: la notifica PTZ è identificata solo per
 * numero, non per contenuto. La stima usa quindi dead reckoning, integrando le velocità
 * comandate — sufficiente a memorizzare i waypoint e a ripercorrerli in modo ripetibile.
 * Se un giorno la notifica verrà decodificata, [onCameraPosition] la sostituisce al dato stimato.
 */
class GimbalController(
    private val commands: LunaCommands,
    private val settings: StateFlow<AppSettings>,
    private val calibration: StateFlow<GimbalCalibrationProfile>,
    private val log: EventLog,
    private val scope: CoroutineScope,
) {

    private val _position = MutableStateFlow(PtzState())
    val position: StateFlow<PtzState> = _position

    private var jogJob: Job? = null

    /**
     * Direzione richiesta dal comando manuale, letta a ogni tick dal ciclo di invio.
     *
     * È volatile perché la scrive il thread della UI (il dito sulla levetta) e la legge la
     * coroutine che parla con la camera. Tenerla fuori dal ciclo è ciò che permette di cambiare
     * direzione senza interromperlo: una levetta analogica cambia valore a ogni frazione di
     * secondo, e riavviare il ciclo ogni volta manderebbe alla camera raffiche di avvii.
     */
    @Volatile
    private var jogPan = 0f

    @Volatile
    private var jogTilt = 0f

    private val _moving = MutableStateFlow(false)
    val moving: StateFlow<Boolean> = _moving

    /** Ultimo vettore realmente consegnato alla camera, per integrare il tempo reale. */
    private var appliedPanPercent = 0f
    private var appliedTiltPercent = 0f
    private var appliedSinceNanos = 0L

    /** Aggiorna la stima con un dato reale letto dalla camera. */
    fun onCameraPosition(state: PtzState) {
        _position.value = state
        appliedSinceNanos = System.nanoTime()
    }

    /** Azzera/forza la posizione stimata (usato quando la camera non espone il PTZ). */
    fun setEstimated(pan: Float, tilt: Float) {
        integrateAppliedUntilNow()
        _position.value = _position.value.copy(
            pan = pan,
            tilt = tilt,
            fromCamera = false,
            lastUpdateMs = System.currentTimeMillis(),
        )
        appliedSinceNanos = System.nanoTime()
    }

    /** Movimento manuale continuo: direzioni in [-1, 1]. Va fermato con [stop]. */
    fun startJog(panDirection: Float, tiltDirection: Float) = setJog(panDirection, tiltDirection)

    /**
     * Cambia la direzione del movimento manuale, avviando il ciclo di comandi se è fermo.
     *
     * Le direzioni sono in [-1, 1] su entrambi gli assi e valgono insieme: la levetta analogica
     * manda una diagonale, la croce direzionale un asse solo. Va fermato con [stop].
     */
    fun setJog(panDirection: Float, tiltDirection: Float) {
        jogPan = panDirection.coerceIn(-1f, 1f)
        jogTilt = tiltDirection.coerceIn(-1f, 1f)
        _moving.value = jogPan != 0f || jogTilt != 0f
        if (jogJob?.isActive != true) startJogLoop()
    }

    private fun startJogLoop() {
        jogJob?.cancel()
        jogJob = scope.launch {
            while (isActive) {
                // Velocità e cadenza si rileggono a ogni giro: il cursore della velocità deve
                // avere effetto mentre il gimbal si muove, non al movimento successivo.
                val cfg = settings.value.gimbal
                val rateHz = cfg.commandRateHz.coerceIn(1, 50)
                val periodMs = (1000L / rateHz).coerceAtLeast(20L)
                val speedFraction = cfg.manualSpeedPercent.coerceIn(1, 100) / 100f
                val panPercent = jogPan * speedFraction
                val tiltPercent = jogTilt * speedFraction
                sendVelocity(panPercent, tiltPercent)
                    .onFailure {
                        log.error("Movimento gimbal non riuscito: ${it.message}")
                        _moving.value = false
                        return@launch
                    }
                delay(periodMs)
            }
        }
    }

    /**
     * Stop del movimento manuale e stop di emergenza.
     *
     * Insta360Linker mantiene quattro vettori nulli dopo il rilascio: ripeterli evita che un
     * singolo pacchetto perso lasci il gimbal in movimento.
     */
    suspend fun stop() {
        jogPan = 0f
        jogTilt = 0f
        jogJob?.cancel()
        jogJob = null
        _moving.value = false
        integrateAppliedUntilNow()
        repeat(STOP_VECTOR_REPETITIONS) { index ->
            commands.gimbalStop().onFailure {
                log.warn("Stop gimbal non inviato: ${it.message}")
                return
            }
            if (index == 0) {
                appliedPanPercent = 0f
                appliedTiltPercent = 0f
                appliedSinceNanos = System.nanoTime()
            }
            if (index < STOP_VECTOR_REPETITIONS - 1) delay(STOP_VECTOR_INTERVAL_MS)
        }
    }

    /**
     * Porta il gimbal verso [targetPan]/[targetTilt] nell'arco di [stepSeconds], calcolando la
     * velocità necessaria a coprire l'errore residuo nel tempo del tick.
     *
     * Il movimento è solo a velocità: il comando di posizione assoluta
     * (`PHONE_COMMAND_SET_PTZ_OPTION`) esiste per nome ma non se ne conosce il numero, quindi
     * non c'è modo di inviarlo.
     */
    suspend fun driveTo(targetPan: Float, targetTilt: Float, stepSeconds: Float): Result<Unit> {
        integrateAppliedUntilNow()
        val speeds = effectiveSpeeds()
        val current = _position.value
        val dt = stepSeconds.coerceAtLeast(0.02f)
        val panSpeed = clampSpeed((targetPan - current.pan) / dt, speeds.pan)
        val tiltSpeed = clampSpeed((targetTilt - current.tilt) / dt, speeds.tilt)
        val panPercent = if (speeds.pan > 0f) panSpeed / speeds.pan else 0f
        val tiltPercent = if (speeds.tilt > 0f) tiltSpeed / speeds.tilt else 0f
        return sendVelocity(panPercent, tiltPercent)
    }

    /** Tempo minimo stimato per raggiungere un punto, considerando entrambi gli assi. */
    fun estimatedTravelSeconds(targetPan: Float, targetTilt: Float): Float {
        integrateAppliedUntilNow()
        val speeds = effectiveSpeeds()
        val current = _position.value
        val panSeconds = if (speeds.pan > 0f) {
            abs(targetPan - current.pan) / speeds.pan
        } else 0f
        val tiltSeconds = if (speeds.tilt > 0f) {
            abs(targetTilt - current.tilt) / speeds.tilt
        } else 0f
        return max(panSeconds, tiltSeconds)
    }

    /**
     * Raggiunge una posizione senza il vecchio limite fisso di due/tre secondi.
     *
     * La durata cresce con la distanza: era il limite fisso a lasciare il punto iniziale
     * spostato verso destra quando il ritorno dal punto 2 richiedeva più di circa 60°.
     */
    suspend fun moveToPosition(
        targetPan: Float,
        targetTilt: Float,
        minimumSeconds: Float = 0f,
        tickHz: Int = POSITION_TICK_HZ,
    ): Result<Unit> {
        stop()
        val travel = max(minimumSeconds, estimatedTravelSeconds(targetPan, targetTilt))
        val duration = travel * POSITION_TIME_MARGIN + POSITION_SETTLE_MARGIN_SECONDS
        val rate = tickHz.coerceIn(1, 50)
        val steps = ceil(duration * rate).toInt().coerceIn(1, MAX_POSITION_STEPS)
        val stepSeconds = duration / steps
        repeat(steps) {
            val result = driveTo(targetPan, targetTilt, stepSeconds)
            if (result.isFailure) {
                stop()
                return result
            }
            delay((stepSeconds * 1000f).toLong().coerceAtLeast(20L))
        }
        stop()
        return Result.success(Unit)
    }

    /**
     * Piccolo impulso a velocità controllata usato dal riallineamento fotografico.
     *
     * Non passa dalla velocità della levetta manuale: la correzione deve essere ripetibile e
     * abbastanza lenta da poter essere misurata nel fotogramma successivo. Ogni impulso parte
     * e termina con vettori nulli, evitando che una correzione resti attiva oltre il previsto.
     */
    suspend fun correctionPulse(
        panPercent: Float,
        tiltPercent: Float,
        durationMs: Long,
    ): Result<Unit> {
        stop()
        val duration = durationMs.coerceIn(60L, 600L)
        val periodMs = (1000L / settings.value.gimbal.commandRateHz.coerceIn(1, 50)).coerceAtLeast(20L)
        val startedAt = System.nanoTime()
        do {
            val sent = sendVelocity(
                panPercent.coerceIn(-VISUAL_MAX_SPEED, VISUAL_MAX_SPEED),
                tiltPercent.coerceIn(-VISUAL_MAX_SPEED, VISUAL_MAX_SPEED),
            )
            if (sent.isFailure) {
                stop()
                return sent
            }
            delay(periodMs)
        } while ((System.nanoTime() - startedAt) / 1_000_000L < duration)
        stop()
        return Result.success(Unit)
    }

    private suspend fun sendVelocity(panPercent: Float, tiltPercent: Float): Result<Unit> {
        integrateAppliedUntilNow()
        val result = commands.gimbalVelocity(panPercent, tiltPercent)
        if (result.isSuccess) {
            appliedPanPercent = panPercent
            appliedTiltPercent = tiltPercent
            appliedSinceNanos = System.nanoTime()
        }
        return result
    }

    private fun clampSpeed(requested: Float, max: Float): Float {
        if (max <= 0f) return 0f
        return if (abs(requested) > max) max * sign(requested) else requested
    }

    /** Dead reckoning: integra la velocità comandata nella posizione stimata. */
    private fun integrate(panPercent: Float, tiltPercent: Float, dtSeconds: Float) {
        val cfg = settings.value.gimbal
        val speeds = effectiveSpeeds()
        val current = _position.value
        val pan = (current.pan + panPercent * speeds.pan * dtSeconds)
            .coerceIn(cfg.panMinDeg, cfg.panMaxDeg)
        val tilt = (current.tilt + tiltPercent * speeds.tilt * dtSeconds)
            .coerceIn(cfg.tiltMinDeg, cfg.tiltMaxDeg)
        // Il flag fromCamera resta a false: verrà rialzato dal prossimo dato reale della camera.
        _position.value = current.copy(
            pan = pan,
            tilt = tilt,
            fromCamera = false,
            lastUpdateMs = System.currentTimeMillis(),
        )
    }

    /**
     * Integra quanto il comando precedente è rimasto davvero attivo, non il periodo teorico.
     * Latenza TCP e scheduler altrimenti si accumulavano e spostavano i waypoint.
     */
    private fun integrateAppliedUntilNow(nowNanos: Long = System.nanoTime()) {
        val since = appliedSinceNanos
        if (since == 0L) {
            appliedSinceNanos = nowNanos
            return
        }
        val elapsed = ((nowNanos - since) / 1_000_000_000.0).toFloat().coerceAtLeast(0f)
        if (elapsed > 0f && (appliedPanPercent != 0f || appliedTiltPercent != 0f)) {
            integrate(appliedPanPercent, appliedTiltPercent, elapsed)
        }
        appliedSinceNanos = nowNanos
    }

    /**
     * I valori in gradi/s restano il riferimento del livello Veloce; la calibrazione misura
     * quanto Lenta e Media rispondono davvero rispetto a quello e corregge tutta l'integrazione.
     */
    private fun effectiveSpeeds(): EffectiveSpeeds {
        val cfg = settings.value.gimbal
        val measured = calibration.value.takeIf(GimbalCalibrationProfile::isValid)
            ?.level(cfg.hardwareSpeedLevel)
        return EffectiveSpeeds(
            pan = cfg.maxPanSpeedDegPerSec * (measured?.panSpeedScale ?: 1f),
            tilt = cfg.maxTiltSpeedDegPerSec * (measured?.tiltSpeedScale ?: 1f),
        )
    }

    private data class EffectiveSpeeds(val pan: Float, val tilt: Float)

    companion object {
        const val STOP_VECTOR_REPETITIONS = 4
        const val STOP_VECTOR_INTERVAL_MS = 25L
        private const val POSITION_TICK_HZ = 10
        private const val POSITION_TIME_MARGIN = 1.15f
        private const val POSITION_SETTLE_MARGIN_SECONDS = 0.35f
        private const val MAX_POSITION_STEPS = 3_600
        private const val VISUAL_MAX_SPEED = 0.35f
    }
}
