package com.example.proxyshare.ui.screens

import com.example.proxyshare.R
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

    @Test
    fun blockedDomainValidation() {
        assertTrue(validateBlockedDomain("ads.example.com"))
        assertTrue(validateBlockedDomain("*.example.com"))
        assertFalse(validateBlockedDomain("ads .example.com"))
        assertFalse(validateBlockedDomain("http://ads.example.com"))
        assertFalse(validateBlockedDomain("https://example.com"))
        assertFalse(validateBlockedDomain("ads.example.com/path"))
        assertFalse(validateBlockedDomain("ads.example.com:8080"))
        assertFalse(validateBlockedDomain(""))
        assertFalse(validateBlockedDomain("   "))
    }

    @Test
    fun blockedListParsing() {
        val (added, invalid) = parseBlockedList(
            "ads.example.com\n" +
                "# a comment\n" +
                "\n" +
                "  *.tracker.example  \n" +
                "not a domain\n" +
                "ads.example.com\n" +
                "http://x.example.com\n",
            existing = listOf("old.example.com"),
        )
        assertEquals(listOf("ads.example.com", "*.tracker.example"), added)
        assertEquals(2, invalid)
        // Duplicates against existing and within the paste are dropped, not invalid.
        val (added2, invalid2) = parseBlockedList(
            "old.example.com\nads.example.com\nads.example.com\n",
            existing = listOf("old.example.com"),
        )
        assertEquals(listOf("ads.example.com"), added2)
        assertEquals(0, invalid2)
    }

    @Test
    fun idleStopLabelResMapsMinutes() {
        assertEquals(R.string.idle_stop_off, idleStopLabelRes(0))
        assertEquals(R.string.idle_stop_15, idleStopLabelRes(15))
        assertEquals(R.string.idle_stop_30, idleStopLabelRes(30))
        assertEquals(R.string.idle_stop_60, idleStopLabelRes(60))
        assertEquals(R.string.idle_stop_120, idleStopLabelRes(120))
        assertEquals(R.string.idle_stop_off, idleStopLabelRes(999))
    }
}
