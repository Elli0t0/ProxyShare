package io.github.elli0t0.proxyshare.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val load: (String) -> String = { id ->
            when (id) {
                BUILTIN_RULE_SET_ID -> "a.com\nb.com\n"
                "c1" -> "x.com\n"
                else -> "y.com\n"
            }
        }
        val apply: (String, String, String) -> String = { id, action, text ->
            calls += Triple(id, action, text)
            "ok 2"
        }
        val (pushed, errors) = pushRuleSetsDiffed(sets, emptyMap(), load, apply)
        assertEquals(
            listOf(
                Triple(BUILTIN_RULE_SET_ID, "block", "a.com\nb.com\n"),
                Triple("c1", "direct", "x.com\n"),
                // Disabled: pushed as empty text, which clears it.
                Triple("c2", "block", ""),
            ),
            calls,
        )
        assertTrue(errors.isEmpty())
        assertEquals(3, pushed.size)
        // Second push with the same state: nothing is sent again.
        calls.clear()
        val (pushed2, _) = pushRuleSetsDiffed(sets, pushed, load, apply)
        assertTrue(calls.isEmpty())
        assertEquals(pushed, pushed2)
        // A name-only change (same enabled/action/content) also pushes nothing.
        val renamed = sets.map { if (it.id == "c1") it.copy(name = "Work 2") else it }
        val (pushed3, _) = pushRuleSetsDiffed(renamed, pushed, load, apply)
        assertTrue(calls.isEmpty())
        assertEquals(pushed, pushed3)
    }

    @Test
    fun pushErrorIsReportedAndNotRecorded() = runBlocking {
        val sets = listOf(custom(id = "c1"))
        val (pushed, errors) = pushRuleSetsDiffed(
            sets, emptyMap(),
            load = { "a.com\n" },
            apply = { _, _, _ -> "error line 1: bad domain: bad!" },
        )
        assertEquals(mapOf("c1" to "error line 1: bad domain: bad!"), errors)
        assertTrue(pushed.isEmpty())
        // The next push retries the failed set.
        val (pushed2, errors2) = pushRuleSetsDiffed(
            sets, pushed,
            load = { "a.com\n" },
            apply = { _, _, _ -> "ok 1" },
        )
        assertTrue(errors2.isEmpty())
        assertEquals(1, pushed2.size)
    }

    @Test
    fun parseRuleSetDomainsKeepsPlainDomainsOnly() {
        val (added, skipped) = parseRuleSetDomains(
            "# a comment\n\nA.COM.\n b.com # trailing\n*.c.com\n=a.com\n" +
                "1.2.3.4\n2001:db8::1\na.com\nhttp://d.com\ne.com/f\nbad domain\n" +
                "x".repeat(254) + ".com\n",
        )
        assertEquals(listOf("a.com", "b.com"), added)
        assertEquals(9, skipped)
    }

    @Test
    fun parseRuleSetDomainsDedupesAgainstExisting() {
        val (added, skipped) = parseRuleSetDomains("a.com\nb.com\n", setOf("a.com"))
        assertEquals(listOf("b.com"), added)
        assertEquals(1, skipped)
    }

    @Test
    fun stripRuleSetActionPrefixesKeepsPlainDomains() {
        assertEquals(
            "a.com\nb.com\nc.com",
            stripRuleSetActionPrefixes("block\ta.com\ndirect\tb.com\nc.com"),
        )
    }

    @Test
    fun isIpLiteralSpotsV4AndV6() {
        assertTrue(isIpLiteral("1.2.3.4"))
        assertTrue(isIpLiteral("::1"))
        assertTrue(isIpLiteral("2001:db8::1"))
        assertFalse(isIpLiteral("a.com"))
        assertFalse(isIpLiteral("1.2.3"))
        assertFalse(isIpLiteral(""))
    }
}
