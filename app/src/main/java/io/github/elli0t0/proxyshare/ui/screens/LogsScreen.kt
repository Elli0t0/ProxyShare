package io.github.elli0t0.proxyshare.ui.screens

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ShareCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.data.barFractions
import io.github.elli0t0.proxyshare.data.todayKey
import io.github.elli0t0.proxyshare.data.usageLast7Days
import io.github.elli0t0.proxyshare.data.usageMonthTotal
import io.github.elli0t0.proxyshare.data.usageToday
import io.github.elli0t0.proxyshare.engine.DiagLine
import io.github.elli0t0.proxyshare.engine.parseDiagLog
import io.github.elli0t0.proxyshare.engine.redactLog
import io.github.elli0t0.proxyshare.service.ProxyController
import io.github.elli0t0.proxyshare.ui.components.OptionRow
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** Unix ms -> "HH:mm:ss" in the device's timezone. */
fun fmtTs(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms))

/**
 * Filter engine log lines by message text and severity.
 * minLevel: 0 = All, 1 = Warn+ (WARN or ERROR), 2 = Error only.
 */
fun filterDiagLines(lines: List<DiagLine>, query: String, minLevel: Int): List<DiagLine> {
    val q = query.trim().lowercase()
    return lines.filter { line ->
        val levelOk = when (minLevel) {
            1 -> line.level == "WARN" || line.level == "ERROR"
            2 -> line.level == "ERROR"
            else -> true
        }
        val queryOk = q.isEmpty() || line.msg.lowercase().contains(q)
        levelOk && queryOk
    }
}

private fun shareDiagLog(context: Context, text: String) {
    val redacted = redactLog(text)
    if (redacted.isBlank()) return
    ShareCompat.IntentBuilder(context)
        .setType("text/plain")
        .setText(redacted)
        .setChooserTitle(R.string.diag_share)
        .startChooser()
}

/**
 * Logs tab: the session log, the engine diagnostics log and usage
 * history, switched with an OptionRow. The ⋮ menu shares or clears
 * whichever of the first two tabs is shown; Usage has its own Reset
 * button instead (nothing to share there).
 */
@Composable
fun LogsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    val sessionLog by ProxyController.sessionLog.collectAsStateWithLifecycle()
    val diagText by ProxyController.diagLogText.collectAsStateWithLifecycle()
    // Usage has no shareable content (its Reset lives in the tab itself).
    val canShare = when (tab) {
        0 -> sessionLog.isNotEmpty()
        1 -> diagText.isNotBlank()
        else -> false
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.nav_logs),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.connections_menu),
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.diag_share)) },
                        onClick = {
                            showMenu = false
                            if (tab == 0) {
                                exportSessionLog(context, sessionLog)
                            } else {
                                shareDiagLog(context, diagText)
                            }
                        },
                        enabled = canShare,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.clear_log)) },
                        onClick = {
                            showMenu = false
                            if (tab == 0) ProxyController.clearSessionLog()
                            else ProxyController.clearDiagLog()
                        },
                        enabled = canShare,
                    )
                }
            }
            OptionRow(
                options = listOf(
                    0 to stringResource(R.string.logs_history),
                    1 to stringResource(R.string.logs_engine),
                    2 to stringResource(R.string.logs_usage),
                ),
                selected = tab,
                onSelect = { tab = it },
            )
            when (tab) {
                0 -> SessionLogContent(modifier = Modifier.weight(1f))
                1 -> EngineLogContent(modifier = Modifier.weight(1f))
                else -> UsageContent(modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Engine diagnostics: one row per line, newest at the bottom.
 * Refreshes once a second while visible; "Follow" keeps the view
 * pinned to the newest line.
 */
@Composable
private fun EngineLogContent(modifier: Modifier = Modifier) {
    val diagText by ProxyController.diagLogText.collectAsStateWithLifecycle()
    val lines = remember(diagText) { parseDiagLog(diagText) }
    var query by rememberSaveable { mutableStateOf("") }
    var levelFilter by rememberSaveable { mutableIntStateOf(0) }
    var follow by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Refresh once a second, only while this screen is visible:
    // repeatOnLifecycle cancels the loop as soon as we leave STARTED,
    // so nothing polls in the background.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                ProxyController.refreshDiag()
                delay(1000)
            }
        }
    }

    val visible = remember(lines, query, levelFilter) {
        filterDiagLines(lines, query, levelFilter)
    }
    // Follow the newest VISIBLE line (visible.lastIndex, not
    // lines.lastIndex: with a filter on, the last line may be hidden,
    // and scrolling past the end of the list would crash).
    LaunchedEffect(visible.size, follow) {
        if (follow && visible.isNotEmpty()) listState.scrollToItem(visible.lastIndex)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.log_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = levelFilter == 0,
                onClick = { levelFilter = 0 },
                label = { Text(stringResource(R.string.log_filter_all)) },
            )
            FilterChip(
                selected = levelFilter == 1,
                onClick = { levelFilter = 1 },
                label = { Text(stringResource(R.string.log_filter_warn)) },
            )
            FilterChip(
                selected = levelFilter == 2,
                onClick = { levelFilter = 2 },
                label = { Text(stringResource(R.string.log_filter_error)) },
            )
            FilterChip(
                selected = follow,
                onClick = { follow = !follow },
                label = { Text(stringResource(R.string.log_follow)) },
            )
        }
        if (visible.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.diag_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(
                    visible,
                    key = { line -> "${line.tsMs}|${line.level}|${line.msg}" },
                ) { line ->
                    DiagRow(line)
                }
            }
        }
    }
}

