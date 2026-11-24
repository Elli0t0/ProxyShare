package io.github.elli0t0.proxyshare.ui.screens

import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.data.AppSettings
import io.github.elli0t0.proxyshare.data.BUILTIN_RULE_SET_META
import io.github.elli0t0.proxyshare.data.RuleAction
import io.github.elli0t0.proxyshare.data.RuleEntry
import io.github.elli0t0.proxyshare.data.RuleSetMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLogicTest {

    @Test
    fun intRangeValidation() {
        assertEquals(5, validateIntInRange("5", 1..512))
        assertNull(validateIntInRange("0", 1..512))
        assertNull(validateIntInRange("abc", 1..512))
    }

    @Test
    fun idleStopLabelResMapsMinutes() {
        assertEquals(R.string.idle_stop_off, idleStopLabelRes(0))
        assertEquals(R.string.idle_stop_15, idleStopLabelRes(15))
        assertEquals(R.string.idle_stop_30, idleStopLabelRes(30))
        assertEquals(R.string.idle_stop_60, idleStopLabelRes(60))
        assertEquals(R.string.idle_stop_120, idleStopLabelRes(120))
        assertEquals(R.string.idle_stop_off, idleStopLabelRes(999))
    }

    @Test
    fun serverSummaryDefaults() {
        assertEquals("HTTP 8080 · SOCKS 1080", serverSummary(AppSettings()))
    }

    @Test
    fun rulesSummaryCountsRulesAndLists() {
        assertEquals("No rules yet", rulesSummary(AppSettings()))
        assertEquals(
            "1 rule",
            rulesSummary(AppSettings(rules = listOf(RuleEntry("a.com", RuleAction.BLOCK)))),
        )
        assertEquals(
            "2 rules · 1 rule set",
            rulesSummary(
                AppSettings(
                    rules = listOf(
                        RuleEntry("a.com", RuleAction.BLOCK),
                        RuleEntry("b.com", RuleAction.DIRECT),
                    ),
                    ruleSets = listOf(
                        BUILTIN_RULE_SET_META,
                        RuleSetMeta("c1", "Work", RuleAction.DIRECT, true, false, 5),
                    ),
                ),
            ),
        )
    }

    @Test
    fun serverSummarySinglePort() {
        assertEquals("Port 9090", serverSummary(AppSettings(separatePorts = false, port = 9090)))
    }

    @Test
    fun serverSummarySocksOnly() {
        assertEquals("SOCKS 1080", serverSummary(AppSettings(protocols = setOf("socks5"))))
    }

    @Test
    fun securitySummaryVariants() {
        assertEquals("Password on · private only", securitySummary(AppSettings()))
        assertEquals(
            "No password",
            securitySummary(AppSettings(authEnabled = false, privateOnly = false)),
        )
        assertEquals("Password on", securitySummary(AppSettings(privateOnly = false)))
    }

    @Test
    fun formatSpeedKbpsValues() {
        assertEquals("500 KB/s", formatSpeedKbps(500))
        assertEquals("2 MB/s", formatSpeedKbps(2000))
        assertEquals("2.5 MB/s", formatSpeedKbps(2500))
    }

    @Test
    fun limitsSummaryVariants() {
        assertEquals("Unlimited", limitsSummary(AppSettings()))
        assertEquals(
            "↓ 2 MB/s ↑ 1 MB/s",
            limitsSummary(AppSettings(limitDownKbps = 2000, limitUpKbps = 1000)),
        )
        assertEquals("↓ 500 KB/s", limitsSummary(AppSettings(limitDownKbps = 500)))
    }

    @Test
    fun batterySummaryVariants() {
        assertEquals("Saver on · Boot off", batterySummary(AppSettings()))
        assertEquals(
            "Saver off · Boot on",
            batterySummary(AppSettings(batterySaver = false, startOnBoot = true)),
        )
    }

    @Test
    fun appearanceSummaryVariants() {
        assertEquals("System theme", appearanceSummary(AppSettings()))
        assertEquals("Light theme", appearanceSummary(AppSettings(themeMode = "light")))
        assertEquals("Dark theme", appearanceSummary(AppSettings(themeMode = "dark")))
    }
}
