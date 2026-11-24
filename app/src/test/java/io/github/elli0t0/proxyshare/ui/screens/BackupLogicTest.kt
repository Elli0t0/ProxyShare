package io.github.elli0t0.proxyshare.ui.screens

import io.github.elli0t0.proxyshare.data.AppSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupLogicTest {

    // The real validators live in Rust and need the native library;
    // the tests pass stubs, so only the JSON parsing is exercised here.
    private val acceptAll: (String) -> Boolean = { true }

    private fun fullSettings() = AppSettings(
        bindAddr = "192.168.1.2",
        port = 1234,
        separatePorts = true,
        httpPort = 8081,
        socksPort = 1081,
        protocols = setOf("http", "socks5"),
        authEnabled = true,
        authUser = "alice",
        authPass = "secret",
        allowedClients = listOf("192.168.1.0/24"),
        blockedDomains = listOf("ads.example.com", "*.tracker.example"),
        privateOnly = false,
        maxConnections = 100,
        idleTimeoutS = 60,
        connectTimeoutS = 5,
        limitDownKbps = 2000,
        limitUpKbps = 1000,
        themeMode = "dark",
        language = "en",
        keepScreenOn = true,
        requireVpn = true,
        clientNames = mapOf("192.168.1.5" to "Laptop"),
        batterySaver = false,
        startOnBoot = true,
        idleStopMinutes = 30,
        outboundMode = "wifi",
        exitIpUrl = "https://example.com/ip",
    )

    @Test
    fun roundTripEqualsOriginalMinusSecrets() {
        val original = fullSettings()
        val restored = settingsFromBackupJson(
            original.toBackupJson(),
            isValidCidr = acceptAll,
            isValidDomain = acceptAll,
        ).getOrThrow()
        assertEquals(original.copy(authUser = "proxy", authPass = ""), restored)
    }

    @Test
    fun secretsAbsentFromExport() {
        val json = fullSettings().toBackupJson()
        assertFalse(JSONObject(json).has("authPass"))
        assertFalse(JSONObject(json).has("authUser"))
        assertFalse(json.contains("secret"))
    }

    @Test
    fun garbageTextFails() {
        assertTrue(settingsFromBackupJson("not json at all").isFailure)
        assertTrue(settingsFromBackupJson("{}").isFailure)
        assertTrue(settingsFromBackupJson("{\"version\": 0}").isFailure)
    }

    @Test
    fun outOfRangePortIsClamped() {
        val restored = settingsFromBackupJson(
            "{\"version\": 1, \"port\": 99999, \"httpPort\": 0, \"maxConnections\": 99999}",
            isValidCidr = acceptAll,
            isValidDomain = acceptAll,
        ).getOrThrow()
        assertEquals(65535, restored.port)
        assertEquals(1, restored.httpPort)
        assertEquals(2000, restored.maxConnections)
    }

    @Test
    fun missingKeysKeepDefaults() {
        val restored = settingsFromBackupJson(
            "{\"version\": 1, \"port\": 1234}",
            isValidCidr = acceptAll,
            isValidDomain = acceptAll,
        ).getOrThrow()
        assertEquals(AppSettings().copy(port = 1234), restored)
    }

    @Test
    fun invalidListEntriesAreDropped() {
        val restored = settingsFromBackupJson(
            "{\"version\": 1, " +
                "\"allowedClients\": [\"10.0.0.0/8\", \"nonsense\"], " +
                "\"blockedDomains\": [\"ads.example.com\", \"bad domain\"]}",
            isValidCidr = { it.startsWith("10.") },
            isValidDomain = { it == "ads.example.com" },
        ).getOrThrow()
        assertEquals(listOf("10.0.0.0/8"), restored.allowedClients)
        assertEquals(listOf("ads.example.com"), restored.blockedDomains)
    }
}
