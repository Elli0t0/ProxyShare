package io.github.elli0t0.proxyshare.data

import org.json.JSONObject

/** Most client names kept (oldest evicted first when the cap is hit). */
const val MAX_CLIENT_NAMES = 50

/** A client name is trimmed to this many characters. */
const val MAX_CLIENT_NAME_LENGTH = 24

/** Trim a name to the stored length (blank stays blank). */
fun normalizeClientName(name: String): String =
    name.trim().take(MAX_CLIENT_NAME_LENGTH)

/**
 * Store a name for one client IP. A blank name removes the entry.
 * Keeps insertion order and evicts the oldest names past the cap.
 * Pure: no DataStore, no coroutines.
 */
fun putClientName(
    names: Map<String, String>,
    ip: String,
    name: String,
): Map<String, String> {
    val out = LinkedHashMap(names)
    val clean = normalizeClientName(name)
    if (clean.isEmpty()) {
        out.remove(ip)
        return out
    }
    out[ip] = clean
    while (out.size > MAX_CLIENT_NAMES) {
        out.remove(out.keys.first())
    }
    return out
}

/** Parse the stored JSON object (ip -> name). Malformed entries are skipped. */
fun parseClientNames(json: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    runCatching {
        val obj = JSONObject(json)
        val keys = obj.keys()
        while (keys.hasNext() && out.size < MAX_CLIENT_NAMES) {
            val ip = keys.next()
            val name = normalizeClientName(obj.optString(ip, ""))
            if (ip.isNotBlank() && name.isNotEmpty()) out[ip] = name
        }
    }
    return out
}

/** Serialize the names map for DataStore. */
fun clientNamesJson(names: Map<String, String>): String =
    JSONObject(names).toString()
