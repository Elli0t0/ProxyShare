package io.github.elli0t0.proxyshare.ui.screens

import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.data.AppSettings
import io.github.elli0t0.proxyshare.engine.NativeEngine

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
