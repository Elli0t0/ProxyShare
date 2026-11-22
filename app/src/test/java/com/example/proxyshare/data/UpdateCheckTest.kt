package com.example.proxyshare.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    @Test
    fun newerPatchIsNewer() {
        assertTrue(isNewer("v0.3.10", "0.3.9"))
        assertTrue(isNewer("0.3.10", "0.3.9"))
        assertTrue(isNewer("v1.0.0", "0.3.9"))
    }

    @Test
    fun equalOrOlderIsNotNewer() {
        assertFalse(isNewer("v0.3.9", "0.3.9"))
        assertFalse(isNewer("v0.3.8", "0.3.9"))
        assertFalse(isNewer("v0.3.9", "0.3.10"))
    }

    @Test
    fun garbageIsNotNewer() {
        assertFalse(isNewer("garbage", "0.3.9"))
        assertFalse(isNewer("v0.3.10", "garbage"))
        assertFalse(isNewer("", "0.3.9"))
        assertFalse(isNewer("0.3", "0.3.9"))
        assertFalse(isNewer("v0.3.9-rc1", "0.3.9"))
    }

    @Test
    fun parseValidRelease() {
        val json = """{"tag_name":"v0.3.9","html_url":"https://github.com/Elli0t0/ProxyShare/releases/tag/v0.3.9","name":"0.3.9"}"""
        assertEquals(
            LatestRelease("v0.3.9", "https://github.com/Elli0t0/ProxyShare/releases/tag/v0.3.9"),
            parseLatestRelease(json),
        )
    }

    @Test
    fun parseReleaseRejectsMissingFieldsAndGarbage() {
        assertNull(parseLatestRelease("""{"html_url":"https://example.com"}"""))
        assertNull(parseLatestRelease("""{"tag_name":"v0.3.9"}"""))
        assertNull(parseLatestRelease("{}"))
        assertNull(parseLatestRelease("not json"))
        assertNull(parseLatestRelease(""))
    }
}
