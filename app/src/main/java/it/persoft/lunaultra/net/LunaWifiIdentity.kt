package it.persoft.lunaultra.net

/** Regole pure e testabili per riconoscere la rete locale della Luna Ultra. */
internal object LunaWifiIdentity {
    const val SSID_PREFIX = "Luna Ultra"
    private const val CAMERA_SUBNET_PREFIX = "192.168.42."

    /**
     * Android e alcuni firmware OEM usano sia `UNKNOWN_SSID` sia la stringa letterale
     * `<unknown ssid>` quando oscurano il nome della rete. Nessuna delle due è un SSID reale.
     */
    fun normalizeSsid(raw: String?): String? = raw
        ?.trim()
        ?.removeSurrounding("\"")
        ?.takeUnless {
            it.isBlank() ||
                it.equals("<unknown ssid>", ignoreCase = true) ||
                it.equals("unknown ssid", ignoreCase = true)
        }

    fun isLunaSsid(raw: String?): Boolean =
        normalizeSsid(raw)?.startsWith(SSID_PREFIX, ignoreCase = true) == true

    fun isCameraSubnetAddress(address: String?): Boolean =
        address?.startsWith(CAMERA_SUBNET_PREFIX) == true

    fun isCameraGateway(gateway: String?, cameraHost: String): Boolean =
        gateway != null && gateway == cameraHost
}
