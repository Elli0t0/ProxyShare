package com.example.proxyshare.service

import android.content.Context
import com.example.proxyshare.data.AppSettings
import com.example.proxyshare.data.SettingsRepository
import com.example.proxyshare.engine.Engine
import com.example.proxyshare.engine.EngineEvent
import com.example.proxyshare.engine.EngineState
import com.example.proxyshare.engine.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private var started = false

    private val _settings = MutableStateFlow(AppSettings())
    /** The current settings (defaults until the DataStore read lands). */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val state: StateFlow<EngineState> = engine.state
    val snapshot: StateFlow<Snapshot> = engine.snapshot
    val events: SharedFlow<EngineEvent> = engine.events

    /** Called once from ProxyShareApp.onCreate. Safe to call twice. */
    fun init(context: Context) {
        if (repository != null) return
        val repo = SettingsRepository(context.applicationContext)
        repository = repo
        scope.launch {
            repo.settings.collect { _settings.value = it }
        }
    }

    /** Read the settings, generate a password on first run, start the engine. */
    fun start() {
        scope.launch {
            startMutex.withLock {
                if (started) return@withLock
                val repo = repository ?: return@withLock
                started = true
                try {
                    repo.ensurePassword()
                    val settings = repo.settings.first()
                    val error = engine.start(settings)
                    if (error.isNotEmpty()) started = false
                } catch (_: Exception) {
                    started = false
                    runCatching { engine.stop() }
                }
            }
        }
    }

    /** Stop the engine. Also resets an Error state back to Stopped. */
    fun stop() {
        scope.launch {
            startMutex.withLock {
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
}
