package io.github.elli0t0.proxyshare.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageHistoryTest {

    @Test
    fun applyDeltaAddsToTheSameDay() {
        val map = applyDelta(emptyMap(), "2026-11-22", 100, 200)
        val again = applyDelta(map, "2026-11-22", 50, 25)
        assertEquals(150L to 225L, again["2026-11-22"])
    }

    @Test
    fun applyDeltaRollsOverToANewDay() {
        val map = applyDelta(emptyMap(), "2026-11-22", 100, 200)
        val next = applyDelta(map, "2026-11-23", 10, 20)
        assertEquals(100L to 200L, next["2026-11-22"])
        assertEquals(10L to 20L, next["2026-11-23"])
    }

    @Test
    fun applyDeltaTrimsToTheNewestDays() {
        var map = emptyMap<String, Pair<Long, Long>>()
        val start = LocalDate.parse("2026-10-01")
        for (i in 0 until 36) {
            val date = start.plusDays(i.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            map = applyDelta(map, date, 1, 1, keepDays = 35)
        }
        assertEquals(35, map.size)
        assertTrue(!map.containsKey("2026-10-01"))
        assertTrue(map.containsKey("2026-10-02"))
        assertTrue(map.containsKey("2026-11-05"))
    }

    @Test
    fun applyDeltaNeverShrinksFromNegativeDeltas() {
        val map = applyDelta(mapOf("2026-11-22" to (100L to 200L)), "2026-11-22", -50, -25)
        assertEquals(100L to 200L, map["2026-11-22"])
    }

    @Test
    fun usageTodayAndMonthTotal() {
        val map = mapOf(
            "2026-11-21" to (10L to 20L),
            "2026-11-22" to (100L to 200L),
            "2026-10-22" to (1000L to 2000L),
        )
        assertEquals(100L to 200L, usageToday(map, "2026-11-22"))
        assertEquals(0L to 0L, usageToday(map, "2026-11-23"))
        assertEquals(110L to 220L, usageMonthTotal(map, "2026-11"))
        assertEquals(1000L to 2000L, usageMonthTotal(map, "2026-10"))
    }

    @Test
    fun usageLast7DaysEndsTodayWithGapsAsZero() {
        val map = mapOf(
            "2026-11-20" to (10L to 20L),
            "2026-11-22" to (100L to 200L),
        )
        val days = usageLast7Days(map, "2026-11-22")
        assertEquals(7, days.size)
        assertEquals("2026-11-16" to (0L to 0L), days.first())
        assertEquals("2026-11-20" to (10L to 20L), days[4])
        assertEquals("2026-11-22" to (100L to 200L), days.last())
    }

    @Test
    fun barFractionsScaleToTheMax() {
        assertEquals(listOf(0.5f, 1f, 0f), barFractions(listOf(50, 100, 0)))
        assertEquals(listOf(0f, 0f), barFractions(listOf(0, 0)))
        assertEquals(emptyList<Float>(), barFractions(emptyList()))
    }

    @Test
    fun parseAndSerializeRoundTrip() {
        val map = mapOf("2026-11-21" to (10L to 20L), "2026-11-22" to (100L to 200L))
        assertEquals(map, parseUsageJson(usageJson(map)))
    }

    @Test
    fun parseSkipsMalformedEntries() {
        assertEquals(emptyMap<String, Pair<Long, Long>>(), parseUsageJson("not json"))
        val parsed = parseUsageJson("""{"2026-11-22": [100, 200], "bad": [1], "neg": [-1, 5]}""")
        assertEquals(mapOf("2026-11-22" to (100L to 200L)), parsed)
    }
}
