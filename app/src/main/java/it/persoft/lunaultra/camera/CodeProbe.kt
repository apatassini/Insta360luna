package it.persoft.lunaultra.camera

import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.protocol.Hex
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.protocol.ProtoWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Scanner dei codici di comando, per trovare i numeri che l'estrazione pubblica non nomina —
 * in pratica: il comando del gimbal.
 *
 * **Il metodo previsto non funziona su questo firmware.** L'idea era usare `Error.ErrorCode`,
 * che distingue `UNKNOWN_MSG_CODE` (comando inesistente) da `UNKNOWN_MSG_PAYLOAD` (comando
 * esistente, argomenti sbagliati): un corpo vuoto che riceve "argomenti sbagliati" avrebbe
 * detto che il comando c'è e non ha eseguito nulla. Misurato sulla Luna Ultra 1.0.288, la
 * camera non manda messaggi `Error` affatto: risponde **200 con corpo vuoto** sia a un codice
 * inesistente, sia a un payload spazzatura, sia a un comando reale con argomenti mancanti.
 *
 * Resta però un segnale, ed è quello che questa versione usa: un codice che risponde **con
 * dati** a un corpo vuoto esiste ed è un *getter* — restituisce qualcosa senza bisogno di
 * argomenti. `PHONE_COMMAND_GET_PTZ_OPTION` è esattamente uno di questi, e trovarlo dà anche
 * il vicinato dove cercare gli altri comandi PTZ: nei protocolli Insta360 i codici affini
 * stanno vicini.
 *
 * [calibrate] misura come risponde un codice inesistente e verifica che almeno un segnale
 * distinguibile esista, invece di dare per scontato quale sia.
 */
