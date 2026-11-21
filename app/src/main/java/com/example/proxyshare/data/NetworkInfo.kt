package com.example.proxyshare.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

enum class IfKind { WIFI, HOTSPOT, ETHERNET, USB, MOBILE, VPN, OTHER }

data class NetIf(val name: String, val ip: String, val kind: IfKind)

/** The connected Wi-Fi interface name, or null when not on Wi-Fi. */
@Suppress("DEPRECATION")
fun connectedWifiIface(cm: ConnectivityManager): String? =
    cm.allNetworks.firstNotNullOfOrNull { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@firstNotNullOfOrNull null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            cm.getLinkProperties(n)?.interfaceName
        } else {
            null
        }
    }

/**
 * Classify one interface. The name is stripped of any alias suffix
 * ("tun0:1" -> "tun0") before matching. A wlan* interface that is NOT
 * the currently connected Wi-Fi interface is the hotspot (some phones
 * run the hotspot on wlan1); pass null when the Wi-Fi interface is
 * unknown and every wlan* counts as Wi-Fi.
 */
fun classifyInterface(name: String, wifiIface: String? = null): IfKind {
    val base = name.substringBefore(':')
    return when {
        base.startsWith("tun") || base.startsWith("ppp") || base.startsWith("wg") -> IfKind.VPN
        base.startsWith("swlan") || base.startsWith("ap") -> IfKind.HOTSPOT
        base.startsWith("wlan") && wifiIface != null && base != wifiIface -> IfKind.HOTSPOT
        base.startsWith("wlan") -> IfKind.WIFI
        base.startsWith("eth") -> IfKind.ETHERNET
        base.startsWith("rndis") || base.startsWith("usb") -> IfKind.USB
        base.startsWith("rmnet") || base.startsWith("ccmni") -> IfKind.MOBILE
        else -> IfKind.OTHER
    }
}

/**
 * Rank interfaces for the Home addresses card: hotspot first, then
 * USB tethering, Wi-Fi, Ethernet, other, mobile last. VPN interfaces
 * are dropped (they are outbound only, shown in their own section).
 */
fun rankInterfaces(list: List<NetIf>): List<NetIf> {
    val order = listOf(
        IfKind.HOTSPOT, IfKind.USB, IfKind.WIFI, IfKind.ETHERNET, IfKind.OTHER, IfKind.MOBILE
    )
    return list.filter { it.kind != IfKind.VPN }.sortedBy { order.indexOf(it.kind) }
}

private fun isUsableIpv4(ip: String): Boolean {
    val parts = ip.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets[0] == 192 && octets[1] == 0 && octets[2] == 0 && octets[3] in 0..7) return false
    if (octets[0] == 169 && octets[1] == 254) return false
    return true
}

fun listIPv4(cm: ConnectivityManager? = null): List<NetIf> {
    val wifiIface = cm?.let { runCatching { connectedWifiIface(it) }.getOrNull() }
    return (NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nif ->
            nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .map { it.hostAddress.orEmpty() }
                .filter { isUsableIpv4(it) }
                .map {
                    NetIf(
                        nif.name.substringBefore(':'),
                        it,
                        classifyInterface(nif.name, wifiIface),
                    )
                }
        }
        .filter { !(it.name.startsWith("v4-")) }
}

/** Emits on every network change (plus once immediately). */
fun networkChanges(context: Context): Flow<Unit> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(Unit) }
        override fun onLost(network: Network) { trySend(Unit) }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { trySend(Unit) }
    }
    val request = NetworkRequest.Builder()
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) // also report VPN networks
        .build()
    cm.registerNetworkCallback(request, callback)
    trySend(Unit)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}

/**
 * The current IPv4 interfaces, re-listed on every network change and
 * every 15 s (hotspot toggles do not always fire callbacks; the
 * NetworkCallback covers real changes, the timer is only a safety net).
 * Debounced 300 ms. The ConnectivityManager is passed through so the
 * hotspot check can tell the Wi-Fi interface from the hotspot one.
 */
@OptIn(FlowPreview::class)
fun networkInterfaces(context: Context): Flow<List<NetIf>> {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    return merge(
        networkChanges(context),
        flow {
            while (true) {
                emit(Unit)
                delay(15000)
            }
        },
    )
        .debounce(300)
        .map { listIPv4(cm) }
        .flowOn(Dispatchers.IO)
}
