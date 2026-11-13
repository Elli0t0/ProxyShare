package com.example.proxyshare.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.proxyshare.R
import com.example.proxyshare.engine.ConnInfo
import com.example.proxyshare.service.ProxyController

/**
 * Live monitor: header with totals and current speed, then one
 * expandable card per client IP, sorted by total traffic.
 * started_ms is a unix time (see docs/ENGINE_API.md), so duration is
 * now minus startedMs. Fast polling runs only while this screen is
 * visible.
 */
@Composable
fun ConnectionsScreen(modifier: Modifier = Modifier) {
    val snapshot by ProxyController.snapshot.collectAsState()
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: totals and current speed.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.totals,
                            formatBytes(snapshot.totalUp),
                            formatBytes(snapshot.totalDown),
                            snapshot.active,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.connections_speed,
                            formatBytes(totalUpSpeed),
                            formatBytes(totalDownSpeed),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.connections_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Text(
                    text = group.client,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.connections_count, group.connections.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatBytes(group.totalUp + group.totalDown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                for (conn in group.connections) {
                    ConnectionRow(conn = conn, speed = speeds[conn.id])
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
                modifier = Modifier.weight(1f),
            )
            ProtoChip(conn.proto.uppercase())
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

@Composable
private fun ProtoChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
