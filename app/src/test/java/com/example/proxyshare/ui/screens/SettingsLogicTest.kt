package com.example.proxyshare.ui.screens

import com.example.proxyshare.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLogicTest {

    @Test
    fun intRangeValidation() {
        assertEquals(5, validateIntInRange("5", 1..512))
        assertNull(validateIntInRange("0", 1..512))
        assertNull(validateIntInRange("abc", 1..512))
    }

    @Test
    fun blockedListParsing() {
        // The real validator lives in Rust now (see validate.rs) and
        // its cases are ported there; here a stub stands in so this
        // test checks only the splitting, comments, duplicates and
        // the invalid count, without loading the native library.
        val isValid: (String) -> Boolean =
            { !it.contains(' ') && !it.startsWith("http") }
        val (added, invalid) = parseBlockedList(
            "ads.example.com\n" +
                "# a comment\n" +
                "\n" +
                "  *.tracker.example  \n" +
                "not a domain\n" +
                "ads.example.com\n" +
                "http://x.example.com\n",
            existing = listOf("old.example.com"),
            isValid = isValid,
        )
        assertEquals(listOf("ads.example.com", "*.tracker.example"), added)
        assertEquals(2, invalid)
        // Duplicates against existing and within the paste are dropped, not invalid.
        val (added2, invalid2) = parseBlockedList(
            "old.example.com\nads.example.com\nads.example.com\n",
            existing = listOf("old.example.com"),
            isValid = isValid,
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
