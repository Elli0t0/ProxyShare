package io.github.elli0t0.proxyshare.ui.screens

import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.data.AppSettings
import io.github.elli0t0.proxyshare.data.parseClientNames
import io.github.elli0t0.proxyshare.engine.NativeEngine
import org.json.JSONArray
import org.json.JSONObject

/** Validation kinds for [validate]: must match rust/engine/src/validate.rs. */
const val VALIDATE_CIDR = 0
const val VALIDATE_DOMAIN = 1
const val VALIDATE_PORT = 2

/**
 * Validate one settings value in Rust, the single validation source.
 * Returns "" when valid, or a stable error code ("empty",
 * "has_scheme", "has_path", "bad_cidr", "bad_port", "bad_domain",
 * "bad_kind"). Kotlin holds no validation logic of its own.
 */
fun validate(kind: Int, value: String): String = NativeEngine.nativeValidate(kind, value)

/** Error code from [validate] -> string resource. */
val validationErrorRes: Map<String, Int> = mapOf(
    "empty" to R.string.validation_empty,
    "has_scheme" to R.string.validation_has_scheme,
    "has_path" to R.string.validation_has_path,
    "bad_cidr" to R.string.allowed_clients_invalid,
    "bad_port" to R.string.port_error,
    "bad_domain" to R.string.blocked_domains_invalid,
    "bad_kind" to R.string.blocked_domains_invalid,
)

/** Port 1024-65535, or null when the text is not a valid port (validated in Rust). */
fun validatePort(text: String): Int? =
    if (validate(VALIDATE_PORT, text).isEmpty()) text.trim().toIntOrNull() else null

/** A domain to block: "ads.example.com" or "*.example.com". Validated in Rust. */
fun validateBlockedDomain(text: String): Boolean = validate(VALIDATE_DOMAIN, text).isEmpty()

/** A whole number inside [range], or null when not a number or out of range. */
fun validateIntInRange(text: String, range: IntRange): Int? =
    text.toIntOrNull()?.takeIf { it in range }

/**
 * Split pasted text into the entries to add and the invalid count:
 * one per line, blank lines and #-comments ignored, duplicates
 * (within the paste and against [existing]) dropped silently.
 * Each line is validated through the same Rust call as the single
 * add row (one JNI call per line; this is not a hot path).
 * [isValid] is injectable so JVM unit tests can check the splitting
 * without loading the native library.
 */
fun parseBlockedList(
    text: String,
    existing: List<String> = emptyList(),
    isValid: (String) -> Boolean = ::validateBlockedDomain,
): Pair<List<String>, Int> {
    val added = mutableListOf<String>()
    var invalid = 0
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        if (!isValid(line)) {
            invalid++
        } else if (line !in existing && line !in added) {
            added += line
        }
    }
    return added to invalid
}

/** Map idle-stop minutes to its label string. 0 = off. */
fun idleStopLabelRes(minutes: Int): Int = when (minutes) {
    15 -> R.string.idle_stop_15
    30 -> R.string.idle_stop_30
    60 -> R.string.idle_stop_60
    120 -> R.string.idle_stop_120
    else -> R.string.idle_stop_off
}

/** Live one-line summary for the Server row: "HTTP 8080 · SOCKS 1080" (or "Port 8080" in single-port mode). */
fun serverSummary(s: AppSettings): String {
    if (!s.separatePorts) return "Port ${s.port}"
    val parts = buildList {
        if ("http" in s.protocols) add("HTTP ${s.httpPort}")
        if ("socks4" in s.protocols || "socks5" in s.protocols) add("SOCKS ${s.socksPort}")
    }
    return parts.joinToString(" · ")
}

/** Live one-line summary for the Security row: "Password on · private only". */
fun securitySummary(s: AppSettings): String {
    val parts = buildList {
        add(if (s.authEnabled) "Password on" else "No password")
        if (s.privateOnly) add("private only")
    }
    return parts.joinToString(" · ")
}

