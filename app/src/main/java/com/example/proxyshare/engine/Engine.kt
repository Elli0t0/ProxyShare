package com.example.proxyshare.engine

import com.example.proxyshare.data.AppSettings
import com.example.proxyshare.data.toEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One connection from the engine snapshot. */
data class ConnInfo(
    val id: Long,
    val client: String,
    val host: String,
    val port: Int,
    val proto: String,
    val startedMs: Long,
    val up: Long,
    val down: Long,
    /** True when the connection was refused for a blocked domain. */
    val blocked: Boolean = false,
)

/** The whole snapshot: totals plus one line per active connection. */
data class Snapshot(
    val state: String,
    val totalUp: Long,
    val totalDown: Long,
    val active: Int,
    val connections: List<ConnInfo>,
) {
    /**
     * Totals including active connections. The engine reports totalUp/
     * totalDown for closed connections only, so the live total is the
     * closed total plus what the active connections moved so far.
     * Monotonic: bytes just move from active to closed.
     */
    val liveUp: Long get() = totalUp + connections.sumOf { it.up }
    val liveDown: Long get() = totalDown + connections.sumOf { it.down }
}

/**
 * Session totals: the native counters are cumulative across restarts
 * in one process, so the totals seen at start are the baseline and
 * the display shows only what this session moved.
 */
fun sessionTotals(rawUp: Long, rawDown: Long, baselineUp: Long, baselineDown: Long): Pair<Long, Long> =
    (rawUp - baselineUp).coerceAtLeast(0) to (rawDown - baselineDown).coerceAtLeast(0)

/** The engine's lifecycle state, mirroring the Rust EngineState. */
sealed interface EngineState {
    data object Stopped : EngineState
    data object Starting : EngineState
    data object Running : EngineState
    data class Error(val message: String) : EngineState
}

/** Map a Rust state name ("stopped", "starting", "running", "error") to EngineState. */
fun engineStateFromName(name: String, message: String = ""): EngineState = when (name) {
    "starting" -> EngineState.Starting
    "running" -> EngineState.Running
    "error" -> EngineState.Error(message)
    else -> EngineState.Stopped
}

/** Parse one connection line: the 9 tab-separated fields of a snapshot line, or the payload (b) of a type 2/3/4 event. The 9th field is "1" when the connection was refused for a blocked domain (absent in older lines: false). Null when malformed. */
fun parseConnLine(line: String): ConnInfo? {
    val f = line.split('\t')
    if (f.size < 8) return null
    return ConnInfo(
        id = f[0].toLongOrNull() ?: return null,
        client = f[1], host = f[2],
        port = f[3].toIntOrNull() ?: return null,
        proto = f[4],
        startedMs = f[5].toLongOrNull() ?: return null,
        up = f[6].toLongOrNull() ?: return null,
        down = f[7].toLongOrNull() ?: return null,
        blocked = f.getOrNull(8) == "1",
    )
}

/**
 * Parse the snapshot text from nativeSnapshot (see docs/ENGINE_API.md):
 * first line "state<TAB>total_up<TAB>total_down<TAB>active", then one
 * tab-separated line per connection: id, client, host, port, proto,
 * started_ms, up, down, blocked. Returns null when there is no usable head
 * line; a malformed connection line is skipped, never a crash.
 */
fun parseSnapshot(text: String): Snapshot? {
    val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
    val head = lines.firstOrNull()?.split('\t') ?: return null
    if (head.size < 4) return null
    val conns = lines.drop(1).mapNotNull { parseConnLine(it) }
    return Snapshot(
        state = head[0],
        totalUp = head[1].toLongOrNull() ?: 0L,
        totalDown = head[2].toLongOrNull() ?: 0L,
        active = head[3].toIntOrNull() ?: 0,
        connections = conns,
    )
}

/** One event from the engine (see docs/ENGINE_API.md for the type numbers). */
data class EngineEvent(val type: Int, val a: String, val b: String)

/** One diagnostics line from the engine ring buffer. */
data class DiagLine(val tsMs: Long, val level: String, val msg: String)

/**
 * Parse the diagnostics wire text from nativeDiagLog (see
 * docs/ENGINE_API.md): one `ts_ms<TAB>LEVEL<TAB>message` line each,
 * oldest first. Malformed lines are skipped, never a crash.
 */
fun parseDiagLog(text: String): List<DiagLine> =
    text.lineSequence().filter { it.isNotEmpty() }.mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 3) return@mapNotNull null
        val ts = f[0].toLongOrNull() ?: return@mapNotNull null
        DiagLine(ts, f[1], f[2])
    }.toList()

/**
 * Redact anything resembling credentials from log text before sharing.
 * The engine never logs the password, but this is the safety net for
 * the share path: key=value secrets, userinfo in URLs, and
 * Proxy-Authorization headers are replaced with ***.
 */
