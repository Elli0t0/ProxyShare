package io.github.elli0t0.proxyshare.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.elli0t0.proxyshare.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Starts the proxy after boot, only when "Start on boot" is enabled (default off). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ProxyController.init(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = SettingsRepository(context).settings.first().startOnBoot
                if (enabled) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, ProxyService::class.java),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
