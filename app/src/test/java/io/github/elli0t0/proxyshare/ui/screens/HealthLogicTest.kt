package io.github.elli0t0.proxyshare.ui.screens

import io.github.elli0t0.proxyshare.data.IfKind
import io.github.elli0t0.proxyshare.data.NetIf
import io.github.elli0t0.proxyshare.engine.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthLogicTest {

    private fun good() = HealthInputs(
        notificationsOk = true,
        batteryUnrestricted = true,
        vpnActive = true,
        hasShareableAddress = true,
        engineState = EngineState.Running,
        authEnabled = false,
        authPass = "",
    )

    @Test
    fun allGoodIsAllOk() {
        val items = computeHealth(good())
        assertEquals(6, items.size)
        assertTrue(items.all { it.status == HealthStatus.OK })
        assertTrue(items.all { it.fix == HealthFix.NONE })
    }

    @Test
    fun orderIsStable() {
        assertEquals(
            listOf("notifications", "battery", "vpn", "address", "engine", "auth"),
            computeHealth(good()).map { it.id },
        )
    }

    @Test
    fun notificationsOffWarns() {
        val item = computeHealth(good().copy(notificationsOk = false))[0]
        assertEquals(HealthStatus.WARN, item.status)
        assertEquals(HealthFix.NOTIFICATIONS, item.fix)
    }

    @Test
    fun batteryRestrictedWarns() {
        val item = computeHealth(good().copy(batteryUnrestricted = false))[1]
        assertEquals(HealthStatus.WARN, item.status)
        assertEquals(HealthFix.BATTERY, item.fix)
    }

    @Test
    fun vpnOffWarnsWithNoFix() {
        val item = computeHealth(good().copy(vpnActive = false))[2]
        assertEquals(HealthStatus.WARN, item.status)
        assertEquals(HealthFix.NONE, item.fix)
    }

    @Test
    fun noAddressFails() {
        val item = computeHealth(good().copy(hasShareableAddress = false))[3]
        assertEquals(HealthStatus.FAIL, item.status)
        assertEquals(HealthFix.HOTSPOT, item.fix)
    }

    @Test
    fun engineErrorFailsWithMessage() {
        val item = computeHealth(
            good().copy(engineState = EngineState.Error("bind failed")),
        )[4]
        assertEquals(HealthStatus.FAIL, item.status)
        assertEquals("bind failed", item.message)
        assertEquals(HealthFix.NONE, item.fix)
    }

    @Test
    fun engineStoppedWarns() {
        val item = computeHealth(
            good().copy(engineState = EngineState.Stopped),
        )[4]
        assertEquals(HealthStatus.WARN, item.status)
    }

    @Test
    fun authOnEmptyPassFails() {
        val item = computeHealth(
            good().copy(authEnabled = true, authPass = ""),
        )[5]
        assertEquals(HealthStatus.FAIL, item.status)
        assertEquals(HealthFix.SETTINGS_SECURITY, item.fix)
        val ok = computeHealth(
            good().copy(authEnabled = true, authPass = "secret"),
        )[5]
        assertEquals(HealthStatus.OK, ok.status)
    }

    @Test
    fun hasShareableAddressSkipsVpnAndMobile() {
        assertTrue(
            hasShareableAddress(
                listOf(NetIf("wlan0", "192.168.1.2", IfKind.WIFI)),
            ),
        )
        assertTrue(
            !hasShareableAddress(
                listOf(NetIf("tun0", "10.0.0.1", IfKind.VPN)),
            ),
        )
        assertTrue(
            !hasShareableAddress(
                listOf(NetIf("rmnet0", "10.1.2.3", IfKind.MOBILE)),
            ),
        )
        assertTrue(!hasShareableAddress(emptyList()))
    }
}