@Composable
private fun DiagRow(line: DiagLine) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = fmtTs(line.tsMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LevelBadge(level = line.level)
        if (line.count > 1) {
            Text(
                text = "×${line.count}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = line.msg,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LevelBadge(level: String) {
    val (bg, fg) = when (level) {
        "WARN" -> Color(0xFFFFB300) to Color.Black
        "ERROR" -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = level,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * Usage history: today, the last 7 days as a bar chart (up/down per
 * day, forced LTR), this month's total, and a Reset button. Reads
 * ProxyController.usageHistory: no polling.
 */
@Composable
private fun UsageContent(modifier: Modifier = Modifier) {
    val history by ProxyController.usageHistory.collectAsStateWithLifecycle()
    var showReset by remember { mutableStateOf(false) }
    val today = todayKey()
    val days = remember(history, today) { usageLast7Days(history, today) }
    val (todayUp, todayDown) = usageToday(history, today)
    val (monthUp, monthDown) = remember(history, today) {
        usageMonthTotal(history, today.substring(0, 7))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (history.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.usage_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.usage_today),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "↑ ${formatBytes(todayUp)} · ↓ ${formatBytes(todayDown)}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFeatureSettings = "tnum",
                ),
            )
            Text(
                text = stringResource(R.string.usage_last_7_days),
                style = MaterialTheme.typography.titleMedium,
            )
            UsageChart(days = days)
            Text(
                text = stringResource(R.string.usage_this_month),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "↑ ${formatBytes(monthUp)} · ↓ ${formatBytes(monthDown)}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFeatureSettings = "tnum",
                ),
            )
            OutlinedButton(onClick = { showReset = true }) {
                Text(stringResource(R.string.usage_reset))
            }
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(stringResource(R.string.usage_reset_title)) },
            text = { Text(stringResource(R.string.usage_reset_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showReset = false
                    ProxyController.clearUsage()
                }) {
                    Text(stringResource(R.string.usage_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Two thin bars per day (up in tertiary, down in primary), forced LTR, no library. */
@Composable
private fun UsageChart(days: List<Pair<String, Pair<Long, Long>>>) {
    val fractions = remember(days) {
        barFractions(days.flatMap { listOf(it.second.first, it.second.second) })
    }
    val upColor = MaterialTheme.colorScheme.tertiary
    val downColor = MaterialTheme.colorScheme.primary
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Canvas(
                modifier = Modifier.fillMaxWidth().height(120.dp),
            ) {
                val groupWidth = size.width / 7
                val barWidth = 8.dp.toPx()
                val gap = 2.dp.toPx()
                for (i in 0..6) {
                    val centerX = groupWidth * i + groupWidth / 2
                    val upF = fractions.getOrNull(i * 2) ?: 0f
                    val downF = fractions.getOrNull(i * 2 + 1) ?: 0f
                    drawRect(
                        color = upColor,
                        topLeft = Offset(
                            centerX - barWidth - gap / 2,
                            size.height - upF * size.height,
                        ),
                        size = Size(barWidth, upF * size.height),
                    )
                    drawRect(
                        color = downColor,
                        topLeft = Offset(
                            centerX + gap / 2,
                            size.height - downF * size.height,
                        ),
                        size = Size(barWidth, downF * size.height),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                for ((date, _) in days) {
                    Text(
                        text = date.substring(8, 10),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(color = upColor)
                    }
                    Text(
                        text = stringResource(R.string.usage_up),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(color = downColor)
                    }
                    Text(
                        text = stringResource(R.string.usage_down),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
