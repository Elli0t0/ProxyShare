package io.github.elli0t0.proxyshare.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.service.ProxyController

/**
 * Activity tab: the old Connections (Live) and Logs screens merged
 * into one tab, switched with a segmented row: Live | History |
 * Engine | Usage. The wrapper adds statusBarsPadding once; the old
 * screens get topInset = false. The ⋮ menu beside the segments
 * shares or clears whichever log segment is shown (the old Logs
 * menu, moved here); Live keeps its own menu inside
 * ConnectionsScreen, Usage has its own Reset button. A segment's
 * 1 s refresh loop only runs while that segment is composed, so
 * switching away stops it.
 */
@Composable
fun ActivityScreen(modifier: Modifier = Modifier, onGoHome: () -> Unit) {
    var seg by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf(
        R.string.act_live,
        R.string.act_history,
        R.string.act_engine,
        R.string.act_usage,
    )
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val sessionLog by ProxyController.sessionLog.collectAsStateWithLifecycle()
    val diagText by ProxyController.diagLogText.collectAsStateWithLifecycle()
    val canShare = when (seg) {
        1 -> sessionLog.isNotEmpty()
        2 -> diagText.isNotBlank()
        else -> false
    }

    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                labels.forEachIndexed { i, l ->
                    SegmentedButton(
                        selected = seg == i,
                        onClick = { seg = i },
                        shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                    ) { Text(stringResource(l), maxLines = 1) }
                }
            }
            // The menu and its button share one Box, so the menu
            // anchors under the ⋮ button instead of at the row's
            // left edge (a DropdownMenu anchors to its parent).
            if (seg == 1 || seg == 2) {
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
                            text = { Text(stringResource(R.string.diag_share)) },
                            onClick = {
                                showMenu = false
                                if (seg == 1) {
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
                                if (seg == 1) {
                                    ProxyController.clearSessionLog()
                                } else {
                                    ProxyController.clearDiagLog()
                                }
                            },
                            enabled = canShare,
                        )
                    }
                }
            }
        }
        // Live brings its own 16dp padding; the log contents relied
        // on the old LogsScreen column for theirs, so it is added here.
        val logModifier = Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        when (seg) {
            0 -> ConnectionsScreen(
                modifier = Modifier.weight(1f),
                topInset = false,
                onGoHome = onGoHome,
                onGoLogs = { seg = 1 },
            )
            1 -> SessionLogContent(modifier = logModifier)
            2 -> EngineLogContent(modifier = logModifier)
            else -> UsageContent(modifier = logModifier)
        }
    }
}
