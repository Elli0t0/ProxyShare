package com.example.proxyshare.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyControllerTest {

    @Test
    fun zeroNewBytesSnapsToZero() {
        assertEquals(
            SpeedSample(0, 0),
            smoothSpeed(1000, 2000, 1000, 2000, 1000, SpeedSample(500, 600))
        )
    }

    @Test
    fun firstTickHalvesRawSpeed() {
        // 2000 new up bytes in 1 s, EMA from 0 with alpha 0.5 -> 1000.
        assertEquals(
            SpeedSample(1000, 1500),
            smoothSpeed(0, 0, 2000, 3000, 1000, SpeedSample(0, 0))
        )
    }

    @Test
    fun emaBlendsWithPrevious() {
        // Raw up 2000/s, prev 1000 -> 1500; down has no new bytes -> 0.
        assertEquals(
            SpeedSample(1500, 0),
            smoothSpeed(1000, 5000, 3000, 5000, 1000, SpeedSample(1000, 800))
        )
    }

    @Test
    fun elapsedTimeScalesSpeed() {
        // 2000 new bytes in 2 s -> 1000 raw, EMA from 0 -> 500.
        assertEquals(
            SpeedSample(500, 0),
            smoothSpeed(0, 0, 2000, 0, 2000, SpeedSample(0, 0))
        )
    }

    @Test
    fun pushSampleAppendsAndPreservesOrder() {
        val history = listOf(SpeedSample(1, 2), SpeedSample(3, 4))
        assertEquals(
            listOf(SpeedSample(1, 2), SpeedSample(3, 4), SpeedSample(5, 6)),
            pushSample(history, SpeedSample(5, 6))
        )
    }

    @Test
    fun pushSampleCapsAtMaxAndDropsOldest() {
        val history = (1..120).map { SpeedSample(it.toLong(), 0) }
        val result = pushSample(history, SpeedSample(121, 0))
        assertEquals(120, result.size)
        assertEquals(SpeedSample(2, 0), result.first())
        assertEquals(SpeedSample(121, 0), result.last())
    }

    @Test
    fun pushSampleRespectsMaxParam() {
        val history = listOf(SpeedSample(1, 0), SpeedSample(2, 0), SpeedSample(3, 0))
        assertEquals(
            listOf(SpeedSample(2, 0), SpeedSample(3, 0), SpeedSample(4, 0)),
            pushSample(history, SpeedSample(4, 0), max = 3)
        )
    }

    @Test
    fun rebaselineTakesFreshTotalsAndZeroesSample() {
        assertEquals(
            SamplerState(9000, 8000, 12345, SpeedSample(0, 0)),
            rebaseline(9000, 8000, 12345)
        )
    }

    @Test
    fun firstTickAfterRebaselineCannotSpike() {
        // Bytes moved while hidden must not appear as speed: the first
        // tick after a rebaseline sees no delta from the fresh totals.
        val base = rebaseline(9000, 8000, 1000)
        assertEquals(
            SpeedSample(0, 0),
            smoothSpeed(base.prevUp, base.prevDown, 9000, 8000, 1000, base.prev)
        )
    }
}
