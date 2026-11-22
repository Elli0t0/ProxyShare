package com.example.proxyshare.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    @Test
    fun parsesGoodSnapshot() {
        val text = "running\t1024\t2048\t1\n" +
            "1\t192.168.1.5\texample.com\t443\thttp\t1794500000000\t1024\t2048\n"
        val snap = parseSnapshot(text)
        assertEquals(
            Snapshot(
                state = "running",
                totalUp = 1024L,
                totalDown = 2048L,
                active = 1,
                connections = listOf(
                    ConnInfo(
                        id = 1L,
                        client = "192.168.1.5",
                        host = "example.com",
                        port = 443,
                        proto = "http",
                        startedMs = 1794500000000L,
                        up = 1024L,
                        down = 2048L,
                    )
                ),
            ),
            snap
        )
    }

    @Test
    fun emptyTextReturnsNull() {
        assertNull(parseSnapshot(""))
        assertNull(parseSnapshot("\n\n"))
    }

    @Test
    fun parsesRejectedCountsFromHeadLine() {
        val snap = parseSnapshot("running\t0\t0\t1\t3\t1\t2\n")
        assertEquals(3L, snap?.rejectedLimit)
        assertEquals(1L, snap?.rejectedDenied)
        assertEquals(2L, snap?.rejectedNotAllowed)
    }

    @Test
    fun missingRejectedCountsDefaultToZero() {
        val snap = parseSnapshot("running\t0\t0\t0\n")
        assertEquals(0L, snap?.rejectedLimit)
        assertEquals(0L, snap?.rejectedDenied)
        assertEquals(0L, snap?.rejectedNotAllowed)
    }

    @Test
    fun shortHeadReturnsNull() {
        assertNull(parseSnapshot("running\t1024\n"))
    }

    @Test
    fun badHeadNumbersFallBackToZero() {
        val snap = parseSnapshot("running\tx\ty\tz\n")
        assertEquals("running", snap?.state)
        assertEquals(0L, snap?.totalUp)
        assertEquals(0L, snap?.totalDown)
        assertEquals(0, snap?.active)
    }

    @Test
    fun malformedConnectionLinesAreSkipped() {
        val text = "running\t10\t20\t2\n" +
            "bad line with no tabs\n" +
            "2\t10.0.0.1\thost\t80\tsocks5\t100\t5\t5\n" +
            "3\t10.0.0.2\thost\t80\tsocks5\t100\tnot-a-number\t5\n"
        val snap = parseSnapshot(text)
        assertEquals(1, snap?.connections?.size)
        assertEquals(2L, snap?.connections?.first()?.id)
    }

    @Test
    fun blankLinesAreIgnored() {
        val text = "\nrunning\t0\t0\t0\n\n"
        val snap = parseSnapshot(text)
        assertEquals("running", snap?.state)
        assertTrue(snap?.connections.isNullOrEmpty())
    }

    @Test
    fun parseConnLineRoundTripsSnapshotLine() {
        val conn = parseConnLine("7\t192.168.1.10\texample.com\t443\thttps\t1700000000000\t500\t4000")
        assertEquals(
            ConnInfo(
                id = 7, client = "192.168.1.10", host = "example.com", port = 443,
                proto = "https", startedMs = 1700000000000L, up = 500, down = 4000,
            ),
            conn,
        )
    }

    @Test
    fun parseConnLineReadsBlockedFlag() {
        val blocked = parseConnLine("7\t192.168.1.10\tads.example.com\t443\thttps\t1700000000000\t500\t4000\t1")
        assertEquals(true, blocked?.blocked)
        assertEquals("ads.example.com", blocked?.host)
        // An 8-field line (older engine) has no flag: not blocked.
        val plain = parseConnLine("7\t192.168.1.10\texample.com\t443\thttps\t1700000000000\t500\t4000")
        assertEquals(false, plain?.blocked)
    }

    @Test
    fun parseConnLineRejectsShortAndBadLines() {
        assertEquals(null, parseConnLine("7\t192.168.1.10\texample.com"))
        assertEquals(null, parseConnLine("x\t192.168.1.10\texample.com\t443\thttps\t1\t2\t3"))
        assertEquals(null, parseConnLine(""))
    }
    @Test
    fun liveTotalsIncludeActiveConnections() {
        val text = "running\t100\t200\t2\n" +
            "1\t10.0.0.2\thost\t80\thttp\t100\t10\t20\n" +
            "2\t10.0.0.3\thost\t443\tsocks5\t100\t30\t40\n"
        val snap = parseSnapshot(text)!!
        assertEquals(140L, snap.liveUp)
        assertEquals(260L, snap.liveDown)
    }

    @Test
    fun sessionTotalsSubtractBaseline() {
        assertEquals(150L to 250L, sessionTotals(1000L, 2000L, 850L, 1750L))
        assertEquals(0L to 0L, sessionTotals(100L, 100L, 200L, 200L))
        assertEquals(0L to 0L, sessionTotals(0L, 0L, 0L, 0L))
    }

    @Test
    fun stateNameMapsToEngineState() {
        assertEquals(EngineState.Stopped, engineStateFromName("stopped"))
        assertEquals(EngineState.Starting, engineStateFromName("starting"))
        assertEquals(EngineState.Running, engineStateFromName("running"))
        assertEquals(EngineState.Error("boom"), engineStateFromName("error", "boom"))
        assertEquals(EngineState.Stopped, engineStateFromName("weird"))
    }

    @Test
    fun parsesDiagLogLines() {
        val text = "1794500000000\tINFO\tstarted: bind 0.0.0.0:8080\t1\n" +
            "1794500001000\tWARN\tblocked ads.example.com from 192.168.1.5\t3\n" +
            "bad line\n" +
            "1794500002000\tERROR\tbind failed: address in use\n"
        val lines = parseDiagLog(text)
        assertEquals(3, lines.size)
        assertEquals(DiagLine(1794500000000L, "INFO", "started: bind 0.0.0.0:8080", 1), lines[0])
        assertEquals(
            DiagLine(1794500001000L, "WARN", "blocked ads.example.com from 192.168.1.5", 3),
            lines[1],
        )
        // A pre-0.4.4 line without the count field still parses, as count 1.
        assertEquals(
            DiagLine(1794500002000L, "ERROR", "bind failed: address in use", 1),
            lines[2],
        )
        assertEquals(emptyList<DiagLine>(), parseDiagLog(""))
    }

    @Test
    fun redactLogHidesCredentials() {
        assertEquals("auth_pass=***", redactLog("auth_pass=s3cret"))
        assertEquals("token=*** rest", redactLog("token=abc123 rest"))
        assertEquals(
            "see http://***@host:8080/x",
            redactLog("see http://user:pass@host:8080/x"),
        )
        assertEquals(
            "Proxy-Authorization: ***",
            redactLog("Proxy-Authorization: Basic dXNlcjpwYXNz"),
        )
        assertEquals(
            "blocked ads.example.com from 192.168.1.5",
            redactLog("blocked ads.example.com from 192.168.1.5"),
        )
    }
}
