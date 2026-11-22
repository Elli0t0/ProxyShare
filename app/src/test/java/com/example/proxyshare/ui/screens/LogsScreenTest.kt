package com.example.proxyshare.ui.screens

import com.example.proxyshare.engine.DiagLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsScreenTest {

    private val lines = listOf(
        DiagLine(1794500000000L, "INFO", "started: bind 0.0.0.0:8080", 1),
        DiagLine(1794500001000L, "WARN", "blocked ads.example.com from 192.168.1.5", 3),
        DiagLine(1794500002000L, "ERROR", "bind failed: address in use", 1),
    )

    @Test
    fun fmtTsFormatsAsHoursMinutesSeconds() {
        // Timezone-safe: only the shape is asserted, never the hour.
        assertTrue(fmtTs(1794500000000L).matches(Regex("\\d{2}:\\d{2}:\\d{2}")))
        assertEquals(fmtTs(1794500000000L), fmtTs(1794500000000L))
    }

    @Test
    fun filterDiagLinesKeepsAllByDefault() {
        assertEquals(3, filterDiagLines(lines, "", 0).size)
    }

    @Test
    fun filterDiagLinesWarnPlusDropsInfo() {
        val out = filterDiagLines(lines, "", 1)
        assertEquals(2, out.size)
        assertTrue(out.all { it.level == "WARN" || it.level == "ERROR" })
    }

    @Test
    fun filterDiagLinesErrorOnlyKeepsErrors() {
        val out = filterDiagLines(lines, "", 2)
        assertEquals(1, out.size)
        assertEquals("ERROR", out[0].level)
    }

    @Test
    fun filterDiagLinesMatchesMessageCaseInsensitively() {
        assertEquals(1, filterDiagLines(lines, "ADS", 0).size)
        // "bind" matches two lines: the INFO start line and the ERROR line.
        assertEquals(2, filterDiagLines(lines, "bind", 0).size)
        assertEquals(0, filterDiagLines(lines, "nope", 0).size)
        assertEquals(0, filterDiagLines(lines, "ads", 2).size)
    }
}
