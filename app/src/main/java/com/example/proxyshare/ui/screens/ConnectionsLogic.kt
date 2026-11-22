package com.example.proxyshare.ui.screens

import com.example.proxyshare.data.IfKind
import com.example.proxyshare.data.NetIf
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
    // Whole values read "5 MB", not "5.0 MB".
    return String.format(Locale.US, "%.1f %s", v, units[i]).replace(".0 ", " ")
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

/**
 * The address to advertise in the Connections empty state and on the
 * Home hero card, or null when there is no reachable address. A mobile
 * carrier address (CGNAT) is never advertised: rankInterfaces sorts
 * mobile last, so when the primary interface is mobile every
 * interface is mobile and the caller shows the mobile-only hint
 * instead.
 */
fun advertiseAddress(ranked: List<NetIf>, httpPort: Int?, socksPort: Int?): String? {
    val primary = ranked.firstOrNull() ?: return null
    if (primary.kind == IfKind.MOBILE) return null
    val port = httpPort ?: socksPort ?: return primary.ip
    return "${primary.ip}:$port"
}

/** True when every ranked interface is a mobile carrier address (nothing reachable). */
fun isMobileOnly(ranked: List<NetIf>): Boolean =
    ranked.isNotEmpty() && ranked.all { it.kind == IfKind.MOBILE }

/** One closed connection in the session log: metadata only, never payloads. */
data class SessionLogEntry(
    val client: String,
    val host: String,
    val port: Int,
    val proto: String,
    val up: Long,
    val down: Long,
    val durationMs: Long,
    val closedAtMs: Long,
    /** True when the connection was refused for a blocked domain. */
    val blocked: Boolean = false,
)

/** Build a log entry from a closed connection (type 3 event payload, parsed as a connection line). */
fun sessionLogEntry(conn: ConnInfo, closedAtMs: Long): SessionLogEntry =
    SessionLogEntry(
        client = conn.client,
        host = conn.host,
        port = conn.port,
        proto = conn.proto,
        up = conn.up,
        down = conn.down,
        durationMs = (closedAtMs - conn.startedMs).coerceAtLeast(0),
        closedAtMs = closedAtMs,
        blocked = conn.blocked,
    )

/**
 * Parse the engine session-log text (see docs/ENGINE_API.md): one
 * "log_id<TAB>conn_id<TAB>client<TAB>host<TAB>port<TAB>proto<TAB>started_ms<TAB>up<TAB>down<TAB>blocked"
 * line per record, oldest first. Returns (log id, entry) pairs;
 * malformed lines are skipped, never a crash. `closedAtMs` is the
 * time the UI received the records (the engine does not stamp the
 * close time).
 */
fun parseSessionLog(text: String, closedAtMs: Long): List<Pair<Long, SessionLogEntry>> {
    if (text.isBlank()) return emptyList()
    return text.lineSequence().filter { it.isNotEmpty() }.mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 10) return@mapNotNull null
        val logId = f[0].toLongOrNull() ?: return@mapNotNull null
        val conn = ConnInfo(
            id = f[1].toLongOrNull() ?: return@mapNotNull null,
            client = f[2],
            host = f[3],
            port = f[4].toIntOrNull() ?: return@mapNotNull null,
            proto = f[5],
            startedMs = f[6].toLongOrNull() ?: return@mapNotNull null,
            up = f[7].toLongOrNull() ?: return@mapNotNull null,
            down = f[8].toLongOrNull() ?: return@mapNotNull null,
            blocked = f[9] == "1",
        )
        logId to sessionLogEntry(conn, closedAtMs)
    }.toList()
}

/** Append an entry, keeping only the last `max` (500) entries. */
fun pushLogEntry(
    log: List<SessionLogEntry>,
    entry: SessionLogEntry,
    max: Int = 500,
): List<SessionLogEntry> =
    (log + entry).takeLast(max.coerceAtLeast(1))

/**
 * Filter the session log by a host/client query (case-insensitive)
 * and/or blocked-only. The caller reverses for newest-first display.
 */
fun filterSessionLog(
    log: List<SessionLogEntry>,
    query: String,
    blockedOnly: Boolean,
): List<SessionLogEntry> {
    val q = query.trim().lowercase()
    return log.filter { e ->
        (!blockedOnly || e.blocked) &&
            (q.isEmpty() || e.host.lowercase().contains(q) || e.client.lowercase().contains(q))
    }
}

/** The session log as a JSON array (hand-built: no serialization library). */
fun sessionLogJson(log: List<SessionLogEntry>): String = buildString {
    append('[')
    log.forEachIndexed { i, e ->
        if (i > 0) append(',')
        append("{\"client\":${jsonString(e.client)},")
        append("\"host\":${jsonString(e.host)},")
        append("\"port\":${e.port},")
        append("\"proto\":${jsonString(e.proto)},")
        append("\"up\":${e.up},")
        append("\"down\":${e.down},")
        append("\"durationMs\":${e.durationMs},")
        append("\"closedAtMs\":${e.closedAtMs}")
        if (e.blocked) append(",\"blocked\":true")
        append("}")
    }
    append(']')
}

private fun jsonString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
