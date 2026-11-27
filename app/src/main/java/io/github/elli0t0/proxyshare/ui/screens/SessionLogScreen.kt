package io.github.elli0t0.proxyshare.ui.screens

import android.content.Context
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import io.github.elli0t0.proxyshare.data.RuleAction
import io.github.elli0t0.proxyshare.data.RuleEntry
import io.github.elli0t0.proxyshare.data.isIpLiteral
import io.github.elli0t0.proxyshare.data.upsertRule
import io.github.elli0t0.proxyshare.engine.EngineState
import io.github.elli0t0.proxyshare.service.ProxyController
import io.github.elli0t0.proxyshare.ui.components.copyToClipboard
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Session log content for the Activity tab's History segment: the
 * last 500 closed connections, newest first. Search filters
 * host/client, "Blocked only" shows refused connections. Tap a
 * row's host to copy it; long-press a row to add a routing rule for
 * its host (IP literals get a plain rule, no subdomains checkbox).
 * Reads ProxyController.sessionLog. The ⋮ menu lives in ActivityScreen.
 */
@Composable
fun SessionLogContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by ProxyController.settings.collectAsStateWithLifecycle()
    val sessionLog by ProxyController.sessionLog.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var blockedOnly by rememberSaveable { mutableStateOf(false) }
    var ruleTarget by rememberSaveable { mutableStateOf<String?>(null) }

    val entries = remember(sessionLog, search, blockedOnly) {
        filterSessionLog(sessionLog, search, blockedOnly).reversed()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by ProxyController.state.collectAsStateWithLifecycle()
    val running = state is EngineState.Running

    // Pull new records from the engine once a second, only while this
    // screen is visible and the proxy is running: repeatOnLifecycle
    // cancels the loop as soon as we leave STARTED, and the loop
    // itself stops when the engine stops (a stopped engine has no
    // new records). One pull still runs when the tab opens.
    LaunchedEffect(lifecycle, running) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            ProxyController.pullSessionLog()
            while (running) {
                delay(1000)
                ProxyController.pullSessionLog()
            }
            // Stopped: wait for the effect to restart (running
            // changed) instead of returning from repeatOnLifecycle.
            awaitCancellation()
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
                            onCopyHost = { copyToClipboard(context, entry.host) },
                            onBlock = { ruleTarget = entry.host },
                        )
                    }
                }
            }
        }

    if (ruleTarget != null) {
        RuleForHostDialog(
            host = ruleTarget ?: "",
            onDismiss = { ruleTarget = null },
            onAdd = { pattern, action ->
                ruleTarget = null
                // Hot: the rules job pushes the new rule to the
                // engine at once, no restart needed.
                scope.launch {
                    ProxyController.updateSettings {
                        updateRules(settings.rules.upsertRule(RuleEntry(pattern, action)))
                    }
                }
            },
            // IP literals take a plain rule: no subdomains checkbox.
            showSubdomainsCheckbox = !isIpLiteral(ruleTarget ?: ""),
        )
    }
}

/**
 * Long-press a hostname in History (or tap a row in Domains): add a
 * routing rule for it. "Include subdomains" (on by default) writes
 * `host` (the domain and all its subdomains); off writes `=host`
 * (that exact host only). The host comes from a live connection, so
 * it is valid by construction — no validation round-trip. Domains
 * passes the row's current rule as the initial state, hides the
 * subdomains checkbox for IP literals, and offers [onRemove] to
 * delete that rule.
 */
@Composable
fun RuleForHostDialog(
    host: String,
    onDismiss: () -> Unit,
    onAdd: (String, RuleAction) -> Unit,
    initialAction: RuleAction = RuleAction.BLOCK,
    initialIncludeSubdomains: Boolean = true,
    showSubdomainsCheckbox: Boolean = true,
    onRemove: (() -> Unit)? = null,
) {
    var action by rememberSaveable { mutableStateOf(initialAction) }
    var includeSubdomains by rememberSaveable { mutableStateOf(initialIncludeSubdomains) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rule_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RuleAction.entries.forEachIndexed { i, a ->
                        SegmentedButton(
                            selected = action == a,
                            onClick = { action = a },
                            shape = SegmentedButtonDefaults.itemShape(i, RuleAction.entries.size),
                        ) {
                            Text(
                                stringResource(
                                    when (a) {
                                        RuleAction.PROXY -> R.string.rules_action_proxy
                                        RuleAction.DIRECT -> R.string.rules_action_direct
                                        RuleAction.BLOCK -> R.string.rules_action_block
                                    }
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (showSubdomainsCheckbox) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeSubdomains,
                            onCheckedChange = { includeSubdomains = it },
                        )
                        Text(
                            text = stringResource(R.string.rule_dialog_subdomains),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // An IP literal has no subdomains: it is always the
                // plain pattern (a bare IP is a valid host route).
                onAdd(
                    if (!showSubdomainsCheckbox || includeSubdomains) host else "=$host",
                    action,
                )
            }) {
                Text(stringResource(R.string.rule_dialog_add))
            }
        },
        dismissButton = {
            Row {
                if (onRemove != null) {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.domains_remove_rule))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionLogRow(
    entry: SessionLogEntry,
    name: String?,
    onCopyHost: () -> Unit,
    onBlock: () -> Unit,
) {
    // Any non-blank host can take a routing rule (0.6.6 rules match
    // IP literals too); the dialog hides the subdomains checkbox for
    // them. No JNI call during composition: the old
    // validateBlockedDomain check ran on the main thread for every
    // visible row.
    val canBlock = entry.host.isNotBlank()
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
