package com.example.proxyshare.ui.components

import com.example.proxyshare.engine.EngineState
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerButtonTest {

    @Test
    fun stoppedMapsToStopped() {
        assertEquals(PowerState.Stopped, EngineState.Stopped.toPowerState())
    }

    @Test
    fun startingMapsToStarting() {
        assertEquals(PowerState.Starting, EngineState.Starting.toPowerState())
    }

    @Test
    fun runningMapsToRunning() {
        assertEquals(PowerState.Running, EngineState.Running.toPowerState())
    }

    @Test
    fun errorMapsToError() {
        assertEquals(PowerState.Error, EngineState.Error("boom").toPowerState())
    }
}
