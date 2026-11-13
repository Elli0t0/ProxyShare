package com.example.proxyshare.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class OutboundMode { DEFAULT, VPN_ONLY, WIFI, MOBILE }

/** Map the stored setting string ("default", "vpn_only", "wifi", "mobile") to the mode. Unknown values fall back to DEFAULT. */
fun String.toOutboundMode(): OutboundMode = when (this) {
    "vpn_only" -> OutboundMode.VPN_ONLY
    "wifi" -> OutboundMode.WIFI
    "mobile" -> OutboundMode.MOBILE
    else -> OutboundMode.DEFAULT
}

/** Map the mode back to the string stored in settings. */
fun OutboundMode.toSettingString(): String = when (this) {
    OutboundMode.DEFAULT -> "default"
    OutboundMode.VPN_ONLY -> "vpn_only"
    OutboundMode.WIFI -> "wifi"
    OutboundMode.MOBILE -> "mobile"
}

class NetworkSelector(private val cm: ConnectivityManager) {
    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network.asStateFlow()
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun select(mode: OutboundMode) {
        callback?.let { cm.unregisterNetworkCallback(it) }
        callback = null
        _network.value = null
        val transport = when (mode) {
            OutboundMode.VPN_ONLY -> NetworkCapabilities.TRANSPORT_VPN
            OutboundMode.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            OutboundMode.MOBILE -> NetworkCapabilities.TRANSPORT_CELLULAR
            OutboundMode.DEFAULT -> return
        }
        val builder = NetworkRequest.Builder().addTransportType(transport)
        if (transport == NetworkCapabilities.TRANSPORT_VPN) {
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _network.value = network }
            override fun onLost(network: Network) {
                if (_network.value == network) _network.value = null
            }
        }
        callback = cb
        cm.registerNetworkCallback(builder.build(), cb)
    }
}

/**
 * Fetch the exit IP from [url], optionally bound to [network].
 * User-triggered only, never automatic. The caller wraps it in
 * runCatching and shows the error text on failure.
 */
suspend fun checkExitIp(url: String, network: Network?): String = withContext(Dispatchers.IO) {
    val conn = (network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    try {
        conn.inputStream.bufferedReader().use { it.readText().trim().take(64) }
    } finally {
        conn.disconnect()
    }
}
