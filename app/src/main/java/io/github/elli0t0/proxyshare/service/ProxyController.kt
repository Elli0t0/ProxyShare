package io.github.elli0t0.proxyshare.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import io.github.elli0t0.proxyshare.data.AppSettings
import io.github.elli0t0.proxyshare.data.BUILTIN_RULE_SET_ID
import io.github.elli0t0.proxyshare.data.RuleAction
import io.github.elli0t0.proxyshare.data.RuleEntry
import io.github.elli0t0.proxyshare.data.RuleSetMeta
import io.github.elli0t0.proxyshare.data.SettingsRepository
import io.github.elli0t0.proxyshare.data.fetchList
import io.github.elli0t0.proxyshare.data.isSubscriptionStale
import io.github.elli0t0.proxyshare.data.loadRuleSetFileText
import io.github.elli0t0.proxyshare.data.pushRuleSets
import io.github.elli0t0.proxyshare.data.readBuiltinRuleSetText
import io.github.elli0t0.proxyshare.data.saveRuleSetFileText
import io.github.elli0t0.proxyshare.data.SubscriptionUpdate
import io.github.elli0t0.proxyshare.data.updateSubscriptionContent
import io.github.elli0t0.proxyshare.data.UsageHistoryStore
import io.github.elli0t0.proxyshare.data.VpnWatcher
import io.github.elli0t0.proxyshare.data.networkChanges
import io.github.elli0t0.proxyshare.data.toEngineText
import io.github.elli0t0.proxyshare.engine.Engine
import io.github.elli0t0.proxyshare.engine.EngineEvent
import io.github.elli0t0.proxyshare.engine.EngineState
import io.github.elli0t0.proxyshare.engine.NativeEngine
import io.github.elli0t0.proxyshare.engine.Snapshot
import io.github.elli0t0.proxyshare.engine.SpeedSample
import io.github.elli0t0.proxyshare.ui.screens.SessionLogEntry
import io.github.elli0t0.proxyshare.ui.screens.parseSessionLog
import io.github.elli0t0.proxyshare.ui.screens.pushLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Result of the user-triggered exit-IP check. */
sealed interface ExitIpResult {
    data class Success(val ip: String) : ExitIpResult
    data class Failure(val failure: ExitIpFailure) : ExitIpResult
}

/**
 * Clients blocked until the proxy restarts (one IP each). Pure: no
 * coroutines, no flows. The engine holds the same set; this mirrors
 * it so the UI can badge blocked clients without polling.
 */
class DeniedClients {
    private val set = HashSet<String>()

    fun deny(ip: String) {
        set += ip
    }

    fun allow(ip: String) {
        set -= ip
    }

    fun isDenied(ip: String): Boolean = ip in set

    fun snapshot(): Set<String> = set.toSet()

