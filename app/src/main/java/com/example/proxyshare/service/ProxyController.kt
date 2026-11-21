package com.example.proxyshare.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.example.proxyshare.data.AppSettings
import com.example.proxyshare.data.SettingsRepository
import com.example.proxyshare.engine.Engine
import com.example.proxyshare.engine.EngineEvent
import com.example.proxyshare.engine.EngineState
import com.example.proxyshare.engine.Snapshot
import com.example.proxyshare.engine.parseConnLine
import com.example.proxyshare.ui.screens.SessionLogEntry
import com.example.proxyshare.ui.screens.pushLogEntry
import com.example.proxyshare.ui.screens.sessionLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Live speed sample, in bytes per second. */
data class SpeedSample(val upBps: Long, val downBps: Long)

/**
 * One speed step: raw bytes-per-second from the delta over the actual
 * elapsed time, smoothed with EMA (alpha 0.5). A direction with no new
 * bytes snaps to exactly 0 instead of decaying forever.
 */
fun smoothSpeed(
    prevUp: Long,
    prevDown: Long,
    nowUp: Long,
    nowDown: Long,
    elapsedMs: Long,
    prev: SpeedSample,
): SpeedSample {
    val seconds = elapsedMs.coerceAtLeast(1) / 1000.0
    val upDelta = nowUp - prevUp
    val downDelta = nowDown - prevDown
    val upBps = if (upDelta <= 0) {
        0
    } else {
        (0.5 * (upDelta / seconds) + 0.5 * prev.upBps).toLong()
    }
    val downBps = if (downDelta <= 0) {
        0
    } else {
        (0.5 * (downDelta / seconds) + 0.5 * prev.downBps).toLong()
    }
    return SpeedSample(upBps, downBps)
}

/** Append one sample to the rolling history, capped at [max] newest, order preserved. */
fun pushSample(
    history: List<SpeedSample>,
    sample: SpeedSample,
    max: Int = 120,
): List<SpeedSample> = (history + sample).takeLast(max.coerceAtLeast(1))

/** Sampler state carried between 1 Hz ticks. */
data class SamplerState(
    val prevUp: Long,
    val prevDown: Long,
    val prevTimeMs: Long,
    val prev: SpeedSample,
)

/**
 * Fresh baseline after the UI comes back: totals from the current
 * snapshot, clock now, sample zeroed — so the first tick after a
 * hidden period cannot report a fake spike from bytes that moved
 * while the UI was hidden.
 */
