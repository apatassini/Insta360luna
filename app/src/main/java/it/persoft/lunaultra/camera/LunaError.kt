package it.persoft.lunaultra.camera

import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.protocol.ProtoField
import it.persoft.lunaultra.protocol.ProtoReader

/**
 * Il messaggio `insta360.messages.Error { ErrorCode code = 1; string message = 2; }`.
 *
 * La camera risponde a un errore riusando il codice del comando: non c'è un flag di errore
 * nell'header, quindi l'unico modo per riconoscerlo è la forma del corpo. [parse] applica un
 * riconoscimento volutamente stretto — un varint piccolo nel campo 1 ed eventualmente una
 * stringa nel campo 2, nient'altro — perché scambiare una risposta valida per un errore è
 * peggio che non riconoscere un errore.
 */
data class LunaError(
    val code: Int,
    val message: String?,
) {
    val name: String get() = LunaProtocolCodes.ErrorCode.name(code)

    /** Il comando non esiste su questo firmware. */
    val isUnknownCommand: Boolean get() = code == LunaProtocolCodes.ErrorCode.UNKNOWN_MSG_CODE

    /** Il comando esiste e ha rifiutato gli argomenti: non ha eseguito nulla. */
    val isBadPayload: Boolean get() = code == LunaProtocolCodes.ErrorCode.UNKNOWN_MSG_PAYLOAD

    override fun toString(): String = message?.let { "$name (\"$it\")" } ?: name

    companion object {
        /** Il numero più alto definito in `Error.ErrorCode`. */
        private const val MAX_ERROR_CODE = 5

        fun parse(payload: ByteArray): LunaError? {
            if (payload.isEmpty()) return null
            val fields = ProtoReader(payload).fields()
            if (fields.isEmpty() || fields.size > 2) return null

            val first = fields[0] as? ProtoField.VarInt ?: return null
            if (first.number != 1 || first.value < 0 || first.value > MAX_ERROR_CODE) return null

            if (fields.size == 1) return LunaError(first.asInt, null)

            val second = fields[1] as? ProtoField.LengthDelimited ?: return null
            if (second.number != 2) return null
            return LunaError(first.asInt, second.asString)
        }
    }
}
