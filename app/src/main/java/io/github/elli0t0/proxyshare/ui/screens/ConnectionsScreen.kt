package io.github.elli0t0.proxyshare.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.launch
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.data.IfKind
import io.github.elli0t0.proxyshare.data.NetworkWatcher
import io.github.elli0t0.proxyshare.data.enabledPorts
import io.github.elli0t0.proxyshare.data.rankInterfaces
import io.github.elli0t0.proxyshare.engine.ConnInfo
import io.github.elli0t0.proxyshare.engine.EngineState
import io.github.elli0t0.proxyshare.engine.SpeedSample
import io.github.elli0t0.proxyshare.service.ProxyController
import io.github.elli0t0.proxyshare.ui.components.InfoChip
import io.github.elli0t0.proxyshare.ui.components.StatTile
import io.github.elli0t0.proxyshare.ui.components.TrafficChart

/**
 * Live monitor: header with session totals, traffic chart, then one
 * expandable card per client IP, sorted by total traffic.
 * started_ms is a unix time (see docs/ENGINE_API.md), so duration is
 * now minus startedMs. Fast polling runs only while this screen is
 * visible.
 */
@Composable
fun ConnectionsScreen(
    modifier: Modifier = Modifier,
    onGoHome: () -> Unit = {},
    onGoLogs: () -> Unit = {},
) {
    val state by ProxyController.state.collectAsStateWithLifecycle()
    val snapshot by ProxyController.snapshot.collectAsStateWithLifecycle()
    val settings by ProxyController.settings.collectAsStateWithLifecycle()
    val interfaces by NetworkWatcher.interfaces.collectAsStateWithLifecycle()
    val ranked = rankInterfaces(interfaces)
    val primary = ranked.firstOrNull()
    val (httpPort, socksPort) = settings.enabledPorts()
    val advertise = advertiseAddress(ranked, httpPort, socksPort)
    val speed = SpeedSample(snapshot.speedUp, snapshot.speedDown)
    val history by ProxyController.history.collectAsStateWithLifecycle()
    val sessionLog by ProxyController.sessionLog.collectAsStateWithLifecycle()
    val deniedClients by ProxyController.deniedClients.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var blockTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var showRejectedInfo by remember { mutableStateOf(false) }
    var prevConns by remember { mutableStateOf<Map<Long, ConnInfo>>(emptyMap()) }
    var prevTimeMs by remember { mutableLongStateOf(0L) }
    var speeds by remember { mutableStateOf<Map<Long, Speed>>(emptyMap()) }

    LaunchedEffect(snapshot) {
        val now = System.currentTimeMillis()
        val dt = if (prevTimeMs == 0L) 0L else now - prevTimeMs
        speeds = computeSpeeds(prevConns, snapshot.connections, dt)
        prevConns = snapshot.connections.associateBy { it.id }
        prevTimeMs = now
    }

    val groups = remember(snapshot.connections) { groupByClient(snapshot.connections) }
    val running = state is EngineState.Running
    val peakUp = remember(history) { history.maxOfOrNull { it.upBps } ?: 0L }
    val peakDown = remember(history) { history.maxOfOrNull { it.downBps } ?: 0L }

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
                    text = stringResource(R.string.nav_connections),
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
                        text = { Text(stringResource(R.string.session_log_title)) },
                        onClick = {
                            showMenu = false
                            onGoLogs()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_log)) },
                        onClick = {
                            showMenu = false
                            exportSessionLog(context, sessionLog)
                        },
                        enabled = sessionLog.isNotEmpty(),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.clear_log)) },
                        onClick = {
                            showMenu = false
                            ProxyController.clearSessionLog()
                        },
                        enabled = sessionLog.isNotEmpty(),
                    )
                }
            }

            // Header: active clients and this session's totals.
            // Speeds live only in the chart legend (single source).
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            ) {
                StatTile(
                    label = stringResource(R.string.stat_active),
                    value = snapshot.active.toString(),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                StatTile(
                    label = stringResource(R.string.stat_session_upload),
                    value = formatBytes(snapshot.liveUp),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    animate = false,
                )
                StatTile(
                    label = stringResource(R.string.stat_session_download),
                    value = formatBytes(snapshot.liveDown),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    animate = false,
                )
            }

            if (running) {
                TrafficChart(
                    history = history,
                    current = speed,
                    downloadLimitBps = settings.limitDownKbps * 1024L,
                    uploadLimitBps = settings.limitUpKbps * 1024L,
                )
                Text(
                    text = stringResource(
                        R.string.traffic_peak,
                        formatBytes(peakUp),
                        formatBytes(peakDown),
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val blockedCount = sessionLog.count { it.blocked }
                if (blockedCount > 0) {
                    Text(
                        text = stringResource(R.string.connections_blocked, blockedCount),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (snapshot.rejectedLimit > 0) {
                    Text(
                        text = stringResource(
                            R.string.rejected_connections,
                            snapshot.rejectedLimit,
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { showRejectedInfo = true },
                    )
                }
            } else {
                // Stopped: dimmed chart with an overlay, no radar state.
                Box(contentAlignment = Alignment.Center) {
                    TrafficChart(
                        history = history,
                        current = speed,
                        modifier = Modifier.alpha(0.4f),
                        downloadLimitBps = settings.limitDownKbps * 1024L,
                        uploadLimitBps = settings.limitUpKbps * 1024L,
                    )
                    Text(
                        text = stringResource(R.string.proxy_stopped),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!running) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.connections_stopped_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = onGoHome) {
                            Text(stringResource(R.string.go_home))
                        }
                    }
                }
            } else if (groups.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        EmptyStateDrawing()
                        Text(
                            text = stringResource(R.string.connections_empty),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (primary?.kind == IfKind.MOBILE) {
                            Text(
                                text = stringResource(R.string.mobile_only_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else if (advertise != null) {
                            Text(
                                text = advertise,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(onClick = { copyText(context, advertise) }) {
                                Text(stringResource(R.string.copy))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(groups, key = { it.client }) { group ->
                        ClientCard(
                            group = group,
                            speeds = speeds,
                            denied = group.client in deniedClients,
                            name = settings.clientNames[group.client],
                            onDisconnect = {
                                scope.launch { ProxyController.disconnectClient(group.client) }
                            },
                            onBlock = { blockTarget = group.client },
                            onUnblock = {
                                scope.launch { ProxyController.allowClient(group.client) }
                            },
                            onRename = { renameTarget = group.client },
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.session_log_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (blockTarget != null) {
        AlertDialog(
            onDismissRequest = { blockTarget = null },
            title = { Text(stringResource(R.string.block_client_title)) },
            text = { Text(stringResource(R.string.block_client_text, blockTarget ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    val ip = blockTarget ?: return@TextButton
                    blockTarget = null
                    scope.launch { ProxyController.denyClient(ip) }
                }) {
                    Text(stringResource(R.string.block_client))
                }
            },
            dismissButton = {
                TextButton(onClick = { blockTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (renameTarget != null) {
        RenameClientDialog(
            ip = renameTarget ?: "",
            currentName = settings.clientNames[renameTarget] ?: "",
            onDismiss = { renameTarget = null },
            onSave = { name ->
                val ip = renameTarget ?: return@RenameClientDialog
                renameTarget = null
                scope.launch {
                    ProxyController.updateSettings { updateClientName(ip, name) }
                }
            },
        )
    }

    if (showRejectedInfo) {
        AlertDialog(
            onDismissRequest = { showRejectedInfo = false },
            text = { Text(stringResource(R.string.rejected_connections_info)) },
            confirmButton = {
                TextButton(onClick = { showRejectedInfo = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}

/** Write the session log as JSON into the cache dir and share it via FileProvider. */
fun exportSessionLog(context: Context, log: List<SessionLogEntry>) {
    val file = File(context.cacheDir, "proxyshare-session-log.json")
    file.writeText(sessionLogJson(log))
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    ShareCompat.IntentBuilder(context)
        .setType("application/json")
        .setStream(uri)
        .setChooserTitle(R.string.export_log)
        .startChooser()
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText("ProxyShare", text))
}

@Composable
private fun ClientCard(
    group: ClientGroup,
    speeds: Map<Long, Speed>,
    denied: Boolean,
    name: String?,
    onDisconnect: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onRename: () -> Unit,
) {
    var expanded by rememberSaveable(group.client) { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            CircleShape,
                        ),
                ) {
                    Text(
                        text = name?.firstOrNull()?.uppercase()
                            ?: group.client.substringAfterLast('.', group.client),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = name ?: group.client,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = if (name == null) FontFamily.Monospace else null,
                    )
                    val countText = LocalContext.current.resources.getQuantityString(
                        R.plurals.connections_count,
                        group.connections.size,
                        group.connections.size,
                    )
                    Text(
                        text = if (name == null) countText else "${group.client} · $countText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatBytes(group.totalUp + group.totalDown),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFeatureSettings = "tnum",
                    ),
                )
                // The button and its menu share one Box, so the menu
                // anchors under the ⋮ button instead of at the card's
                // left edge (a DropdownMenu anchors to its parent).
                Box {
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
                            text = { Text(stringResource(R.string.rename_client)) },
                            onClick = {
                                showMenu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.disconnect_client)) },
                            onClick = {
                                showMenu = false
                                onDisconnect()
                            },
                        )
                        if (!denied) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.block_client)) },
                                onClick = {
                                    showMenu = false
                                    onBlock()
                                },
                            )
                        }
                    }
                }
            }
            if (denied) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BlockedBadge()
                    TextButton(onClick = onUnblock) {
                        Text(stringResource(R.string.unblock_client))
                    }
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (conn in group.connections) {
                        ConnectionRow(conn = conn, speed = speeds[conn.id])
                    }
                }
            }
        }
    }
}

/** Rename dialog for one client IP. An empty name clears the stored name. */
@Composable
private fun RenameClientDialog(
    ip: String,
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_client_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = ip,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.rename_client_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.rename_client_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Small error-colored "Blocked" chip (a blocked client, not a blocked domain). */
@Composable
private fun BlockedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = stringResource(R.string.client_blocked),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ConnectionRow(conn: ConnInfo, speed: Speed?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${conn.host}:${conn.port}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            InfoChip(conn.proto.uppercase())
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "↑ ${formatBytes(speed?.upBps ?: 0)}/s ↓ ${formatBytes(speed?.downBps ?: 0)}/s",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration(System.currentTimeMillis() - conn.startedMs),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Simple radar-style drawing for the empty state (no image files). */
@Composable
private fun EmptyStateDrawing() {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(120.dp)) {
        val stroke = 4.dp.toPx()
        val radius = size.minDimension / 2 - stroke
        val topLeft = Offset(center.x - radius, center.y - radius)
        val diameter = Size(radius * 2, radius * 2)
        drawCircle(
            color = outline,
            radius = radius,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = accent,
            startAngle = -90f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = topLeft,
            size = diameter,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawCircle(color = accent, radius = 10.dp.toPx())
    }
}
