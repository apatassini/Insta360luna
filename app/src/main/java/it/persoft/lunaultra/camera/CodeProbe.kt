package it.persoft.lunaultra.camera

import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.protocol.Hex
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.protocol.ProtoWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Scanner dei codici di comando, per trovare i numeri che l'estrazione pubblica non nomina —
 * in pratica: il comando del gimbal.
 *
 * Il metodo si regge su un dettaglio del protocollo: `Error.ErrorCode` distingue
 * `UNKNOWN_MSG_CODE` (comando inesistente) da `UNKNOWN_MSG_PAYLOAD` (comando esistente,
 * argomenti sbagliati). Inviando un corpo VUOTO a un codice sconosciuto, una risposta
 * "argomenti sbagliati" dice che il comando c'è **e non ha eseguito nulla**. È questo che
 * rende la scansione difendibile: un comando che rifiuta il payload non è mai partito.
 *
 * Prima di scansionare, [calibrate] verifica che l'oracolo funzioni davvero su questa camera.
 * Se un codice inesistente e uno esistente rispondono allo stesso modo, la scansione non
 * distingue nulla e viene rifiutata invece di produrre migliaia di righe senza significato.
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

        /** Il comando esiste e vuole argomenti: è il candidato più interessante. */
        val existsAndTakesArguments: Boolean
            get() = (reply as? Reply.Error)?.error?.isBadPayload == true
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
        val echo = frame.code == code
        if (frame.payload.isEmpty()) return Reply.Empty(echo)
        val error = LunaError.parse(frame.payload)
        return if (error != null) {
            Reply.Error(error, echo)
        } else {
            Reply.Data(frame.payload.size, Hex.encode(frame.payload, limit = 24), echo)
        }
    }

    /**
     * Stabilisce come rispondono un codice inesistente e un payload sbagliato, e si rifiuta di
     * benedire l'oracolo se i due casi non sono distinguibili.
     */
    suspend fun calibrate(timeoutMs: Long = 3_000): Calibration {
        log.info("Calibrazione dell'oracolo sui codici di errore")

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

        val reason = when {
            !stable ->
                "Due codici inesistenti rispondono in modo diverso: la risposta non dipende dal codice, quindi non lo identifica."

            absent1 is Reply.Silent ->
                "La camera non risponde ai codici inesistenti. Silenzio e comando bloccato sono indistinguibili: la scansione produrrebbe solo timeout."

            absent == junk.signature || absent == emptyOnReal.signature ->
                "Un codice inesistente risponde come uno esistente: niente separa \"comando assente\" da \"argomenti sbagliati\"."

            else ->
                "Oracolo utilizzabile: un codice che risponde diversamente da \"$absent\" è un comando che questo firmware ha e l'estrazione non nomina."
        }

        val usable = stable && absent1 !is Reply.Silent &&
            absent != junk.signature && absent != emptyOnReal.signature

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
        timeoutMs: Long = 700,
        gapMs: Long = 40,
        onProgress: (done: Int, total: Int, hits: Int) -> Unit = { _, _, _ -> },
    ): List<Hit> {
        require(calibration.usable) { "Scansione rifiutata: l'oracolo non è utilizzabile" }
        val codes = range.codes()
        log.info("Scansione di ${codes.size} codici senza nome in ${range.label} (${range.from}-${range.to})")

        val hits = mutableListOf<Hit>()
        for ((index, code) in codes.withIndex()) {
            if (!currentCoroutineContext().isActive) break
            val reply = probe(code, ByteArray(0), timeoutMs)
            if (reply.signature != calibration.absent) {
                hits += Hit(code, reply)
                log.info("  $code (0x${code.toString(16)}) → ${reply.describe}")
            }
            onProgress(index + 1, codes.size, hits.size)
            delay(gapMs)
        }

        val takingArguments = hits.filter { it.existsAndTakesArguments }
        log.info("${hits.size} codici su ${codes.size} hanno risposto diversamente da uno inesistente")
        if (takingArguments.isNotEmpty()) {
            log.info("Esistono e vogliono argomenti: ${takingArguments.joinToString { it.code.toString() }}")
        }
        return hits
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
