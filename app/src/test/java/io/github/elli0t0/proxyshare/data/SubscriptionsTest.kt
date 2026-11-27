package io.github.elli0t0.proxyshare.data

import java.io.ByteArrayInputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionsTest {

    @Test
    fun fetchListRejectsNonHttpsWithoutTouchingTheNetwork() = runBlocking {
        val http = fetchList("http://example.com/list.txt?token=secret")
        assertTrue(http.isFailure)
        // The error is a short label: the URL (and its query string) never leaks into it.
        assertEquals("not https", http.exceptionOrNull()?.message)
        assertTrue(fetchList("ftp://example.com/list.txt").isFailure)
        assertTrue(fetchList("").isFailure)
    }

    @Test
    fun readListBodyEnforcesTheSizeLimit() {
        val tooBig = ByteArrayInputStream(ByteArray(SUBSCRIPTION_MAX_BYTES + 1) { 'a'.code.toByte() })
        val over = readListBody(tooBig)
        assertTrue(over.isFailure)
        assertEquals("list too large", over.exceptionOrNull()?.message)

        val exact = ByteArrayInputStream(ByteArray(SUBSCRIPTION_MAX_BYTES) { 'a'.code.toByte() })
        val ok = readListBody(exact)
        assertTrue(ok.isSuccess)
        assertEquals(SUBSCRIPTION_MAX_BYTES, ok.getOrThrow().length)
    }

    @Test
    fun fetchedBodyParsesCommentsBlanksAndCrlf() {
        val body = "# a comment\r\n\r\na.com\r\nb.com # trailing\r\n*.c.com\r\n"
        val update = updateSubscriptionContent(Result.success(body))
        // Plain domains only (0.7.2): *.c.com is not a plain domain.
        assertEquals(SubscriptionUpdate.Updated("a.com\nb.com", 2), update)
        // Duplicates collapse; a body with no valid lines keeps the old content.
        val dupes = updateSubscriptionContent(Result.success("a.com\na.com\n"))
        assertEquals(SubscriptionUpdate.Updated("a.com", 1), dupes)
        assertEquals(
            SubscriptionUpdate.KeptOld("no valid lines"),
            updateSubscriptionContent(Result.success("# only comments\n\n")),
        )
    }

    @Test
    fun failedFetchKeepsOldContentWithAShortLabel() {
        assertEquals(
            SubscriptionUpdate.KeptOld("timeout"),
            updateSubscriptionContent(Result.failure(SocketTimeoutException())),
        )
        assertEquals(
            SubscriptionUpdate.KeptOld("cannot resolve host"),
            updateSubscriptionContent(
                Result.failure(UnknownHostException("example.com"))
            ),
        )
        assertEquals(
            SubscriptionUpdate.KeptOld("network error"),
            updateSubscriptionContent(
                Result.failure(java.io.IOException("boom"))
            ),
        )
    }

    @Test
    fun stalenessIsOlderThan24Hours() {
        val now = 1_800_000_000_000L
        assertTrue(isSubscriptionStale(0L, now))
        assertTrue(isSubscriptionStale(now - SUBSCRIPTION_STALE_MS - 1, now))
        // Exactly 24 h old is not stale yet; 23 h old is fresh.
        assertFalse(isSubscriptionStale(now - SUBSCRIPTION_STALE_MS, now))
        assertFalse(isSubscriptionStale(now - 23L * 60 * 60 * 1000, now))
    }

    @Test
    fun subscriptionMetaRoundTripsThroughJson() {
        val meta = RuleSetMeta(
            id = "sub1", name = "Ads", action = RuleAction.BLOCK,
            enabled = true, builtin = false, count = 42,
            url = "https://example.com/list.txt",
            lastUpdatedMs = 1_700_000_000_000L, lastError = "timeout",
        )
        assertEquals(listOf(meta), ruleSetsFromJson(listOf(meta).toRuleSetsJson()))
        // JSON stored before 0.6.9 has no subscription fields: defaults apply.
        val oldJson = """[{"id": "c1", "name": "Work", "action": "direct", "enabled": true, "builtin": false, "count": 2}]"""
        val parsed = ruleSetsFromJson(oldJson)
        assertEquals(1, parsed.size)
        assertEquals("", parsed[0].url)
        assertEquals(0L, parsed[0].lastUpdatedMs)
        assertEquals("", parsed[0].lastError)
        assertFalse(parsed[0].isSubscription)
        assertTrue(meta.isSubscription)
    }
}
