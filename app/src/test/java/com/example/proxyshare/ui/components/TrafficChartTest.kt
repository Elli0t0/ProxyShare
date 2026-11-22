package com.example.proxyshare.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficChartTest {

    @Test
    fun zeroAndNegativeScaleToTenKilobytes() {
        assertEquals(10_240L, niceCeiling(0))
        assertEquals(10_240L, niceCeiling(-5))
    }

    @Test
    fun floorValueIsKept() {
        assertEquals(10_240L, niceCeiling(10_240))
        assertEquals(10_240L, niceCeiling(5120))
    }

    @Test
    fun justAboveFloorRoundsToTwentyKilobytes() {
        assertEquals(20_480L, niceCeiling(10_241))
        assertEquals(20_480L, niceCeiling(11_264))
    }

    @Test
    fun megabytesRoundInTheDisplayUnit() {
        // Exact nice values are kept, so the label reads "5 MB/s".
        assertEquals(5L * 1024 * 1024, niceCeiling(5L * 1024 * 1024))
        assertEquals(20L * 1024 * 1024, niceCeiling(20L * 1024 * 1024))
        assertEquals(500L * 1024, niceCeiling(500L * 1024))
        // The old decimal ladder rounded 4.8 MB up to 5,000,000 bytes,
        // which the label rendered as "4.8 MB/s"; now it is 5 MB.
        assertEquals(5L * 1024 * 1024, niceCeiling(4_800_000))
    }

    @Test
    fun aboveLargestStepGoesToNextUnit() {
        assertEquals(1024L * 1024, niceCeiling(600L * 1024))
    }

    @Test
    fun chartMaxWithLimitKeepsTheLimitOnScale() {
        // No limit: the ceiling follows the peak.
        assertEquals(102_400L, chartMaxWithLimit(102_400L, 0L))
        // A limit above the peak raises the ceiling to the limit.
        assertEquals(1024L * 1024, chartMaxWithLimit(0L, 512L * 1024))
        // A limit below the peak changes nothing.
        assertEquals(5L * 1024 * 1024, chartMaxWithLimit(5L * 1024 * 1024, 512L * 1024))
    }

    @Test
    fun limitLineYPositionsTheLine() {
        // Half the max sits halfway down the plot.
        assertEquals(50f, limitLineY(512L * 1024, 1024L * 1024, 100f))
        // No limit: the line is at the bottom; at/above the max: at the top.
        assertEquals(100f, limitLineY(0L, 1024L * 1024, 100f))
        assertEquals(0f, limitLineY(2048L * 1024, 1024L * 1024, 100f))
        // A non-positive max never divides by zero.
        assertEquals(100f, limitLineY(512L * 1024, 0L, 100f))
    }
}
