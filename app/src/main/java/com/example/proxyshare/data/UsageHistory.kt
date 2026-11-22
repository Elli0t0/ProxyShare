package com.example.proxyshare.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** Days of usage history kept. */
const val USAGE_KEEP_DAYS = 35

/** "yyyy-MM-dd" for today, in the device's timezone. */
fun todayKey(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

/**
 * Add one flush of bytes to a day's totals. Negative deltas are
 * clamped to 0 (a flush must never shrink history). Keeps only the
 * newest [keepDays] dates. Pure: no DataStore, no clock.
 */
fun applyDelta(
    map: Map<String, Pair<Long, Long>>,
    date: String,
    up: Long,
    down: Long,
    keepDays: Int = USAGE_KEEP_DAYS,
): Map<String, Pair<Long, Long>> {
    val out = LinkedHashMap(map)
    val (oldUp, oldDown) = out[date] ?: (0L to 0L)
    out[date] = (oldUp + up.coerceAtLeast(0)) to (oldDown + down.coerceAtLeast(0))
    val sorted = out.keys.sorted()
    val drop = sorted.size - keepDays.coerceAtLeast(1)
    if (drop > 0) sorted.take(drop).forEach { out.remove(it) }
    return out
}

/** Totals for one day, or (0, 0) when the day has no entry. */
fun usageToday(map: Map<String, Pair<Long, Long>>, date: String): Pair<Long, Long> =
    map[date] ?: (0L to 0L)

/** Totals for a whole month: [yearMonth] is "yyyy-MM". */
fun usageMonthTotal(map: Map<String, Pair<Long, Long>>, yearMonth: String): Pair<Long, Long> {
    var up = 0L
    var down = 0L
    for ((date, totals) in map) {
        if (date.startsWith(yearMonth)) {
            up += totals.first
            down += totals.second
        }
    }
    return up to down
}

/** The 7 days ending on [today] (inclusive), oldest first, with their totals. */
fun usageLast7Days(
    map: Map<String, Pair<Long, Long>>,
    today: String,
): List<Pair<String, Pair<Long, Long>>> {
    val end = runCatching { LocalDate.parse(today) }.getOrNull() ?: return emptyList()
    return (6 downTo 0).map { back ->
        val date = end.minusDays(back.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
        date to usageToday(map, date)
    }
}

/** Scale bar values to 0..1 fractions of the largest value (all 0 when the max is 0). */
fun barFractions(values: List<Long>): List<Float> {
    val max = values.maxOrNull() ?: return emptyList()
    if (max <= 0) return values.map { 0f }
    return values.map { it.toFloat() / max }
}

/** Parse the stored JSON object (date -> [up, down]). Malformed entries are skipped. */
fun parseUsageJson(json: String): Map<String, Pair<Long, Long>> {
    val out = LinkedHashMap<String, Pair<Long, Long>>()
    runCatching {
        val obj = JSONObject(json)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val date = keys.next()
            val arr = obj.optJSONArray(date) ?: continue
            if (arr.length() < 2) continue
            val up = arr.optLong(0, -1)
            val down = arr.optLong(1, -1)
            if (up >= 0 && down >= 0) out[date] = up to down
        }
    }
    return out
}

/** Serialize the usage map for DataStore. */
fun usageJson(map: Map<String, Pair<Long, Long>>): String {
    val obj = JSONObject()
    for ((date, totals) in map) {
        obj.put(date, JSONArray(listOf(totals.first, totals.second)))
    }
    return obj.toString()
}

private val Context.usageDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "usage"
)

/** Persists per-day up/down totals (see applyDelta) and exposes them as a Flow. */
class UsageHistoryStore(context: Context) {
    private val dataStore = context.applicationContext.usageDataStore

    val history: Flow<Map<String, Pair<Long, Long>>> = dataStore.data.map { prefs ->
        parseUsageJson(prefs[Keys.USAGE_JSON] ?: "")
    }

    /** Add one flush of bytes to today's totals. */
    suspend fun add(up: Long, down: Long, date: String = todayKey()) {
        if (up <= 0 && down <= 0) return
        dataStore.edit { prefs ->
            val map = parseUsageJson(prefs[Keys.USAGE_JSON] ?: "")
            prefs[Keys.USAGE_JSON] = usageJson(applyDelta(map, date, up, down))
        }
    }

    /** Drop all recorded history. */
    suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(Keys.USAGE_JSON) }
    }

    private object Keys {
        val USAGE_JSON = stringPreferencesKey("usage_json")
    }
}
