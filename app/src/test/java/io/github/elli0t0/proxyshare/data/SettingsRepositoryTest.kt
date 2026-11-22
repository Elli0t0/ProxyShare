package io.github.elli0t0.proxyshare.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun defaultsMapToEngineConfig() {
        val config = AppSettings(authPass = "secret").toEngineConfig()
        assertEquals(
            "bind_addr=0.0.0.0\n" +
                "http_port=8080\n" +
                "socks_port=1080\n" +
                "protocols=http,socks4,socks5\n" +
                "auth_user=proxy\n" +
                "auth_pass=secret\n" +
                "private_only=true\n" +
                "max_connections=256\n" +
                "idle_timeout_s=300\n" +
                "connect_timeout_s=10\n",
            config
        )
    }

    @Test
    fun singleModeWritesPort() {
        val config = AppSettings(authPass = "secret", separatePorts = false).toEngineConfig()
        assertTrue(config.contains("port=8080\n"))
        assertFalse(config.contains("http_port"))
        assertFalse(config.contains("socks_port"))
    }

    @Test
    fun splitModeOnlyHttpWritesOnlyHttpPort() {
        val config = AppSettings(authPass = "secret", protocols = setOf("http")).toEngineConfig()
        assertTrue(config.contains("http_port=8080\n"))
        assertFalse(config.contains("socks_port"))
    }

    @Test
    fun splitModeOnlySocksWritesOnlySocksPort() {
        val config = AppSettings(
            authPass = "secret",
            protocols = setOf("socks5"),
        ).toEngineConfig()
        assertFalse(config.contains("http_port"))
        assertTrue(config.contains("socks_port=1080\n"))
    }

    @Test
    fun httpPortOnlyWhenHttpEnabled() {
        // HTTP off, SOCKS on: no http_port line even in split mode.
        val config = AppSettings(
            authPass = "secret",
            protocols = setOf("socks4", "socks5"),
        ).toEngineConfig()
        assertFalse(config.contains("http_port"))
        assertTrue(config.contains("socks_port=1080\n"))
    }

    @Test
    fun emptyAuthPassOmitsAuthKeys() {
        val config = AppSettings().toEngineConfig()
        assertFalse(config.contains("auth_user"))
        assertFalse(config.contains("auth_pass"))
    }

    @Test
    fun authDisabledOmitsAuthKeys() {
        // Stored password, but auth off: no auth lines at all.
        val config = AppSettings(
            authEnabled = false,
            authUser = "proxy",
            authPass = "secret",
        ).toEngineConfig()
        assertFalse(config.contains("auth_user"))
        assertFalse(config.contains("auth_pass"))
    }

    @Test
    fun emptyAllowedClientsOmitsKey() {
        val config = AppSettings(authPass = "secret").toEngineConfig()
        assertFalse(config.contains("allowed_clients"))
    }

    @Test
    fun allowedClientsAreJoined() {
        val config = AppSettings(
            authPass = "secret",
            allowedClients = listOf("192.168.1.0/24", "10.0.0.5"),
        ).toEngineConfig()
        assertTrue(config.contains("allowed_clients=192.168.1.0/24,10.0.0.5\n"))
    }

    @Test
    fun blockedDomainsAreJoined() {
        val config = AppSettings(
            authPass = "secret",
            blockedDomains = listOf("ads.example.com", "*.tracker.example"),
        ).toEngineConfig()
        assertTrue(config.contains("blocked_domains=ads.example.com,*.tracker.example\n"))
    }

    @Test
    fun outboundSettingsDoNotChangeEngineConfig() {
        val base = AppSettings(authPass = "secret").toEngineConfig()
        val changed = AppSettings(
            authPass = "secret",
            outboundMode = "vpn_only",
            exitIpUrl = "https://example.org",
        ).toEngineConfig()
        assertEquals(base, changed)
    }

    @Test
    fun batterySaverDoesNotChangeEngineConfig() {
        val base = AppSettings(authPass = "secret").toEngineConfig()
        val changed = AppSettings(authPass = "secret", batterySaver = false).toEngineConfig()
        assertEquals(base, changed)
    }

    @Test
    fun startOnBootDoesNotChangeEngineConfig() {
        val base = AppSettings(authPass = "secret").toEngineConfig()
        val changed = AppSettings(authPass = "secret", startOnBoot = true).toEngineConfig()
        assertEquals(base, changed)
    }

    @Test
    fun idleStopMinutesDefaultIsOff() {
        assertEquals(0, AppSettings().idleStopMinutes)
    }

    @Test
    fun idleStopMinutesWritesIdleStopSecs() {
        val base = AppSettings(authPass = "secret").toEngineConfig()
        assertFalse(base.contains("idle_stop_secs"))
        val changed = AppSettings(authPass = "secret", idleStopMinutes = 30).toEngineConfig()
        assertTrue(changed.contains("idle_stop_secs=1800"))
    }

    @Test
    fun randomPasswordHasLengthAndChars() {
        val password = randomPassword()
        assertEquals(12, password.length)
        val allowed = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        assertTrue(password.all { it in allowed })
        assertEquals(8, randomPassword(8).length)
    }

    @Test
    fun limitsDefaultToZeroAndAreOmitted() {
        val config = AppSettings().toEngineConfig()
        assertTrue(!config.contains("limit_down_kbps"))
        assertTrue(!config.contains("limit_up_kbps"))
    }

    @Test
    fun limitsAreSentWhenSet() {
        val config = AppSettings(limitDownKbps = 512, limitUpKbps = 128).toEngineConfig()
        assertTrue(config.contains("limit_down_kbps=512\n"))
        assertTrue(config.contains("limit_up_kbps=128\n"))
    }

    @Test
    fun requireVpnDefaultsToOffAndIsOmitted() {
        val config = AppSettings().toEngineConfig()
        assertFalse(AppSettings().requireVpn)
        assertTrue(!config.contains("require_vpn"))
    }

    @Test
    fun requireVpnIsSentWhenOn() {
        val config = AppSettings(requireVpn = true).toEngineConfig()
        assertTrue(config.contains("require_vpn=true\n"))
    }
}
