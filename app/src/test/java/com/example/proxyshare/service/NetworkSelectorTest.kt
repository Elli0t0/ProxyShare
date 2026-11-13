package com.example.proxyshare.service

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkSelectorTest {

    @Test
    fun settingStringMapsToMode() {
        assertEquals(OutboundMode.DEFAULT, "default".toOutboundMode())
        assertEquals(OutboundMode.VPN_ONLY, "vpn_only".toOutboundMode())
        assertEquals(OutboundMode.WIFI, "wifi".toOutboundMode())
        assertEquals(OutboundMode.MOBILE, "mobile".toOutboundMode())
    }

    @Test
    fun unknownSettingFallsBackToDefault() {
        assertEquals(OutboundMode.DEFAULT, "".toOutboundMode())
        assertEquals(OutboundMode.DEFAULT, "bogus".toOutboundMode())
    }

    @Test
    fun modeMapsToSettingString() {
        assertEquals("default", OutboundMode.DEFAULT.toSettingString())
        assertEquals("vpn_only", OutboundMode.VPN_ONLY.toSettingString())
        assertEquals("wifi", OutboundMode.WIFI.toSettingString())
        assertEquals("mobile", OutboundMode.MOBILE.toSettingString())
    }
}
