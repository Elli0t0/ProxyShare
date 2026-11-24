package io.github.elli0t0.proxyshare.ui.screens

import io.github.elli0t0.proxyshare.engine.DiagLine

/**
 * How many engine WARN lines say Direct traffic is failing
 * (0.6.7, the lockdown warning's conservative trigger). Two
 * signals, both from the engine, never guessed from system
 * settings:
 * - "direct route requested but no direct network is set" — the
 *   Direct fallback WARN (connector.rs, at most once a minute).
 * - "connect to host:port failed: ..." — a connect that failed
 *   with a network error (http/socks handlers).
 *
 * The second line does not name the route, so a failed Proxy
 * connect also counts: the card says "may not work" and only
 * shows when a Direct rule exists, so the signal stays
 * conservative — it waits for an observed failure instead of
 * guessing from the VPN's lockdown setting. `count` is the
 * repeat count of a collapsed line (see parseDiagLog).
 */
fun directFailureWarnCount(lines: List<DiagLine>): Int =
    lines.filter { it.level == "WARN" }.sumOf { line ->
        val n = line.count
        when {
            line.msg.startsWith("direct route requested but no direct network is set") -> n
            line.msg.startsWith("connect to ") && line.msg.contains(" failed: ") -> n
            else -> 0
        }
    }
