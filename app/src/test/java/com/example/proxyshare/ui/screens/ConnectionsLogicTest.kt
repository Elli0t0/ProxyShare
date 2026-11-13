package com.example.proxyshare.ui.screens

import com.example.proxyshare.engine.ConnInfo
import org.junit.Assert.assertEquals
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
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("5.0 GB", formatBytes(5L * 1024 * 1024 * 1024))
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
}
