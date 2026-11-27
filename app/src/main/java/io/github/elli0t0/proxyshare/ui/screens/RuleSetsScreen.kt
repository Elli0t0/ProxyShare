package io.github.elli0t0.proxyshare.ui.screens

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.data.AppSettings
import io.github.elli0t0.proxyshare.data.BUILTIN_RULE_SET_ID
import io.github.elli0t0.proxyshare.data.RuleAction
import io.github.elli0t0.proxyshare.data.RuleSetMeta
import io.github.elli0t0.proxyshare.data.isSubscription
import io.github.elli0t0.proxyshare.data.countRuleSetLines
import io.github.elli0t0.proxyshare.data.deleteRuleSetFile
import io.github.elli0t0.proxyshare.data.loadRuleSetFileText
import io.github.elli0t0.proxyshare.data.parseRuleSetDomains
import io.github.elli0t0.proxyshare.data.readBuiltinRuleSetText
import io.github.elli0t0.proxyshare.data.saveRuleSetFileText
import io.github.elli0t0.proxyshare.service.ProxyController
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rule sets page (0.6.8), the second half of the Rules page: the
 * built-in Ads & trackers list (OISD small, bundled as an asset,
 * Block only) and user-made named lists. Subscriptions (0.6.9) are
 * named lists whose content comes from an https URL instead of a
 * file import: "Update now" per set, "Update all", and one automatic
 * refresh on proxy start when the last update is older than 24 h. A set is one action for all
 * its domains; manual rules always win over every set, and the sets
 * apply in list order, first hit wins. Toggling, importing, creating
 * and editing push hot through ProxyController.pushRuleSets — no
 * restart. Deleting a set clears it in the engine first, or the
 * engine would keep serving a set the metadata no longer lists.
 */
