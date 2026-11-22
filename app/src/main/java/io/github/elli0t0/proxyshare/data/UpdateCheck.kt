package io.github.elli0t0.proxyshare.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHub owner/repo, shared by the About row and the update check. */
const val GITHUB_REPO = "Elli0t0/ProxyShare"

/** A GitHub release, as returned by /releases/latest. */
data class LatestRelease(val tag: String, val url: String)

/**
 * True when [latestTag] is a newer version than [current].
 * Both are "major.minor.patch", with or without a leading "v".
 * Anything unparsable compares as not newer.
 */
fun isNewer(latestTag: String, current: String): Boolean {
    val latest = parseVersion(latestTag) ?: return false
    val cur = parseVersion(current) ?: return false
    for (i in 0..2) {
        if (latest[i] != cur[i]) return latest[i] > cur[i]
    }
    return false
}

private fun parseVersion(tag: String): List<Int>? {
    val parts = tag.trim().removePrefix("v").split(".")
    if (parts.size != 3) return null
    return parts.map { it.toIntOrNull() ?: return null }
}

/** Parse the tag_name and html_url out of a /releases/latest body. Null when either is missing. */
fun parseLatestRelease(json: String): LatestRelease? {
    return try {
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name", "").trim()
        val url = obj.optString("html_url", "").trim()
        if (tag.isEmpty() || url.isEmpty()) null else LatestRelease(tag, url)
    } catch (_: JSONException) {
        null
    }
}

/**
 * Manual update check: GET /repos/$repo/releases/latest, 8 s
 * timeout. Null on any failure (no network, non-200, unparsable).
 * Call from a coroutine; never throws.
 */
suspend fun fetchLatestRelease(repo: String, timeoutMs: Int = 8000): LatestRelease? =
    withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://api.github.com/repos/$repo/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                // GitHub requires a User-Agent on every API request.
                setRequestProperty("User-Agent", "ProxyShare")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                parseLatestRelease(conn.inputStream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
