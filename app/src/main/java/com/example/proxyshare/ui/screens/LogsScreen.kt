package com.example.proxyshare.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ShareCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.proxyshare.R
import com.example.proxyshare.engine.DiagLine
import com.example.proxyshare.engine.parseDiagLog
import com.example.proxyshare.engine.redactLog
import com.example.proxyshare.service.ProxyController
import com.example.proxyshare.ui.components.OptionRow
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
 * Logs tab: the session log and the engine diagnostics log, switched
 * with an OptionRow. The ⋮ menu shares or clears whichever tab is
 * shown.
 */
@Composable
fun LogsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    val sessionLog by ProxyController.sessionLog.collectAsStateWithLifecycle()
    val diagText by ProxyController.diagLogText.collectAsStateWithLifecycle()
    val canShare = if (tab == 0) sessionLog.isNotEmpty() else diagText.isNotBlank()

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
                    0 to stringResource(R.string.nav_connections),
                    1 to stringResource(R.string.logs_engine),
                ),
                selected = tab,
                onSelect = { tab = it },
            )
            if (tab == 0) {
                SessionLogContent(modifier = Modifier.weight(1f))
            } else {
                EngineLogContent(modifier = Modifier.weight(1f))
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
