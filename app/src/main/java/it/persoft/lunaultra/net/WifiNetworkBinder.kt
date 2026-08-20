package it.persoft.lunaultra.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Socket
import kotlin.coroutines.resume

/**
 * L'access point della camera non offre accesso a Internet: senza un binding esplicito Android
 * continua a instradare i socket sui dati mobili e la connessione a 192.168.42.1 fallisce.
 * Qui si richiede esplicitamente la rete Wi-Fi corrente e vi si associano i socket.
 */
class WifiNetworkBinder(context: Context, private val log: EventLog) : SocketBinder {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var network: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Richiede la rete Wi-Fi e la mantiene registrata finché non si chiama [release]. */
    suspend fun acquire(timeoutMs: Long = 8_000): Network? {
        network?.let { return it }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val result = try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont: CancellableContinuation<Network> ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(available: Network) {
                            network = available
                            if (cont.isActive) cont.resume(available)
                        }

                        override fun onLost(lost: Network) {
                            if (network == lost) network = null
                            log.warn("Rete Wi-Fi persa")
                        }
                    }
                    callback = cb
                    connectivityManager.requestNetwork(request, cb)
                    // Il callback resta registrato fino a release(): serve a mantenere il binding.
                }
            }
        } catch (e: SecurityException) {
            log.warn("Permesso mancante per richiedere la rete Wi-Fi: ${e.message}")
            null
        }
        if (result == null) log.warn("Nessuna rete Wi-Fi disponibile entro ${timeoutMs} ms")
        return result
    }

    /** Associa il socket alla rete Wi-Fi. Restituisce true se il binding è riuscito. */
    override fun bind(socket: Socket): Boolean {
        val net = network ?: return false
        return try {
            net.bindSocket(socket)
            true
        } catch (e: Exception) {
            log.warn("Binding del socket alla rete Wi-Fi fallito: ${e.message}")
            false
        }
    }

    fun release() {
        callback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        callback = null
        network = null
    }
}
