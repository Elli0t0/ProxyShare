package com.example.proxyshare.data

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
                "port=8080\n" +
                "protocols=http,socks4,socks5\n" +
                "auth_user=proxy\n" +
                "auth_pass=secret\n" +
                "private_only=true\n" +
                "max_connections=64\n" +
                "idle_timeout_s=300\n" +
                "connect_timeout_s=10\n",
            config
        )
    }

    @Test
    fun emptyAuthPassOmitsAuthKeys() {
        val config = AppSettings().toEngineConfig()
        assertFalse(config.contains("auth_user"))
        assertFalse(config.contains("auth_pass"))
    }

    @Test
    fun authDisabledOmitsAuthKeys() {
        val config = AppSettings(authEnabled = false, authPass = "secret").toEngineConfig()
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
    fun randomPasswordHasLengthAndChars() {
        val password = randomPassword()
        assertEquals(12, password.length)
        val allowed = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        assertTrue(password.all { it in allowed })
        assertEquals(8, randomPassword(8).length)
    }
}
