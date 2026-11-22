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
import com.example.proxyshare.data.VpnWatcher
import com.example.proxyshare.engine.EngineState
import com.example.proxyshare.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    private var idleObserving = false
    private var vpnDropObserving = false
    /** True while a client is connected (from the engine's Busy/Idle edge events). */
    private var busy = false
    private var wakeGraceJob: Job? = null

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
                notificationText(
                    ProxyController.settings.value,
                    ProxyController.snapshot.value.devices,
                    singleDeviceName(ProxyController.settings.value),
                ),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        observeWakeLock()
        observeState()
        observeEngineEvents()
        observeVpnDrop()
        ProxyController.start()
        return START_STICKY
    }

    override fun onDestroy() {
        observing = false
        // Persist the usage totals before the process goes away: the
        // controller's stop() normally flushes, but a system kill can
        // skip that path. A DataStore edit is a small local write.
        runBlocking { ProxyController.flushUsage() }
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Hold the wake lock while a client is connected (or while battery
     * saver is off), driven by the engine's Busy/Idle edge events.
     * With no clients and battery saver on, the lock is released after
     * a 60 s grace period; a Busy edge cancels that wait at once. The
     * busy flag is seeded from a fresh snapshot poll at start: an
     * edge that fired before this collector started must not be
     * missed.
     */
    private fun observeWakeLock() {
        serviceScope.launch {
            ProxyController.pollNow()
            busy = ProxyController.snapshot.value.active > 0
            updateWakeLock()
            ProxyController.events.collect { e ->
                when (e.type) {
                    10 -> {
                        busy = true
                        updateWakeLock()
                    }
                    11 -> {
                        busy = false
                        updateWakeLock()
                    }
                }
            }
        }
        serviceScope.launch {
            ProxyController.settings.map { it.batterySaver }.collect { updateWakeLock() }
        }
    }

    /** Acquire the wake lock when busy or battery saver is off; otherwise release it after the 60 s grace. */
    private fun updateWakeLock() {
        val saver = ProxyController.settings.value.batterySaver
        if (!saver || busy) {
            wakeGraceJob?.cancel()
            wakeGraceJob = null
            acquireWakeLock()
        } else {
            wakeGraceJob?.cancel()
            wakeGraceJob = serviceScope.launch {
                delay(60_000)
                releaseWakeLock()
            }
        }
    }

    /**
     * Engine edge events the service acts on: IdleStop (type 13) stops
     * the proxy after the engine's idle countdown expired. The
     * Busy/Idle edges are consumed by observeWakeLock instead.
     */
    private fun observeEngineEvents() {
        if (idleObserving) return
        idleObserving = true
        serviceScope.launch {
            ProxyController.events.collect { e ->
                if (e.type == 13) {
                    ProxyController.stop()
                    showIdleStopped()
                    stopSelf()
                }
            }
        }
    }

    /**
     * One notification when the VPN drops while clients are connected
     * (Default or VPN-only mode). Only the true->false transition
     * fires: a client connecting later, while the VPN is already down,
     * does not re-notify. Separate channel, default importance.
     */
    private fun observeVpnDrop() {
        if (vpnDropObserving) return
        vpnDropObserving = true
        serviceScope.launch {
            var wasActive = VpnWatcher.active.value
            VpnWatcher.active.collect { active ->
                val mode = ProxyController.settings.value.outboundMode.toOutboundMode()
                if (!active && wasActive &&
                    (mode == OutboundMode.DEFAULT || mode == OutboundMode.VPN_ONLY) &&
                    ProxyController.state.value is EngineState.Running &&
                    ProxyController.snapshot.value.active > 0
                ) {
                    showVpnDropped()
                }
                wasActive = active
            }
        }
    }

    private fun showVpnDropped() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                VPN_CHANNEL_ID,
                getString(R.string.notif_vpn_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val tapIntent = Intent(this, MainActivity::class.java)
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, VPN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_proxy_status)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_dropped_notification))
            .setContentIntent(tapPending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(VPN_NOTIF_ID, notification)
    }

    /** One-line notification after an idle auto-stop. A separate, non-ongoing notification (no Stop action: the proxy is already stopped) that survives the service going away. */
    private fun showIdleStopped() {
        val tapIntent = Intent(this, MainActivity::class.java)
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_proxy_status)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.idle_stopped_notification))
            .setContentIntent(tapPending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(IDLE_NOTIF_ID, notification)
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
                            notificationText(
                                settings,
                                ProxyController.snapshot.value.devices,
                                singleDeviceName(settings),
                            ),
                        ),
                    )
                }
            }
        }
        // Live device count in the notification, on DevicesChanged
        // edges only (no timer, no polling).
        serviceScope.launch {
            ProxyController.events.collect { e ->
                if (e.type == 12 && ProxyController.state.value is EngineState.Running) {
                    getSystemService(NotificationManager::class.java)?.notify(
                        NOTIF_ID,
                        buildNotification(
                            notificationText(
                                ProxyController.settings.value,
                                ProxyController.snapshot.value.devices,
                                singleDeviceName(ProxyController.settings.value),
                            ),
                        ),
                    )
                }
            }
        }
    }

    /** "1 device · SOCKS :1080", or the ports alone when no device is connected. With exactly one device and a name for it, the name leads instead of "1 device". */
    private fun notificationText(
        settings: AppSettings,
        devices: Int,
        singleDeviceName: String? = null,
    ): String {
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
        val parts = mutableListOf<String>()
        if (devices == 1 && singleDeviceName != null) {
            parts += singleDeviceName
        } else if (devices > 0) {
            parts += resources.getQuantityString(R.plurals.notif_devices, devices, devices)
        }
        if (parts.isEmpty()) return base
        return (parts + base).joinToString(" · ")
    }

    /** The stored name of the one connected device, if exactly one device is connected and it has a name. */
    private fun singleDeviceName(settings: AppSettings): String? {
        if (ProxyController.snapshot.value.devices != 1) return null
        val ip = ProxyController.snapshot.value.connections.firstOrNull()?.client
            ?: return null
        return settings.clientNames[ip]
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
        const val IDLE_NOTIF_ID = 2
        const val VPN_NOTIF_ID = 3
        const val CHANNEL_ID = "proxy_running"
        const val VPN_CHANNEL_ID = "vpn_watchdog"
    }
}
