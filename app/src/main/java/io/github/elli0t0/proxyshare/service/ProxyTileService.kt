package io.github.elli0t0.proxyshare.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.engine.EngineState
import io.github.elli0t0.proxyshare.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: shows the proxy state and starts/stops it from
 * the notification shade. Add it from the tile editor; the system
 * binds this service only while the tile is visible.
 */
class ProxyTileService : TileService() {

    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job = CoroutineScope(Dispatchers.Main).launch {
            ProxyController.state.collect { s ->
                qsTile?.apply {
                    state = if (s is EngineState.Running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    label = getString(R.string.app_name)
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (ProxyController.state.value is EngineState.Running) {
            ProxyController.stop()
            return
        }
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ProxyService::class.java),
            )
        } catch (_: Exception) {
            // Background FGS start blocked: open the app instead.
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        }
    }
}
