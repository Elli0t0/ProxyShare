package io.github.elli0t0.proxyshare.engine

import io.github.elli0t0.proxyshare.data.AppSettings
import io.github.elli0t0.proxyshare.data.toEngineConfig
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
import java.util.concurrent.atomic.AtomicLong

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
    /** How the connection is routed: "proxy" | "direct" | "block" (0.6.6). */
    val route: String = "proxy",
)

/** The whole snapshot: totals plus one line per active connection. */
data class Snapshot(
    val state: String,
    val totalUp: Long,
    val totalDown: Long,
    val active: Int,
    val connections: List<ConnInfo>,
    /** Connections refused since start: the limit / a denied client / a client outside allowed_clients. */
    val rejectedLimit: Long = 0,
    val rejectedDenied: Long = 0,
    val rejectedNotAllowed: Long = 0,
    /**
     * Engine-wide live totals (monotonic, including active and closed
     * connections), read straight from the engine: Kotlin no longer
     * adds the active connections' bytes to the closed totals itself.
     */
    val liveUp: Long = 0,
    val liveDown: Long = 0,
    /** Distinct client devices (one per IP). */
    val devices: Int = 0,
    /** Latest 1 s speed samples from the engine sampler (bytes/s). */
    val speedUp: Long = 0,
    val speedDown: Long = 0,
    /** False when the PAC server failed to bind (0.6.5); true when it is off or up. */
    val pacOk: Boolean = true,
    /** Connections refused by a rule-set Block since the process started (0.6.8). */
    val blockedBySet: Long = 0,
)

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

/** Parse one connection line: the 10 tab-separated fields of a snapshot line, or the payload (b) of a type 2/3/4 event. The 9th field is "1" when the connection was refused for a blocked domain (absent in older lines: false). The 10th field is the route ("proxy" | "direct" | "block", absent in older lines: "proxy"). Null when malformed. */
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
        route = f.getOrNull(9) ?: "proxy",
    )
}

/**
 * Parse the snapshot text from nativeSnapshot (see docs/ENGINE_API.md):
 * first line "state<TAB>total_up<TAB>total_down<TAB>active<TAB>rejected_limit<TAB>rejected_denied<TAB>rejected_not_allowed<TAB>live_up<TAB>live_down<TAB>devices<TAB>speed_up<TAB>speed_down<TAB>pac_ok",
 * then one tab-separated line per connection: id, client, host, port,
 * proto, started_ms, up, down, blocked. The trailing head fields are
 * optional (older snapshots lack them: 0, pac_ok: true). Returns null when there is
 * no usable head line; a malformed connection line is skipped, never
 * a crash.
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
        rejectedLimit = head.getOrNull(4)?.toLongOrNull() ?: 0L,
        rejectedDenied = head.getOrNull(5)?.toLongOrNull() ?: 0L,
        rejectedNotAllowed = head.getOrNull(6)?.toLongOrNull() ?: 0L,
        liveUp = head.getOrNull(7)?.toLongOrNull() ?: 0L,
        liveDown = head.getOrNull(8)?.toLongOrNull() ?: 0L,
        devices = head.getOrNull(9)?.toIntOrNull() ?: 0,
        speedUp = head.getOrNull(10)?.toLongOrNull() ?: 0L,
        speedDown = head.getOrNull(11)?.toLongOrNull() ?: 0L,
        pacOk = head.getOrNull(12)?.toIntOrNull() != 0,
        blockedBySet = head.getOrNull(13)?.toLongOrNull() ?: 0L,
    )
}

/** Live speed sample, in bytes per second. */
data class SpeedSample(val upBps: Long, val downBps: Long)

/**
 * Parse the speed history text from nativeHistory (see
 * docs/ENGINE_API.md): "up,down;up,down;…" oldest first, one pair per
 * second for the last 120 s. Malformed pairs are skipped, never a
 * crash; an empty text parses to an empty list.
 */
fun parseHistory(text: String): List<SpeedSample> {
    if (text.isBlank()) return emptyList()
    return text.split(';').mapNotNull { pair ->
        val f = pair.split(',')
        if (f.size != 2) return@mapNotNull null
        val up = f[0].toLongOrNull() ?: return@mapNotNull null
        val down = f[1].toLongOrNull() ?: return@mapNotNull null
        SpeedSample(up, down)
    }
}

/** One event from the engine (see docs/ENGINE_API.md for the type numbers). */
data class EngineEvent(val type: Int, val a: String, val b: String)

