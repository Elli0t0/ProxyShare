package com.example.proxyshare.ui.screens

import com.example.proxyshare.data.IfKind
import com.example.proxyshare.data.NetIf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaptopCommandsTest {

    @Test
    fun commandsWithCredentials() {
        val cmds = laptopCommands("192.168.1.5", 8080, 1080, "proxy", "s3cret")
        assertEquals(6, cmds.size)
        assertEquals("curl (HTTP)", cmds[0].title)
        assertEquals(
            "curl -x http://proxy:s3cret@192.168.1.5:8080 https://ifconfig.me",
            cmds[0].text,
        )
        assertEquals(
            "export http_proxy=http://proxy:s3cret@192.168.1.5:8080 https_proxy=http://proxy:s3cret@192.168.1.5:8080",
            cmds[1].text,
        )
        assertEquals("curl (SOCKS5)", cmds[2].title)
        assertEquals(
            "curl --proxy socks5h://proxy:s3cret@192.168.1.5:1080 https://ifconfig.me",
            cmds[2].text,
        )
        assertEquals("Firefox", cmds[3].title)
        assertEquals(
            "Enable \"Proxy DNS when using SOCKS v5\" in Settings → Network Settings",
            cmds[3].text,
        )
        assertEquals("Chromium / Chrome", cmds[4].title)
        assertEquals(
            "chromium --proxy-server=\"socks5://192.168.1.5:1080\"",
            cmds[4].text,
        )
        assertEquals("GNOME proxy settings", cmds[5].title)
        assertTrue(cmds[5].text.contains("gsettings set org.gnome.system.proxy mode 'manual'"))
        assertTrue(cmds[5].text.contains("gsettings set org.gnome.system.proxy.socks host '192.168.1.5'"))
        assertTrue(cmds[5].text.contains("gsettings set org.gnome.system.proxy.socks port 1080"))
        assertTrue(cmds[5].text.contains("gsettings set org.gnome.system.proxy.http host '192.168.1.5'"))
        assertTrue(cmds[5].text.contains("gsettings set org.gnome.system.proxy.http port 8080"))
        assertTrue(cmds[5].text.contains("authentication-user 'proxy'"))
        assertTrue(cmds[5].text.contains("authentication-password 's3cret'"))
    }

    @Test
    fun commandsWithoutCredentials() {
        val cmds = laptopCommands("192.168.1.5", 8080, 1080, null, null)
        assertEquals(6, cmds.size)
        assertEquals("curl -x http://192.168.1.5:8080 https://ifconfig.me", cmds[0].text)
        assertEquals(
            "curl --proxy socks5h://192.168.1.5:1080 https://ifconfig.me",
            cmds[2].text,
        )
        assertEquals("Firefox", cmds[3].title)
        assertFalse(cmds.any { "@" in it.text })
        assertFalse(cmds[5].text.contains("authentication-"))
    }

    @Test
    fun credentialsAreUrlEncoded() {
        val cmds = laptopCommands("10.0.0.2", 8080, 1080, "us er", "p@ss")
        assertTrue(cmds[0].text.contains("us+er:p%40ss@10.0.0.2:8080"))
    }

    @Test
    fun nullHttpPortOmitsHttpCommands() {
        val cmds = laptopCommands("192.168.1.5", null, 1080, null, null)
        assertEquals(4, cmds.size)
        assertEquals("curl (SOCKS5)", cmds[0].title)
        assertTrue(cmds[0].text.contains(":1080"))
        assertEquals("Firefox", cmds[1].title)
        assertEquals("GNOME proxy settings", cmds[3].title)
        assertTrue(cmds[3].text.contains("socks port 1080"))
        assertFalse(cmds[3].text.contains("org.gnome.system.proxy.http"))
    }

    @Test
    fun nullSocksPortOmitsSocksCommands() {
        val cmds = laptopCommands("192.168.1.5", 8080, null, null, null)
        assertEquals(3, cmds.size)
        assertTrue(cmds.all { "SOCKS5" !in it.title })
        assertTrue(cmds.all { "Chromium" !in it.title })
        assertEquals("GNOME proxy settings", cmds[2].title)
        assertTrue(cmds[2].text.contains("org.gnome.system.proxy.http port 8080"))
        assertFalse(cmds[2].text.contains("org.gnome.system.proxy.socks"))
    }

    @Test
    fun bothPortsNullGivesNoCommands() {
        assertTrue(laptopCommands("192.168.1.5", null, null, null, null).isEmpty())
    }

    @Test
    fun sheetHostPrefersSelectionThenFirst() {
        val ranked = listOf(
            NetIf("wlan0", "192.168.1.5", IfKind.WIFI),
            NetIf("ap0", "192.168.43.1", IfKind.HOTSPOT),
        )
        assertEquals("192.168.1.5", resolveSheetHost(ranked, null))
        assertEquals("192.168.43.1", resolveSheetHost(ranked, "192.168.43.1"))
        // A stale selection (no longer in the list) falls back to first.
        assertEquals("192.168.1.5", resolveSheetHost(ranked, "10.0.0.9"))
        assertEquals(null, resolveSheetHost(emptyList(), null))
    }

    @Test
    fun sheetCommandHostUsesPlaceholderForMobileOnly() {
        val mobile = listOf(NetIf("rmnet0", "10.20.30.40", IfKind.MOBILE))
        assertEquals("PHONE_IP", sheetCommandHost(mobile, null))
        val wifi = listOf(NetIf("wlan0", "192.168.1.5", IfKind.WIFI))
        assertEquals("192.168.1.5", sheetCommandHost(wifi, null))
        assertEquals(null, sheetCommandHost(emptyList(), null))
    }
}
