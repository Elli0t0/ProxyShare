package io.github.elli0t0.proxyshare.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {

    @Test
    fun toEngineTextFormat() {
        val rules = listOf(
            RuleEntry("a.com", RuleAction.PROXY),
            RuleEntry("*.b.com", RuleAction.DIRECT),
            RuleEntry("=c.com", RuleAction.BLOCK),
            RuleEntry("10.0.0.0/8", RuleAction.BLOCK),
        )
        assertEquals(
            "proxy\ta.com\ndirect\t*.b.com\nblock\t=c.com\nblock\t10.0.0.0/8",
            rules.toEngineText()
        )
        assertEquals("", emptyList<RuleEntry>().toEngineText())
    }

    @Test
    fun engineTextRoundTrips() {
        val text = "proxy\ta.com\ndirect\t*.b.com\nblock\t=c.com\n"
        assertEquals(
            listOf(
                RuleEntry("a.com", RuleAction.PROXY),
                RuleEntry("*.b.com", RuleAction.DIRECT),
                RuleEntry("=c.com", RuleAction.BLOCK),
            ),
            rulesFromEngineText(text)
        )
        // Malformed lines are skipped, duplicates keep the first action.
        assertEquals(
            listOf(RuleEntry("a.com", RuleAction.PROXY)),
            rulesFromEngineText("proxy\ta.com\nbogus line\nproxy\ta.com\nblock\ta.com\n")
        )
    }

    @Test
    fun parseImportedRulesSkipsCommentsBlanksSpacesAndDuplicates() {
        val text = "# a comment\n" +
            "a.com\n" +
            "\n" +
            "   \n" +
            "b.com # trailing comment\n" +
            "two words.com\n" +
            "a.com\n" +
            "x".repeat(254) + "\n"
        val (ok, skipped) = parseImportedRules(text, RuleAction.BLOCK)
        assertEquals(
            listOf(RuleEntry("a.com", RuleAction.BLOCK), RuleEntry("b.com", RuleAction.BLOCK)),
            ok
        )
        assertEquals(2, skipped)
    }

    @Test
    fun parseImportedRulesDropsExistingPatterns() {
        val (ok, skipped) = parseImportedRules(
            "a.com\nc.com\n",
            RuleAction.DIRECT,
            existing = listOf(RuleEntry("a.com", RuleAction.BLOCK)),
        )
        assertEquals(listOf(RuleEntry("c.com", RuleAction.DIRECT)), ok)
        assertEquals(0, skipped)
    }

    @Test
    fun migrationIsIdempotent() {
        val (once, remaining) = migrateRules(
            emptyList(),
            listOf("ads.example.com", "*.tracker.com"),
        )
        assertEquals(
            listOf(
                RuleEntry("ads.example.com", RuleAction.BLOCK),
                RuleEntry("*.tracker.com", RuleAction.BLOCK),
            ),
            once
        )
        assertTrue(remaining.isEmpty())
        val (twice, remaining2) = migrateRules(once, remaining)
        assertEquals(once, twice)
        assertTrue(remaining2.isEmpty())
    }

    @Test
    fun migrationKeepsExistingActionForDuplicatePattern() {
        val (out, _) = migrateRules(
            listOf(RuleEntry("a.com", RuleAction.DIRECT)),
            listOf("a.com", "b.com"),
        )
        assertEquals(
            listOf(
                RuleEntry("a.com", RuleAction.DIRECT),
                RuleEntry("b.com", RuleAction.BLOCK),
            ),
            out
        )
    }

    @Test
    fun upsertReplacesSamePatternAndKeepsOrder() {
        val rules = listOf(
            RuleEntry("a.com", RuleAction.PROXY),
            RuleEntry("b.com", RuleAction.BLOCK),
        )
        assertEquals(
            listOf(
                RuleEntry("a.com", RuleAction.DIRECT),
                RuleEntry("b.com", RuleAction.BLOCK),
            ),
            rules.upsertRule(RuleEntry("a.com", RuleAction.DIRECT))
        )
        assertEquals(
            rules + RuleEntry("c.com", RuleAction.BLOCK),
            rules.upsertRule(RuleEntry("c.com", RuleAction.BLOCK))
        )
    }

    @Test
    fun filterRulesByActionAndQuery() {
        val rules = listOf(
            RuleEntry("ads.example.com", RuleAction.BLOCK),
            RuleEntry("news.example.com", RuleAction.PROXY),
            RuleEntry("video.example.org", RuleAction.DIRECT),
        )
        assertEquals(rules, filterRules(rules, RuleFilter.ALL, ""))
        assertEquals(
            listOf(RuleEntry("ads.example.com", RuleAction.BLOCK)),
            filterRules(rules, RuleFilter.BLOCK, "")
        )
        assertEquals(
            listOf(RuleEntry("news.example.com", RuleAction.PROXY)),
            filterRules(rules, RuleFilter.ALL, "NEWS")
        )
        assertEquals(
            listOf(RuleEntry("video.example.org", RuleAction.DIRECT)),
            filterRules(rules, RuleFilter.DIRECT, "example")
        )
        assertTrue(filterRules(rules, RuleFilter.PROXY, "example.org").isEmpty())
    }
}
