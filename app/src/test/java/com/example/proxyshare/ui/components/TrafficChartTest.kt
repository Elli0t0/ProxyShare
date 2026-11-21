package com.example.proxyshare.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficChartTest {

    @Test
    fun zeroScalesToTenKilobytes() {
        assertEquals(10_240L, niceCeiling(0))
    }

    @Test
    fun exactNiceValueIsKept() {
        assertEquals(10L, niceCeiling(10))
        assertEquals(500L, niceCeiling(500))
    }

    @Test
    fun justAboveNiceValueRoundsUpOneStep() {
        assertEquals(20L, niceCeiling(11))
        assertEquals(1000L, niceCeiling(501))
    }

    @Test
    fun megabytesUseTheSameLadder() {
        assertEquals(2_000_000L, niceCeiling(1_500_000))
        assertEquals(5_000_000L, niceCeiling(2_000_001))
    }
}