class CodeProbe(
    private val session: CameraSession,
    private val log: EventLog,
) {

    /** Come ha risposto la camera a un codice. */
    sealed class Reply {
        object Silent : Reply()
        data class Empty(val echo: Boolean) : Reply()
        data class Error(val error: LunaError, val echo: Boolean) : Reply()
        data class Data(val bytes: Int, val hex: String, val echo: Boolean) : Reply()

        /**
         * Firma confrontabile della risposta. Descrive la FORMA, mai il numero del codice: la
         * camera rimanda indietro il codice richiesto, quindi includerlo renderebbe ogni
         * risposta unica e ogni confronto inutile.
         */
        val signature: String
            get() = when (this) {
                is Silent -> "silent"
                is Empty -> "empty/${echoTag(echo)}"
                is Error -> "error:${error.code}/${echoTag(echo)}"
                is Data -> "data/${echoTag(echo)}"
            }

        val describe: String
            get() = when (this) {
                is Silent -> "nessuna risposta"
                is Empty -> "risposta vuota"
                is Error -> error.toString()
                is Data -> "$bytes byte [$hex]"
            }

        private fun echoTag(echo: Boolean) = if (echo) "echo" else "altro-codice"
    }

    data class Hit(val code: Int, val reply: Reply) {
        val name: String? get() = LunaProtocolCodes.nameOf(code)

        /** Il comando esiste e vuole argomenti. Su firmware che mandano messaggi Error. */
        val existsAndTakesArguments: Boolean
            get() = (reply as? Reply.Error)?.error?.isBadPayload == true

        /**
         * Ha risposto con dati a un corpo vuoto: esiste ed è un getter. Sulla Luna Ultra è
         * l'unico segnale disponibile, ed è quello che porta a GET_PTZ_OPTION.
         */
        val answersWithData: Boolean get() = reply is Reply.Data

        /** Ordine di interesse per la lista dei risultati. */
        val rank: Int get() = when {
            answersWithData -> 2
            existsAndTakesArguments -> 1
            else -> 0
        }
    }

    data class Calibration(
        val absent: String,
        val badPayload: String,
        val emptyOnReal: String,
        val usable: Boolean,
        val reason: String,
    )

    /** Gamme di codici scansionabili. */
    enum class Range(val label: String, val from: Int, val to: Int, val note: String) {
        PHONE("Comandi telefono", 0, 152, "I buchi dentro il blocco già noto: qui atterrano le aggiunte dopo il 2020"),
        REQUEST("Blocco richieste", 4096, 8191, "PHONE_REQUEST_*: dichiarato e mai popolato, il posto più probabile per un pan/tilt interattivo"),
        HIGH("Intervallo alto", 153, 4095, "Tutto ciò che sta fra i due blocchi"),
        ;

        /** Solo i codici senza nome: quelli noti non hanno bisogno di essere scoperti. */
        fun codes(): List<Int> = (from..to).filter {
            LunaProtocolCodes.nameOf(it) == null && LunaProtocolCodes.isSweepable(it)
        }
    }

    private suspend fun probe(code: Int, body: ByteArray, timeoutMs: Long): Reply {
        val frame = session.requestRaw(code, body, timeoutMs).getOrNull() ?: return Reply.Silent
        // La camera risponde 200 a tutto: l'eco del codice non è un segnale, ma va registrata
        // perché su un firmware diverso potrebbe tornare a esserlo.
        val echo = frame.code == code
        if (frame.payload.isEmpty()) return Reply.Empty(echo)
        val declared = if (frame.code == LunaProtocolCodes.RESPONSE_ERROR) {
            LunaError.parse(frame.payload) ?: LunaError(LunaProtocolCodes.ErrorCode.UNKNOWN_ERROR, null)
        } else {
            LunaError.parse(frame.payload)
        }
        return if (declared != null) {
            Reply.Error(declared, echo)
        } else {
            Reply.Data(frame.payload.size, Hex.encode(frame.payload, limit = 24), echo)
        }
    }

    /**
     * Misura come risponde la camera nei casi noti, e stabilisce se una scansione può dire
     * qualcosa.
     *
     * Il requisito è volutamente più debole di "distinguere comando assente da argomenti
     * sbagliati", perché su questo firmware quella distinzione non esiste. Basta che la
     * risposta a un codice inesistente sia **stabile** e che almeno un caso noto risponda in
     * modo **diverso**: quel diverso è ciò che la scansione va a cercare.
     */
    suspend fun calibrate(timeoutMs: Long = 3_000): Calibration {
        log.info("Calibrazione: misuro come risponde la camera nei casi noti")

        val validBody = ProtoWriter().int32(1, LunaProtocolCodes.OptionType.CAMERA_TYPE).toByteArray()
        val known = probe(LunaProtocolCodes.GET_OPTIONS, validBody, timeoutMs)
        log.info("  GET_OPTIONS con corpo valido → ${known.describe}")
        delay(STEP_DELAY_MS)

        val junk = probe(LunaProtocolCodes.GET_OPTIONS, ByteArray(8) { 0xFF.toByte() }, timeoutMs)
        log.info("  GET_OPTIONS con corpo spazzatura → ${junk.describe}")
        delay(STEP_DELAY_MS)

        val emptyOnReal = probe(LunaProtocolCodes.GET_OPTIONS, ByteArray(0), timeoutMs)
        log.info("  GET_OPTIONS con corpo vuoto → ${emptyOnReal.describe}")
        delay(STEP_DELAY_MS)

        val absent1 = probe(ABSENT_PROBE_A, ByteArray(0), timeoutMs)
        log.info("  codice $ABSENT_PROBE_A (inesistente) → ${absent1.describe}")
        delay(STEP_DELAY_MS)

        val absent2 = probe(ABSENT_PROBE_B, ByteArray(0), timeoutMs)
        log.info("  codice $ABSENT_PROBE_B (inesistente) → ${absent2.describe}")

        val absent = absent1.signature
        val stable = absent == absent2.signature
        val knownDiffers = known.signature != absent

        val reason = when {
            !stable ->
                "Due codici inesistenti rispondono in modo diverso: la risposta non dipende dal " +
                    "codice, quindi non lo identifica. Scansione inutile."

            absent1 is Reply.Silent ->
                "La camera non risponde ai codici inesistenti. Silenzio e comando bloccato sono " +
                    "indistinguibili: la scansione produrrebbe solo timeout."

            !knownDiffers ->
                "Anche un comando reale con argomenti validi risponde come uno inesistente: non " +
                    "c'è nessun segnale su cui basare la ricerca."

            junk.signature == absent && emptyOnReal.signature == absent ->
                "Questa camera non manda messaggi Error: a un codice inesistente, a un payload " +
                    "sbagliato e a un comando reale senza argomenti risponde sempre \"$absent\". " +
                    "Resta un solo segnale utile: un codice che risponde CON DATI a un corpo " +
                    "vuoto esiste ed è un getter — ed è così che si trova GET_PTZ_OPTION."

            else ->
                "Un codice che risponde diversamente da \"$absent\" è un comando che questo " +
                    "firmware ha e l'estrazione non nomina."
        }

        val usable = stable && absent1 !is Reply.Silent && knownDiffers

        if (usable) log.info(reason) else log.warn(reason)

        return Calibration(
            absent = absent,
            badPayload = junk.signature,
            emptyOnReal = emptyOnReal.signature,
            usable = usable,
            reason = reason,
        )
    }

    /**
     * Sonda tutti i codici della gamma con un corpo VUOTO e restituisce quelli che rispondono
     * diversamente da un codice inesistente.
     *
     * I comandi distruttivi (cancellazione, riavvio, ripristino di fabbrica, Wi-Fi) e l'intero
     * blocco di fabbrica sono esclusi a monte: lì un corpo vuoto non protegge, perché un
     * comando senza argomenti si limita a eseguire.
     */
    suspend fun scan(
        range: Range,
        calibration: Calibration,
        timeoutMs: Long = 500,
        parallel: Int = 4,
        onProgress: (done: Int, total: Int, hits: Int) -> Unit = { _, _, _ -> },
    ): List<Hit> {
        require(calibration.usable) { "Scansione rifiutata: nessun segnale su cui basarsi" }
        val codes = range.codes()
        log.info("Scansione di ${codes.size} codici senza nome in ${range.label} (${range.from}-${range.to})")

        // Le sonde viaggiano a gruppi: la correlazione è sul requestId, quindi più richieste
        // possono stare in volo insieme. Su 4000 codici è la differenza fra un quarto d'ora e
        // un paio di minuti, ed è ciò che rende la scansione una cosa che si sta a guardare.
        val batch = parallel.coerceIn(1, 8)
        var done = 0
        val hits = mutableListOf<Hit>()

        // La sessione viene interrotta dal log dell'app, non dalla scansione: mentre sonda,
        // ogni comando e ogni risposta finirebbero nel log a migliaia di righe.
        session.quiet = true
        try {
            for (group in codes.chunked(batch)) {
                if (!currentCoroutineContext().isActive) break

                val replies = coroutineScope {
                    group.map { code -> async { code to probeStable(code, timeoutMs) } }.awaitAll()
                }

                for ((code, reply) in replies) {
                    if (reply.signature != calibration.absent) {
                        hits += Hit(code, reply)
                        log.info("  $code (0x${code.toString(16)}) → ${reply.describe}")
                    }
                }
                done += group.size
                onProgress(done, codes.size, hits.size)

                // Alcuni codici fanno chiudere la sessione alla camera. Fermarsi e dire quali
                // erano in volo vale più che riprovare: è un risultato, non un incidente.
                if (session.state.value != ConnectionState.CONNECTED) {
                    log.error(
                        "La camera ha chiuso la sessione durante la scansione. Ultimi codici " +
                            "provati: ${group.joinToString()}. Uno di questi non è innocuo: " +
                            "riconnetti e riparti da ${group.last() + 1} per saltarli."
                    )
                    break
                }
            }
        } finally {
            session.quiet = false
        }

        val takingArguments = hits.filter { it.existsAndTakesArguments }
        val getters = hits.filter { it.answersWithData }
        val silent = hits.count { it.reply is Reply.Silent }

        log.info("$done codici provati, ${hits.size} hanno risposto diversamente da uno inesistente")
        if (getters.isNotEmpty()) {
            log.info(
                "Rispondono con dati a un corpo vuoto (esistono, sono getter): " +
                    getters.joinToString { "${it.code} (${(it.reply as Reply.Data).bytes}B)" }
            )
        }
        if (takingArguments.isNotEmpty()) {
            log.info(
                "Rifiutano il corpo vuoto (esistono e vogliono argomenti): " +
                    takingArguments.joinToString { it.code.toString() }
            )
        }
        if (silent > 0) {
            log.info("$silent non hanno risposto affatto, nemmeno al secondo tentativo: da guardare con sospetto, un timeout ripetuto non è una prova")
        }
        return hits
    }

    /**
     * Sonda un codice, ripetendo una volta sola se non risponde.
     *
     * Su questa camera l'assenza di risposta è l'eccezione, non la regola: un solo timeout è
     * più spesso un pacchetto perso che un codice speciale, e senza la conferma la lista dei
     * risultati si riempie di rumore.
     */
    private suspend fun probeStable(code: Int, timeoutMs: Long): Reply {
        val first = probe(code, ByteArray(0), timeoutMs)
        if (first !is Reply.Silent) return first
        return probe(code, ByteArray(0), timeoutMs)
    }

    companion object {
        /**
         * Due codici scelti ben lontani da qualsiasi blocco documentato: servono a fotografare
         * come risponde la camera a un comando che sicuramente non ha.
         */
        const val ABSENT_PROBE_A = 3000
        const val ABSENT_PROBE_B = 3001

        private const val STEP_DELAY_MS = 200L
    }
}
