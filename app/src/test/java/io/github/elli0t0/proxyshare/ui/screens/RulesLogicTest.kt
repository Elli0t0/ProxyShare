package io.github.elli0t0.proxyshare.ui.screens

import io.github.elli0t0.proxyshare.engine.DiagLine
import org.junit.Assert.assertEquals
import org.junit.Test

class RulesLogicTest {

    private fun warn(msg: String, count: Int = 1) = DiagLine(1000, "WARN", msg, count)
    private fun info(msg: String) = DiagLine(1000, "INFO", msg)

    @Test
    fun directFailureWarnCountCountsBothSignals() {
        val lines = listOf(
            warn("direct route requested but no direct network is set; using the default network"),
            warn("connect to example.com:443 failed: Network is unreachable"),
            warn("connect to example.com:443 failed: Network is unreachable", count = 3),
            warn("auth failed from 192.168.1.10"),
            info("connect to example.com:443 failed: Network is unreachable"),
            info("started: proxy on 0.0.0.0:8080"),
        )
        // 1 fallback WARN + 1 connect failure + 3 repeats = 5.
        // The auth WARN, the INFO failure and the INFO start do not count.
        assertEquals(5, directFailureWarnCount(lines))
    }

    @Test
    fun directFailureWarnCountIsZeroWithoutFailures() {
        assertEquals(0, directFailureWarnCount(emptyList()))
        assertEquals(0, directFailureWarnCount(listOf(info("started: ok"))))
    }
}
