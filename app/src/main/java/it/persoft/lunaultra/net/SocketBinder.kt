package it.persoft.lunaultra.net

import java.net.Socket
import java.net.URL
import java.net.URLConnection

/**
 * Astrazione sul binding alla rete corretta. Tenerla separata dall'implementazione Android
 * permette di provare il trasporto su JVM pura nei test.
 *
 * Serve per due trasporti diversi: il socket TCP del canale di controllo e le connessioni HTTP
 * dell'anteprima MJPEG. Entrambe vanno legate al Wi-Fi della camera, che non offre Internet e
 * che Android altrimenti scavalcherebbe usando i dati mobili.
 */
interface SocketBinder {
    fun bind(socket: Socket): Boolean

    /**
     * Apre una connessione HTTP sulla rete della camera. L'implementazione predefinita usa il
     * routing di sistema, che è ciò che serve nei test su loopback.
     */
    fun openConnection(url: URL): URLConnection = url.openConnection()
}
