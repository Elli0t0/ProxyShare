package com.example.proxyshare.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyControllerTest {

    @Test
    fun deniedClientsTracksBlockedIps() {
        val denied = DeniedClients()
        assertEquals(false, denied.isDenied("192.168.1.5"))
        denied.deny("192.168.1.5")
        denied.deny("192.168.1.5")
        assertEquals(true, denied.isDenied("192.168.1.5"))
        assertEquals(setOf("192.168.1.5"), denied.snapshot())
        denied.allow("192.168.1.5")
        assertEquals(false, denied.isDenied("192.168.1.5"))
        // Allowing an IP that was never denied is a no-op.
        denied.allow("10.0.0.1")
        assertEquals(emptySet<String>(), denied.snapshot())
    }

    @Test
    fun deniedClientsClearEmptiesTheSet() {
        val denied = DeniedClients()
        denied.deny("192.168.1.5")
        denied.deny("192.168.1.9")
        denied.clear()
        assertEquals(emptySet<String>(), denied.snapshot())
    }
}