fun rebaseline(liveUp: Long, liveDown: Long, nowMs: Long): SamplerState =
    SamplerState(liveUp, liveDown, nowMs, SpeedSample(0, 0))

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
    private var started = false
    private var networkJob: Job? = null
    private var speedJob: Job? = null

    private val _settings = MutableStateFlow(AppSettings())
    /** The current settings (defaults until the DataStore read lands). */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _network = MutableStateFlow<Network?>(null)
    /** The network matching the chosen outbound mode, if any. */
    val network: StateFlow<Network?> = _network.asStateFlow()

    private val _speed = MutableStateFlow(SpeedSample(0, 0))
    /** Live upload/download speed, sampled once per second while running. 0 when stopped. */
    val speed: StateFlow<SpeedSample> = _speed.asStateFlow()

    private val _activeConns = MutableStateFlow(0)
    /** Active connections, tracked from engine events (type 2 opened, 3 closed). */
    val activeConns: StateFlow<Int> = _activeConns.asStateFlow()

    private val _sessionLog = MutableStateFlow<List<SessionLogEntry>>(emptyList())
    /** Last 500 closed connections (metadata only). In-memory only, never persisted. */
    val sessionLog: StateFlow<List<SessionLogEntry>> = _sessionLog.asStateFlow()

    /** Clear the session log (Connections overflow menu). */
    fun clearSessionLog() {
        _sessionLog.value = emptyList()
    }

    private val _history = MutableStateFlow<List<SpeedSample>>(emptyList())
    /** Rolling 2-minute speed history (120 samples at 1 Hz), cleared on every start. */
    val history: StateFlow<List<SpeedSample>> = _history.asStateFlow()

    val state: StateFlow<EngineState> = engine.state
    val snapshot: StateFlow<Snapshot> = engine.snapshot
    val events: SharedFlow<EngineEvent> = engine.events

    /**
     * Sample the live totals once per second while running AND the UI
     * is visible. While hidden the loop suspends at uiVisible.first
     * (zero wakeups) and pushes no samples; on return it polls once
     * and re-baselines so the first tick cannot spike.
     */
    private fun startSpeedSampler() {
        speedJob?.cancel()
        _history.value = emptyList()
        speedJob = scope.launch {
            var state = SamplerState(
                engine.snapshot.value.liveUp,
                engine.snapshot.value.liveDown,
                SystemClock.elapsedRealtime(),
                SpeedSample(0, 0),
            )
            while (isActive) {
                uiVisible.first { it }
                engine.pollNow()
                state = rebaseline(
                    engine.snapshot.value.liveUp,
                    engine.snapshot.value.liveDown,
                    SystemClock.elapsedRealtime(),
                )
                while (isActive && uiVisible.value) {
                    delay(1000)
                    val now = SystemClock.elapsedRealtime()
                    val snap = engine.snapshot.value
                    val sample = smoothSpeed(
                        state.prevUp, state.prevDown, snap.liveUp, snap.liveDown,
                        now - state.prevTimeMs, state.prev,
                    )
                    _speed.value = sample
                    _history.value = pushSample(_history.value, sample)
                    state = SamplerState(snap.liveUp, snap.liveDown, now, sample)
                }
            }
        }
    }

    private fun stopSpeedSampler() {
        speedJob?.cancel()
        speedJob = null
        _speed.value = SpeedSample(0, 0)
    }

    /** Called by MainActivity onStart/onStop. */
    fun setUiVisible(v: Boolean) {
        _uiVisible.value = v
    }

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
        // Active connections from engine events (no polling). The
        // SharedFlow drops old events, so resync from the snapshot
        // whenever the UI becomes visible.
        scope.launch {
            events.collect { e ->
                when (e.type) {
                    2 -> _activeConns.update { it + 1 }
                    3 -> {
                        _activeConns.update { (it - 1).coerceAtLeast(0) }
                        // Type 3 payload (b) is the connection's snapshot
                        // line: id, client, host, port, proto, started_ms,
                        // up, down. Only metadata, never payloads.
                        parseConnLine(e.b)?.let { conn ->
                            _sessionLog.update { log ->
                                pushLogEntry(log, sessionLogEntry(conn, System.currentTimeMillis()))
                            }
                        }
                    }
                }
            }
        }
        scope.launch {
            uiVisible.collect { visible ->
                if (visible) _activeConns.value = engine.snapshot.value.active
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
                networkJob?.cancel()
                networkJob = null
                stopSpeedSampler()
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
        _activeConns.value = 0
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
                        _activeConns.value = 0
                        runCatching { engine.stop() }
                        stopSpeedSampler()
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
                stopSpeedSampler()
            } else {
                startSpeedSampler()
            }
        } catch (_: Exception) {
            started = false
            networkJob?.cancel()
            networkJob = null
            stopSpeedSampler()
            runCatching { engine.stop() }
        }
    }

    /** Stop the engine. Also resets an Error state back to Stopped. */
    fun stop() {
        scope.launch {
            startMutex.withLock {
                networkJob?.cancel()
                networkJob = null
                stopSpeedSampler()
                _activeConns.value = 0
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

    /** Change the outbound mode. Only used while stopped (the UI enforces it). */
    suspend fun setOutboundMode(mode: OutboundMode) {
        repository?.updateOutboundMode(mode.toSettingString())
    }

    /** Run a settings update (saves immediately). The Settings screen calls this for every change. */
    suspend fun updateSettings(block: suspend SettingsRepository.() -> Unit) {
        repository?.block()
    }

    /** User-triggered exit-IP check, bound to the selected network when there is one. */
    suspend fun checkExitIp(): Result<String> = runCatching {
        val repo = repository ?: return@runCatching ""
        val settings = repo.settings.first()
        checkExitIp(settings.exitIpUrl, network.value)
    }
}
