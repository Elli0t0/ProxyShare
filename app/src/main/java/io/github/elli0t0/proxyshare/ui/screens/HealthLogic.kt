package io.github.elli0t0.proxyshare.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.data.IfKind
import io.github.elli0t0.proxyshare.data.NetIf
import io.github.elli0t0.proxyshare.engine.EngineState

enum class HealthStatus { OK, WARN, FAIL }

enum class HealthFix { NONE, NOTIFICATIONS, BATTERY, HOTSPOT, SETTINGS_SECURITY }

/**
 * One health check. `detail` is a fixed explanation; `message`
 * carries a dynamic one (the engine error text) when present.
 */
data class HealthItem(
    val id: String,
    @StringRes val label: Int,
    val status: HealthStatus,
    @StringRes val detail: Int?,
    val fix: HealthFix,
    val message: String? = null,
)

data class HealthInputs(
    val notificationsOk: Boolean,
    val batteryUnrestricted: Boolean,
    val vpnActive: Boolean,
    val hasShareableAddress: Boolean,
    val engineState: EngineState,
    val authEnabled: Boolean,
    val authPass: String,
    /** PAC is on but its port did not bind: the setup page is down. */
    val pacEnabled: Boolean = false,
    val pacOk: Boolean = true,
)

/**
 * Pure health check: one item per check, in a stable order
 * (notifications, battery, VPN, address, engine, auth). No
 * Android types inside: the caller gathers the facts.
 */
fun computeHealth(i: HealthInputs): List<HealthItem> {
    val authBad = i.authEnabled && i.authPass.isBlank()
    return listOf(
        HealthItem(
            id = "notifications",
            label = R.string.health_notifications,
            status = if (i.notificationsOk) HealthStatus.OK else HealthStatus.WARN,
            detail = if (i.notificationsOk) null else R.string.health_notifications_off,
            fix = if (i.notificationsOk) HealthFix.NONE else HealthFix.NOTIFICATIONS,
        ),
        HealthItem(
            id = "battery",
            label = R.string.health_battery,
            status = if (i.batteryUnrestricted) HealthStatus.OK else HealthStatus.WARN,
            detail = if (i.batteryUnrestricted) null else R.string.health_battery_optimized,
            fix = if (i.batteryUnrestricted) HealthFix.NONE else HealthFix.BATTERY,
        ),
        HealthItem(
            id = "vpn",
            label = R.string.health_vpn,
            status = if (i.vpnActive) HealthStatus.OK else HealthStatus.WARN,
            detail = if (i.vpnActive) null else R.string.health_vpn_off,
            fix = HealthFix.NONE,
        ),
        HealthItem(
            id = "address",
            label = R.string.health_address,
            status = if (i.hasShareableAddress) HealthStatus.OK else HealthStatus.FAIL,
            detail = if (i.hasShareableAddress) null else R.string.health_address_none,
            fix = if (i.hasShareableAddress) HealthFix.NONE else HealthFix.HOTSPOT,
        ),
        when (val s = i.engineState) {
            is EngineState.Running -> HealthItem(
                id = "engine",
                label = R.string.health_engine,
                status = if (i.pacEnabled && !i.pacOk) HealthStatus.WARN else HealthStatus.OK,
                detail = if (i.pacEnabled && !i.pacOk) R.string.health_pac_failed else null,
                fix = HealthFix.NONE,
            )
            is EngineState.Error -> HealthItem(
                id = "engine",
                label = R.string.health_engine,
                status = HealthStatus.FAIL,
                detail = null,
                fix = HealthFix.NONE,
                message = s.message,
            )
            else -> HealthItem(
                id = "engine",
                label = R.string.health_engine,
                status = HealthStatus.WARN,
                detail = R.string.health_engine_stopped,
                fix = HealthFix.NONE,
            )
        },
        HealthItem(
            id = "auth",
            label = R.string.health_auth,
            status = if (authBad) HealthStatus.FAIL else HealthStatus.OK,
            detail = if (authBad) R.string.health_auth_empty else null,
            fix = if (authBad) HealthFix.SETTINGS_SECURITY else HealthFix.NONE,
        ),
    )
}

/**
 * True when some interface can be shared with a laptop: not a VPN
 * address and not a mobile carrier address (neither is reachable
 * from the local network).
 */
fun hasShareableAddress(ranked: List<NetIf>): Boolean =
    ranked.any { it.kind != IfKind.VPN && it.kind != IfKind.MOBILE }

/** True when the app may show its running notification. */
fun notificationsAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/** True when the system will not kill the service for battery. */
fun batteryUnrestricted(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
