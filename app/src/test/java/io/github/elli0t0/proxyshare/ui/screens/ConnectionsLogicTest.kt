package io.github.elli0t0.proxyshare.ui.screens

import io.github.elli0t0.proxyshare.data.IfKind
import io.github.elli0t0.proxyshare.data.NetIf
import io.github.elli0t0.proxyshare.data.RuleAction
import io.github.elli0t0.proxyshare.data.RuleEntry
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
    fun otherAddressesSkipsPrimaryAndMobile() {
        val wifi = NetIf("wlan0", "192.168.1.2", IfKind.WIFI)
        val eth = NetIf("eth0", "192.168.1.3", IfKind.ETHERNET)
        val mobile = NetIf("rmnet0", "10.0.0.5", IfKind.MOBILE)
        assertEquals(
            listOf("192.168.1.3:8080"),
            otherAddresses(listOf(wifi, eth, mobile), 8080, 1080),
        )
    }

    @Test
    fun otherAddressesEmptyWhenOnlyPrimary() {
        val wifi = NetIf("wlan0", "192.168.1.2", IfKind.WIFI)
        assertEquals(emptyList<String>(), otherAddresses(listOf(wifi), 8080, 1080))
    }

    @Test
    fun otherAddressesFallsBackToSocksPort() {
        val wifi = NetIf("wlan0", "192.168.1.2", IfKind.WIFI)
        val eth = NetIf("eth0", "192.168.1.3", IfKind.ETHERNET)
        assertEquals(listOf("192.168.1.3:1080"), otherAddresses(listOf(wifi, eth), null, 1080))
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
    fun identicalSessionLogEntriesHaveDistinctIds() {
        // Same connection closed three times in the same ms with 0 bytes
        // moved: the LazyColumn keys must still be 3 distinct ids.
        val entries = List(3) { sessionLogEntry(conn(), closedAtMs = 4000) }
        assertEquals(3, entries.map { it.id }.toSet().size)
    }

    @Test
    fun sessionLogEntryCopiesBlockedFlag() {
        assertEquals(true, sessionLogEntry(conn().copy(blocked = true), closedAtMs = 4000).blocked)
        assertEquals(false, sessionLogEntry(conn(), closedAtMs = 4000).blocked)
    }

    @Test
    fun sessionLogJsonIncludesRoute() {
        val json = sessionLogJson(
            listOf(
                sessionLogEntry(conn(), closedAtMs = 4000),
                sessionLogEntry(conn().copy(route = "direct"), closedAtMs = 5000),
            )
        )
        assertTrue(json.contains("\"route\":\"proxy\""))
        assertTrue(json.contains("\"route\":\"direct\""))
    }

    @Test
    fun parseSessionLogReadsRouteField() {
        val text = "1\t7\t192.168.1.10\texample.com\t443\thttps\t1000\t500\t4000\t0\tdirect\n" +
            "2\t8\t192.168.1.10\tads.example.com\t443\thttps\t2000\t100\t200\t1\tblock\n" +
            "3\t9\t192.168.1.10\tnews.example.com\t443\thttps\t3000\t100\t200\t0\n"
        val entries = parseSessionLog(text, closedAtMs = 4000)
        assertEquals(3, entries.size)
        assertEquals("direct", entries[0].second.route)
        assertEquals("block", entries[1].second.route)
        // An older 10-field line has no route: proxy.
        assertEquals("proxy", entries[2].second.route)
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

    // Domains view (0.7.0).

    private fun entry(
        host: String,
        up: Long = 0,
        down: Long = 0,
        closedAtMs: Long = 1_000L,
        blocked: Boolean = false,
        route: String = "proxy",
    ) = SessionLogEntry("10.0.0.2", host, 443, "http", up, down, 100L, closedAtMs, blocked, route)

    private fun live(
        host: String,
        up: Long = 0,
        down: Long = 0,
        startedMs: Long = 1_000L,
        blocked: Boolean = false,
        route: String = "proxy",
    ) = ConnInfo(1L, "10.0.0.2", host, 443, "http", startedMs, up, down, blocked, route)

    @Test
    fun aggregationGroupsCaseAndTrailingDot() {
        val rows = aggregateDomains(
            listOf(
                entry("Example.COM", up = 100, down = 50),
                entry("example.com.", up = 10, down = 5),
                entry("EXAMPLE.com", up = 1, down = 1),
                entry("other.com", up = 7, down = 7),
            ),
            emptyList(),
        )
        assertEquals(2, rows.size)
        val main = rows.first { it.host == "example.com" }
        assertEquals(3, main.count)
        assertEquals(111L, main.up)
        assertEquals(56L, main.down)
        assertFalse(main.active)
        assertFalse(main.blocked)
    }

    @Test
    fun aggregationSumsAndNewestRouteWins() {
        val rows = aggregateDomains(
            listOf(
                entry("a.com", up = 100, closedAtMs = 1_000L, route = "direct"),
                entry("a.com", up = 50, closedAtMs = 2_000L, route = "proxy"),
            ),
            emptyList(),
        )
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].count)
        assertEquals(150L, rows[0].up)
        assertEquals("proxy", rows[0].route)
        assertEquals(2_000L, rows[0].lastSeenMs)
    }

    @Test
    fun aggregationBlockedFlagAndLiveIsNewest() {
        val rows = aggregateDomains(
            listOf(
                entry("a.com", closedAtMs = 5_000L, route = "direct"),
                entry("b.com", blocked = true),
            ),
            listOf(live("a.com", startedMs = 1_000L, route = "proxy")),
            nowMs = 9_999L,
        )
        val a = rows.first { it.host == "a.com" }
        assertTrue(a.active)
        assertEquals("proxy", a.route)
        assertEquals(9_999L, a.lastSeenMs)
        assertFalse(a.blocked)
        val b = rows.first { it.host == "b.com" }
        assertTrue(b.blocked)
        assertFalse(b.active)
    }

    @Test
    fun filterDomainsStatusRouteAndQuery() {
        val rows = listOf(
            DomainRow("a.com", 1, 10, 0, 1_000L, "proxy", false, true),
            DomainRow("b.com", 2, 20, 0, 1_000L, "direct", false, false),
            DomainRow("c.com", 3, 30, 0, 1_000L, "block", true, false),
        )
        assertEquals(3, filterDomains(rows, DomainStatusFilter.ALL, DomainRouteFilter.ALL, "").size)
        assertEquals(
            listOf("a.com"),
            filterDomains(rows, DomainStatusFilter.ACTIVE, DomainRouteFilter.ALL, "").map { it.host },
        )
        assertEquals(
            listOf("c.com"),
            filterDomains(rows, DomainStatusFilter.BLOCKED, DomainRouteFilter.ALL, "").map { it.host },
        )
        assertEquals(
            listOf("b.com"),
            filterDomains(rows, DomainStatusFilter.ALL, DomainRouteFilter.DIRECT, "").map { it.host },
        )
        assertEquals(
            listOf("b.com"),
            filterDomains(rows, DomainStatusFilter.ALL, DomainRouteFilter.ALL, "https://B.com/x").map { it.host },
        )
        assertEquals(
            0,
            filterDomains(rows, DomainStatusFilter.ACTIVE, DomainRouteFilter.BLOCK, "").size,
        )
    }

    @Test
    fun sortDomainsEachOrderIsDeterministic() {
        val rows = listOf(
            DomainRow("b.com", 5, 100, 0, 1_000L, "proxy", false, false),
            DomainRow("a.com", 5, 100, 0, 1_000L, "proxy", false, true),
            DomainRow("c.com", 1, 500, 0, 1_000L, "proxy", false, false),
        )
        // Ties break on host, so the order is the same every run.
        assertEquals(
            listOf("a.com", "c.com", "b.com"),
            sortDomains(rows, DomainSort.ACTIVE_FIRST).map { it.host },
        )
        assertEquals(
            listOf("c.com", "a.com", "b.com"),
            sortDomains(rows, DomainSort.MOST_TRAFFIC).map { it.host },
        )
        assertEquals(
            listOf("a.com", "b.com", "c.com"),
            sortDomains(rows, DomainSort.MOST_REQUESTS).map { it.host },
        )
        assertEquals(
            listOf("a.com", "b.com", "c.com"),
            sortDomains(rows, DomainSort.AZ).map { it.host },
        )
    }

    @Test
    fun normalizeDomainQueryStripsSchemePathQueryPortAndDot() {
        assertEquals("sub.example.com", normalizeDomainQuery("https://Sub.Example.com/path?q=1"))
        assertEquals("example.com", normalizeDomainQuery("example.com:443"))
        assertEquals("example.com", normalizeDomainQuery("EXAMPLE.COM."))
        assertEquals("a.com", normalizeDomainQuery("http://a.com#frag"))
        assertEquals("::1", normalizeDomainQuery("::1"))
        assertEquals("", normalizeDomainQuery("  "))
    }

    @Test
    fun ruleForHostFindsOnlyExactHostRules() {
        val rules = listOf(
            RuleEntry("a.com", RuleAction.BLOCK),
            RuleEntry("=b.com", RuleAction.DIRECT),
        )
        assertEquals(RuleEntry("a.com", RuleAction.BLOCK), ruleForHost("a.com", rules))
        assertEquals(RuleEntry("=b.com", RuleAction.DIRECT), ruleForHost("b.com", rules))
        assertEquals(null, ruleForHost("sub.a.com", rules))
        assertEquals(null, ruleForHost("c.com", rules))
    }
}
