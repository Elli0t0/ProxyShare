package com.example.proxyshare.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaptopCommandsTest {

    @Test
    fun commandsWithCredentials() {
        val cmds = laptopCommands("192.168.1.5", 8080, "proxy", "s3cret")
        assertEquals(3, cmds.size)
        assertEquals("curl (HTTP)", cmds[0].title)
        assertEquals(
            "curl -x http://proxy:s3cret@192.168.1.5:8080 https://ifconfig.me",
            cmds[0].text,
        )
        assertEquals(
            "curl --socks5-hostname proxy:s3cret@192.168.1.5:8080 https://ifconfig.me",
            cmds[1].text,
        )
        assertEquals(
            "export http_proxy=http://proxy:s3cret@192.168.1.5:8080 https_proxy=http://proxy:s3cret@192.168.1.5:8080",
            cmds[2].text,
        )
    }

    @Test
    fun commandsWithoutCredentials() {
        val cmds = laptopCommands("192.168.1.5", 8080, null, null)
        assertEquals(3, cmds.size)
        assertEquals("curl -x http://192.168.1.5:8080 https://ifconfig.me", cmds[0].text)
        assertEquals(
            "curl --socks5-hostname 192.168.1.5:8080 https://ifconfig.me",
            cmds[1].text,
        )
        assertFalse(cmds.any { "@" in it.text })
    }

    @Test
    fun credentialsAreUrlEncoded() {
        val cmds = laptopCommands("10.0.0.2", 8080, "us er", "p@ss")
        assertTrue(cmds[0].text.contains("us+er:p%40ss@10.0.0.2:8080"))
    }
}