@Composable
fun RuleSetsPage(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sets = settings.ruleSets
    val builtin = sets.firstOrNull { it.id == BUILTIN_RULE_SET_ID }
    val customs = sets.filter { !it.builtin }
    val updatingSubscriptions by ProxyController.updatingSubscriptions.collectAsStateWithLifecycle()
    val ruleSetErrors by ProxyController.ruleSetErrors.collectAsStateWithLifecycle()

    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showSubscription by rememberSaveable { mutableStateOf(false) }
    var editTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var menuTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var importTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var importResult by rememberSaveable { mutableStateOf<String?>(null) }

    // Count the built-in list once: the stored count is 0 until the
    // asset is first read, so the card shows the count from then on.
    LaunchedEffect(builtin?.count) {
        if (builtin?.count != 0) return@LaunchedEffect
        val text = withContext(Dispatchers.IO) {
            runCatching { context.readBuiltinRuleSetText() }.getOrNull()
        } ?: return@LaunchedEffect
        val count = countRuleSetLines(text)
        if (count > 0) {
            ProxyController.updateSettings {
                updateRuleSets(
                    ProxyController.settings.value.ruleSets.map {
                        if (it.id == BUILTIN_RULE_SET_ID) it.copy(count = count) else it
                    }
                )
            }
        }
    }

    fun saveSets(newSets: List<RuleSetMeta>) {
        scope.launch { ProxyController.updateSettings { updateRuleSets(newSets) } }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val targetId = importTargetId ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = readTextFile(context, uri) ?: return@launch
            // Read the set fresh, not the composed snapshot: the
            // launcher callback keeps the first composition's closure.
            // Skip the import when the set was deleted while the
            // picker was open.
            if (ProxyController.settings.value.ruleSets.none { it.id == targetId }) {
                return@launch
            }
            val existing = withContext(Dispatchers.IO) {
                context.loadRuleSetFileText(targetId)
            }.let { parseRuleSetDomains(it).first.toSet() }
            // Files are plain domains (0.7.2): the set's action is
            // metadata, never part of the file.
            val (added, skipped) = parseRuleSetDomains(text, existing)
            if (added.isNotEmpty()) {
                val merged = (existing + added).joinToString("\n")
                withContext(Dispatchers.IO) {
                    context.saveRuleSetFileText(targetId, merged)
                }
                saveSets(
                    ProxyController.settings.value.ruleSets.map {
                        if (it.id == targetId) {
                            it.copy(count = existing.size + added.size)
                        } else {
                            it
                        }
                    }
                )
            }
            importResult = context.getString(
                R.string.rules_imported, added.size, skipped
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (builtin != null) {
            item(key = "builtin") {
                RuleSetCard(
                    meta = builtin,
                    updating = false,
                    error = ruleSetErrors[builtin.id],
                    onToggle = { enabled ->
                        saveSets(
                            ProxyController.settings.value.ruleSets.map {
                                if (it.id == builtin.id) it.copy(enabled = enabled) else it
                            }
                        )
                    },
                    onOpenMenu = null,
                    menuOpen = false,
                    onDismissMenu = {},
                    onImport = {},
                    onEdit = {},
                    onDelete = {},
                    onUpdate = null,
                )
            }
        }
        item(key = "my-title") {
            Text(
                text = stringResource(R.string.rulesets_my_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item(key = "create") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showCreate = true }) {
                    Text(stringResource(R.string.rulesets_create))
                }
                OutlinedButton(onClick = { showSubscription = true }) {
                    Text(stringResource(R.string.subscriptions_add))
                }
            }
        }
        if (customs.any { it.isSubscription }) {
            item(key = "update-all") {
                OutlinedButton(
                    onClick = { ProxyController.refreshAllSubscriptions() },
                ) {
                    Text(stringResource(R.string.subscriptions_update_all))
                }
            }
        }
        if (importResult != null) {
            item(key = "import-result") {
                Text(
                    text = importResult ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (customs.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.rulesets_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(customs, key = { it.id }) { meta ->
            RuleSetCard(
                meta = meta,
                updating = meta.id in updatingSubscriptions,
                error = ruleSetErrors[meta.id],
                onToggle = { enabled ->
                    saveSets(
                        ProxyController.settings.value.ruleSets.map {
                            if (it.id == meta.id) it.copy(enabled = enabled) else it
                        }
                    )
                },
                onOpenMenu = { menuTarget = meta.id },
                menuOpen = menuTarget == meta.id,
                onDismissMenu = { menuTarget = null },
                onImport = {
                    menuTarget = null
                    importTargetId = meta.id
                    importLauncher.launch(arrayOf("text/*"))
                },
                onEdit = {
                    menuTarget = null
                    editTarget = meta.id
                },
                onUpdate = if (meta.isSubscription) {
                    {
                        menuTarget = null
                        ProxyController.refreshSubscription(meta.id)
                    }
                } else {
                    null
                },
                onDelete = {
                    menuTarget = null
                    deleteTarget = meta.id
                },
            )
        }
    }

    if (showCreate) {
        CreateRuleSetDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, action ->
                showCreate = false
                val id = UUID.randomUUID().toString()
                val meta = RuleSetMeta(
                    id = id,
                    name = name,
                    action = action,
                    enabled = true,
                    builtin = false,
                    count = 0,
                )
                scope.launch {
                    ProxyController.updateSettings {
                        updateRuleSets(
                            ProxyController.settings.value.ruleSets + meta
                        )
                    }
                    // Import straight into the new set; cancelling
                    // the picker leaves an empty set to fill later.
                    importTargetId = id
                    importLauncher.launch(arrayOf("text/*"))
                }
            },
        )
    }
    if (showSubscription) {
        AddSubscriptionDialog(
            onDismiss = { showSubscription = false },
            onCreate = { name, url, action ->
                showSubscription = false
                val id = UUID.randomUUID().toString()
                val meta = RuleSetMeta(
                    id = id,
                    name = name,
                    action = action,
                    enabled = true,
                    builtin = false,
                    count = 0,
                    url = url,
                )
                scope.launch {
                    ProxyController.updateSettings {
                        updateRuleSets(
                            ProxyController.settings.value.ruleSets + meta
                        )
                    }
                    // Fetch the list right away, so the new set is not empty.
                    ProxyController.refreshSubscription(id)
                }
            },
        )
    }
    val editMeta = editTarget?.let { id -> customs.firstOrNull { it.id == id } }
    if (editMeta != null) {
        EditRuleSetDialog(
            meta = editMeta,
            onDismiss = { editTarget = null },
            onSave = { name, action, url ->
                editTarget = null
                scope.launch {
                    // Files are plain domains (0.7.2): the action is
                    // metadata only, so a changed action needs no
                    // file rewrite.
                    val urlChanged = url != editMeta.url
                    ProxyController.updateSettings {
                        updateRuleSets(
                            ProxyController.settings.value.ruleSets.map {
                                if (it.id == editMeta.id) {
                                    it.copy(
                                        name = name,
                                        action = action,
                                        url = url,
                                        // A new source starts over: the old
                                        // list stays until the next update.
                                        lastUpdatedMs = if (urlChanged) 0L else it.lastUpdatedMs,
                                        lastError = if (urlChanged) "" else it.lastError,
                                    )
                                } else {
                                    it
                                }
                            }
                        )
                    }
                    if (urlChanged) ProxyController.refreshSubscription(editMeta.id)
                }
            },
        )
    }
    val deleteMeta = deleteTarget?.let { id -> customs.firstOrNull { it.id == id } }
    if (deleteMeta != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.rulesets_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.rulesets_delete_confirm, deleteMeta.name),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            // Clear the engine first: the rule-sets job
                            // only pushes sets still listed, so a deleted
                            // set would otherwise stay live.
                            ProxyController.clearRuleSet(deleteMeta.id, deleteMeta.action)
                            withContext(Dispatchers.IO) {
                                context.deleteRuleSetFile(deleteMeta.id)
                            }
                            ProxyController.updateSettings {
                                updateRuleSets(
                                    ProxyController.settings.value.ruleSets
                                        .filter { it.id != deleteMeta.id }
                                )
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.rules_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RuleSetCard(
    meta: RuleSetMeta,
    updating: Boolean,
    error: String?,
    onToggle: (Boolean) -> Unit,
    onOpenMenu: (() -> Unit)?,
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onImport: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (() -> Unit)?,
) {
    // The card and its menu share one Box, so the menu anchors under
    // the card instead of at the list's left edge.
    Box {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
                .let { if (onOpenMenu != null) it.clickable(onClick = onOpenMenu) else it },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = meta.name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (meta.count > 0) {
                        Text(
                            text = stringResource(R.string.rulesets_domains, meta.count),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (meta.isSubscription) {
                        Text(
                            text = meta.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (meta.lastUpdatedMs == 0L) {
                                stringResource(R.string.subscriptions_never_updated)
                            } else {
                                stringResource(
                                    R.string.subscriptions_updated,
                                    DateUtils.getRelativeTimeSpanString(
                                        meta.lastUpdatedMs,
                                        System.currentTimeMillis(),
                                        DateUtils.MINUTE_IN_MILLIS,
                                    ),
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (meta.lastError.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.subscriptions_failed, meta.lastError
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (updating) {
                        Text(
                            text = stringResource(R.string.subscriptions_updating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (error != null) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (updating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
                ActionTag(meta.action)
                Switch(checked = meta.enabled, onCheckedChange = onToggle)
            }
        }
        if (onOpenMenu != null) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                if (onUpdate != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subscriptions_update_now)) },
                        onClick = onUpdate,
                        enabled = !updating,
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rules_import)) },
                    onClick = onImport,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rulesets_edit)) },
                    onClick = onEdit,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rules_delete)) },
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun SetActionSegmentedRow(
    selected: RuleAction,
    onSelect: (RuleAction) -> Unit,
) {
    // Rule sets are Direct or Block only: Proxy is the default when
    // no rule matches, so it is not a set action.
    val options = listOf(RuleAction.DIRECT, RuleAction.BLOCK)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, action ->
            SegmentedButton(
                selected = selected == action,
                onClick = { onSelect(action) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) {
                Text(
                    stringResource(
                        when (action) {
                            RuleAction.DIRECT -> R.string.rules_action_direct
                            RuleAction.BLOCK -> R.string.rules_action_block
                            RuleAction.PROXY -> R.string.rules_action_proxy
                        }
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, RuleAction) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf(RuleAction.BLOCK) }
    val urlOk = url.startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subscriptions_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.rulesets_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.subscriptions_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (url.isNotBlank() && !urlOk) {
                    Text(
                        text = stringResource(R.string.subscriptions_url_https),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                SetActionSegmentedRow(selected = action, onSelect = { action = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), url.trim(), action) },
                enabled = name.isNotBlank() && urlOk,
            ) {
                Text(stringResource(R.string.subscriptions_add))
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
private fun CreateRuleSetDialog(
    onDismiss: () -> Unit,
    onCreate: (String, RuleAction) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf(RuleAction.BLOCK) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rulesets_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.rulesets_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SetActionSegmentedRow(selected = action, onSelect = { action = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), action) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.rulesets_create))
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
private fun EditRuleSetDialog(
    meta: RuleSetMeta,
    onDismiss: () -> Unit,
    onSave: (String, RuleAction, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(meta.name) }
    var action by rememberSaveable { mutableStateOf(meta.action) }
    var url by rememberSaveable { mutableStateOf(meta.url) }
    val urlOk = !meta.isSubscription || url.startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rulesets_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.rulesets_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (meta.isSubscription) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.subscriptions_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (url.isNotBlank() && !urlOk) {
                        Text(
                            text = stringResource(R.string.subscriptions_url_https),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                SetActionSegmentedRow(selected = action, onSelect = { action = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), action, url.trim()) },
                enabled = name.isNotBlank() && urlOk,
            ) {
                Text(stringResource(R.string.rulesets_edit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Read a whole text file behind a content URI (0.7.2: shared with RulesScreen, which used to keep its own copy). Blocking I/O: call on Dispatchers.IO. */
suspend fun readTextFile(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }
