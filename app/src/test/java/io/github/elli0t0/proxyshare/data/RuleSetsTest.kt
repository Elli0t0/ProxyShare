package io.github.elli0t0.proxyshare.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleSetsTest {

    private fun builtin(enabled: Boolean = false, count: Int = 0) =
        BUILTIN_RULE_SET_META.copy(enabled = enabled, count = count)

    private fun custom(
        id: String = "c1",
        name: String = "Work",
        action: RuleAction = RuleAction.DIRECT,
        enabled: Boolean = true,
        count: Int = 2,
    ) = RuleSetMeta(id, name, action, enabled, builtin = false, count)

    @Test
    fun jsonRoundTrips() {
        val metas = listOf(builtin(enabled = true, count = 61063), custom())
        assertEquals(metas, ruleSetsFromJson(metas.toRuleSetsJson()))
        assertEquals(emptyList<RuleSetMeta>(), ruleSetsFromJson(""))
        assertEquals(emptyList<RuleSetMeta>(), ruleSetsFromJson("   "))
    }

    @Test
    fun malformedJsonEntriesAreSkipped() {
        val json = """[
            {"id": "ok", "name": "Good", "action": "block", "enabled": true, "builtin": false, "count": 3},
            {"id": "bad-action", "name": "Bad", "action": "nope", "enabled": true, "builtin": false, "count": 1},
            {"name": "No id", "action": "direct", "enabled": true, "builtin": false, "count": 1},
            {"id": "bad-count", "name": "Bad count", "action": "direct", "enabled": false, "builtin": false, "count": -5}
        ]"""
        val parsed = ruleSetsFromJson(json)
        assertEquals(
            listOf(
                RuleSetMeta("ok", "Good", RuleAction.BLOCK, true, false, 3),
                RuleSetMeta("bad-count", "Bad count", RuleAction.DIRECT, false, false, 0),
            ),
            parsed,
        )
        // Not JSON at all: an empty list, never a crash.
        assertEquals(emptyList<RuleSetMeta>(), ruleSetsFromJson("{not json"))
    }

    @Test
    fun ensureBuiltinPrependsOnlyWhenMissing() {
        val onlyCustom = listOf(custom())
        val ensured = ensureBuiltinRuleSet(onlyCustom)
        assertEquals(BUILTIN_RULE_SET_META, ensured.first())
        assertEquals(onlyCustom, ensured.drop(1))
        // Already there: the list is returned unchanged.
        val withBuiltin = listOf(builtin(enabled = true), custom())
        assertEquals(withBuiltin, ensureBuiltinRuleSet(withBuiltin))
    }

    @Test
    fun countRuleSetLinesSkipsBlanks() {
        assertEquals(3, countRuleSetLines("a.com\n\nb.com # ad\n  \nc.com\n"))
        assertEquals(0, countRuleSetLines(""))
    }

    @Test
    fun pushOrderAndEnableDisable() = runBlocking {
        val sets = listOf(
            builtin(enabled = true),
            custom(id = "c1", action = RuleAction.DIRECT, enabled = true),
            custom(id = "c2", name = "Off", action = RuleAction.BLOCK, enabled = false),
        )
        val calls = mutableListOf<Triple<String, String, String>>()
        pushRuleSets(
            sets = sets,
            load = { id ->
                when (id) {
                    BUILTIN_RULE_SET_ID -> "a.com\nb.com\n"
                    "c1" -> "x.com\n"
                    else -> "y.com\n"
                }
            },
            apply = { id, action, text ->
                calls += Triple(id, action, text)
                "ok 2"
            },
        )
        assertEquals(
            listOf(
                Triple(BUILTIN_RULE_SET_ID, "block", "a.com\nb.com\n"),
                Triple("c1", "direct", "x.com\n"),
                // Disabled: pushed as empty text, which clears it.
                Triple("c2", "block", ""),
            ),
            calls,
        )
    }
}
