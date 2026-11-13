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

enum class IfKind { WIFI, HOTSPOT, ETHERNET, MOBILE, VPN, OTHER }

data class NetIf(val name: String, val ip: String, val kind: IfKind)

fun classifyInterface(name: String): IfKind = when {
    name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("wg") -> IfKind.VPN
    name.startsWith("swlan") || name.startsWith("ap") -> IfKind.HOTSPOT
    name.startsWith("wlan") -> IfKind.WIFI
    name.startsWith("eth") || name.startsWith("rndis") -> IfKind.ETHERNET
    name.startsWith("rmnet") || name.startsWith("ccmni") -> IfKind.MOBILE
    else -> IfKind.OTHER
}

fun listIPv4(): List<NetIf> =
    (NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nif ->
            nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .map { NetIf(nif.name, it.hostAddress.orEmpty(), classifyInterface(nif.name)) }
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
 * every 5 s (hotspot toggles do not always fire callbacks).
 * Debounced 300 ms.
 */
@OptIn(FlowPreview::class)
fun networkInterfaces(context: Context): Flow<List<NetIf>> =
    merge(
        networkChanges(context),
        flow {
            while (true) {
                emit(Unit)
                delay(5000)
            }
        },
    )
        .debounce(300)
        .map { listIPv4() }
        .flowOn(Dispatchers.IO)
