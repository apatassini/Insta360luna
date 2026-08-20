package it.persoft.lunaultra.camera

import it.persoft.lunaultra.data.AppSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Traduce i nomi simbolici dei comandi negli id numerici configurati dall'utente.
 * Un id pari a 0 (o assente) significa "non ancora scoperto".
 */
class CommandRegistry(private val settings: StateFlow<AppSettings>) {

    fun idOf(command: LunaCommand): Int? =
        settings.value.commandIds[command.key]?.takeIf { it != 0 }

    fun idOf(notification: LunaNotification): Int? =
        settings.value.notificationIds[notification.key]?.takeIf { it != 0 }

    fun notificationFor(commandId: Int): LunaNotification? {
        val ids = settings.value.notificationIds
        return LunaNotification.entries.firstOrNull { ids[it.key] == commandId }
    }

    fun isConfigured(command: LunaCommand): Boolean = idOf(command) != null

    fun configuredCount(): Int = LunaCommand.entries.count { isConfigured(it) }

    fun missing(): List<LunaCommand> = LunaCommand.entries.filterNot { isConfigured(it) }
}
