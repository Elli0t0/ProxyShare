package io.github.elli0t0.proxyshare.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * True while a VPN network is active. One shared flow for the Home
 * banner, the controller (engine setVpnActive) and the service (drop
 * notification), kept alive 5 s after the last subscriber so nothing
 * polls or wakes up when idle.
 */
object VpnWatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var flow: StateFlow<Boolean>? = null

    /** False until init() runs (ProxyShareApp.onCreate does that). */
    val active: StateFlow<Boolean>
        get() = flow ?: MutableStateFlow(false)

    fun init(context: Context) {
        if (flow != null) return
        flow = vpnActive(context.applicationContext)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)
    }
}

/** A VPN network is active: emits the current state, then every change. */
fun vpnActive(context: Context): Flow<Boolean> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val req = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()
    val active = HashSet<Network>()
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            active += n
            trySend(true)
        }

        override fun onLost(n: Network) {
            active -= n
            trySend(active.isNotEmpty())
        }
    }
    trySend(false)
    cm.registerNetworkCallback(req, cb)
    awaitClose { cm.unregisterNetworkCallback(cb) }
}.distinctUntilChanged()
