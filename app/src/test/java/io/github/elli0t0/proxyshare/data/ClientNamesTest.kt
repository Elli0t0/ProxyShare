package io.github.elli0t0.proxyshare.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientNamesTest {

    @Test
    fun putTrimsAndCapsNameAt24Chars() {
        val names = putClientName(emptyMap(), "192.168.1.5", "  My Laptop With A Very Long Name  ")
        assertEquals("My Laptop With A Very Lo", names["192.168.1.5"])
    }

    @Test
    fun putBlankNameRemovesTheEntry() {
        val names = putClientName(mapOf("192.168.1.5" to "Laptop"), "192.168.1.5", "   ")
        assertFalse(names.containsKey("192.168.1.5"))
    }

    @Test
    fun putEvictsOldestPastFiftyEntries() {
        var names = emptyMap<String, String>()
        for (i in 0 until 51) {
            names = putClientName(names, "10.0.0.$i", "Device $i")
        }
        assertEquals(MAX_CLIENT_NAMES, names.size)
        assertFalse(names.containsKey("10.0.0.0"))
        assertTrue(names.containsKey("10.0.0.50"))
    }

    @Test
    fun parseAndSerializeRoundTrip() {
        val names = mapOf("192.168.1.5" to "Laptop", "192.168.1.9" to "Phone")
        assertEquals(names, parseClientNames(clientNamesJson(names)))
    }

    @Test
    fun parseSkipsMalformedEntries() {
        assertEquals(emptyMap<String, String>(), parseClientNames("not json"))
        assertEquals(emptyMap<String, String>(), parseClientNames(""))
        val parsed = parseClientNames("""{"192.168.1.5": "  ", "192.168.1.9": "Phone"}""")
        assertEquals(mapOf("192.168.1.9" to "Phone"), parsed)
    }
}
