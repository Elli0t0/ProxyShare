package io.github.elli0t0.proxyshare.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceLocksTest {

    @Test
    fun heldWithClientsAndBatterySaver() {
        assertEquals(true, shouldHoldServiceLocks(1, true))
        assertEquals(true, shouldHoldServiceLocks(3, true))
    }

    @Test
    fun heldWithoutBatterySaverEvenWithNoClients() {
        assertEquals(true, shouldHoldServiceLocks(0, false))
    }

    @Test
    fun releasedWithNoClientsAndBatterySaver() {
        assertEquals(false, shouldHoldServiceLocks(0, true))
    }
}
