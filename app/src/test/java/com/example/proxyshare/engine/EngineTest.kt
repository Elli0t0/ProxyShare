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
    fun stateNameMapsToEngineState() {
        assertEquals(EngineState.Stopped, engineStateFromName("stopped"))
        assertEquals(EngineState.Starting, engineStateFromName("starting"))
        assertEquals(EngineState.Running, engineStateFromName("running"))
        assertEquals(EngineState.Error("boom"), engineStateFromName("error", "boom"))
        assertEquals(EngineState.Stopped, engineStateFromName("weird"))
    }
}
