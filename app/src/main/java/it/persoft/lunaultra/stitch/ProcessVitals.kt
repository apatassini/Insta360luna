package it.persoft.lunaultra.stitch

import android.os.Debug
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Quanto sta lavorando il telefono in questo istante, e quanta memoria ha in mano.
 *
 * Serve a chi aspetta. Un'unione dura minuti e fino a ieri diceva solo «Cucio Foto 4»: non si
 * capiva se stesse macinando o se si fosse impantanata. Questi tre numeri lo dicono a colpo
 * d'occhio — i core impegnati dicono che sta calcolando, la memoria dice quanto margine resta.
 */
data class StitchVitals(
    /** Core mediamente occupati dal processo: 5,8 su 8 vuol dire che sta usando la macchina. */
    val busyCores: Float,
    val totalCores: Int,
    /** Heap Java: i vettori di lavoro dell'allineamento e le griglie della fusione. */
    val heapUsedMb: Int,
    val heapMaxMb: Int,
    /** Memoria nativa: è dove vivono i Bitmap, cioè la tela e gli originali aperti. */
    val nativeMb: Int,
    /** Da quanto sta lavorando questa unione. */
    val elapsedMs: Long,
    /**
     * La frazione di tempo in cui la scheda grafica ha disegnato, da zero a uno.
     *
     * `null` quando non è misurabile: o la scheda non è in uso, o il driver non offre i
     * cronometri. Sono due cose diverse da «ferma», e mostrare uno zero al posto di un «non
     * lo so» è il modo più veloce per farsi credere.
     *
     * Non sono processori. Quanti ne abbia una scheda, e quanti ne stia usando, non lo dice
     * nessuna versione di OpenGL ES: si sa solo quanto tempo ha passato a disegnare.
     */
    val gpuBusyShare: Float?,
) {
    val heapFraction: Float get() = if (heapMaxMb > 0) heapUsedMb.toFloat() / heapMaxMb else 0f
}

/**
 * La sonda: legge i tempi di CPU del proprio processo e li trasforma in core occupati.
 *
 * I tempi stanno in `/proc/self/stat`, che un'app può leggere per sé senza permessi. I campi
 * utili sono il quattordicesimo e il quindicesimo — tempo in spazio utente e tempo in kernel —
 * contati in tick. La differenza fra due letture, divisa per il tempo trascorso, dice quanti
 * core sono stati tenuti occupati nel frattempo.
 *
 * Il nome del processo, nel secondo campo, sta fra parentesi e può contenere spazi: per questo
 * si taglia dopo l'ultima parentesi chiusa invece di dividere tutta la riga.
 */
class ProcessVitals(private val startedAtMs: Long = System.currentTimeMillis()) {

    private val ticksPerSecond: Long =
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrNull()?.takeIf { it > 0 } ?: DEFAULT_TICKS
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private var previousTicks = -1L
    private var previousAtMs = 0L
    private var previousGpuNanos = -1L
    private var previousGpuAtMs = 0L

    fun sample(): StitchVitals {
        val now = System.currentTimeMillis()
        val ticks = readProcessTicks()
        var busy = 0f
        if (ticks != null && previousTicks >= 0) {
            val elapsedSeconds = (now - previousAtMs) / 1000f
            if (elapsedSeconds > 0.05f) {
                busy = ((ticks - previousTicks).toFloat() / ticksPerSecond) / elapsedSeconds
            }
        }
        if (ticks != null) {
            previousTicks = ticks
            previousAtMs = now
        }

        // La scheda: stessa aritmetica della CPU, su un contatore che cresce da solo. Quanto
        // ha disegnato fra due campioni, diviso il tempo passato fra i due.
        var gpuShare: Float? = null
        val gpuNanos = GpuLoad.busy()
        if (gpuNanos != null) {
            if (previousGpuNanos >= 0) {
                val elapsedSeconds = (now - previousGpuAtMs) / 1000f
                if (elapsedSeconds > 0.05f) {
                    gpuShare = (((gpuNanos - previousGpuNanos) / 1_000_000_000f) / elapsedSeconds)
                        .coerceIn(0f, 1f)
                }
            }
            previousGpuNanos = gpuNanos
            previousGpuAtMs = now
        }

        val runtime = Runtime.getRuntime()
        return StitchVitals(
            busyCores = busy.coerceIn(0f, cores.toFloat()),
            totalCores = cores,
            heapUsedMb = ((runtime.totalMemory() - runtime.freeMemory()) / MEGABYTE).toInt(),
            heapMaxMb = (runtime.maxMemory() / MEGABYTE).toInt(),
            nativeMb = (Debug.getNativeHeapAllocatedSize() / MEGABYTE).toInt(),
            elapsedMs = now - startedAtMs,
            gpuBusyShare = gpuShare,
        )
    }

    private fun readProcessTicks(): Long? = runCatching {
        val stat = File("/proc/self/stat").readText()
        val afterName = stat.substring(stat.lastIndexOf(')') + 2)
        val fields = afterName.split(' ')
        // Dopo il nome il primo campo è il terzo della riga: utime e stime, che sono il
        // quattordicesimo e il quindicesimo, cadono quindi in undicesima e dodicesima posizione.
        fields[11].toLong() + fields[12].toLong()
    }.getOrNull()

    private companion object {
        const val MEGABYTE = 1024L * 1024L

        /** Il valore di praticamente ogni Linux, quando `sysconf` non risponde. */
        const val DEFAULT_TICKS = 100L
    }
}
