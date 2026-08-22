package it.persoft.lunaultra.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Socket
import java.net.URL
import java.net.URLConnection
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
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile
    private var network: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val acquireMutex = Mutex()

    /**
     * Cerca direttamente l'access point della Luna e lo mantiene registrato fino a [release].
     *
     * Non c'è un elenco di reti dell'app: il selettore accetta soltanto SSID che iniziano con
     * "Luna Ultra". Android può chiedere una conferma di sistema la prima volta, ma le aperture
     * successive riutilizzano l'autorizzazione già data.
     */
    suspend fun acquire(timeoutMs: Long = 15_000): Network? = acquireMutex.withLock {
        network?.let { return it }

        currentLunaNetwork()?.let {
            // Se una vecchia richiesta con WifiNetworkSpecifier era caduta, il suo callback
            // può essere ancora registrato e continuare a contendere la rete alla connessione
            // manuale. Prima di adottare la rete già attiva lo si elimina.
            unregisterRequest()
            network = it
            log.info("Wi-Fi Luna già connesso: ${ssidOf(it) ?: LUNA_SSID_PREFIX}")
            return it
        }

        // onLost azzera `network`, ma non può sospendere per serializzare un nuovo tentativo.
        // Ogni acquire riparte quindi da una sola richiesta pulita: due specifier concorrenti
        // fanno alternare connessione/disconnessione su diversi firmware Android.
        unregisterRequest()

        val builder = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // L'access point della camera è locale e non dichiara Internet.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val accessPoint = discoverLunaAccessPoint()
            val specifier = WifiNetworkSpecifier.Builder().apply {
                if (accessPoint != null) {
                    // Una richiesta specifica evita il selettore OEM e, dopo la prima conferma,
                    // Android può riconnettersi allo stesso access point senza chiederla ancora.
                    setSsid(accessPoint.ssid)
                    accessPoint.bssid?.let(::setBssid)
                    log.info(
                        "Access point Luna individuato: ${accessPoint.ssid}" +
                            (accessPoint.bssid?.let { " ($it)" } ?: "")
                    )
                } else {
                    // Ripiego per posizione disattivata, permesso negato o scansione limitata.
                    setSsidPattern(PatternMatcher(LUNA_SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
                    log.warn("SSID Luna esatto non leggibile: uso il filtro per prefisso")
                }
            }.build()
            builder.setNetworkSpecifier(specifier)
        }
        val request = builder.build()

        val result = try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont: CancellableContinuation<Network?> ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(available: Network) {
                            if (callback !== this) return
                            network = available
                            log.info("Wi-Fi Luna acquisito: ${ssidOf(available) ?: LUNA_SSID_PREFIX}")
                            if (cont.isActive) cont.resume(available)
                        }

                        override fun onLost(lost: Network) {
                            if (callback !== this) return
                            if (network == lost) network = null
                            log.warn("Rete Wi-Fi persa")
                        }

                        override fun onUnavailable() {
                            if (callback !== this) return
                            log.warn("Rete $LUNA_SSID_PREFIX non disponibile")
                            if (cont.isActive) cont.resume(null)
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
        if (result == null) {
            unregisterRequest()
            log.warn("Nessuna rete Wi-Fi disponibile entro ${timeoutMs} ms")
        }
        return result
    }

    /**
     * Trova il punto di accesso Luna più vicino e restituisce identificatori esatti.
     *
     * Il risultato memorizzato dal sistema viene provato subito. Se non contiene la Luna si
     * chiede una nuova scansione e si attende il broadcast, con un timeout breve: il sistema può
     * rifiutare `startScan` per throttling, ma in quel caso lascia comunque disponibili gli
     * ultimi risultati noti.
     */
    @SuppressLint("MissingPermission")
    private suspend fun discoverLunaAccessPoint(): LunaAccessPoint? {
        bestCachedLunaAccessPoint()?.let { return it }

        withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
                lateinit var receiver: BroadcastReceiver
                fun unregister() {
                    runCatching { appContext.unregisterReceiver(receiver) }
                }

                receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        unregister()
                        if (cont.isActive) cont.resume(Unit)
                    }
                }

                try {
                    val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        @Suppress("DEPRECATION")
                        appContext.registerReceiver(receiver, filter)
                    }
                    cont.invokeOnCancellation { unregister() }
                    if (!wifiManager.startScan()) {
                        unregister()
                        if (cont.isActive) cont.resume(Unit)
                    }
                } catch (e: SecurityException) {
                    unregister()
                    log.warn("Scansione Wi-Fi non autorizzata: ${e.message}")
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
        return bestCachedLunaAccessPoint()
    }

    @SuppressLint("MissingPermission")
    private fun bestCachedLunaAccessPoint(): LunaAccessPoint? = runCatching {
        wifiManager.scanResults
            .asSequence()
            .filter { it.SSID.startsWith(LUNA_SSID_PREFIX, ignoreCase = true) }
            .maxByOrNull(ScanResult::level)
            ?.let { result ->
                LunaAccessPoint(
                    ssid = result.SSID,
                    bssid = result.BSSID
                        ?.takeUnless { it == UNKNOWN_BSSID }
                        ?.let { runCatching { MacAddress.fromString(it) }.getOrNull() },
                )
            }
    }.onFailure {
        log.warn("Risultati scansione Wi-Fi non leggibili: ${it.message}")
    }.getOrNull()

    /**
     * Una rete Luna già attiva non deve passare da nessuna finestra di scelta.
     *
     * L'SSID può risultare `UNKNOWN_SSID` anche se la rete è connessa (permesso appena concesso,
     * posizione disattivata o comportamento OEM). La sottorete 192.168.42.x è quindi il secondo
     * identificatore: consente di adottare anche una connessione fatta manualmente dall'utente.
     */
    private fun currentLunaNetwork(): Network? = runCatching {
        connectivityManager.allNetworks.firstOrNull { candidate ->
            val capabilities = connectivityManager.getNetworkCapabilities(candidate) ?: return@firstOrNull false
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstOrNull false
            val matchingSsid = ssidOf(candidate)?.startsWith(LUNA_SSID_PREFIX, ignoreCase = true) == true
            val matchingSubnet = connectivityManager.getLinkProperties(candidate)
                ?.linkAddresses
                ?.any { it.address.hostAddress?.startsWith(LUNA_SUBNET_PREFIX) == true } == true
            matchingSsid || matchingSubnet
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun ssidOf(candidate: Network): String? {
        val fromCapabilities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectivityManager.getNetworkCapabilities(candidate)
                ?.transportInfo
                ?.let { it as? WifiInfo }
                ?.ssid
        } else {
            null
        }
        val raw = fromCapabilities ?: wifiManager.connectionInfo?.ssid
        return raw
            ?.removeSurrounding("\"")
            ?.takeUnless { it == WifiManager.UNKNOWN_SSID }
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

    /**
     * Apre una connessione HTTP sulla rete Wi-Fi della camera. `Network.openConnection` è
     * l'equivalente HTTP di `bindSocket`: senza, l'anteprima MJPEG partirebbe sui dati mobili
     * e non troverebbe nessuna camera.
     */
    override fun openConnection(url: URL): URLConnection {
        val net = network
        if (net == null) {
            log.warn("Connessione HTTP non associata al Wi-Fi: nessuna rete acquisita")
            return url.openConnection()
        }
        return try {
            net.openConnection(url)
        } catch (e: Exception) {
            log.warn("Binding HTTP alla rete Wi-Fi fallito: ${e.message}")
            url.openConnection()
        }
    }

    fun release() {
        unregisterRequest()
        network = null
    }

    private fun unregisterRequest() {
        val registered = callback ?: return
        callback = null
        runCatching { connectivityManager.unregisterNetworkCallback(registered) }
            .onFailure { log.warn("Chiusura richiesta Wi-Fi non riuscita: ${it.message}") }
    }

    companion object {
        const val LUNA_SSID_PREFIX = "Luna Ultra"
        private const val LUNA_SUBNET_PREFIX = "192.168.42."
        private const val UNKNOWN_BSSID = "02:00:00:00:00:00"
        private const val SCAN_TIMEOUT_MS = 5_000L
    }

    private data class LunaAccessPoint(val ssid: String, val bssid: MacAddress?)
}
