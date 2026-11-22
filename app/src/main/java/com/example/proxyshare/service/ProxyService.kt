package com.example.proxyshare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.proxyshare.R
import com.example.proxyshare.data.AppSettings
import com.example.proxyshare.engine.EngineState
import com.example.proxyshare.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the proxy running: it holds a partial
 * wake lock, shows an ongoing notification with a Stop action, and
 * stops itself when the engine stops or errors.
 *
 * Start it from the UI with
 * ContextCompat.startForegroundService(context, Intent(context, ProxyService::class.java)).
 */
class ProxyService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ProxyController.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(
                notificationText(ProxyController.settings.value, ProxyController.activeConns.value),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        observeWakeLock()
        observeState()
        ProxyController.start()
        return START_STICKY
    }

    override fun onDestroy() {
        observing = false
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Hold the wake lock only while a client is connected (or while
     * battery saver is off). With no clients and battery saver on, the
     * lock is released after a 60 s grace period; collectLatest cancels
     * that wait the moment a client connects.
     */
    private fun observeWakeLock() {
        serviceScope.launch {
            combine(
                ProxyController.activeConns,
                ProxyController.settings.map { it.batterySaver },
            ) { n, saver -> n to saver }
                .collectLatest { (n, saver) ->
                    if (!saver || n > 0) {
                        acquireWakeLock()
                    } else {
                        delay(60_000)
                        releaseWakeLock()
                    }
                }
        }
    }

    /**
     * Watch the engine state (once per service lifetime). The first
     * emission is the state at start time, so it is dropped: only
     * transitions matter.
     */
    private fun observeState() {
        if (observing) return
        observing = true
        serviceScope.launch {
            ProxyController.state.drop(1).collect { state ->
                when (state) {
                    is EngineState.Stopped -> stopSelf()
                    is EngineState.Error -> {
                        val errorText =
                            state.message.ifBlank { getString(R.string.proxy_error) }
                        showErrorAndStop(errorText)
                    }
                    else -> {}
                }
            }
        }
        // Keep the notification's ports current if the settings land or
        // change after the service started.
        serviceScope.launch {
            ProxyController.settings.collect { settings ->
                if (ProxyController.state.value is EngineState.Running) {
                    getSystemService(NotificationManager::class.java)?.notify(
                        NOTIF_ID,
                        buildNotification(
                            notificationText(settings, ProxyController.activeConns.value),
                        ),
                    )
                }
            }
        }
        // Live client count in the notification, on count changes only
        // (no timer, no polling): the count is event-driven already.
        serviceScope.launch {
            ProxyController.activeConns.collect { n ->
                if (ProxyController.state.value is EngineState.Running) {
                    getSystemService(NotificationManager::class.java)?.notify(
                        NOTIF_ID,
                        buildNotification(
                            notificationText(ProxyController.settings.value, n),
                        ),
                    )
                }
            }
        }
    }

    /** "HTTP :8080 · SOCKS :1080", only the enabled protocols, or the single port. With clients connected, the count leads: "2 clients · SOCKS :1080". */
    private fun notificationText(settings: AppSettings, clients: Int): String {
        val base = if (!settings.separatePorts) {
            getString(R.string.notif_running, settings.port)
        } else {
            val http = if ("http" in settings.protocols) settings.httpPort else null
            val socks = if ("socks4" in settings.protocols || "socks5" in settings.protocols) {
                settings.socksPort
            } else {
                null
            }
            when {
                http != null && socks != null -> getString(R.string.notif_running_ports, http, socks)
                http != null -> getString(R.string.notif_running_http, http)
                socks != null -> getString(R.string.notif_running_socks, socks)
                else -> getString(R.string.notif_running, settings.port)
            }
        }
        if (clients <= 0) return base
        val count = resources.getQuantityString(R.plurals.notif_clients, clients, clients)
        return "$count · $base"
    }

    /** Show the error text (kept visible) and stop the service. */
    private fun showErrorAndStop(errorText: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID, buildNotification(errorText, ongoing = false))
        ProxyController.stop()
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, ongoing: Boolean = true): Notification {
        val tapIntent = Intent(this, MainActivity::class.java)
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, ProxyService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_proxy_status)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(tapPending)
            .setOngoing(ongoing)
            // Count updates must never buzz.
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_proxy_status, getString(R.string.notif_stop), stopPending)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProxyShare:proxy").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "com.example.proxyshare.STOP"
        const val NOTIF_ID = 1
        const val CHANNEL_ID = "proxy_running"
    }
}
