package io.github.elli0t0.proxyshare.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.data.RuleAction
import io.github.elli0t0.proxyshare.data.RuleEntry
import io.github.elli0t0.proxyshare.data.RuleFilter
import io.github.elli0t0.proxyshare.data.filterRules
import io.github.elli0t0.proxyshare.data.parseImportedRules
import io.github.elli0t0.proxyshare.data.toEngineText
import io.github.elli0t0.proxyshare.data.upsertRule
import io.github.elli0t0.proxyshare.engine.EngineState
import io.github.elli0t0.proxyshare.engine.parseDiagLog
import io.github.elli0t0.proxyshare.service.ProxyController
import io.github.elli0t0.proxyshare.service.ProxyController.RulesStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rules page (0.6.7, 0.6.8; moved into Settings in 0.7.1): two pages
 * under one segmented control. Manual (0.6.7) is the manual routing
 * rules: search + All/Proxy/Direct/Block chips, Add (dialog), Import
 * file (one action for the whole file), Export (engine text). Tap a
 * row to change its action or delete it. Rules are stored in
 * DataStore and pushed to the engine hot (ProxyController.pushRules)
 * — no restart. The one-line status under the buttons is the push
 * result: "Applied N rules" or the engine's error line (a bad rule
 * rejects the whole set, so the previous rules stay live until it is
 * fixed or deleted). Rule sets (0.6.8) is the built-in Ads & trackers
 * list plus user-made named lists (see RuleSetsScreen.kt).
 * [topInset] is false when a wrapper already added the status-bar
 * inset (Settings detail does, same pattern as ConnectionsScreen).
 */
private enum class RulesPage { MANUAL, SETS }