    fun clear() {
        set.clear()
    }
}

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

    private val _uiVisible = MutableStateFlow(false)
    /** True while an activity is in the foreground (MainActivity onStart/onStop). */
    val uiVisible: StateFlow<Boolean> = _uiVisible.asStateFlow()

    private val engine = Engine(scope, _uiVisible.asStateFlow())
    private val startMutex = Mutex()

    private var repository: SettingsRepository? = null
    private var selector: NetworkSelector? = null
    private var appContext: Context? = null
    /** The built-in rule-set asset, read once and cached (1.1 MB). */
    private var builtinRuleSetText: String? = null
    private var started = false
    private var networkJob: Job? = null
    private var directNetworkJob: Job? = null
    private var vpnJob: Job? = null

    private var usageStore: UsageHistoryStore? = null
    private val usageMutex = Mutex()
    private var flushedUp = 0L
    private var flushedDown = 0L

    private val _usageHistory =
        MutableStateFlow<Map<String, Pair<Long, Long>>>(emptyMap())
    /** Per-day up/down totals (last 35 days), persisted. */
    val usageHistory: StateFlow<Map<String, Pair<Long, Long>>> =
        _usageHistory.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    /** The current settings (defaults until the DataStore read lands). */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _network = MutableStateFlow<Network?>(null)
    /** The network matching the chosen outbound mode, if any. */
    val network: StateFlow<Network?> = _network.asStateFlow()

    private val deniedClientsState = DeniedClients()
    private val _deniedClients = MutableStateFlow<Set<String>>(emptySet())
    /** Client IPs blocked until the proxy restarts (in-memory only). */
    val deniedClients: StateFlow<Set<String>> = _deniedClients.asStateFlow()

    private val _sessionLog = MutableStateFlow<List<SessionLogEntry>>(emptyList())
    /** Last 500 closed connections (metadata only). In-memory only, never persisted. Pulled from the engine by the Logs tab. */
    val sessionLog: StateFlow<List<SessionLogEntry>> = _sessionLog.asStateFlow()

    /** Highest session-log id pulled so far (the engine's ids only increase). */
    private var lastLogId = 0L

    /** Pull session-log records newer than [lastLogId] from the engine and append them (capped at 500). No-op when nothing is new. Called by the Logs tab while it is visible. */
    suspend fun pullSessionLog() {
        val text = engine.sessionLogSince(lastLogId)
        val entries = parseSessionLog(text, System.currentTimeMillis())
        if (entries.isEmpty()) return
        lastLogId = entries.maxOf { it.first }
        _sessionLog.update { log ->
            entries.fold(log) { acc, (_, entry) -> pushLogEntry(acc, entry) }
        }
    }

    /** Clear the session log, in the engine and locally. */
    fun clearSessionLog() {
        engine.clearSessionLog()
        lastLogId = 0L
        _sessionLog.value = emptyList()
    }

    /** Drop all recorded usage history (Logs > Usage > Reset usage). */
    fun clearUsage() {
        scope.launch { usageStore?.clear() }
    }

    private val _restartNeeded = MutableStateFlow(false)
    /** An engine setting changed while running: the proxy needs a restart to apply it. Cleared on start. */
    val restartNeeded: StateFlow<Boolean> = _restartNeeded.asStateFlow()

    /** Result of the last rules push to the engine (0.6.7). */
    sealed interface RulesStatus {
        /** Nothing pushed yet (proxy never started, or no change). */
        data object Idle : RulesStatus
        /** The engine accepted the rules: how many lines it parsed. */
        data class Applied(val count: Int) : RulesStatus
        /** The engine rejected the text: its "error line N: msg" verbatim. */
        data class Error(val text: String) : RulesStatus
    }

    private val _rulesStatus = MutableStateFlow<RulesStatus>(RulesStatus.Idle)
    /** Result of the last [pushRules]: "Applied N rules" or the engine's error line. */
    val rulesStatus: StateFlow<RulesStatus> = _rulesStatus.asStateFlow()

    /**
     * Push [rules] to the engine (hot, no restart). No-op while
     * stopped: the start path pushes the current rules after
     * engine.start, and the rules job pushes on every change while
     * running. The engine validates the text atomically, so a bad
     * rule rejects the whole set and the previous rules stay live;
     * the error line is kept in [rulesStatus] for the Rules page.
     */
    fun pushRules(rules: List<RuleEntry> = _settings.value.rules) {
        if (!started) return
        val result = engine.setRules(rules.toEngineText())
        _rulesStatus.value = if (result.startsWith("ok ")) {
            RulesStatus.Applied(result.removePrefix("ok ").trim().toIntOrNull() ?: rules.size)
        } else {
            RulesStatus.Error(result)
        }
    }

    /**
     * Push the named rule sets to the engine (hot, no restart).
     * No-op while stopped: the start path pushes the current sets
     * after engine.start, and the rule-sets job pushes on every
     * change while running. The built-in list is read from the asset
     * once and cached (1.1 MB); a custom set is read from its file.
     * A bad list rejects only that set in the engine; the error is
     * not shown anywhere (the importer only sends parsed entries),
     * so a failure here is unexpected.
     */
    fun pushRuleSets(sets: List<RuleSetMeta> = _settings.value.ruleSets) {
        if (!started) return
        scope.launch {
            pushRuleSets(sets, ::loadRuleSetText) { id, action, text ->
                engine.setRuleSet(id, action, text)
            }
        }
    }

    /**
     * Clear one set in the engine (used when a set is deleted: the
     * rule-sets job only pushes sets still in the metadata, so a
     * deleted set would otherwise stay live). No-op while stopped:
     * a fresh start only pushes the sets still listed.
     */
    fun clearRuleSet(id: String, action: RuleAction) {
        if (!started) return
        engine.setRuleSet(id, action.wire, "")
    }

    /**
     * Refresh one subscription from its URL (0.6.9). Runs on the
     * DEFAULT network (the fetch may go through the phone's VPN, that
     * is expected). A failed download, or one with 0 valid lines,
     * keeps the old content and records a short error label (never
     * the URL) under the subscription. The rule-sets job pushes the
     * new content to the engine when the metadata changes.
     */
    fun refreshSubscription(id: String) {
        scope.launch {
            val ctx = appContext ?: return@launch
            val meta = _settings.value.ruleSets
                .firstOrNull { it.id == id && it.url.isNotBlank() } ?: return@launch
            val update = updateSubscriptionContent(fetchList(meta.url), meta.action)
            if (update is SubscriptionUpdate.Updated) {
                withContext(Dispatchers.IO) {
                    ctx.saveRuleSetFileText(id, update.text)
                }
            }
            val now = System.currentTimeMillis()
            val updated = when (update) {
                is SubscriptionUpdate.Updated ->
                    meta.copy(count = update.count, lastUpdatedMs = now, lastError = "")
                is SubscriptionUpdate.KeptOld ->
                    meta.copy(lastError = update.reason)
            }
            updateSettings {
                updateRuleSets(
                    _settings.value.ruleSets.map {
                        if (it.id == id) updated else it
                    }
                )
            }
        }
    }

    /** Refresh every subscription (0.6.9). Each one fetches in its own coroutine. */
    fun refreshAllSubscriptions() {
        for (meta in _settings.value.ruleSets) {
            if (meta.url.isNotBlank()) refreshSubscription(meta.id)
        }
    }

    /** Refresh the subscriptions whose last update is older than 24 h (0.6.9). */
    fun refreshStaleSubscriptions() {
        val now = System.currentTimeMillis()
        for (meta in _settings.value.ruleSets) {
            if (meta.url.isNotBlank() && isSubscriptionStale(meta.lastUpdatedMs, now)) {
                refreshSubscription(meta.id)
            }
        }
    }

    /** A set's content by id: the asset (built-in, cached) or its file. Runs on Dispatchers.IO via pushRuleSets. */
    private fun loadRuleSetText(id: String): String {
        val ctx = appContext ?: return ""
        return if (id == BUILTIN_RULE_SET_ID) {
            builtinRuleSetText ?: ctx.readBuiltinRuleSetText().also { builtinRuleSetText = it }
        } else {
            ctx.loadRuleSetFileText(id)
        }
    }

    /** Mark that an engine setting changed while running (shows the restart banner in Settings). */
    fun markRestartNeeded() {
        _restartNeeded.value = true
    }

    /** Clear the restart flag (the banner's Restart button). */
    fun clearRestartNeeded() {
        _restartNeeded.value = false
    }

    /** Abort every open connection from this client IP (the client can reconnect). */
    suspend fun disconnectClient(ip: String) {
        engine.closeClient(ip)
    }

    /** Block a client until the proxy restarts: deny first, then close its connections. */
    suspend fun denyClient(ip: String) {
        engine.denyClient(ip)
        engine.closeClient(ip)
        deniedClientsState.deny(ip)
        _deniedClients.value = deniedClientsState.snapshot()
    }

    /** Lift a block set by denyClient. */
    suspend fun allowClient(ip: String) {
        engine.allowClient(ip)
        deniedClientsState.allow(ip)
        _deniedClients.value = deniedClientsState.snapshot()
    }

    /** The engine diagnostics log text (see docs/ENGINE_API.md), read on IO. */
    suspend fun diagLog(): String = withContext(Dispatchers.IO) {
        NativeEngine.nativeDiagLog()
    }

    private val _diagLogText = MutableStateFlow("")
    /** Latest diagnostics log text, refreshed by the Logs screen while it is visible. */
    val diagLogText: StateFlow<String> = _diagLogText.asStateFlow()

    /** Re-read the engine diagnostics log into [diagLogText]. */
    suspend fun refreshDiag() {
        _diagLogText.value = diagLog()
    }

    /** Clear the engine diagnostics ring buffer and the cached text. */
    fun clearDiagLog() {
        NativeEngine.nativeDiagClear()
        _diagLogText.value = ""
    }

    /** Rolling 2-minute speed history (120 samples at 1 Hz) from the engine sampler, pulled with the snapshot (same 1 s poll, same visibility gate). */
    val history: StateFlow<List<SpeedSample>> = engine.history

    val state: StateFlow<EngineState> = engine.state
    val snapshot: StateFlow<Snapshot> = engine.snapshot
    val events: SharedFlow<EngineEvent> = engine.events

    /** Poll the snapshot (and history) once right now (e.g. when the service starts, so the wake lock sees a fresh active count). */
    suspend fun pollNow() {
        engine.pollNow()
    }

    /** Called by MainActivity onStart/onStop. */
    fun setUiVisible(v: Boolean) {
        _uiVisible.value = v
    }

    /** Persist the bytes moved since the last flush into today's usage totals. No-op when nothing moved. Safe to call when stopped. Serialized: stop() and the service's onDestroy can flush at the same time, and a delta must never be counted twice. */
    suspend fun flushUsage() {
        usageMutex.withLock {
            val store = usageStore ?: return
            val snap = snapshot.value
            val up = (snap.liveUp - flushedUp).coerceAtLeast(0)
            val down = (snap.liveDown - flushedDown).coerceAtLeast(0)
            if (up > 0 || down > 0) {
                store.add(up, down)
            }
            flushedUp = snap.liveUp
            flushedDown = snap.liveDown
        }
    }

    /** Push the live VPN state into the engine while running (require_vpn reads it). */
    private fun startVpnJob() {
        vpnJob?.cancel()
        vpnJob = scope.launch {
            VpnWatcher.active.collect { active ->
                engine.setVpnActive(active)
            }
        }
    }

    private fun stopVpnJob() {
        vpnJob?.cancel()
        vpnJob = null
    }

    private fun stopDirectNetworkJob() {
        directNetworkJob?.cancel()
        directNetworkJob = null
    }

    /** Called once from ProxyShareApp.onCreate. Safe to call twice. */
    fun init(context: Context) {
        if (repository != null) return
        val appContext = context.applicationContext
        this.appContext = appContext
        val repo = SettingsRepository(appContext)
        repository = repo
        val store = UsageHistoryStore(appContext)
        usageStore = store
        scope.launch {
            store.history.collect { _usageHistory.value = it }
        }
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
        // Push the rules to the engine whenever they change while
        // running (hot, no restart). distinctUntilChanged so an
        // unrelated settings change does not re-push the same text.
        scope.launch {
            repo.settings.map { it.rules }.distinctUntilChanged().collect {
                if (started) pushRules(it)
            }
        }
        // Same for the named rule sets (0.6.8): pushed on every
        // metadata change while running (enable/disable, import,
        // create, edit). The content is read from the asset/file
        // inside pushRuleSets, on Dispatchers.IO.
        scope.launch {
            repo.settings.map { it.ruleSets }.distinctUntilChanged().collect {
                if (started) pushRuleSets(it)
            }
        }
    }

    /** Read the settings, generate a password on first run, start the engine. */
    fun start() {
        scope.launch {
            startMutex.withLock { startLocked() }
        }
    }

    /** Stop the engine and start it again with the current settings. No-op when stopped. */
    fun restart() {
        scope.launch {
            startMutex.withLock {
                if (!started) return@withLock
                runCatching { engine.stop() }
                flushUsage()
                networkJob?.cancel()
                networkJob = null
                stopVpnJob()
                stopDirectNetworkJob()
                started = false
                startLocked()
            }
        }
    }

    private suspend fun startLocked() {
        if (started) return
        val repo = repository ?: return
        val selector = selector ?: return
        started = true
        deniedClientsState.clear()
        _deniedClients.value = emptySet()
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
                    return
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
                        flushUsage()
                        stopVpnJob()
                        stopDirectNetworkJob()
                        engine.setError("VPN lost — proxy stopped")
                        networkJob?.cancel()
                        return@collect
                    }
                    engine.setOutboundNetwork(net?.networkHandle ?: 0L)
                }
            }
            // The DIRECT network (0.6.6): set on start and on every
            // network change (hotspot toggle, Wi-Fi connect/disconnect).
            // networkChanges emits once immediately, so it is set
            // before the engine starts.
            directNetworkJob?.cancel()
            directNetworkJob = scope.launch {
                val ctx = appContext ?: return@launch
                networkChanges(ctx).collect {
                    if (!started) return@collect
                    engine.setDirectNetwork(selector.directNetwork()?.networkHandle ?: 0L)
                }
            }
            val error = engine.start(settings)
            if (error.isNotEmpty()) {
                started = false
                networkJob?.cancel()
                networkJob = null
                stopVpnJob()
                stopDirectNetworkJob()
            } else {
                // A fresh start applies the current settings.
                _restartNeeded.value = false
                flushedUp = 0L
                flushedDown = 0L
                startVpnJob()
                // Rules (0.6.7): pushed on start, then on every
                // change while running (see the rules job in init).
                pushRules(settings.rules)
                // Rule sets (0.6.8): same, via the rule-sets job.
                pushRuleSets(settings.ruleSets)
                // Subscriptions (0.6.9): refresh once on start when
                // the last update is older than 24 h. No background
                // worker: this is the only automatic refresh.
                refreshStaleSubscriptions()
            }
        } catch (_: Exception) {
            started = false
            networkJob?.cancel()
            networkJob = null
            stopVpnJob()
            stopDirectNetworkJob()
            runCatching { engine.stop() }
        }
    }

    /** Stop the engine. Also resets an Error state back to Stopped. */
    fun stop() {
        scope.launch {
            startMutex.withLock {
                networkJob?.cancel()
                networkJob = null
                stopVpnJob()
                stopDirectNetworkJob()
                deniedClientsState.clear()
                _deniedClients.value = emptySet()
                selector?.select(OutboundMode.DEFAULT)
                if (started) {
                    started = false
                    runCatching { engine.stop() }
                    flushUsage()
                } else if (engine.state.value is EngineState.Error) {
                    runCatching { engine.stop() }
                }
            }
        }
    }

    /** Change the outbound mode. Only used while stopped (the UI enforces it). */
    suspend fun setOutboundMode(mode: OutboundMode) {
        repository?.updateOutboundMode(mode.toSettingString())
    }

    /** Run a settings update (saves immediately). The Settings screen calls this for every change. */
    suspend fun updateSettings(block: suspend SettingsRepository.() -> Unit) {
        repository?.block()
    }

    /**
     * User-triggered exit-IP check, bound to the selected network when
     * there is one. The custom URL (if set) is tried first, then the
     * built-in endpoints.
     */
    suspend fun checkExitIp(): ExitIpResult {
        val repo = repository ?: return ExitIpResult.Failure(ExitIpFailure.UNREACHABLE)
        val settings = repo.settings.first()
        val urls = (listOfNotNull(settings.exitIpUrl.trim().takeIf { it.isNotEmpty() }) +
            EXIT_IP_ENDPOINTS).distinct()
        return try {
            ExitIpResult.Success(checkExitIp(urls, network.value))
        } catch (e: ExitIpCheckException) {
            ExitIpResult.Failure(e.failure)
        } catch (_: Exception) {
            ExitIpResult.Failure(ExitIpFailure.UNREACHABLE)
        }
    }
}
