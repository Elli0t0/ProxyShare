package io.github.elli0t0.proxyshare.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rule-set subscriptions (0.6.9): a rule set whose content comes from
 * an https URL and can be refreshed. Kotlin only, no new libraries
 * (HttpURLConnection). The fetch runs on the DEFAULT network — it may
 * go through the phone's VPN, that is expected. The URL is never
 * logged, not even its query string: fetch errors are reduced to
 * short labels by [fetchErrorLabel] before they are stored or shown.
 */

const val SUBSCRIPTION_MAX_BYTES = 5_000_000
const val SUBSCRIPTION_TIMEOUT_MS = 10_000
const val SUBSCRIPTION_MAX_REDIRECTS = 3

/** A subscription is stale when its last update is older than 24 h (never updated counts as stale). */
const val SUBSCRIPTION_STALE_MS = 24L * 60 * 60 * 1000

fun isSubscriptionStale(lastUpdatedMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
    nowMs - lastUpdatedMs > SUBSCRIPTION_STALE_MS

/**
 * Read at most [maxBytes] + 1 bytes: the extra byte proves the body
 * is over the limit. (readNBytes needs API 33; minSdk is 26, so this
 * is a manual bounded loop instead.)
 */
fun readBounded(stream: InputStream, maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
        if (total > maxBytes) break
    }
    return out.toByteArray()
}

/** Read and validate a fetched list body: at most 5 MB, UTF-8 text. */
fun readListBody(stream: InputStream): Result<String> = runCatching {
    val bytes = readBounded(stream, SUBSCRIPTION_MAX_BYTES)
    require(bytes.size <= SUBSCRIPTION_MAX_BYTES) { "list too large" }
    bytes.toString(Charsets.UTF_8)
}

/** Download a list: https only, 10 s timeouts, text/plain only, max 3 redirects (https only), 5 MB max body. */
suspend fun fetchList(url: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        require(url.startsWith("https://")) { "not https" }
        var conn = openListConnection(url)
        var hops = 0
        while (conn.responseCode in 301..308 && hops < SUBSCRIPTION_MAX_REDIRECTS) {
            val next = conn.getHeaderField("Location") ?: error("redirect without Location")
            require(next.startsWith("https://")) { "redirect not https" }
            conn.disconnect()
            conn = openListConnection(next)
            hops++
        }
        require(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
        val type = conn.contentType ?: ""
        require(type.startsWith("text/plain")) { "not text/plain" }
        conn.inputStream.use { readListBody(it).getOrThrow() }
    }
}

private fun openListConnection(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = SUBSCRIPTION_TIMEOUT_MS
        readTimeout = SUBSCRIPTION_TIMEOUT_MS
        instanceFollowRedirects = false
    }

/** Short display label for a fetch failure. Never contains the URL or its query string. */
fun fetchErrorLabel(e: Throwable): String = when (e) {
    is SocketTimeoutException -> "timeout"
    is UnknownHostException -> "cannot resolve host"
    is IllegalArgumentException -> e.message ?: "invalid list"
    is IOException -> "network error"
    else -> "update failed"
}

/** What a subscription refresh does with the fetched body. */
sealed interface SubscriptionUpdate {
    /** Replace the set's content with the parsed list. */
    data class Updated(val text: String, val count: Int) : SubscriptionUpdate

    /** Keep the old content: the download failed, or it yielded 0 valid lines. */
    data class KeptOld(val reason: String) : SubscriptionUpdate
}

/**
 * Apply a fetched list: parse it with parseRuleSetDomains (comments,
 * blanks and CRLF line endings are handled there); keep the old
 * content when the download failed or yielded 0 valid lines. A
 * successful fetch replaces the set's content entirely — a
 * subscription's content IS the URL's content.
 */
/**
 * Turn a fetched subscription body into the text to store: plain
 * domains, one per line (0.7.2; the action is the set's metadata,
 * not part of the file). A failed download, or one with 0 valid
 * lines, keeps the old content and reports a short reason.
 */
fun updateSubscriptionContent(
    fetched: Result<String>,
): SubscriptionUpdate {
    val body = fetched.getOrElse { return SubscriptionUpdate.KeptOld(fetchErrorLabel(it)) }
    val (domains, _) = parseRuleSetDomains(body)
    return if (domains.isEmpty()) {
        SubscriptionUpdate.KeptOld("no valid lines")
    } else {
        SubscriptionUpdate.Updated(domains.joinToString("\n"), domains.size)
    }
}
