package com.example.proxyshare.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkInfoTest {

    @Test
    fun vpnPrefixes() {
        assertEquals(IfKind.VPN, classifyInterface("tun0"))
        assertEquals(IfKind.VPN, classifyInterface("ppp0"))
        assertEquals(IfKind.VPN, classifyInterface("wg0"))
    }

    @Test
    fun hotspotPrefixes() {
        assertEquals(IfKind.HOTSPOT, classifyInterface("swlan0"))
        assertEquals(IfKind.HOTSPOT, classifyInterface("ap0"))
    }

    @Test
    fun wifiPrefixes() {
        assertEquals(IfKind.WIFI, classifyInterface("wlan0"))
        // Some phones use wlan1 for the hotspot; the name still says
        // Wi-Fi, and the UI always shows the raw interface name.
        assertEquals(IfKind.WIFI, classifyInterface("wlan1"))
    }

    @Test
    fun ethernetPrefixes() {
        assertEquals(IfKind.ETHERNET, classifyInterface("eth0"))
        assertEquals(IfKind.ETHERNET, classifyInterface("rndis0"))
    }

    @Test
    fun mobilePrefixes() {
        assertEquals(IfKind.MOBILE, classifyInterface("rmnet0"))
        assertEquals(IfKind.MOBILE, classifyInterface("ccmni0"))
    }

    @Test
    fun unknownIsOther() {
        assertEquals(IfKind.OTHER, classifyInterface("lo"))
        assertEquals(IfKind.OTHER, classifyInterface("dummy0"))
        assertEquals(IfKind.OTHER, classifyInterface(""))
    }
}
