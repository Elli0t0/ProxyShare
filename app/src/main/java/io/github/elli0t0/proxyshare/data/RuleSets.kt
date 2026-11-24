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

/** The built-in list, read from the bundled asset. Blocking I/O: call on Dispatchers.IO. */
fun Context.readBuiltinRuleSetText(): String =
    assets.open("rules/ads-oisd-small.txt").bufferedReader().use { it.readText() }

private fun Context.ruleSetFile(id: String): File =
    File(File(filesDir, "rulesets"), "$id.txt")

/** A custom set's content, or "" when it has none yet. Blocking I/O: call on Dispatchers.IO. */
fun Context.loadRuleSetFileText(id: String): String {
    val file = ruleSetFile(id)
    return if (file.exists()) runCatching { file.readText() }.getOrElse { "" } else ""
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

/**
 * Push every set to the engine, in list order (first hit wins).
 * A disabled set is pushed as empty text, which clears it in the
 * engine but keeps its position. `load` reads a set's content by id
 * (asset for the built-in, file for a custom set); `apply` is
 * Engine.setRuleSet. Runs on Dispatchers.IO because loading reads
 * files. The engine validates the text atomically, so a bad list
 * rejects that set and the previous content stays live.
 */
suspend fun pushRuleSets(
    sets: List<RuleSetMeta>,
    load: (String) -> String,
    apply: (String, String, String) -> String,
) = withContext(Dispatchers.IO) {
    for (s in sets) {
        val text = if (s.enabled) load(s.id) else ""
        apply(s.id, s.action.wire, text)
    }
}