/** One diagnostics line from the engine ring buffer. `count` is how many times this exact line repeated in a row (1 = no repeat). */
data class DiagLine(
    val tsMs: Long,
    val level: String,
    val msg: String,
    val count: Int = 1,
    /**
     * Process-wide unique id, assigned at creation. Identical lines
     * (same ms, same text) must still get distinct LazyColumn keys,
     * or the list crashes with "Key ... was already used". It is a
     * constructor property so copy() — the merge path — keeps the old
     * id; tests compare content fields, or pass the parsed id in.
     */
    val id: Long = LogIds.next(),
)

/** Process-wide ids for log lines, so LazyColumn keys stay unique even for identical lines. */
object LogIds {
    private val n = AtomicLong()
    fun next(): Long = n.incrementAndGet()
}

/**
 * Parse the diagnostics wire text from nativeDiagLog (see
 * docs/ENGINE_API.md): one `ts_ms<TAB>LEVEL<TAB>message<TAB>count` line
 * each, oldest first. The count field is optional so lines written
 * before 0.4.4 still parse (they count as 1). Malformed lines are
 * skipped, never a crash.
 */
fun parseDiagLog(text: String): List<DiagLine> =
    text.lineSequence().filter { it.isNotEmpty() }.mapNotNull { line ->
        val f = line.split('\t')
        if (f.size !in 3..4) return@mapNotNull null
        val ts = f[0].toLongOrNull() ?: return@mapNotNull null
        val count = f.getOrNull(3)?.toIntOrNull() ?: 1
        DiagLine(ts, f[1], f[2], count)
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

    private val _history = MutableStateFlow<List<SpeedSample>>(emptyList())
    /** Last 120 seconds of speed from the engine sampler, pulled with the snapshot (same 1 s poll, same visibility gate). Empty until the first poll. */
    val history: StateFlow<List<SpeedSample>> = _history.asStateFlow()

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

    /** Tell the engine whether a VPN network is active right now (only read when require_vpn is on). No-op after close. */
    fun setVpnActive(active: Boolean) {
        if (closed) return
        NativeEngine.nativeSetVpnActive(handle, active)
    }

    /**
     * Replace the routing rules from hot-reload text (see docs/ENGINE_API.md):
     * one "ACTION<TAB>pattern" line per rule. Returns "ok <count>" on
     * success, or "error line N: msg" with nothing applied. No-op after
     * close (returns "error: engine closed").
     */
    fun setRules(text: String): String {
        if (closed) return "error: engine closed"
        return NativeEngine.nativeSetRules(handle, text)
    }

    /**
     * Replace (or clear) one named rule set from list text (see
     * docs/ENGINE_API.md): one domain per line. Returns "ok <count>"
     * (unique domains parsed) or "error line N: msg" with nothing
     * applied; an empty text clears the set. No-op after close
     * (returns "error: engine closed").
     */
    fun setRuleSet(id: String, action: String, text: String): String {
        if (closed) return "error: engine closed"
        return NativeEngine.nativeSetRuleSet(handle, id, action, text)
    }

    /** Set the Android Network handle for DIRECT connections (0 = none set). No-op after close. */
    fun setDirectNetwork(network: Long) {
        if (closed) return
        NativeEngine.nativeSetDirectNetwork(handle, network)
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

    /** Session-log records newer than `sinceId` (see docs/ENGINE_API.md), read on IO. */
    suspend fun sessionLogSince(sinceId: Long): String = withContext(Dispatchers.IO) {
        NativeEngine.nativeSessionLog(handle, sinceId)
    }

    /** Drop every session-log record in the engine. */
    fun clearSessionLog() {
        NativeEngine.nativeSessionLogClear(handle)
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
        val (text, histText) = withContext(Dispatchers.IO) {
            NativeEngine.nativeSnapshot(handle) to NativeEngine.nativeHistory(handle)
        }
        parseSnapshot(text)?.let { snap ->
            lastRawUp = snap.totalUp
            lastRawDown = snap.totalDown
            val (up, down) = sessionTotals(snap.totalUp, snap.totalDown, baselineUp, baselineDown)
            // The live counters are cumulative across restarts too,
            // and equal the closed totals at start (no active
            // connections), so the same baseline applies to them.
            val (liveUp, liveDown) =
                sessionTotals(snap.liveUp, snap.liveDown, baselineUp, baselineDown)
            val adjusted = snap.copy(totalUp = up, totalDown = down, liveUp = liveUp, liveDown = liveDown)
            _snapshot.value = adjusted
            _state.value = engineStateFromName(adjusted.state)
        }
        _history.value = parseHistory(histText)
    }
}