fun redactLog(text: String): String {
    var out = text
    out = out.replace(Regex("(?i)(password|passwd|pass|pwd|token|secret)[=:][^\\s]*"), "$1=***")
    out = out.replace(Regex("://[^\\s/@:]+:[^\\s/@]+@"), "://***@")
    out = out.replace(Regex("(?i)Proxy-Authorization:[^\\n]*"), "Proxy-Authorization: ***")
    return out
}

/**
 * Kotlin wrapper that owns the native engine handle.
 *
 * start(settings) and stop() run on Dispatchers.IO; close() destroys
 * the handle. While running, nativeSnapshot is polled every 1 s, but
 * only while the UI is visible ([uiVisible]): the poll loop suspends
 * with zero wakeups while hidden. Events arrive on a native thread:
 * they are only tryEmit-ted here, never blocking, and never touch
 * the UI.
 */
class Engine(
    private val scope: CoroutineScope,
    private val uiVisible: StateFlow<Boolean> = MutableStateFlow(true),
) {

    private val handle: Long = NativeEngine.nativeCreate()

    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow(
        Snapshot(state = "stopped", totalUp = 0L, totalDown = 0L, active = 0, connections = emptyList())
    )
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    // Events arrive on a native thread: never block, never touch the UI here.
    private val _events = MutableSharedFlow<EngineEvent>(
        extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private val listener = object : EngineListener {
        override fun onEvent(type: Int, a: String, b: String) {
            if (type == 1) {
                _state.value = engineStateFromName(a, b)
            }
            _events.tryEmit(EngineEvent(type, a, b))
        }
    }

    private var pollJob: Job? = null
    private var closed = false

    // The native totals are cumulative across restarts: the totals seen
    // at start are the baseline, so the display resets each session.
    private var baselineUp = 0L
    private var baselineDown = 0L
    private var lastRawUp = 0L
    private var lastRawDown = 0L

    init {
        NativeEngine.nativeSetListener(handle, listener)
    }

    /** Set the state to Error without starting the engine (e.g. no network for the chosen outbound mode, or the VPN kill switch). */
    fun setError(message: String) {
        _state.value = EngineState.Error(message)
    }

    /** Set the Android Network handle for outbound TCP connections (0 = default network). No-op after close. */
    fun setOutboundNetwork(network: Long) {
        if (closed) return
        NativeEngine.nativeSetOutboundNetwork(handle, network)
    }

    /**
     * Start the engine with these settings. Returns "" on success, or
     * an error text. On success the snapshot is polled until stop().
     */
    suspend fun start(settings: AppSettings): String {
        _state.value = EngineState.Starting
        val error = withContext(Dispatchers.IO) {
            NativeEngine.nativeStart(handle, settings.toEngineConfig())
        }
        if (error.isNotEmpty()) {
            _state.value = EngineState.Error(error)
            return error
        }
        baselineUp = lastRawUp
        baselineDown = lastRawDown
        _state.value = EngineState.Running
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                // Suspends with zero wakeups while the UI is hidden.
                uiVisible.first { it }
                pollOnce()
                delay(1000)
            }
        }
        return ""
    }

    /** Stop the engine and the snapshot polling. */
    suspend fun stop() {
        pollJob?.cancel()
        pollJob = null
        withContext(Dispatchers.IO) {
            NativeEngine.nativeStop(handle)
        }
        pollOnce()
        _state.value = EngineState.Stopped
    }

    /** Poll the snapshot once right now (e.g. when the UI becomes visible again). */
    suspend fun pollNow() {
        pollOnce()
    }

    /** Abort every open connection from one client IP. No-op after close. */
    suspend fun closeClient(ip: String) {
        if (closed) return
        withContext(Dispatchers.IO) {
            NativeEngine.nativeCloseClient(handle, ip)
        }
    }

    /** Refuse a client until the proxy restarts (existing connections stay open). No-op after close. */
    suspend fun denyClient(ip: String) {
        if (closed) return
        withContext(Dispatchers.IO) {
            NativeEngine.nativeDenyClient(handle, ip)
        }
    }

    /** Lift a block set by denyClient. No-op after close. */
    suspend fun allowClient(ip: String) {
        if (closed) return
        withContext(Dispatchers.IO) {
            NativeEngine.nativeAllowClient(handle, ip)
        }
    }

    /** Destroy the native handle. Safe to call more than once. */
    fun close() {
        if (closed) return
        closed = true
        pollJob?.cancel()
        pollJob = null
        NativeEngine.nativeDestroy(handle)
    }

    private suspend fun pollOnce() {
        val text = withContext(Dispatchers.IO) {
            NativeEngine.nativeSnapshot(handle)
        }
        parseSnapshot(text)?.let { snap ->
            lastRawUp = snap.totalUp
            lastRawDown = snap.totalDown
            val (up, down) = sessionTotals(snap.totalUp, snap.totalDown, baselineUp, baselineDown)
            val adjusted = snap.copy(totalUp = up, totalDown = down)
            _snapshot.value = adjusted
            _state.value = engineStateFromName(adjusted.state)
        }
    }
}
