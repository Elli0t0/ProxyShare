package io.github.elli0t0.proxyshare.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.elli0t0.proxyshare.data.IfKind
import io.github.elli0t0.proxyshare.data.classifyInterface
import io.github.elli0t0.proxyshare.data.connectedWifiIface
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
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

    /**
     * The best validated non-VPN network for DIRECT routing (out
     * through the phone's real network, bypassing the VPN): prefer
     * Mobile when the hotspot is on, else Wi-Fi, else any validated
     * non-VPN network. Null when no validated non-VPN network exists.
     * A network is "validated" when the OS confirmed it reaches the
     * internet (NET_CAPABILITY_VALIDATED). When the hotspot is on,
     * the phone is not a Wi-Fi client, so the cellular network is the
     * one carrying the hotspot's traffic.
     */
    fun directNetwork(): Network? {
        val wifiIface = runCatching { connectedWifiIface(cm) }.getOrNull()
        val hotspotOn = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any {
                it.isUp && !it.isLoopback &&
                    classifyInterface(it.name, wifiIface) == IfKind.HOTSPOT
            }
        }.getOrNull() == true
        val candidates = cm.allNetworks.mapNotNull { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return@mapNotNull null
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return@mapNotNull null
            }
            net to caps
        }
        if (candidates.isEmpty()) return null
        if (hotspotOn) {
            candidates.firstOrNull { (_, caps) ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }?.let { return it.first }
        }
        candidates.firstOrNull { (_, caps) ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }?.let { return it.first }
        return candidates.firstOrNull()?.first
    }
}

/**
 * Fetch the exit IP from [url], optionally bound to [network].
 * User-triggered only, never automatic. Returns the parsed IP and
 * throws when the request fails or the body is not an IP (an HTML
 * error page from a filtering network must never be shown as the IP).
 */
suspend fun checkExitIp(url: String, network: Network?): String = withContext(Dispatchers.IO) {
    val conn = (network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
    conn.connectTimeout = 5000
    conn.readTimeout = 5000
    try {
        val body = conn.inputStream.bufferedReader().use { it.readText().take(4096) }
        parseExitIp(body) ?: throw IllegalStateException("Unparsable response from $url")
    } finally {
        conn.disconnect()
    }
}

/** Built-in exit-IP endpoints, tried in order. The 1.1.1.1 literal works even when DNS is filtered. */
val EXIT_IP_ENDPOINTS = listOf(
    "https://api.ipify.org",
    "https://icanhazip.com",
    "https://1.1.1.1/cdn-cgi/trace",
)

/**
 * Extract a real IP from a check-service body: a plain IP, or the
 * ip= line of a cdn-cgi/trace body. Anything else (an HTML error
 * page) returns null.
 */
fun parseExitIp(body: String): String? {
    val t = body.trim()
    Regex("""(?m)^ip=([0-9a-fA-F:.]+)$""").find(t)?.let { return it.groupValues[1] }
    return t.takeIf { Regex("""[0-9a-fA-F:.]{3,45}""").matches(it) && (':' in it || it.count { c -> c == '.' } == 3) }
}

/** Why an exit-IP check failed, after every endpoint was tried. */
enum class ExitIpFailure { DNS_BLOCKED, TIMEOUT, UNREACHABLE }

/** Thrown by [checkExitIp] when every endpoint failed, carrying the classified failure. */
class ExitIpCheckException(val failure: ExitIpFailure) : Exception(failure.name)

/**
 * Classify a failed exit-IP check from the collected per-endpoint
 * errors: a filtered DNS answers 127.0.0.1 (or nothing at all) for
 * the check hosts; a timeout is its own message; anything else is
 * "could not reach any check service".
 */
fun classifyExitIpFailure(errors: List<Throwable>): ExitIpFailure {
    val dnsBlocked = errors.any { e ->
        e is UnknownHostException || e.cause is UnknownHostException ||
            e.message?.contains("127.0.0.1") == true ||
            e.message?.contains("0.0.0.0") == true ||
            e.cause?.message?.contains("127.0.0.1") == true ||
            e.cause?.message?.contains("0.0.0.0") == true
    }
    if (dnsBlocked) return ExitIpFailure.DNS_BLOCKED
    val timedOut = errors.any { e ->
        e is SocketTimeoutException || e.cause is SocketTimeoutException
    }
    if (timedOut) return ExitIpFailure.TIMEOUT
    return ExitIpFailure.UNREACHABLE
}

/**
 * Try the given endpoints in order (5 s each) and return the first
 * parseable IP. Throws [ExitIpCheckException] when every endpoint
 * fails.
 */
suspend fun checkExitIp(urls: List<String>, network: Network?): String = withContext(Dispatchers.IO) {
    val errors = mutableListOf<Throwable>()
    for (url in urls) {
        try {
            return@withContext checkExitIp(url, network)
        } catch (e: Exception) {
            errors += e
        }
    }
    throw ExitIpCheckException(classifyExitIpFailure(errors))
}
