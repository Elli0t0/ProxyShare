package io.github.elli0t0.proxyshare.ui.screens

import io.github.elli0t0.proxyshare.data.IfKind
import io.github.elli0t0.proxyshare.data.NetIf
import io.github.elli0t0.proxyshare.engine.ConnInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsLogicTest {

    private fun conn(
        id: Long,
        client: String = "10.0.0.2",
        up: Long = 0,
        down: Long = 0,
    ) = ConnInfo(id, client, "example.com", 80, "http", 1_000L, up, down)

    @Test
    fun newConnectionHasZeroSpeed() {
        val speeds = computeSpeeds(emptyMap(), listOf(conn(1, up = 500, down = 700)), 1000)
        assertEquals(Speed(0, 0), speeds[1])
    }

    @Test
    fun growingConnectionSpeedIsDeltaOverTime() {
        val prev = mapOf(1L to conn(1, up = 1000, down = 2000))
        val speeds = computeSpeeds(prev, listOf(conn(1, up = 3000, down = 5000)), 1000)
        assertEquals(Speed(2000, 3000), speeds[1])
    }

    @Test
    fun shrinkingCounterIsClampedToZero() {
        val prev = mapOf(1L to conn(1, up = 5000, down = 5000))
        val speeds = computeSpeeds(prev, listOf(conn(1, up = 3000, down = 4000)), 1000)
        assertEquals(Speed(0, 0), speeds[1])
    }

    @Test
    fun formatBytesBoundaries() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("5 GB", formatBytes(5L * 1024 * 1024 * 1024))
    }

    @Test
    fun groupingByClientSortsByTraffic() {
        val conns = listOf(
            conn(1, client = "10.0.0.2", up = 100, down = 100),
            conn(2, client = "10.0.0.3", up = 5000, down = 0),
            conn(3, client = "10.0.0.2", up = 50, down = 50),
        )
        val groups = groupByClient(conns)
        assertEquals(listOf("10.0.0.3", "10.0.0.2"), groups.map { it.client })
        assertEquals(5000L, groups[0].totalUp)
        assertEquals(0L, groups[0].totalDown)
        assertEquals(150L, groups[1].totalUp)
        assertEquals(150L, groups[1].totalDown)
        assertEquals(2, groups[1].connections.size)
    }

    @Test
    fun formatDurationValues() {
        assertEquals("0s", formatDuration(0))
        assertEquals("12s", formatDuration(12_000))
        assertEquals("3m 12s", formatDuration(192_000))
        assertEquals("1h 1m", formatDuration(3_660_000))
    }

    @Test
    fun advertiseAddressSkipsMobileOnly() {
        val mobile = NetIf("rmnet0", "10.0.0.5", IfKind.MOBILE)
        assertEquals(null, advertiseAddress(listOf(mobile), 8080, 1080))
    }

    @Test
    fun advertiseAddressUsesPrimaryWithHttpPort() {
        val wifi = NetIf("wlan0", "192.168.1.2", IfKind.WIFI)
        assertEquals("192.168.1.2:8080", advertiseAddress(listOf(wifi), 8080, 1080))
    }

    @Test
    fun advertiseAddressFallsBackToSocksPort() {
        val wifi = NetIf("wlan0", "192.168.1.2", IfKind.WIFI)
        assertEquals("192.168.1.2:1080", advertiseAddress(listOf(wifi), null, 1080))
    }

    @Test
    fun advertiseAddressEmptyIsNull() {
        assertEquals(null, advertiseAddress(emptyList(), 8080, 1080))
    }

    @Test
    fun advertiseAddressPrefersRankedPrimary() {
        val wifi = NetIf("wlan0", "192.168.1.2", IfKind.WIFI)
        val mobile = NetIf("rmnet0", "10.0.0.5", IfKind.MOBILE)
        assertEquals("192.168.1.2:8080", advertiseAddress(listOf(wifi, mobile), 8080, null))
    }

    @Test
    fun mobileOnlyIsTrueForAllMobile() {
        val mobile = NetIf("rmnet0", "10.0.0.5", IfKind.MOBILE)
        assertTrue(isMobileOnly(listOf(mobile, mobile)))
    }

    @Test
    fun mobileOnlyIsFalseWhenAnythingElseRanked() {
        val wifi = NetIf("wlan0", "192.168.1.2", IfKind.WIFI)
        val mobile = NetIf("rmnet0", "10.0.0.5", IfKind.MOBILE)
        assertFalse(isMobileOnly(listOf(wifi, mobile)))
        assertFalse(isMobileOnly(listOf(wifi)))
    }

    @Test
    fun mobileOnlyIsFalseForEmptyList() {
        assertFalse(isMobileOnly(emptyList()))
    }

    private fun conn() = ConnInfo(
        id = 1, client = "192.168.1.10", host = "example.com", port = 443,
        proto = "https", startedMs = 1000, up = 500, down = 4000,
    )

    @Test
    fun sessionLogEntryComputesDuration() {
        val entry = sessionLogEntry(conn(), closedAtMs = 4000)
        assertEquals("192.168.1.10", entry.client)
        assertEquals("example.com", entry.host)
        assertEquals(443, entry.port)
        assertEquals("https", entry.proto)
        assertEquals(500L, entry.up)
        assertEquals(4000L, entry.down)
        assertEquals(3000L, entry.durationMs)
        assertEquals(4000L, entry.closedAtMs)
        assertEquals(false, entry.blocked)
    }

    @Test
    fun sessionLogEntryCopiesBlockedFlag() {
        assertEquals(true, sessionLogEntry(conn().copy(blocked = true), closedAtMs = 4000).blocked)
        assertEquals(false, sessionLogEntry(conn(), closedAtMs = 4000).blocked)
    }

    @Test
    fun sessionLogJsonFlagsBlockedEntries() {
        val json = sessionLogJson(
            listOf(
                sessionLogEntry(conn(), closedAtMs = 4000),
                sessionLogEntry(conn().copy(blocked = true), closedAtMs = 5000),
            )
        )
        assertTrue(json.contains("\"blocked\":true"))
        // Only the blocked entry carries the flag.
        assertEquals(1, json.split("\"blocked\":true").size - 1)
    }

    @Test
    fun sessionLogEntryClampsNegativeDuration() {
        assertEquals(0L, sessionLogEntry(conn(), closedAtMs = 500).durationMs)
    }

    @Test
    fun pushLogEntryCapsAt500() {
        val log = (1..500).map { sessionLogEntry(conn().copy(host = "h$it"), closedAtMs = 1000L) }
        val capped = pushLogEntry(log, sessionLogEntry(conn().copy(host = "h501"), closedAtMs = 1000L))
        assertEquals(500, capped.size)
        assertEquals("h2", capped.first().host) // oldest entry (h1) dropped
        assertEquals("h501", capped.last().host)
    }

    @Test
    fun parseSessionLogReadsLogIdAndEntry() {
        val text = "1\t7\t192.168.1.10\texample.com\t443\thttps\t1000\t500\t4000\t0\n" +
            "2\t8\t192.168.1.10\tads.example.com\t443\thttps\t2000\t100\t200\t1\n"
        val entries = parseSessionLog(text, closedAtMs = 4000)
        assertEquals(2, entries.size)
        assertEquals(1L, entries[0].first)
        assertEquals("example.com", entries[0].second.host)
        assertEquals(3000L, entries[0].second.durationMs)
        assertEquals(false, entries[0].second.blocked)
        assertEquals(2L, entries[1].first)
        assertEquals("ads.example.com", entries[1].second.host)
        assertEquals(true, entries[1].second.blocked)
        assertEquals(2000L, entries[1].second.durationMs)
    }

    @Test
    fun parseSessionLogSkipsMalformedLines() {
        val text = "1\t7\t192.168.1.10\texample.com\t443\thttps\t1000\t500\t4000\t0\n" +
            "bad line\n" +
            "x\t8\t192.168.1.10\texample.com\t443\thttps\t1000\t500\t4000\t0\n" +
            "3\t9\t192.168.1.10\texample.com\n"
        assertEquals(1, parseSessionLog(text, closedAtMs = 4000).size)
        assertEquals(emptyList<Pair<Long, SessionLogEntry>>(), parseSessionLog("", closedAtMs = 4000))
    }

    @Test
    fun sessionLogJsonIsAnArrayOfObjects() {
        val json = sessionLogJson(
            listOf(
                sessionLogEntry(conn(), closedAtMs = 4000),
                sessionLogEntry(conn().copy(host = "exa\"mple", port = 80), closedAtMs = 5000),
            )
        )
        assertTrue(json.startsWith("[{\"client\":\"192.168.1.10\",\"host\":\"example.com\""))
        assertTrue(json.contains("\"port\":443"))
        assertTrue(json.contains("\"durationMs\":3000"))
        // Quotes in a host are escaped, not raw.
        assertTrue(json.contains("exa\\\"mple"))
        assertTrue(json.endsWith("}]"))
    }

    @Test
    fun sessionLogJsonEmptyIsEmptyArray() {
        assertEquals("[]", sessionLogJson(emptyList()))
    }

    @Test
    fun filterSessionLogMatchesHostOrClientAndBlockedOnly() {
        val log = listOf(
            sessionLogEntry(conn().copy(host = "ads.example.com"), closedAtMs = 1000L),
            sessionLogEntry(
                conn().copy(host = "news.example.com", client = "192.168.1.9"),
                closedAtMs = 2000L,
            ),
            sessionLogEntry(
                conn().copy(host = "ads.example.com", blocked = true),
                closedAtMs = 3000L,
            ),
        )
        assertEquals(3, filterSessionLog(log, "", false).size)
        assertEquals(2, filterSessionLog(log, "ads", false).size)
        assertEquals(1, filterSessionLog(log, "192.168.1.9", false).size)
        assertEquals(1, filterSessionLog(log, "", true).size)
        assertEquals(1, filterSessionLog(log, "ADS", true).size)
        assertEquals(0, filterSessionLog(log, "nope", false).size)
    }
}
