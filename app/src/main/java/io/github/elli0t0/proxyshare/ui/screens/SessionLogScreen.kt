package io.github.elli0t0.proxyshare.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Patterns
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.engine.EngineState
import io.github.elli0t0.proxyshare.service.ProxyController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Session log content for the Activity tab's History segment: the
 * last 500 closed connections, newest first. Search filters
 * host/client, "Blocked only" shows refused connections. Tap a
 * row's host to copy it; long-press a hostname to block it (IP
 * literals cannot be blocked: the engine never matches them).
 * Reads ProxyController.sessionLog: no polling. The ⋮ menu lives
 * in ActivityScreen.
 */
@Composable
fun SessionLogContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ProxyController.state.collectAsStateWithLifecycle()
    val settings by ProxyController.settings.collectAsStateWithLifecycle()
    val sessionLog by ProxyController.sessionLog.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var blockedOnly by rememberSaveable { mutableStateOf(false) }
    var blockTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val running = state is EngineState.Running

    val entries = remember(sessionLog, search, blockedOnly) {
        filterSessionLog(sessionLog, search, blockedOnly).reversed()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Pull new records from the engine once a second, only while this
    // screen is visible: repeatOnLifecycle cancels the loop as soon as
    // we leave STARTED, so nothing polls in the background. The pull
    // also runs once when the tab opens (the effect starts on
    // composition).
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                ProxyController.pullSessionLog()
                delay(1000)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(stringResource(R.string.session_log_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FilterChip(
                selected = blockedOnly,
                onClick = { blockedOnly = !blockedOnly },
                label = { Text(stringResource(R.string.session_log_blocked_only)) },
            )
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (sessionLog.isEmpty()) {
                                R.string.session_log_empty
                            } else {
                                R.string.session_log_no_match
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        entries,
                        key = { it.id },
                    ) { entry ->
                        SessionLogRow(
                            entry = entry,
                            name = settings.clientNames[entry.client],
                            onCopyHost = { copyText(context, entry.host) },
                            onBlock = { blockTarget = entry.host },
                        )
                    }
                }
            }
        }

    if (blockTarget != null) {
        AlertDialog(
            onDismissRequest = { blockTarget = null },
            title = { Text(stringResource(R.string.block_domain_title)) },
            text = {
                Text(stringResource(R.string.block_domain_text, blockTarget ?: ""))
            },
            confirmButton = {
                TextButton(onClick = {
                    val host = blockTarget ?: return@TextButton
                    blockTarget = null
                    if (!validateBlockedDomain(host)) return@TextButton
                    scope.launch {
                        ProxyController.updateSettings {
                            updateBlockedDomains((settings.blockedDomains + host).distinct())
                        }
                    }
                    if (running) ProxyController.markRestartNeeded()
                }) {
                    Text(stringResource(R.string.blocked_domains_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { blockTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionLogRow(
    entry: SessionLogEntry,
    name: String?,
    onCopyHost: () -> Unit,
    onBlock: () -> Unit,
) {
    // IP literals cannot be blocked (the engine never matches them),
    // so the long-press only offers blocking for hostnames.
    val canBlock = validateBlockedDomain(entry.host) &&
        !Patterns.IP_ADDRESS.matcher(entry.host).matches()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onCopyHost,
                onLongClick = if (canBlock) onBlock else null,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${entry.host}:${entry.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "${entry.proto.uppercase()} · ${name ?: entry.client} · " +
                        formatDuration(entry.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "↑ ${formatBytes(entry.up)} · ↓ ${formatBytes(entry.down)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.blocked) {
                BlockedBadge()
            }
        }
    }
}

@Composable
private fun BlockedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = stringResource(R.string.blocked_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText("ProxyShare", text))
}
