package com.example.proxyshare.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.example.proxyshare.data.AppSettings
import com.example.proxyshare.data.SettingsRepository
import com.example.proxyshare.engine.Engine
import com.example.proxyshare.engine.EngineEvent
import com.example.proxyshare.engine.EngineState
import com.example.proxyshare.engine.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-scoped singleton that owns the one [Engine], the settings and
 * their coroutine scope. No Hilt. The service and the UI both talk to
 * the engine through here.
 *
 * start() and stop() are safe to call twice and never throw.
 * Concurrent starts are serialized with a Mutex.
 */
object ProxyController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = Engine(scope)
    private val startMutex = Mutex()

    private var repository: SettingsRepository? = null
    private var selector: NetworkSelector? = null
    private var started = false
    private var networkJob: Job? = null

    private val _settings = MutableStateFlow(AppSettings())
    /** The current settings (defaults until the DataStore read lands). */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _network = MutableStateFlow<Network?>(null)
    /** The network matching the chosen outbound mode, if any. */
    val network: StateFlow<Network?> = _network.asStateFlow()

    val state: StateFlow<EngineState> = engine.state
    val snapshot: StateFlow<Snapshot> = engine.snapshot
    val events: SharedFlow<EngineEvent> = engine.events

    /** Called once from ProxyShareApp.onCreate. Safe to call twice. */
    fun init(context: Context) {
        if (repository != null) return
        val appContext = context.applicationContext
        val repo = SettingsRepository(appContext)
        repository = repo
        val selector = NetworkSelector(
            appContext.getSystemService(ConnectivityManager::class.java)
        )
        this.selector = selector
        scope.launch {
            repo.settings.collect { _settings.value = it }
        }
        scope.launch {
            selector.network.collect { _network.value = it }
        }
    }

    /** Read the settings, generate a password on first run, start the engine. */
    fun start() {
        scope.launch {
            startMutex.withLock {
                if (started) return@withLock
                val repo = repository ?: return@withLock
                val selector = selector ?: return@withLock
                started = true
                try {
                    repo.ensurePassword()
                    val settings = repo.settings.first()
                    val mode = settings.outboundMode.toOutboundMode()
                    selector.select(mode)
                    if (mode != OutboundMode.DEFAULT) {
                        val network = withTimeoutOrNull(2000) {
                            selector.network.filterNotNull().first()
                        }
                        if (network == null) {
                            started = false
                            engine.setError("No ${settings.outboundMode} network available")
                            return@withLock
                        }
                    }
                    networkJob?.cancel()
                    networkJob = scope.launch {
                        selector.network.collect { net ->
                            if (!started) return@collect
                            if (mode == OutboundMode.VPN_ONLY && net == null) {
                                // Kill switch: never fall back to the default network.
                                started = false
                                runCatching { engine.stop() }
                                engine.setError("VPN lost — proxy stopped")
                                networkJob?.cancel()
                                return@collect
                            }
                            engine.setOutboundNetwork(net?.networkHandle ?: 0L)
                        }
                    }
                    val error = engine.start(settings)
                    if (error.isNotEmpty()) {
                        started = false
                        networkJob?.cancel()
                        networkJob = null
                    }
                } catch (_: Exception) {
                    started = false
                    networkJob?.cancel()
                    networkJob = null
                    runCatching { engine.stop() }
                }
            }
        }
    }

    /** Stop the engine. Also resets an Error state back to Stopped. */
    fun stop() {
        scope.launch {
            startMutex.withLock {
                networkJob?.cancel()
                networkJob = null
                selector?.select(OutboundMode.DEFAULT)
                if (started) {
                    started = false
                    runCatching { engine.stop() }
                } else if (engine.state.value is EngineState.Error) {
                    runCatching { engine.stop() }
                }
            }
        }
    }

    /** Poll faster (250 ms) while the connections screen is visible. */
    fun setFastPolling(enabled: Boolean) {
        engine.setFastPolling(enabled)
    }

    /** Change the outbound mode. Only used while stopped (the UI enforces it). */
    suspend fun setOutboundMode(mode: OutboundMode) {
        repository?.updateOutboundMode(mode.toSettingString())
    }

    /** User-triggered exit-IP check, bound to the selected network when there is one. */
    suspend fun checkExitIp(): Result<String> = runCatching {
        val repo = repository ?: return@runCatching ""
        val settings = repo.settings.first()
        checkExitIp(settings.exitIpUrl, network.value)
    }
}
