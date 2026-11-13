package com.example.proxyshare.engine

import com.example.proxyshare.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

/** The whole snapshot: totals plus one line per active connection. */
data class Snapshot(
    val state: String,
    val totalUp: Long,
    val totalDown: Long,
    val active: Int,
    val connections: List<ConnInfo>,
)

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

/**
 * Parse the snapshot text from nativeSnapshot (see docs/ENGINE_API.md):
 * first line "state<TAB>total_up<TAB>total_down<TAB>active", then one
 * tab-separated line per connection: id, client, host, port, proto,
 * started_ms, up, down. Returns null when there is no usable head
 * line; a malformed connection line is skipped, never a crash.
 */
fun parseSnapshot(text: String): Snapshot? {
    val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
    val head = lines.firstOrNull()?.split('\t') ?: return null
    if (head.size < 4) return null
    val conns = lines.drop(1).mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 8) return@mapNotNull null
        ConnInfo(
            id = f[0].toLongOrNull() ?: return@mapNotNull null,
            client = f[1], host = f[2],
            port = f[3].toIntOrNull() ?: return@mapNotNull null,
            proto = f[4],
            startedMs = f[5].toLongOrNull() ?: return@mapNotNull null,
            up = f[6].toLongOrNull() ?: return@mapNotNull null,
            down = f[7].toLongOrNull() ?: return@mapNotNull null,
        )
    }
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

/**
 * Kotlin wrapper that owns the native engine handle.
 *
 * start(settings) and stop() run on Dispatchers.IO; close() destroys
 * the handle. While running, nativeSnapshot is polled every 1 s (250 ms
 * when the connections screen asks via setFastPolling). Events arrive
 * on a native thread: they are only tryEmit-ted here, never blocking,
 * and never touch the UI.
 */
class Engine(private val scope: CoroutineScope) {

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

    @Volatile
    private var fastPolling = false
    private var pollJob: Job? = null
    private var closed = false

    init {
        NativeEngine.nativeSetListener(handle, listener)
    }

    /** Poll faster (250 ms) while the connections screen is visible. */
    fun setFastPolling(enabled: Boolean) {
        fastPolling = enabled
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
        _state.value = EngineState.Running
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                pollOnce()
                delay(if (fastPolling) 250L else 1000L)
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
            _snapshot.value = snap
            _state.value = engineStateFromName(snap.state)
        }
    }
}
