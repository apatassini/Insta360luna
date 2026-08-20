package it.persoft.lunaultra.net

import java.net.Socket

/**
 * Astrazione sul binding del socket alla rete corretta. Tenerla separata dall'implementazione
 * Android permette di provare il trasporto su JVM pura nei test.
 */
interface SocketBinder {
    fun bind(socket: Socket): Boolean
}