/** Format one speed limit (KB/s) for the Limits summary: 2000 -> "2 MB/s", 2500 -> "2.5 MB/s", 500 -> "500 KB/s". */
fun formatSpeedKbps(kbps: Int): String {
    if (kbps >= 1000) {
        if (kbps % 1000 == 0) return "${kbps / 1000} MB/s"
        val tenths = kbps / 100
        return "${tenths / 10}.${tenths % 10} MB/s"
    }
    return "$kbps KB/s"
}

/** Live one-line summary for the Limits row: "Unlimited" or "↓ 2 MB/s ↑ 1 MB/s". */
fun limitsSummary(s: AppSettings): String {
    val parts = buildList {
        if (s.limitDownKbps > 0) add("↓ ${formatSpeedKbps(s.limitDownKbps)}")
        if (s.limitUpKbps > 0) add("↑ ${formatSpeedKbps(s.limitUpKbps)}")
    }
    return if (parts.isEmpty()) "Unlimited" else parts.joinToString(" ")
}

/** Live one-line summary for the Battery & startup row: "Saver on · Boot off". */
fun batterySummary(s: AppSettings): String =
    "Saver ${if (s.batterySaver) "on" else "off"} · Boot ${if (s.startOnBoot) "on" else "off"}"

/** Live one-line summary for the Appearance row: "System theme". */
fun appearanceSummary(s: AppSettings): String = when (s.themeMode) {
    "light" -> "Light theme"
    "dark" -> "Dark theme"
    else -> "System theme"
}

/** Backup file format version, written by [AppSettings.toBackupJson]. */
private const val BACKUP_VERSION = 1

private val KNOWN_PROTOCOLS = setOf("http", "socks4", "socks5")
private val KNOWN_THEMES = setOf("system", "light", "dark")
private val KNOWN_LANGUAGES = setOf("system", "en", "fa")
private val KNOWN_OUTBOUND_MODES = setOf("default", "vpn_only", "wifi", "mobile")
private val KNOWN_IDLE_STOP_MINUTES = setOf(0, 15, 30, 60, 120)

/** Read a JSON array of strings (skips non-strings, trims, drops blanks). */
private fun jsonStringList(obj: JSONObject, key: String): List<String> {
    val arr = obj.optJSONArray(key) ?: return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val s = arr.optString(i, "").trim()
        if (s.isNotEmpty()) out += s
    }
    return out
}

/** Serialize every setting except the auth secrets, for a backup file. */
fun AppSettings.toBackupJson(): String = JSONObject().apply {
    put("version", BACKUP_VERSION)
    put("bindAddr", bindAddr)
    put("port", port)
    put("separatePorts", separatePorts)
    put("httpPort", httpPort)
    put("socksPort", socksPort)
    put("pacEnabled", pacEnabled)
    put("pacPort", pacPort)
    put("protocols", JSONArray(protocols.toList()))
    put("authEnabled", authEnabled)
    // authUser and authPass are NEVER exported.
    put("allowedClients", JSONArray(allowedClients))
    put("blockedDomains", JSONArray(blockedDomains))
    put("privateOnly", privateOnly)
    put("blockPrivate", blockPrivate)
    put("maxConnections", maxConnections)
    put("idleTimeoutS", idleTimeoutS)
    put("connectTimeoutS", connectTimeoutS)
    put("limitDownKbps", limitDownKbps)
    put("limitUpKbps", limitUpKbps)
    put("themeMode", themeMode)
    put("language", language)
    put("keepScreenOn", keepScreenOn)
    put("requireVpn", requireVpn)
    put("clientNames", JSONObject(clientNames))
    put("batterySaver", batterySaver)
    put("startOnBoot", startOnBoot)
    put("idleStopMinutes", idleStopMinutes)
    put("outboundMode", outboundMode)
    put("exitIpUrl", exitIpUrl)
}.toString(2)

