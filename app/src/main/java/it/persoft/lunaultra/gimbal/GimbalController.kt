package it.persoft.lunaultra.gimbal

import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.camera.PtzState
import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.net.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
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
    private val log: EventLog,
    private val scope: CoroutineScope,
) {

    private val _position = MutableStateFlow(PtzState())
    val position: StateFlow<PtzState> = _position

    private var jogJob: Job? = null

    private val _moving = MutableStateFlow(false)
    val moving: StateFlow<Boolean> = _moving

    /** Aggiorna la stima con un dato reale letto dalla camera. */
    fun onCameraPosition(state: PtzState) {
        _position.value = state
    }

    /** Azzera/forza la posizione stimata (usato quando la camera non espone il PTZ). */
    fun setEstimated(pan: Float, tilt: Float) {
        _position.value = _position.value.copy(
            pan = pan,
            tilt = tilt,
            fromCamera = false,
            lastUpdateMs = System.currentTimeMillis(),
        )
    }

    /** Movimento manuale continuo: direzioni in [-1, 1]. Va fermato con [stop]. */
    fun startJog(panDirection: Float, tiltDirection: Float) {
        jogJob?.cancel()
        _moving.value = true
        val rateHz = settings.value.gimbal.commandRateHz.coerceIn(1, 50)
        val periodMs = (1000L / rateHz).coerceAtLeast(20L)
        jogJob = scope.launch {
            val speedFraction = settings.value.gimbal.manualSpeedPercent.coerceIn(1, 100) / 100f
            while (isActive) {
                val panPercent = panDirection * speedFraction
                val tiltPercent = tiltDirection * speedFraction
                commands.gimbalVelocity(panPercent, tiltPercent)
                    .onFailure {
                        log.error("Movimento gimbal non riuscito: ${it.message}")
                        _moving.value = false
                        return@launch
                    }
                integrate(panPercent, tiltPercent, periodMs / 1000f)
                delay(periodMs)
            }
        }
    }

    /** Stop del movimento manuale e stop di emergenza. */
    suspend fun stop() {
        jogJob?.cancel()
        jogJob = null
        _moving.value = false
        commands.gimbalStop().onFailure { log.warn("Stop gimbal non confermato: ${it.message}") }
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
        val cfg = settings.value.gimbal
        val current = _position.value
        val dt = stepSeconds.coerceAtLeast(0.02f)
        val panSpeed = clampSpeed((targetPan - current.pan) / dt, cfg.maxPanSpeedDegPerSec)
        val tiltSpeed = clampSpeed((targetTilt - current.tilt) / dt, cfg.maxTiltSpeedDegPerSec)
        val panPercent = if (cfg.maxPanSpeedDegPerSec > 0f) panSpeed / cfg.maxPanSpeedDegPerSec else 0f
        val tiltPercent = if (cfg.maxTiltSpeedDegPerSec > 0f) tiltSpeed / cfg.maxTiltSpeedDegPerSec else 0f
        val result = commands.gimbalVelocity(panPercent, tiltPercent)
        if (result.isSuccess) integrate(panPercent, tiltPercent, dt)
        return result
    }

    private fun clampSpeed(requested: Float, max: Float): Float {
        if (max <= 0f) return 0f
        return if (abs(requested) > max) max * sign(requested) else requested
    }

    /** Dead reckoning: integra la velocità comandata nella posizione stimata. */
    private fun integrate(panPercent: Float, tiltPercent: Float, dtSeconds: Float) {
        val cfg = settings.value.gimbal
        val current = _position.value
        val pan = (current.pan + panPercent * cfg.maxPanSpeedDegPerSec * dtSeconds)
            .coerceIn(cfg.panMinDeg, cfg.panMaxDeg)
        val tilt = (current.tilt + tiltPercent * cfg.maxTiltSpeedDegPerSec * dtSeconds)
            .coerceIn(cfg.tiltMinDeg, cfg.tiltMaxDeg)
        // Il flag fromCamera resta a false: verrà rialzato dal prossimo dato reale della camera.
        _position.value = current.copy(
            pan = pan,
            tilt = tilt,
            fromCamera = false,
            lastUpdateMs = System.currentTimeMillis(),
        )
    }
}
