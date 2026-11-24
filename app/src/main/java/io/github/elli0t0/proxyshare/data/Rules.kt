package io.github.elli0t0.proxyshare.data

/**
 * Routing rules (0.6.6 engine, 0.6.7 UI). A rule routes every
 * request whose host matches [pattern] to [action]: through the
 * proxy's outbound network (PROXY), out the phone's real non-VPN
 * network (DIRECT), or refused (BLOCK). Patterns are the engine's
 * syntax: `a.com` (domain + all subdomains), `*.a.com` (exactly one
 * extra label), `=a.com` (that exact host), `1.2.3.4` (one IP),
 * `10.0.0.0/8` (a CIDR).
 *
 * Pure Kotlin: no pattern parsing here except trimming — the engine
 * is the authority on whether a pattern is valid (nativeSetRules
 * reports "error line N: msg", shown by the Rules page). Stored in
 * DataStore as one string in engine-text format (see [toEngineText]).
 */
enum class RuleAction(val wire: String) { PROXY("proxy"), DIRECT("direct"), BLOCK("block") }

fun ruleActionFromWire(wire: String): RuleAction? =
    RuleAction.entries.firstOrNull { it.wire == wire.trim().lowercase() }

data class RuleEntry(val pattern: String, val action: RuleAction)

/** The engine-text format: one `ACTION<TAB>pattern` line per rule, in list order. */
fun List<RuleEntry>.toEngineText(): String =
    joinToString("\n") { "${it.action.wire}\t${it.pattern}" }

/**
 * Parse stored engine text back into rules. Malformed lines are
 * skipped (stored text is written by us, so this only guards
 * against a corrupt DataStore value).
 */
fun rulesFromEngineText(text: String): List<RuleEntry> {
    val out = ArrayList<RuleEntry>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val tab = line.indexOf('\t')
        if (tab < 0) continue
        val action = ruleActionFromWire(line.substring(0, tab)) ?: continue
        val pattern = line.substring(tab + 1).trim()
        if (pattern.isEmpty()) continue
        if (out.none { it.pattern == pattern }) out += RuleEntry(pattern, action)
    }
    return out
}

/**
 * Parse an imported rule file: one pattern per line, `#` comments
 * ignored, every line gets the one [action] the user picked for the
 * whole file. Returns the entries plus how many lines were skipped
 * (too long, or containing whitespace — the engine rejects those).
 * Duplicates (within the file and against [existing]) are dropped.
 * Invalid-but-well-formed lines (e.g. `a..com`) are kept: the
 * engine reports them when the rules are pushed, and the Rules page
 * shows the error line.
 */
fun parseImportedRules(
    text: String,
    action: RuleAction,
    existing: List<RuleEntry> = emptyList(),
): Pair<List<RuleEntry>, Int> {
    val ok = mutableListOf<RuleEntry>()
    val seen = existing.map { it.pattern }.toHashSet()
    var skipped = 0
    for (raw in text.lineSequence()) {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) continue
        if (line.length > 253 || line.any { it.isWhitespace() }) {
            skipped++
            continue
        }
        if (seen.add(line)) ok += RuleEntry(line, action)
    }
    return ok to skipped
}

/** Replace the rule with the same pattern, or append it. Order is kept. */
fun List<RuleEntry>.upsertRule(entry: RuleEntry): List<RuleEntry> {
    val idx = indexOfFirst { it.pattern == entry.pattern }
    return if (idx < 0) this + entry else toMutableList().also { it[idx] = entry }
}

/** Which rules a filter chip shows. */
enum class RuleFilter { ALL, PROXY, DIRECT, BLOCK }

/**
 * Filter + search helper for the Rules page: keep the rules matching
 * [filter] whose pattern contains [query] (case-insensitive, blank
 * query matches everything). List order is kept.
 */
fun filterRules(rules: List<RuleEntry>, filter: RuleFilter, query: String): List<RuleEntry> {
    val q = query.trim().lowercase()
    return rules.filter { entry ->
        (filter == RuleFilter.ALL || entry.action.name == filter.name) &&
            (q.isEmpty() || entry.pattern.lowercase().contains(q))
    }
}
