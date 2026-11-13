package com.example.proxyshare.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {

    @Test
    fun portValidation() {
        assertNull(validatePort("80"))
        assertNull(validatePort("70000"))
        assertEquals(8080, validatePort("8080"))
        assertNull(validatePort("abc"))
    }

    @Test
    fun cidrValidation() {
        assertTrue(validateCidr("192.168.1.0/24"))
        assertTrue(validateCidr("10.0.0.5"))
        assertFalse(validateCidr("300.1.1.1"))
        assertFalse(validateCidr("1.2.3"))
        assertFalse(validateCidr("1.2.3.4/33"))
    }

    @Test
    fun intRangeValidation() {
        assertEquals(5, validateIntInRange("5", 1..512))
        assertNull(validateIntInRange("0", 1..512))
        assertNull(validateIntInRange("abc", 1..512))
    }
}
