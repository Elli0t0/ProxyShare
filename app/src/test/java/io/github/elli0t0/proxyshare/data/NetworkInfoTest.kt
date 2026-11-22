package io.github.elli0t0.proxyshare.data

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
        // A wlan* interface that is not the connected Wi-Fi one is the
        // hotspot (some phones run the hotspot on wlan1).
        assertEquals(IfKind.HOTSPOT, classifyInterface("wlan1", wifiIface = "wlan0"))
        assertEquals(IfKind.WIFI, classifyInterface("wlan0", wifiIface = "wlan0"))
        // Without the Wi-Fi interface known, every wlan* counts as Wi-Fi.
        assertEquals(IfKind.WIFI, classifyInterface("wlan1"))
    }

    @Test
    fun aliasSuffixIsStripped() {
        assertEquals(IfKind.VPN, classifyInterface("tun0:1"))
        assertEquals(IfKind.WIFI, classifyInterface("wlan0:2"))
    }

    @Test
    fun usbTetheringPrefixes() {
        assertEquals(IfKind.USB, classifyInterface("rndis0"))
        assertEquals(IfKind.USB, classifyInterface("usb0"))
    }

    @Test
    fun ethernetPrefixes() {
        assertEquals(IfKind.ETHERNET, classifyInterface("eth0"))
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

    @Test
    fun rankInterfacesOrdersAndDropsVpn() {
        val ranked = rankInterfaces(
            listOf(
                NetIf("rmnet0", "10.0.0.2", IfKind.MOBILE),
                NetIf("tun0", "10.8.0.2", IfKind.VPN),
                NetIf("wlan0", "192.168.1.5", IfKind.WIFI),
                NetIf("swlan0", "192.168.43.1", IfKind.HOTSPOT),
                NetIf("rndis0", "192.168.42.1", IfKind.USB),
                NetIf("eth0", "192.168.0.9", IfKind.ETHERNET),
                NetIf("dummy0", "172.16.0.1", IfKind.OTHER),
            )
        )
        assertEquals(
            listOf("swlan0", "rndis0", "wlan0", "eth0", "dummy0", "rmnet0"),
            ranked.map { it.name },
        )
    }
}
