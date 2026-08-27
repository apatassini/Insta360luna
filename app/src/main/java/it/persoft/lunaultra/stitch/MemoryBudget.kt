package it.persoft.lunaultra.stitch

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/**
 * Quanta memoria c'è davvero, misurata invece che indovinata.
 *
 * Fin qui il tetto della tela era un numero inventato — «due volte la heap» — e un numero
 * inventato è sbagliato per definizione: su un telefono con dodici giga di RAM lascia sul
 * tavolo metà della risoluzione, su uno con quattro fa morire l'applicazione. Eppure Android
 * quei numeri li dice tutti, basta chiederli.
 *
 * Sono due mondi separati e vanno tenuti separati.
 *
 * **La heap Java** ha un tetto dichiarato ([javaMaxBytes], 512 MB con `largeHeap`) ed è dove
 * vivono i vettori di lavoro: luminanze, piramidi, pesi, griglie della fusione.
 *
 * **La memoria nativa** è dove vivono i Bitmap — la tela, le copie di lavoro, l'originale
 * aperto — e **non ha un tetto per applicazione**. Ha un limite vero, che è il telefono: quando
 * la memoria libera del sistema scende sotto [systemThresholdBytes] il sistema comincia a
 * chiudere applicazioni, e la prima che chiude è quella che ne sta chiedendo di più.
 *
 * Quindi la domanda giusta non è «quanto mi concede la heap» ma «quanta ne resta al telefono, e
 * quanto devo lasciargliene per non farmi chiudere».
 */
data class MemoryBudget(
    /** Il tetto della heap Java, e quanta se ne usa adesso. */
    val javaMaxBytes: Long,
    val javaUsedBytes: Long,
    /** Quanta memoria nativa abbiamo già preso: Bitmap compresi, perché è lì che vivono. */
    val nativeUsedBytes: Long,
    /** Quanta ne resta libera al sistema, e la soglia sotto cui comincia a chiudere. */
    val systemAvailableBytes: Long,
    val systemThresholdBytes: Long,
    /** Il sistema è già in affanno: qualcuno sta per essere chiuso, non aggiungiamoci noi. */
    val systemLow: Boolean,
    /** Vero se i numeri di sistema sono misurati; falso se sono un ripiego prudente. */
    val measured: Boolean,
) {

    /**
     * Quanto può pesare la tela, in byte.
     *
     * Si parte dalla memoria libera vera, si mette da parte la soglia del sistema più un
     * margine fisso, e di quello che resta se ne prende una parte — non tutta, perché fra
     * l'istante della misura e l'istante dell'allocazione il telefono continua a vivere: altre
     * app si svegliano, la fotocamera decodifica, il sistema mette in cache.
     *
     * Se il sistema è già in affanno si scende a una frazione molto più prudente: in quel
     * momento chiedere un gigabyte significa farsi chiudere, e una panoramica che non esce è
     * peggio di una panoramica un po' meno fitta.
     */
    val canvasBytes: Long
        get() {
            val reserve = systemThresholdBytes + SYSTEM_RESERVE_BYTES
            val free = (systemAvailableBytes - reserve).coerceAtLeast(0L)
            val share = if (systemLow) LOW_MEMORY_SHARE else NORMAL_SHARE
            return (free * share).toLong().coerceAtLeast(MINIMUM_CANVAS_BYTES)
        }

    /**
     * Quanti byte restano davvero liberi dopo che la tela è stata messa giù.
     *
     * Serve a decidere se ci si può permettere di tenere aperto **un originale in più** —
     * quello del prossimo fotogramma, aperto mentre si dipinge questo. Sono gli stessi
     * margini di [canvasBytes]: la soglia sotto cui il sistema chiude applicazioni, più la
     * riserva. Quando il telefono è già in affanno la risposta è zero, senza discutere.
     */
    fun spareBytes(canvasBytes: Long): Long = if (systemLow) {
        0L
    } else {
        (systemAvailableBytes - systemThresholdBytes - SYSTEM_RESERVE_BYTES - canvasBytes)
            .coerceAtLeast(0L)
    }

    /** La riga per il log: sono i numeri da cui si capisce perché la tela è grande così. */
    fun describe(): String = if (measured) {
        ("Memoria: heap %d/%d MB · nativa %d MB · sistema libero %d MB (soglia %d MB%s) → " +
            "tela fino a %d MB").format(
            javaUsedBytes / MB,
            javaMaxBytes / MB,
            nativeUsedBytes / MB,
            systemAvailableBytes / MB,
            systemThresholdBytes / MB,
            if (systemLow) ", in affanno" else "",
            canvasBytes / MB,
        )
    } else {
        "Memoria: heap %d/%d MB · nativa %d MB · sistema non misurabile, tela fino a %d MB (prudenziale)"
            .format(javaUsedBytes / MB, javaMaxBytes / MB, nativeUsedBytes / MB, canvasBytes / MB)
    }

    companion object {
        private const val MB = 1024L * 1024L

        /**
         * Il margine da lasciare al sistema oltre la sua soglia dichiarata.
         *
         * La soglia è il punto in cui il sistema *comincia* a chiudere: arrivarci esatti
         * vuol dire vincere per un pixel una gara che non conviene correre.
         */
        private const val SYSTEM_RESERVE_BYTES = 512L * MB

        /** Quanta parte del libero vero ci si può prendere, e quanta se il telefono è in affanno. */
        private const val NORMAL_SHARE = 0.70
        private const val LOW_MEMORY_SHARE = 0.25

        /** Sotto questa tela non ha senso nemmeno provarci: è una panoramica da niente. */
        private const val MINIMUM_CANVAS_BYTES = 128L * MB

        /** Quando il sistema non si può interrogare: la vecchia stima, dichiarata come tale. */
        private const val UNMEASURED_MULTIPLE = 2L

        /**
         * La misura vera. Vuole un [Context] perché la memoria del *sistema* la sa solo
         * `ActivityManager`: `Runtime` conosce soltanto la heap Java della propria macchina
         * virtuale, che è la parte piccola del problema.
         */
        fun measure(context: Context): MemoryBudget {
            val runtime = Runtime.getRuntime()
            val info = runCatching {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
            }.getOrNull() ?: return unmeasured()
            return MemoryBudget(
                javaMaxBytes = runtime.maxMemory(),
                javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
                nativeUsedBytes = Debug.getNativeHeapAllocatedSize(),
                systemAvailableBytes = info.availMem,
                systemThresholdBytes = info.threshold,
                systemLow = info.lowMemory,
                measured = true,
            )
        }

        /**
         * Il ripiego per chi non ha un [Context] — i test, e chiunque costruisca uno
         * stitcher a mano. Tiene la vecchia stima prudente e si dichiara non misurato, così
         * il log non fa credere che sia un dato.
         */
        fun unmeasured(): MemoryBudget {
            val runtime = Runtime.getRuntime()
            val max = runtime.maxMemory()
            return MemoryBudget(
                javaMaxBytes = max,
                javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
                nativeUsedBytes = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L),
                // Si finge che il sistema abbia libero quanto basta per la vecchia stima, e
                // niente di più: da qui esce esattamente «due volte la heap».
                systemAvailableBytes = (max * UNMEASURED_MULTIPLE / NORMAL_SHARE).toLong() + SYSTEM_RESERVE_BYTES,
                systemThresholdBytes = 0L,
                systemLow = false,
                measured = false,
            )
        }
    }
}
