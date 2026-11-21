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
}
