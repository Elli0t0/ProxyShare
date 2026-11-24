package io.github.elli0t0.proxyshare.ui.screens

import io.github.elli0t0.proxyshare.data.IfKind
import io.github.elli0t0.proxyshare.data.NetIf
import io.github.elli0t0.proxyshare.data.RuleEntry
import io.github.elli0t0.proxyshare.engine.ConnInfo
import io.github.elli0t0.proxyshare.engine.LogIds
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

/**
 * The "other" addresses for Home's address card: every ranked interface
 * after the primary, as "ip:port" (same port choice as
 * [advertiseAddress]). Mobile carrier addresses are skipped — they are
 * not reachable from a laptop.
 */
fun otherAddresses(ranked: List<NetIf>, httpPort: Int?, socksPort: Int?): List<String> =
    ranked.drop(1).mapNotNull { nif -> advertiseAddress(listOf(nif), httpPort, socksPort) }

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
    /** How the connection was routed: "proxy" | "direct" | "block" (0.6.6). */
    val route: String = "proxy",
    /**
     * Process-wide unique id, assigned at creation. Identical entries
     * (same ms, same host, 0 bytes) must still get distinct LazyColumn
     * keys, or the list crashes with "Key ... was already used".
     */
    val id: Long = LogIds.next(),
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
        route = conn.route,
    )

/**
 * Parse the engine session-log text (see docs/ENGINE_API.md): one
 * "log_id<TAB>conn_id<TAB>client<TAB>host<TAB>port<TAB>proto<TAB>started_ms<TAB>up<TAB>down<TAB>blocked<TAB>route"
 * line per record, oldest first. The route field is absent in older
 * lines (read as "proxy"). Returns (log id, entry) pairs;
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
            route = f.getOrNull(10) ?: "proxy",
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
        append("\"closedAtMs\":${e.closedAtMs},")
        append("\"route\":${jsonString(e.route)}")
        if (e.blocked) append(",\"blocked\":true")
        append("}")
    }
    append(']')
}

private fun jsonString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * Domains view (0.7.0): one row per host, aggregated from the
 * session log (last 500 closed connections) plus live connections.
 */

/** One domain's traffic: counts and bytes sum over its connections. */
data class DomainRow(
    val host: String,
    val count: Int,
    val up: Long,
    val down: Long,
    val lastSeenMs: Long,
    val route: String,
    val blocked: Boolean,
    /** True when the host has a live connection right now. */
    val active: Boolean,
)

/**
 * Aggregate closed connections ([entries], oldest first) plus live
 * connections ([live]) by host (lowercased, trailing dot stripped).
 * Bytes and counts sum; lastSeenMs is the newest close, or now when
 * the host is active; route is the newest connection's route (a live
 * connection counts as newest); blocked is true when any connection
 * for the host was refused.
 */
fun aggregateDomains(
    entries: List<SessionLogEntry>,
    live: List<ConnInfo>,
    nowMs: Long = System.currentTimeMillis(),
): List<DomainRow> {
    class Acc {
        var count = 0
        var up = 0L
        var down = 0L
        var lastSeenMs = 0L
        var route = "proxy"
        var blocked = false
        var active = false
        var lastLiveStartedMs = -1L
    }
    val accs = LinkedHashMap<String, Acc>()
    for (e in entries) {
        val host = e.host.lowercase().trimEnd('.')
        if (host.isEmpty()) continue
        val a = accs.getOrPut(host) { Acc() }
        a.count++
        a.up += e.up
        a.down += e.down
        if (e.closedAtMs >= a.lastSeenMs) {
            a.lastSeenMs = e.closedAtMs
            a.route = e.route
        }
        a.blocked = a.blocked || e.blocked
    }
    for (c in live) {
        val host = c.host.lowercase().trimEnd('.')
        if (host.isEmpty()) continue
        val a = accs.getOrPut(host) { Acc() }
        a.count++
        a.up += c.up
        a.down += c.down
        // Live is newest: its route is the host's current routing.
        // Among live connections the latest-started one decides.
        if (c.startedMs >= a.lastLiveStartedMs) {
            a.lastLiveStartedMs = c.startedMs
            a.route = c.route
        }
        a.lastSeenMs = nowMs
        a.blocked = a.blocked || c.blocked
        a.active = true
    }
    return accs.map { (host, a) ->
        DomainRow(host, a.count, a.up, a.down, a.lastSeenMs, a.route, a.blocked, a.active)
    }
}

enum class DomainStatusFilter { ALL, ACTIVE, BLOCKED }
enum class DomainRouteFilter { ALL, PROXY, DIRECT, BLOCK }
enum class DomainSort { ACTIVE_FIRST, MOST_TRAFFIC, MOST_REQUESTS, AZ }

/** Filter rows by status, route and a (normalised) search query. */
fun filterDomains(
    rows: List<DomainRow>,
    status: DomainStatusFilter,
    route: DomainRouteFilter,
    query: String,
): List<DomainRow> {
    val q = normalizeDomainQuery(query)
    return rows.filter { r ->
        (status == DomainStatusFilter.ALL ||
            (status == DomainStatusFilter.ACTIVE && r.active) ||
            (status == DomainStatusFilter.BLOCKED && r.blocked)) &&
            (route == DomainRouteFilter.ALL ||
                r.route == route.name.lowercase()) &&
            (q.isEmpty() || r.host.contains(q))
    }
}

/**
 * Sort rows. Every order is total (ties break on host), so the list
 * is stable and deterministic for the same input.
 */
fun sortDomains(rows: List<DomainRow>, sort: DomainSort): List<DomainRow> =
    when (sort) {
        DomainSort.ACTIVE_FIRST -> rows.sortedWith(
            compareByDescending<DomainRow> { it.active }
                .thenByDescending { it.up + it.down }
                .thenBy { it.host },
        )
        DomainSort.MOST_TRAFFIC -> rows.sortedWith(
            compareByDescending<DomainRow> { it.up + it.down }
                .thenBy { it.host },
        )
        DomainSort.MOST_REQUESTS -> rows.sortedWith(
            compareByDescending<DomainRow> { it.count }
                .thenBy { it.host },
        )
        DomainSort.AZ -> rows.sortedBy { it.host }
    }

/**
 * Normalise a domain search: strip a scheme and any path, query,
 * fragment or port, lowercase, drop a trailing dot. "https://
 * Sub.Example.com/path?q=1" becomes "sub.example.com". An IPv6
 * literal keeps its colons (a port is only stripped when there is
 * exactly one colon).
 */
fun normalizeDomainQuery(query: String): String {
    var q = query.trim().lowercase()
    val scheme = q.indexOf("://")
    if (scheme >= 0) q = q.substring(scheme + 3)
    val slash = q.indexOf('/')
    if (slash >= 0) q = q.substring(0, slash)
    val queryMark = q.indexOf('?')
    if (queryMark >= 0) q = q.substring(0, queryMark)
    val hash = q.indexOf('#')
    if (hash >= 0) q = q.substring(0, hash)
    val colon = q.indexOf(':')
    if (colon >= 0 && q.indexOf(':', colon + 1) < 0) q = q.substring(0, colon)
    return q.trimEnd('.')
}

/**
 * The manual rule written for this exact host (`host` or `=host`),
 * if any. Suffix rules (a rule for `a.com` covering `sub.a.com`)
 * are not matched here: the Rules page owns those.
 */
fun ruleForHost(host: String, rules: List<RuleEntry>): RuleEntry? =
    rules.firstOrNull { it.pattern == host || it.pattern == "=$host" }
