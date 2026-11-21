package com.example.proxyshare.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * One shared interface flow for Home and Connections: a single
 * networkInterfaces() collection, kept alive 5 s after the last
 * subscriber so tab switches don't restart it.
 */
object NetworkWatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var flow: StateFlow<List<NetIf>>? = null

    /** Empty until init() runs (ProxyShareApp.onCreate does that). */
    val interfaces: StateFlow<List<NetIf>>
        get() = flow ?: MutableStateFlow<List<NetIf>>(emptyList())

    fun init(context: Context) {
        if (flow != null) return
        flow = networkInterfaces(context.applicationContext)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }
}
