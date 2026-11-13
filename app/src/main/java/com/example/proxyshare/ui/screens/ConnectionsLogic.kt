package com.example.proxyshare.ui.screens

import com.example.proxyshare.engine.ConnInfo
import java.util.Locale

/** Live speed of one connection, in bytes per second. */
data class Speed(val upBps: Long, val downBps: Long)

/**
 * Speeds between two snapshots. A connection missing from [prev] is new
 * (speed 0); a counter that went down is clamped to 0.
 */
fun computeSpeeds(prev: Map<Long, ConnInfo>, cur: List<ConnInfo>, dtMs: Long): Map<Long, Speed> {
    val dt = dtMs.coerceAtLeast(1)
    return cur.associate { c ->
        val p = prev[c.id]
        val up = if (p == null) 0L else (c.up - p.up).coerceAtLeast(0) * 1000 / dt
        val down = if (p == null) 0L else (c.down - p.down).coerceAtLeast(0) * 1000 / dt
        c.id to Speed(up, down)
    }
}

fun formatBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var i = -1
    do {
        v /= 1024
        i++
    } while (v >= 1024 && i < units.size - 1)
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

/** One client's connections, with totals. */
data class ClientGroup(
    val client: String,
    val connections: List<ConnInfo>,
    val totalUp: Long,
    val totalDown: Long,
)

/** Group connections by client IP; clients sorted by total traffic (up + down), most first. */
fun groupByClient(connections: List<ConnInfo>): List<ClientGroup> =
    connections.groupBy { it.client }
        .map { (client, conns) ->
            ClientGroup(client, conns, conns.sumOf { it.up }, conns.sumOf { it.down })
        }
        .sortedByDescending { it.totalUp + it.totalDown }

/** Format a duration in ms as "12s", "3m 12s" or "1h 2m". */
fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs.toString().padStart(2, '0')}s"
        else -> "${secs}s"
    }
}
