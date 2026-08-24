package it.persoft.lunaultra.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
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
import kotlinx.coroutines.delay
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
    suspend fun acquire(
        password: String = "",
        cameraHost: String = DEFAULT_CAMERA_HOST,
        timeoutMs: Long = 15_000,
    ): Network? = acquireMutex.withLock {
        network?.takeIf(::isUsableWifiNetwork)?.let { return it }
        network = null

        currentLunaNetwork(cameraHost)?.let {
            // Se una vecchia richiesta con WifiNetworkSpecifier era caduta, il suo callback
            // può essere ancora registrato e continuare a contendere la rete alla connessione
            // manuale. Prima di adottare la rete già attiva lo si elimina.
            unregisterRequest()
            network = it
            // La rete adottata va anche *rivendicata*: senza una richiesta in piedi, per
            // ConnectivityManager una Wi-Fi senza Internet non serve a nessuno, e appena
            // l'app va in secondo piano il sistema è libero di lasciarla per tornare ai
            // dati mobili. È il «cambio app e si disconnette»: la richiesta la pianta lì.
            pinCurrentWifi()
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
            if (accessPoint?.security?.needsPassphrase == true && password.isBlank()) {
                log.warn(
                    "La rete ${accessPoint.ssid} è protetta (${accessPoint.security.label}), " +
                        "ma l'app non ha ancora la password"
                )
                return@withLock null
            }
            if (accessPoint?.security == WifiSecurity.UNSUPPORTED) {
                log.warn("Sicurezza Wi-Fi non supportata per ${accessPoint.ssid}")
                return@withLock null
            }
            val specifier = WifiNetworkSpecifier.Builder().apply {
                if (accessPoint != null) {
                    // Una richiesta specifica evita il selettore OEM e, dopo la prima conferma,
                    // Android può riconnettersi allo stesso access point senza chiederla ancora.
                    setSsid(accessPoint.ssid)
                    when (accessPoint.security) {
                        WifiSecurity.WPA2 -> setWpa2Passphrase(password)
                        WifiSecurity.WPA3 -> setWpa3Passphrase(password)
                        WifiSecurity.ENHANCED_OPEN -> setIsEnhancedOpen(true)
                        WifiSecurity.OPEN, WifiSecurity.UNSUPPORTED -> Unit
                    }
                    log.info(
                        "Access point Luna individuato: ${accessPoint.ssid} " +
                            "(${accessPoint.security.label})"
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
            // Alcuni firmware completano lo switch proprio mentre chiudono il selettore con
            // onUnavailable(). Prima di dichiarare il fallimento controlliamo quindi lo stato
            // reale: se il telefono è arrivato sulla Luna, la adottiamo senza una seconda
            // richiesta che causerebbe il ciclo connessione/disconnessione.
            awaitCurrentLunaNetwork(cameraHost)?.let {
                network = it
                log.info("Wi-Fi Luna attivo dopo il selettore: ${ssidOf(it) ?: LUNA_SSID_PREFIX}")
                return it
            }
            log.warn("Nessuna rete Wi-Fi Luna disponibile entro ${timeoutMs} ms")
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
                    security = WifiSecurity.fromCapabilities(result.capabilities),
                )
            }
    }.onFailure {
        log.warn("Risultati scansione Wi-Fi non leggibili: ${it.message}")
    }.getOrNull()

    /**
     * Una rete Luna già attiva non deve passare da nessuna finestra di scelta.
     *
     * L'SSID può risultare sconosciuto anche se la rete è connessa (permesso appena concesso,
     * posizione disattivata o comportamento OEM). Indirizzi locali e gateway verso l'host della
     * camera sono quindi identificatori aggiuntivi della connessione fatta manualmente.
     */
    private fun currentLunaNetwork(cameraHost: String): Network? = runCatching {
        val wifiNetworks = connectivityManager.allNetworks.filter(::isUsableWifiNetwork)
        if (wifiNetworks.isEmpty()) return@runCatching null

        // È l'identificatore più preciso e resta corretto anche sui telefoni con due reti Wi-Fi
        // simultanee (funzione disponibile su alcuni Android recenti).
        wifiNetworks.firstOrNull { LunaWifiIdentity.isLunaSsid(capabilitySsidOf(it)) }
            ?.let { return@runCatching it }

        // Se transportInfo è oscurato, WifiManager spesso conserva il vero SSID della rete
        // Wi-Fi primaria. Prima il codice preferiva sempre "<unknown ssid>" e non arrivava mai
        // a questo dato: era la causa del nuovo selettore anche a Luna già connessa.
        val primarySsid = legacyConnectedSsid()
        if (LunaWifiIdentity.isLunaSsid(primarySsid)) {
            return@runCatching wifiNetworks.firstOrNull { matchesCameraLink(it, cameraHost) }
                ?: wifiNetworks.firstOrNull { !isValidated(it) }
                ?: wifiNetworks.singleOrNull()
                ?: wifiNetworks.first()
        }

        // L'indirizzo del telefono non è necessariamente 192.168.42.x. Per questo controlliamo
        // anche il gateway: nelle catture Luna il telefono può avere 10.x ma raggiunge la camera
        // tramite il gateway configurato a 192.168.42.1.
        wifiNetworks.firstOrNull { matchesCameraLink(it, cameraHost) }
    }.onFailure {
        log.warn("Stato della rete Wi-Fi non leggibile: ${it.message}")
    }.getOrNull()

    private suspend fun awaitCurrentLunaNetwork(cameraHost: String): Network? {
        repeat(POST_SELECTOR_CHECKS) { attempt ->
            currentLunaNetwork(cameraHost)?.let { return it }
            if (attempt < POST_SELECTOR_CHECKS - 1) delay(POST_SELECTOR_CHECK_INTERVAL_MS)
        }
        return null
    }

    private fun isUsableWifiNetwork(candidate: Network): Boolean =
        connectivityManager.getNetworkCapabilities(candidate)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    private fun isValidated(candidate: Network): Boolean =
        connectivityManager.getNetworkCapabilities(candidate)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    private fun matchesCameraLink(candidate: Network, cameraHost: String): Boolean {
        val properties = connectivityManager.getLinkProperties(candidate) ?: return false
        val matchingAddress = properties.linkAddresses.any {
            LunaWifiIdentity.isCameraSubnetAddress(it.address.hostAddress)
        }
        val matchingGateway = properties.routes.any {
            LunaWifiIdentity.isCameraGateway(it.gateway?.hostAddress, cameraHost)
        }
        return matchingAddress || matchingGateway
    }

    private fun capabilitySsidOf(candidate: Network): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val raw = connectivityManager.getNetworkCapabilities(candidate)
            ?.transportInfo
            ?.let { it as? WifiInfo }
            ?.ssid
        return LunaWifiIdentity.normalizeSsid(raw)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun legacyConnectedSsid(): String? =
        LunaWifiIdentity.normalizeSsid(wifiManager.connectionInfo?.ssid)

    @Suppress("DEPRECATION")
    private fun ssidOf(candidate: Network): String? {
        return capabilitySsidOf(candidate) ?: legacyConnectedSsid()
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

    /**
     * Tiene in vita la Wi-Fi corrente con una richiesta senza specifier.
     *
     * Serve alla rete a cui l'utente si è collegato dalle impostazioni di sistema: non è stata
     * chiesta dall'app, quindi nessuno la sta «usando» agli occhi di ConnectivityManager, e
     * una Wi-Fi che non dichiara Internet viene abbandonata appena lo schermo si spegne o
     * l'app passa in secondo piano. Una richiesta registrata dice al sistema che quella rete
     * ha un cliente, e il cliente resta anche in secondo piano.
     */
    private fun pinCurrentWifi() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                if (callback !== this) return
                network = available
            }

            override fun onLost(lost: Network) {
                if (callback !== this) return
                if (network == lost) network = null
                log.warn("Rete Wi-Fi persa")
            }
        }
        callback = cb
        runCatching { connectivityManager.requestNetwork(request, cb) }
            .onFailure { log.warn("Rivendicazione della Wi-Fi non riuscita: ${it.message}") }
    }

    private fun unregisterRequest() {
        val registered = callback ?: return
        callback = null
        runCatching { connectivityManager.unregisterNetworkCallback(registered) }
            .onFailure { log.warn("Chiusura richiesta Wi-Fi non riuscita: ${it.message}") }
    }

    companion object {
        const val LUNA_SSID_PREFIX = LunaWifiIdentity.SSID_PREFIX
        private const val DEFAULT_CAMERA_HOST = "192.168.42.1"
        private const val SCAN_TIMEOUT_MS = 5_000L
        private const val POST_SELECTOR_CHECKS = 5
        private const val POST_SELECTOR_CHECK_INTERVAL_MS = 300L
    }

    private data class LunaAccessPoint(val ssid: String, val security: WifiSecurity)

    private enum class WifiSecurity(val label: String, val needsPassphrase: Boolean) {
        OPEN("aperta", false),
        ENHANCED_OPEN("OWE", false),
        WPA2("WPA2", true),
        WPA3("WPA3", true),
        UNSUPPORTED("non riconosciuta", true),
        ;

        companion object {
            fun fromCapabilities(value: String?): WifiSecurity {
                val capabilities = value.orEmpty().uppercase()
                return when {
                    "PSK" in capabilities -> WPA2 // include le reti WPA2/WPA3 transition
                    "SAE" in capabilities -> WPA3
                    "OWE" in capabilities -> ENHANCED_OPEN
                    "WEP" in capabilities || "EAP" in capabilities -> UNSUPPORTED
                    else -> OPEN
                }
            }
        }
    }
}
