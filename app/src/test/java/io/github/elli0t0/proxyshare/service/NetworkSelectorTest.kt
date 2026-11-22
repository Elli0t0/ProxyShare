package io.github.elli0t0.proxyshare.service

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun builtInEndpointsTryIpLiteralLast() {
        assertEquals(3, EXIT_IP_ENDPOINTS.size)
        assertEquals("https://api.ipify.org", EXIT_IP_ENDPOINTS[0])
        assertEquals("https://1.1.1.1/cdn-cgi/trace", EXIT_IP_ENDPOINTS[2])
    }

    @Test
    fun parseExitIpAcceptsPlainIpv4() {
        assertEquals("1.2.3.4", parseExitIp("1.2.3.4"))
        assertEquals("1.2.3.4", parseExitIp("  1.2.3.4\n"))
    }

    @Test
    fun parseExitIpAcceptsIpv6() {
        assertEquals("2001:db8::1", parseExitIp("2001:db8::1"))
    }

    @Test
    fun parseExitIpReadsTraceBody() {
        val body = "fl=123\nh=example.com\nip=203.0.113.7\n" +
            "ts=123\nvisit_scheme=https\nuag=Mozilla"
        assertEquals("203.0.113.7", parseExitIp(body))
        val v6 = "fl=123\nip=2001:db8::1\nts=123"
        assertEquals("2001:db8::1", parseExitIp(v6))
    }

    @Test
    fun parseExitIpRejectsHtmlAndGarbage() {
        assertNull(parseExitIp("<html><body>blocked</body></html>"))
        assertNull(parseExitIp("not an ip"))
        assertNull(parseExitIp(""))
        assertNull(parseExitIp("1.2.3"))
    }

    @Test
    fun classifyDnsBlocked() {
        assertEquals(
            ExitIpFailure.DNS_BLOCKED,
            classifyExitIpFailure(listOf(UnknownHostException("Unable to resolve host"))),
        )
        assertEquals(
            ExitIpFailure.DNS_BLOCKED,
            classifyExitIpFailure(
                listOf(Exception("Failed to connect to api.ipify.org/127.0.0.1:443")),
            ),
        )
        assertEquals(
            ExitIpFailure.DNS_BLOCKED,
            classifyExitIpFailure(listOf(Exception("connect to x/0.0.0.0:443 failed"))),
        )
    }

    @Test
    fun classifyTimeoutAndUnreachable() {
        assertEquals(
            ExitIpFailure.TIMEOUT,
            classifyExitIpFailure(listOf(SocketTimeoutException("timeout"))),
        )
        assertEquals(
            ExitIpFailure.UNREACHABLE,
            classifyExitIpFailure(listOf(Exception("Connection refused"))),
        )
        assertEquals(ExitIpFailure.UNREACHABLE, classifyExitIpFailure(emptyList()))
    }

    @Test
    fun classifyDnsWinsOverTimeout() {
        assertEquals(
            ExitIpFailure.DNS_BLOCKED,
            classifyExitIpFailure(
                listOf(SocketTimeoutException("t"), UnknownHostException("h")),
            ),
        )
    }
}