/**
 * Parse a backup file produced by [AppSettings.toBackupJson].
 * Numbers are clamped to the UI ranges, list entries are dropped
 * when they fail the existing Rust validation, unknown keys are
 * ignored, and missing keys keep [base] (the current settings on
 * import, the defaults in tests). Secrets are never in the file:
 * authUser/authPass always keep [base]'s values.
 * The validators are injectable so JVM unit tests can run without
 * loading the native library (same pattern as [parseBlockedList]).
 */
fun settingsFromBackupJson(
    text: String,
    base: AppSettings = AppSettings(),
    isValidCidr: (String) -> Boolean = { validate(VALIDATE_CIDR, it).isEmpty() },
    isValidDomain: (String) -> Boolean = ::validateBlockedDomain,
): Result<AppSettings> = runCatching {
    val o = JSONObject(text)
    require(o.optInt("version", 0) >= BACKUP_VERSION)
    val bindAddr = o.optString("bindAddr", base.bindAddr)
        .takeIf { isValidCidr(it) } ?: base.bindAddr
    val protocols = jsonStringList(o, "protocols")
        .filter { it in KNOWN_PROTOCOLS }.toSet()
        .takeIf { it.isNotEmpty() } ?: base.protocols
    val themeMode = o.optString("themeMode", base.themeMode)
        .takeIf { it in KNOWN_THEMES } ?: base.themeMode
    val language = o.optString("language", base.language)
        .takeIf { it in KNOWN_LANGUAGES } ?: base.language
    val outboundMode = o.optString("outboundMode", base.outboundMode)
        .takeIf { it in KNOWN_OUTBOUND_MODES } ?: base.outboundMode
    val idleStopMinutes = o.optInt("idleStopMinutes", base.idleStopMinutes)
        .takeIf { it in KNOWN_IDLE_STOP_MINUTES } ?: base.idleStopMinutes
    val clientNames = o.optJSONObject("clientNames")
        ?.let { parseClientNames(it.toString()) } ?: base.clientNames
    base.copy(
        bindAddr = bindAddr,
        port = o.optInt("port", base.port).coerceIn(1, 65535),
        separatePorts = o.optBoolean("separatePorts", base.separatePorts),
        httpPort = o.optInt("httpPort", base.httpPort).coerceIn(1, 65535),
        socksPort = o.optInt("socksPort", base.socksPort).coerceIn(1, 65535),
        pacEnabled = o.optBoolean("pacEnabled", base.pacEnabled),
        pacPort = o.optInt("pacPort", base.pacPort).coerceIn(1, 65535),
        protocols = protocols,
        authEnabled = o.optBoolean("authEnabled", base.authEnabled),
        allowedClients = jsonStringList(o, "allowedClients").filter(isValidCidr),
        blockedDomains = jsonStringList(o, "blockedDomains").filter(isValidDomain),
        privateOnly = o.optBoolean("privateOnly", base.privateOnly),
        blockPrivate = o.optBoolean("blockPrivate", base.blockPrivate),
        maxConnections = o.optInt("maxConnections", base.maxConnections).coerceIn(1, 2000),
        idleTimeoutS = o.optInt("idleTimeoutS", base.idleTimeoutS).coerceIn(30, 3600),
        connectTimeoutS = o.optInt("connectTimeoutS", base.connectTimeoutS).coerceIn(2, 120),
        limitDownKbps = o.optInt("limitDownKbps", base.limitDownKbps).coerceIn(0, 1_000_000),
        limitUpKbps = o.optInt("limitUpKbps", base.limitUpKbps).coerceIn(0, 1_000_000),
        themeMode = themeMode,
        language = language,
        keepScreenOn = o.optBoolean("keepScreenOn", base.keepScreenOn),
        requireVpn = o.optBoolean("requireVpn", base.requireVpn),
        clientNames = clientNames,
        batterySaver = o.optBoolean("batterySaver", base.batterySaver),
        startOnBoot = o.optBoolean("startOnBoot", base.startOnBoot),
        idleStopMinutes = idleStopMinutes,
        outboundMode = outboundMode,
        exitIpUrl = o.optString("exitIpUrl", base.exitIpUrl).trim(),
    )
}
