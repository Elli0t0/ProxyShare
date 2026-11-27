package io.github.elli0t0.proxyshare.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named rule sets (0.6.8): the built-in Ads & trackers list (OISD
 * small, bundled as an asset) and user-made lists stored as files.
 * A set is one action (Direct or Block) for all its domains; manual
 * rules (see Rules.kt) always win over every set, and the sets apply
 * in list order, first hit wins (see the engine's lookup_route).
 */

const val BUILTIN_RULE_SET_ID = "ads-oisd-small"
const val BUILTIN_RULE_SET_NAME = "Ads & trackers (OISD small)"

/** One named rule set: its metadata. The content lives in the asset (built-in) or in filesDir/rulesets/<id>.txt (custom). */
data class RuleSetMeta(
    val id: String,
    val name: String,
    val action: RuleAction,
    val enabled: Boolean,
    val builtin: Boolean,
    /** Domains in the set (0 until counted: the built-in count is filled in when the asset is first read). */
    val count: Int,
    /**
     * Subscription source (0.6.9): "" for a plain set, an https URL for
     * a subscription whose content is fetched from that URL. The URL
     * itself is never logged (not even its query string).
     */
    val url: String = "",
    /** Unix time of the last successful subscription update (0 = never). */
    val lastUpdatedMs: Long = 0L,
    /** Short label of the last failed update ("" = none). Never contains the URL. */
    val lastError: String = "",
)

/** True when the set's content comes from a URL (0.6.9). */
val RuleSetMeta.isSubscription: Boolean
    get() = url.isNotBlank()

/** The built-in set, off by default: the user opts into ad-blocking. */
val BUILTIN_RULE_SET_META = RuleSetMeta(
    id = BUILTIN_RULE_SET_ID,
    name = BUILTIN_RULE_SET_NAME,
    action = RuleAction.BLOCK,
    enabled = false,
    builtin = true,
    count = 0,
)

/** The built-in set is always first: prepend it when a stored list lacks it (e.g. an upgrade from 0.6.7). */
fun ensureBuiltinRuleSet(metas: List<RuleSetMeta>): List<RuleSetMeta> =
    if (metas.any { it.id == BUILTIN_RULE_SET_ID }) metas
    else listOf(BUILTIN_RULE_SET_META) + metas

/** Serialize the metadata as one JSON array string (the DataStore value). */
fun List<RuleSetMeta>.toRuleSetsJson(): String {
    val array = JSONArray()
    for (m in this) {
        array.put(
            JSONObject()
                .put("id", m.id)
                .put("name", m.name)
                .put("action", m.action.wire)
                .put("enabled", m.enabled)
                .put("builtin", m.builtin)
                .put("count", m.count)
                .put("url", m.url)
                .put("lastUpdatedMs", m.lastUpdatedMs)
                .put("lastError", m.lastError),
        )
    }
    return array.toString()
}

/** Parse the metadata JSON. Malformed entries are skipped, never a crash; an empty text parses to an empty list. */
fun ruleSetsFromJson(text: String): List<RuleSetMeta> {
    if (text.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(text)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
                val action = ruleActionFromWire(obj.optString("action")) ?: continue
                add(
                    RuleSetMeta(
                        id = id,
                        name = name,
                        action = action,
                        enabled = obj.optBoolean("enabled", false),
                        builtin = obj.optBoolean("builtin", false),
                        count = obj.optInt("count", 0).coerceAtLeast(0),
                        // Added in 0.6.9: older stored JSON lacks them.
                        url = obj.optString("url", ""),
                        lastUpdatedMs = obj.optLong("lastUpdatedMs", 0L).coerceAtLeast(0L),
                        lastError = obj.optString("lastError", ""),
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }
}

/** Non-blank lines in a list text: the domain count shown on a set's card. */
fun countRuleSetLines(text: String): Int =
    text.lineSequence().count { it.isNotBlank() }

/**
 * True when the host is an IP literal (v4 or v6), not a domain name.
 * Domains never contain ':', and a four-part all-numeric dotted
 * name is an IPv4 literal.
 */
fun isIpLiteral(host: String): Boolean {
    if (host.contains(':')) return true
    val parts = host.split('.')
    return parts.size == 4 && parts.all { it.toIntOrNull() != null }
}

/**
 * Parse a plain-domain rule-set list (0.7.2): one domain per line,
 * matching the engine's DomainSet (docs/ENGINE_API.md) and the
 * bundled OISD asset. `#` comments, blanks, surrounding whitespace
 * and trailing dots are stripped, names are lowercased and
 * deduplicated (against [existing] and within the text). Lines that
 * are not valid plain domains — spaces, `://`, `/`, `*`, `=`, IP
 * literals, longer than 253 chars, a leading dot, `..`, or other
 * characters — are skipped. Returns the added domains and the
 * number of skipped lines.
 */
fun parseRuleSetDomains(text: String, existing: Set<String> = emptySet()): Pair<List<String>, Int> {
    val seen = existing.toMutableSet()
    val added = ArrayList<String>()
    var skipped = 0
    for (raw in text.lineSequence()) {
        val line = raw.substringBefore('#').trim().lowercase().trimEnd('.')
        if (line.isEmpty()) continue
        val valid = line.length <= 253 &&
            !line.startsWith('.') &&
            !line.contains("..") &&
            !line.any { it.isWhitespace() } &&
            !line.contains("://") &&
            !line.contains('/') &&
            !line.contains('*') &&
            !line.contains('=') &&
            line.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_' } &&
            !isIpLiteral(line)
        if (!valid || !seen.add(line)) {
            skipped++
            continue
        }
        added += line
    }
    return added to skipped
}

/**
 * Pre-0.7.2 custom files stored ACTION<TAB>domain lines (0.6.8 /
 * 0.6.9); 0.7.2 files are plain domains. Strip a leading
 * tab-prefixed action on read so old lists keep working until they
 * are rewritten as plain domains.
 */
fun stripRuleSetActionPrefixes(text: String): String =
    text.lineSequence().joinToString("\n") { line ->
        val tab = line.indexOf('\t')
        if (tab >= 0) line.substring(tab + 1) else line
    }

/** The built-in list, read from the bundled asset. Blocking I/O: call on Dispatchers.IO. */
fun Context.readBuiltinRuleSetText(): String =
    assets.open("rules/ads-oisd-small.txt").bufferedReader().use { it.readText() }

private fun Context.ruleSetFile(id: String): File =
    File(File(filesDir, "rulesets"), "$id.txt")

/** A custom set's content, or "" when it has none yet. Pre-0.7.2 files stored ACTION<TAB>domain lines; the action prefix is stripped on read (see [stripRuleSetActionPrefixes]). Blocking I/O: call on Dispatchers.IO. */
fun Context.loadRuleSetFileText(id: String): String {
    val file = ruleSetFile(id)
    if (!file.exists()) return ""
    val raw = runCatching { file.readText() }.getOrElse { "" }
    return stripRuleSetActionPrefixes(raw)
}

/** Replace a custom set's content (creates the dir when needed). Blocking I/O: call on Dispatchers.IO. */
fun Context.saveRuleSetFileText(id: String, text: String) {
    val file = ruleSetFile(id)
    file.parentFile?.mkdirs()
    file.writeText(text)
}

/** Delete a custom set's file (no-op when missing). Blocking I/O: call on Dispatchers.IO. */
fun Context.deleteRuleSetFile(id: String) {
    runCatching { ruleSetFile(id).delete() }
}

/** Last pushed state of one rule set, for diffed pushing (0.7.2): a set is re-pushed only when its enabled state, action, or content changed. */
data class PushedRuleSet(
    val enabled: Boolean,
    val action: String,
    val contentHash: Int,
)

/**
 * Push the named rule sets to the engine, in list order (first hit
 * wins), but only the sets whose enabled state, action, or content
 * differ from [lastPushed] — so editing a custom set's name or
 * storing a subscription's lastError does not re-push the 1.1 MB
 * OISD asset. A disabled set is pushed as empty text, which clears
 * it in the engine but keeps its position. Returns the new
 * last-pushed state and the engine's rejection per set id (an
 * "ok ..." result clears it). `load` reads a set's content by id
 * (asset for the built-in, file for a custom set); `apply` is
 * Engine.setRuleSet. Runs on Dispatchers.IO because loading reads
 * files. The engine validates the text atomically, so a bad list
 * rejects that set and the previous content stays live.
 */
suspend fun pushRuleSetsDiffed(
    sets: List<RuleSetMeta>,
    lastPushed: Map<String, PushedRuleSet>,
    load: (String) -> String,
    apply: (String, String, String) -> String,
): Pair<Map<String, PushedRuleSet>, Map<String, String>> = withContext(Dispatchers.IO) {
    val pushed = lastPushed.toMutableMap()
    val errors = mutableMapOf<String, String>()
    for (s in sets) {
        val text = if (s.enabled) load(s.id) else ""
        val state = PushedRuleSet(s.enabled, s.action.wire, text.hashCode())
        if (pushed[s.id] == state) continue
        val result = apply(s.id, s.action.wire, text)
        if (result.startsWith("ok ")) {
            pushed[s.id] = state
        } else {
            errors[s.id] = result
        }
    }
    pushed.keys.retainAll(sets.map { it.id }.toSet())
    pushed to errors
}