@Composable
fun RulesScreen(modifier: Modifier = Modifier, topInset: Boolean = true) {
    val settings by ProxyController.settings.collectAsStateWithLifecycle()
    val state by ProxyController.state.collectAsStateWithLifecycle()
    val rulesStatus by ProxyController.rulesStatus.collectAsStateWithLifecycle()
    val diagText by ProxyController.diagLogText.collectAsStateWithLifecycle()
    val rules = settings.rules
    val running = state is EngineState.Running
    var page by rememberSaveable { mutableStateOf(RulesPage.MANUAL) }

    Column(
        modifier = modifier.fillMaxSize()
            .let { if (topInset) it.statusBarsPadding() else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RulesPage.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = page == p,
                    onClick = { page = p },
                    shape = SegmentedButtonDefaults.itemShape(i, RulesPage.entries.size),
                ) {
                    Text(
                        stringResource(
                            when (p) {
                                RulesPage.MANUAL -> R.string.rules_tab_manual
                                RulesPage.SETS -> R.string.rules_tab_sets
                            }
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (page) {
                RulesPage.MANUAL -> ManualRulesPage(rules, running, rulesStatus, diagText)
                RulesPage.SETS -> RuleSetsPage(settings)
            }
        }
    }
}

@Composable
private fun ManualRulesPage(
    rules: List<RuleEntry>,
    running: Boolean,
    rulesStatus: RulesStatus,
    diagText: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(RuleFilter.ALL) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var importAction by remember { mutableStateOf(RuleAction.BLOCK) }
    var menuTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var importResult by rememberSaveable { mutableStateOf<String?>(null) }

    // Refresh the diagnostics log when the tab opens, so the Direct
    // warning sees fresh WARN lines (the Logs tab refreshes it too).
    LaunchedEffect(Unit) { ProxyController.refreshDiag() }

    val visible = remember(rules, search, filter) { filterRules(rules, filter, search) }
    val hasDirect = remember(rules) { rules.any { it.action == RuleAction.DIRECT } }
    val directFails = remember(diagText) { directFailureWarnCount(parseDiagLog(diagText)) }

    fun saveRules(newRules: List<RuleEntry>) {
        scope.launch { ProxyController.updateSettings { updateRules(newRules) } }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val action = importAction
        scope.launch {
            val text = readTextFile(context, uri) ?: return@launch
            // Read the current rules here, not the composed snapshot:
            // the launcher callback keeps the first composition's
            // closure, and the rules may have changed since.
            val current = ProxyController.settings.value.rules
            val (added, skipped) = parseImportedRules(text, action, current)
            if (added.isNotEmpty()) saveRules(current + added)
            importResult = context.getString(
                R.string.rules_imported, added.size, skipped
            )
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = ProxyController.settings.value.rules.toEngineText()
        scope.launch { writeTextFile(context, uri, text) }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text(stringResource(R.string.rules_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (f in RuleFilter.entries) {
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = {
                        Text(
                            stringResource(
                                when (f) {
                                    RuleFilter.ALL -> R.string.rules_filter_all
                                    RuleFilter.PROXY -> R.string.rules_action_proxy
                                    RuleFilter.DIRECT -> R.string.rules_action_direct
                                    RuleFilter.BLOCK -> R.string.rules_action_block
                                }
                            )
                        )
                    },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(onClick = { showAdd = true }) {
                Text(stringResource(R.string.rules_add))
            }
            OutlinedButton(onClick = { showImport = true }) {
                Text(stringResource(R.string.rules_import))
            }
            OutlinedButton(onClick = { exportLauncher.launch("proxyshare-rules.txt") }) {
                Text(stringResource(R.string.rules_export))
            }
        }
        // One-line push status: the engine's verdict on the rules.
        val statusText = when {
            !running -> stringResource(R.string.rules_apply_on_start)
            rulesStatus is RulesStatus.Applied ->
                stringResource(
                    R.string.rules_applied, (rulesStatus as RulesStatus.Applied).count
                )
            rulesStatus is RulesStatus.Error -> (rulesStatus as RulesStatus.Error).text
            else -> null
        }
        if (statusText != null) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (rulesStatus is RulesStatus.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (importResult != null) {
            Text(
                text = importResult ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Lockdown warning (0.6.6): only when a Direct rule exists
        // AND the engine already saw Direct traffic fail — never
        // guessed from the VPN's system settings.
        if (hasDirect && directFails > 0) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Text(
                        text = stringResource(R.string.rules_direct_warning),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (visible.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.rules_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.pattern }) { entry ->
                    RuleRow(
                        entry = entry,
                        menuOpen = menuTarget == entry.pattern,
                        onOpenMenu = { menuTarget = entry.pattern },
                        onDismissMenu = { menuTarget = null },
                        onAction = { action ->
                            menuTarget = null
                            saveRules(rules.upsertRule(entry.copy(action = action)))
                        },
                        onDelete = {
                            menuTarget = null
                            saveRules(rules - entry)
                        },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddRuleDialog(
            onDismiss = { showAdd = false },
            onAdd = { pattern, action ->
                showAdd = false
                val p = pattern.trim()
                if (p.isNotEmpty()) saveRules(rules.upsertRule(RuleEntry(p, action)))
            },
        )
    }
    if (showImport) {
        ImportRulesDialog(
            onDismiss = { showImport = false },
            onPick = { action ->
                showImport = false
                importAction = action
                importLauncher.launch(arrayOf("text/*"))
            },
        )
    }
}

@Composable
private fun RuleRow(
    entry: RuleEntry,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onAction: (RuleAction) -> Unit,
    onDelete: () -> Unit,
) {
    // The row and its menu share one Box, so the menu anchors under
    // the row instead of at the list's left edge.
    Box {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenMenu),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = entry.pattern,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                ActionTag(entry.action)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
            for (action in RuleAction.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                when (action) {
                                    RuleAction.PROXY -> R.string.rules_action_proxy
                                    RuleAction.DIRECT -> R.string.rules_action_direct
                                    RuleAction.BLOCK -> R.string.rules_action_block
                                }
                            )
                        )
                    },
                    onClick = { onAction(action) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rules_delete)) },
                onClick = onDelete,
            )
        }
    }
}

/** The colored action tag on a rule row; RuleSetsScreen reuses it for set cards. */
@Composable
fun ActionTag(action: RuleAction) {
    val (container, content) = when (action) {
        RuleAction.PROXY -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        RuleAction.DIRECT -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        RuleAction.BLOCK -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = stringResource(
                when (action) {
                    RuleAction.PROXY -> R.string.rules_action_proxy
                    RuleAction.DIRECT -> R.string.rules_action_direct
                    RuleAction.BLOCK -> R.string.rules_action_block
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ActionSegmentedRow(
    selected: RuleAction,
    onSelect: (RuleAction) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        RuleAction.entries.forEachIndexed { i, action ->
            SegmentedButton(
                selected = selected == action,
                onClick = { onSelect(action) },
                shape = SegmentedButtonDefaults.itemShape(i, RuleAction.entries.size),
            ) {
                Text(
                    stringResource(
                        when (action) {
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
}

@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (String, RuleAction) -> Unit,
) {
    var pattern by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf(RuleAction.BLOCK) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rules_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.rules_pattern)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ActionSegmentedRow(selected = action, onSelect = { action = it })
                Text(
                    text = stringResource(R.string.rules_add_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(pattern, action) }) {
                Text(stringResource(R.string.rules_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ImportRulesDialog(
    onDismiss: () -> Unit,
    onPick: (RuleAction) -> Unit,
) {
    var action by rememberSaveable { mutableStateOf(RuleAction.BLOCK) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rules_import_title)) },
        text = {
            ActionSegmentedRow(selected = action, onSelect = { action = it })
        },
        confirmButton = {
            TextButton(onClick = { onPick(action) }) {
                Text(stringResource(R.string.rules_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private suspend fun readTextFile(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

private suspend fun writeTextFile(context: Context, uri: Uri, text: String) {
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
        }
    }
}
