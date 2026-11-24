package io.github.elli0t0.proxyshare.ui.screens

import android.util.Patterns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.data.RuleAction
import io.github.elli0t0.proxyshare.data.RuleEntry
import io.github.elli0t0.proxyshare.data.ruleActionFromWire
import io.github.elli0t0.proxyshare.data.upsertRule
import io.github.elli0t0.proxyshare.service.ProxyController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Domains view (0.7.0), the second Activity segment: which domains
 * are being used, how much, through which route. The rows come from
 * [aggregateDomains] over the session log plus the live connections.
 * Tap a row to add (or remove) a routing rule for that host, through
 * the 0.6.7 dialog. The session log is pulled once a second, only
 * while this segment is visible (same pattern as History); the live
 * connections arrive through the snapshot flow.
 */
@Composable
fun DomainsContent(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val settings by ProxyController.settings.collectAsStateWithLifecycle()
    val sessionLog by ProxyController.sessionLog.collectAsStateWithLifecycle()
    val snapshot by ProxyController.snapshot.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf(DomainStatusFilter.ALL) }
    var route by rememberSaveable { mutableStateOf(DomainRouteFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(DomainSort.ACTIVE_FIRST) }
    var ruleTargetHost by rememberSaveable { mutableStateOf<String?>(null) }

    val rows = remember(sessionLog, snapshot, search, status, route, sort) {
        sortDomains(
            filterDomains(
                aggregateDomains(sessionLog, snapshot.connections),
                status, route, search,
            ),
            sort,
        )
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
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
            label = { Text(stringResource(R.string.domains_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilterDropdown(
                label = stringResource(R.string.domains_status),
                value = stringResource(
                    when (status) {
                        DomainStatusFilter.ALL -> R.string.domains_status_all
                        DomainStatusFilter.ACTIVE -> R.string.domains_status_active
                        DomainStatusFilter.BLOCKED -> R.string.domains_status_blocked
                    },
                ),
                options = listOf(
                    DomainStatusFilter.ALL to stringResource(R.string.domains_status_all),
                    DomainStatusFilter.ACTIVE to stringResource(R.string.domains_status_active),
                    DomainStatusFilter.BLOCKED to stringResource(R.string.domains_status_blocked),
                ),
                onSelect = { status = it },
                modifier = Modifier.weight(1f),
            )
            FilterDropdown(
                label = stringResource(R.string.domains_route),
                value = stringResource(
                    when (route) {
                        DomainRouteFilter.ALL -> R.string.rules_filter_all
                        DomainRouteFilter.PROXY -> R.string.rules_action_proxy
                        DomainRouteFilter.DIRECT -> R.string.rules_action_direct
                        DomainRouteFilter.BLOCK -> R.string.rules_action_block
                    },
                ),
                options = listOf(
                    DomainRouteFilter.ALL to stringResource(R.string.rules_filter_all),
                    DomainRouteFilter.PROXY to stringResource(R.string.rules_action_proxy),
                    DomainRouteFilter.DIRECT to stringResource(R.string.rules_action_direct),
                    DomainRouteFilter.BLOCK to stringResource(R.string.rules_action_block),
                ),
                onSelect = { route = it },
                modifier = Modifier.weight(1f),
            )
            FilterDropdown(
                label = stringResource(R.string.domains_sort),
                value = stringResource(
                    when (sort) {
                        DomainSort.ACTIVE_FIRST -> R.string.domains_sort_active_first
                        DomainSort.MOST_TRAFFIC -> R.string.domains_sort_traffic
                        DomainSort.MOST_REQUESTS -> R.string.domains_sort_requests
                        DomainSort.AZ -> R.string.domains_sort_az
                    },
                ),
                options = listOf(
                    DomainSort.ACTIVE_FIRST to stringResource(R.string.domains_sort_active_first),
                    DomainSort.MOST_TRAFFIC to stringResource(R.string.domains_sort_traffic),
                    DomainSort.MOST_REQUESTS to stringResource(R.string.domains_sort_requests),
                    DomainSort.AZ to stringResource(R.string.domains_sort_az),
                ),
                onSelect = { sort = it },
                modifier = Modifier.weight(1f),
            )
        }
        if (rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (sessionLog.isEmpty() && snapshot.connections.isEmpty()) {
                            R.string.domains_empty
                        } else {
                            R.string.domains_no_match
                        },
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
                items(rows, key = { it.host }) { row ->
                    DomainRow(
                        row = row,
                        rule = ruleForHost(row.host, settings.rules),
                        onClick = { ruleTargetHost = row.host },
                    )
                }
            }
        }
    }

    val targetHost = ruleTargetHost
    if (targetHost != null) {
        val currentRule = ruleForHost(targetHost, settings.rules)
        val isIp = Patterns.IP_ADDRESS.matcher(targetHost).matches()
        RuleForHostDialog(
            host = targetHost,
            onDismiss = { ruleTargetHost = null },
            onAdd = { pattern, action ->
                ruleTargetHost = null
                // Hot: the rules job pushes the new rule to the
                // engine at once, no restart needed.
                scope.launch {
                    ProxyController.updateSettings {
                        updateRules(settings.rules.upsertRule(RuleEntry(pattern, action)))
                    }
                }
            },
            initialAction = currentRule?.action ?: RuleAction.BLOCK,
            initialIncludeSubdomains = currentRule?.pattern != "=$targetHost",
            showSubdomainsCheckbox = !isIp,
            onRemove = if (currentRule != null) {
                {
                    ruleTargetHost = null
                    scope.launch {
                        ProxyController.updateSettings {
                            updateRules(
                                settings.rules.filter {
                                    it.pattern != targetHost && it.pattern != "=$targetHost"
                                },
                            )
                        }
                    }
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun DomainRow(
    row: DomainRow,
    rule: RuleEntry?,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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
                    text = row.host,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.domains_requests,
                        row.count,
                        formatBytes(row.up + row.down),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ActionTag(ruleActionFromWire(row.route) ?: RuleAction.PROXY)
                if (rule != null) ActionTag(rule.action)
            }
        }
    }
}

@Composable
private fun <T> FilterDropdown(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The button and its menu share one Box, so the menu anchors
        // under the button instead of at the row's left edge.
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = value,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (option, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}
