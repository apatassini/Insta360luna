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
import kotlinx.coroutines.yield

/**
 * Scanner dei codici di comando, per trovare i numeri che l'estrazione pubblica non nomina —
 * in pratica: il comando del gimbal.
 *
 * Due segnali, misurati sulla Luna Ultra 1.0.288.
 *
 * Il primo è `Error.ErrorCode`, che distingue `UNKNOWN_MSG_CODE` (comando inesistente) da
 * `UNKNOWN_MSG_PAYLOAD` (comando esistente, argomenti sbagliati). Non funziona ovunque: sui
 * codici di prova attorno a 3000 la camera risponde 200 con corpo vuoto in entrambi i casi.
 * Ma nella zona 153-250 i messaggi `Error` arrivano davvero, e lì la distinzione vale — un
 * comando che rifiuta il corpo vuoto esiste **e non ha eseguito nulla**.
 *
 * Il secondo vale ovunque: un codice che risponde **con dati** a un corpo vuoto esiste ed è un
 * *getter*. Trovarlo dà anche il vicinato dove cercare i comandi affini, che nei protocolli
 * Insta360 stanno vicini fra loro.
 *
 * [calibrate] misura come risponde un codice inesistente e verifica che almeno un segnale
 * distinguibile esista, invece di dare per scontato quale sia. [shape] è il passo successivo,
 * e a differenza della scansione non è innocuo.
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
                "Con questi codici di prova la camera risponde sempre \"$absent\", quindi qui " +
                    "non distingue un comando assente da argomenti sbagliati. Non vuol dire che " +
                    "non mandi mai messaggi Error: in altre zone dei codici lo fa, e la " +
                    "scansione li mostra. Il segnale che cerca comunque è un codice che " +
                    "risponde CON DATI a un corpo vuoto — esiste ed è un getter."

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
        var silentStreak = 0
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

                // Il silenzio consecutivo è il segnale che la sessione è morta, e arriva prima
                // di qualunque cambio di stato: mentre la scansione corre, la coroutine che
                // osserva la connessione non viene eseguita, e il primo tentativo di fermarsi
                // sullo stato si accorgeva della caduta solo a scansione finita — dopo aver
                // registrato migliaia di finti risultati.
                if (replies.all { it.second is Reply.Silent }) {
                    silentStreak += group.size
                } else {
                    silentStreak = 0
                }

                if (silentStreak >= SILENCE_MEANS_DEAD || session.state.value != ConnectionState.CONNECTED) {
                    // Il silenzio non è un risultato: i codici sondati dopo la caduta vanno tolti.
                    hits.removeAll { it.reply is Reply.Silent && it.code >= group.first() - silentStreak }
                    log.error(
                        "La sessione è caduta durante la scansione: da ${group.first() - silentStreak} " +
                            "in poi non risponde più niente. Uno dei codici lì attorno fa chiudere " +
                            "la connessione alla camera. Riconnetti e riparti da ${group.last() + 1} " +
                            "per saltarli."
                    )
                    break
                }

                // Lascia respirare le altre coroutine: senza, quella che osserva la connessione
                // non gira mai finché la scansione è in corso.
                yield()
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

    /** Esito di una singola forma di messaggio provata su un codice. */
    data class ShapeResult(
        val label: String,
        val bodyHex: String,
        val reply: Reply,
    ) {
        /**
         * La camera non ha protestato: nessun messaggio di errore, quindi con ogni probabilità
         * il comando è stato eseguito.
         *
         * Il criterio è "nessun errore di alcun tipo", non "non è UNKNOWN_MSG_PAYLOAD": una
         * risposta `UNKNOWN_MSG_CODE` è pur sempre un rifiuto, e trattarla come accettazione
         * faceva annunciare come eseguita una forma che la camera aveva scartato.
         */
        val accepted: Boolean get() = reply !is Reply.Error

        /** Rifiutato perché il corpo non va bene per questo messaggio. */
        val badPayload: Boolean get() = (reply as? Reply.Error)?.error?.isBadPayload == true

        /** Rifiutato con "codice sconosciuto", che su un messaggio esistente è un indizio. */
        val unknownCode: Boolean get() = (reply as? Reply.Error)?.error?.isUnknownCommand == true
    }

    /**
     * Prova le forme possibili del messaggio di un codice, un campo alla volta.
     *
     * A differenza della scansione, **questa non è un'operazione innocua**: ogni corpo che la
     * camera non rifiuta è un comando che ha eseguito. Va usata guardando la camera, su un
     * codice alla volta, e solo dopo che la scansione lo ha indicato come esistente.
     *
     * Il criterio è semplice: si parte dal corpo vuoto, che per un comando con argomenti viene
     * rifiutato con `UNKNOWN_MSG_PAYLOAD`. Un corpo che *smette* di essere rifiutato ha
     * indovinato un campo che quel messaggio possiede davvero.
     */
    suspend fun shape(code: Int, timeoutMs: Long = 3_000): List<ShapeResult> {
        log.warn(
            "Sonda della forma sul codice $code: da qui in avanti la camera può ESEGUIRE i " +
                "comandi che accetta. Guarda la camera."
        )

        val probes = buildList {
            add("corpo vuoto" to ByteArray(0))
            for (field in 1..6) {
                add("campo $field = 0" to ProtoWriter().int32(field, 0).toByteArray())
                add("campo $field = 1" to ProtoWriter().int32(field, 1).toByteArray())
                add("campo $field = messaggio vuoto" to ProtoWriter().bytes(field, ByteArray(0)).toByteArray())
            }
            // Una coppia asse+velocità è la forma più plausibile per un pan/tilt.
            add("campo 1 = 1, campo 2 = 30" to ProtoWriter().int32(1, 1).int32(2, 30).toByteArray())
            add("campo 1 = 1, campo 2 = -30" to ProtoWriter().int32(1, 1).sint32(2, -30).toByteArray())
        }

        val results = mutableListOf<ShapeResult>()
        for ((label, body) in probes) {
            if (!currentCoroutineContext().isActive) break
            if (session.state.value != ConnectionState.CONNECTED) {
                log.error("Sessione caduta durante la sonda della forma: mi fermo qui")
                break
            }
            val reply = probe(code, body, timeoutMs)
            val result = ShapeResult(label, Hex.encode(body, separator = ""), reply)
            results += result
            val verdict = when {
                result.accepted -> "ACCETTATO"
                result.unknownCode -> "rifiutato (codice sconosciuto)"
                else -> "rifiutato (corpo non valido)"
            }
            log.info("  $label → $verdict: ${reply.describe}")
            delay(SHAPE_DELAY_MS)
        }

        val accepted = results.filter { it.accepted && it.label != "corpo vuoto" }
        if (accepted.isEmpty()) {
            log.info("Nessuna forma accettata: il messaggio ha campi diversi da quelli provati.")
        } else {
            log.info(
                "Forme accettate (la camera le ha eseguite): " +
                    accepted.joinToString { it.label } +
                    ". Se una di queste ha mosso il gimbal, hai il comando e il campo."
            )
        }
        return results
    }

    /** Esito di un valore provato come selettore in un campo. */
    data class SelectorResult(val value: Int, val reply: Reply) {
        /** Nessun errore: quel valore significa qualcosa per la camera. */
        val valid: Boolean get() = reply !is Reply.Error

        /** L'errore dichiarato, se c'è, per distinguere "non esiste" da "argomenti sbagliati". */
        val errorCode: Int? get() = (reply as? Reply.Error)?.error?.code
    }

    /**
     * Prova una serie di valori in un campo di un comando, per capire se quel campo è un
     * selettore di sotto-comando.
     *
     * Il sospetto nasce da un'osservazione: sul codice 241 il corpo `{1: 1}` cambia l'errore
     * restituito rispetto a qualunque altro corpo. Un campo che cambia *il tipo* di rifiuto sta
     * venendo interpretato, non ignorato — e il candidato più naturale è un selettore.
     *
     * Come [shape], **non è innocua**: un valore valido viene eseguito.
     */
    suspend fun sweepSelector(
        code: Int,
        field: Int = 1,
        from: Int = 0,
        to: Int = 63,
        timeoutMs: Long = 1_500,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SelectorResult> {
        log.warn(
            "Provo i valori $from-$to nel campo $field del codice $code. Un valore valido viene " +
                "ESEGUITO: guarda la camera."
        )

        val results = mutableListOf<SelectorResult>()
        val total = to - from + 1
        for ((index, value) in (from..to).withIndex()) {
            if (!currentCoroutineContext().isActive) break
            if (session.state.value != ConnectionState.CONNECTED) {
                log.error("Sessione caduta durante la prova dei valori: mi fermo a $value")
                break
            }
            val reply = probe(code, ProtoWriter().int32(field, value).toByteArray(), timeoutMs)
            results += SelectorResult(value, reply)
            // Ogni risposta viene registrata grezza: l'interpretazione può essere sbagliata,
            // i byte no.
            log.info("  campo $field = $value → ${reply.describe}")
            onProgress(index + 1, total)
            delay(SELECTOR_DELAY_MS)
        }

        val valid = results.filter { it.valid }
        if (valid.isEmpty()) {
            log.info("Nessun valore accettato: quel campo non è un selettore, o i valori giusti stanno oltre $to.")
        } else {
            log.info(
                "Valori accettati dalla camera: " + valid.joinToString { it.value.toString() } +
                    ". Se uno di questi ha mosso il gimbal, è quello."
            )
        }
        return results
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

        /**
         * Silenzi consecutivi oltre i quali la sessione si considera morta.
         *
         * Su una sessione viva il silenzio è raro e isolato; una sequenza lunga significa che
         * non c'è più nessuno dall'altra parte.
         */
        private const val SILENCE_MEANS_DEAD = 16

        /** Fra una forma e l'altra: il tempo di vedere se la camera si muove. */
        private const val SHAPE_DELAY_MS = 600L

        /** Fra un valore e il successivo, per lo stesso motivo. */
        private const val SELECTOR_DELAY_MS = 400L
    }
}
