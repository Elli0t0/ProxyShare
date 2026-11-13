package com.example.proxyshare.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.proxyshare.R
import com.example.proxyshare.data.enabledPorts
import com.example.proxyshare.data.networkInterfaces
import com.example.proxyshare.data.rankInterfaces
import com.example.proxyshare.engine.ConnInfo
import com.example.proxyshare.service.ProxyController
import com.example.proxyshare.ui.components.InfoChip
import com.example.proxyshare.ui.components.StatTile

/**
 * Live monitor: header with totals and current speed, then one
 * expandable card per client IP, sorted by total traffic.
 * started_ms is a unix time (see docs/ENGINE_API.md), so duration is
 * now minus startedMs. Fast polling runs only while this screen is
 * visible.
 */
@Composable
fun ConnectionsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val snapshot by ProxyController.snapshot.collectAsState()
    val settings by ProxyController.settings.collectAsState()
    val netFlow = remember(context) { networkInterfaces(context) }
    val interfaces by netFlow.collectAsState(initial = emptyList())
    val primary = rankInterfaces(interfaces).firstOrNull()
    val (httpPort, socksPort) = settings.enabledPorts()
    val primaryAddress = primary?.let { nif ->
        val port = httpPort ?: socksPort
        if (port != null) "${nif.ip}:$port" else nif.ip
    }
    var prevConns by remember { mutableStateOf<Map<Long, ConnInfo>>(emptyMap()) }
    var prevTimeMs by remember { mutableLongStateOf(0L) }
    var speeds by remember { mutableStateOf<Map<Long, Speed>>(emptyMap()) }

    DisposableEffect(Unit) {
        ProxyController.setFastPolling(true)
        onDispose { ProxyController.setFastPolling(false) }
    }

    LaunchedEffect(snapshot) {
        val now = System.currentTimeMillis()
        val dt = if (prevTimeMs == 0L) 0L else now - prevTimeMs
        speeds = computeSpeeds(prevConns, snapshot.connections, dt)
        prevConns = snapshot.connections.associateBy { it.id }
        prevTimeMs = now
    }

    val totalUpSpeed = speeds.values.sumOf { it.upBps }
    val totalDownSpeed = speeds.values.sumOf { it.downBps }
    val groups = remember(snapshot.connections) { groupByClient(snapshot.connections) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: three stat tiles plus a small totals line.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatTile(
                    label = stringResource(R.string.stat_active),
                    value = snapshot.active.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.stat_upload_speed),
                    value = "${formatBytes(totalUpSpeed)}/s",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.stat_download_speed),
                    value = "${formatBytes(totalDownSpeed)}/s",
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(
                    R.string.totals,
                    formatBytes(snapshot.totalUp),
                    formatBytes(snapshot.totalDown),
                    snapshot.active,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (groups.isEmpty()) {
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
                        if (primaryAddress != null) {
                            Text(
                                text = stringResource(
                                    R.string.connections_empty_hint,
                                    primaryAddress,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                        ClientCard(group = group, speeds = speeds)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientCard(group: ClientGroup, speeds: Map<Long, Speed>) {
    var expanded by rememberSaveable(group.client) { mutableStateOf(false) }
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
                        text = group.client.substringAfterLast('.', group.client),
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
                        text = group.client,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = stringResource(
                            R.string.connections_count,
                            group.connections.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatBytes(group.totalUp + group.totalDown),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration(System.currentTimeMillis() - conn.startedMs),
                style = MaterialTheme.typography.bodySmall,
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
