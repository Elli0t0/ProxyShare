package io.github.elli0t0.proxyshare.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.data.NetworkWatcher
import io.github.elli0t0.proxyshare.data.VpnWatcher
import io.github.elli0t0.proxyshare.data.enabledPorts
import io.github.elli0t0.proxyshare.data.rankInterfaces
import io.github.elli0t0.proxyshare.engine.EngineState
import io.github.elli0t0.proxyshare.service.ExitIpFailure
import io.github.elli0t0.proxyshare.service.ExitIpResult
import io.github.elli0t0.proxyshare.service.OutboundMode
import io.github.elli0t0.proxyshare.service.ProxyController
import io.github.elli0t0.proxyshare.service.ProxyService
import io.github.elli0t0.proxyshare.service.toOutboundMode
import io.github.elli0t0.proxyshare.ui.components.LocalTextField
import io.github.elli0t0.proxyshare.ui.components.PowerButton
import io.github.elli0t0.proxyshare.ui.components.copyToClipboard
import io.github.elli0t0.proxyshare.ui.components.SectionCard
import io.github.elli0t0.proxyshare.ui.components.SettingRow
import io.github.elli0t0.proxyshare.ui.components.toPowerState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenConnect: () -> Unit,
    onOpenSecuritySettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ProxyController.state.collectAsStateWithLifecycle()
    val settings by ProxyController.settings.collectAsStateWithLifecycle()
    // Only the two snapshot fields this screen shows: collecting the
    // whole snapshot would recompose Home every second.
    val blockedBySet by remember {
        ProxyController.snapshot.map { it.blockedBySet }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = 0L)
    val pacOk by remember {
        ProxyController.snapshot.map { it.pacOk }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = true)
    val interfaces by NetworkWatcher.interfaces.collectAsStateWithLifecycle()
    val vpnActive by VpnWatcher.active.collectAsStateWithLifecycle()
    var exitIpText by rememberSaveable { mutableStateOf<String?>(null) }
    var exitIpOk by rememberSaveable { mutableStateOf(false) }
    var exitIpChecking by rememberSaveable { mutableStateOf(false) }

    // Keep the screen on while the proxy runs, when the setting is on.
    val view = LocalView.current
    SideEffect {
        view.keepScreenOn = settings.keepScreenOn && state is EngineState.Running
    }

    var showNotifExplain by rememberSaveable { mutableStateOf(false) }
    var notifAsked by rememberSaveable { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // The proxy must still start when the permission is denied.
        startProxy(context)
    }
    fun onStartClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED &&
            !notifAsked
        ) {
            notifAsked = true
            showNotifExplain = true
        } else {
            startProxy(context)
        }
    }

    if (showNotifExplain) {
        AlertDialog(
            onDismissRequest = { showNotifExplain = false },
            title = { Text(stringResource(R.string.notif_explain_title)) },
            text = { Text(stringResource(R.string.notif_explain_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showNotifExplain = false
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) {
                    Text(stringResource(R.string.notif_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotifExplain = false
                    startProxy(context)
                }) {
                    Text(stringResource(R.string.notif_skip))
                }
            },
        )
    }

    val running = state is EngineState.Running
    val ranked = rankInterfaces(interfaces)
    val (httpPort, socksPort) = settings.enabledPorts()
    val heroAddress = advertiseAddress(ranked, httpPort, socksPort)
    val mobileOnly = isMobileOnly(ranked)
    val primary = remember(ranked, httpPort, socksPort) {
        advertiseAddress(ranked, httpPort, socksPort)
    }
    val others = remember(ranked, httpPort, socksPort) {
        otherAddresses(ranked, httpPort, socksPort)
    }

    // Health: the two Android facts only change outside the app
    // (the user leaves to change a setting), so re-read them on
    // resume, not every second. Engine, settings, VPN and address
    // changes recompute the list reactively through the keys.
    var notifOk by remember { mutableStateOf(notificationsAllowed(context)) }
    var batteryOk by remember { mutableStateOf(batteryUnrestricted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notifOk = notificationsAllowed(context)
        batteryOk = batteryUnrestricted(context)
    }
    var healthExpanded by rememberSaveable { mutableStateOf(false) }
    val health = remember(notifOk, batteryOk, vpnActive, ranked, state, settings, pacOk) {
        computeHealth(
            HealthInputs(
                notificationsOk = notifOk,
                batteryUnrestricted = batteryOk,
                vpnActive = vpnActive,
                hasShareableAddress = hasShareableAddress(ranked),
                engineState = state,
                authEnabled = settings.authEnabled,
                authPass = settings.authPass,
                pacEnabled = settings.pacEnabled,
                pacOk = pacOk,
            ),
        )
    }
    val healthIssues = health.count { it.status != HealthStatus.OK }
    val healthWorst = when {
        health.any { it.status == HealthStatus.FAIL } -> HealthStatus.FAIL
        health.any { it.status == HealthStatus.WARN } -> HealthStatus.WARN
        else -> HealthStatus.OK
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // VPN banner first (only when needed): in Default or
            // VPN-only mode the laptop's traffic silently bypasses a
            // dropped VPN.
            val outboundMode = settings.outboundMode.toOutboundMode()
            if (running && !vpnActive &&
                (outboundMode == OutboundMode.DEFAULT || outboundMode == OutboundMode.VPN_ONLY)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(
                            if (outboundMode == OutboundMode.VPN_ONLY) {
                                R.string.vpn_down_paused
                            } else {
                                R.string.vpn_down_bypass
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // Hero card: power button, state headline, primary address.
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (state is EngineState.Error) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (state is EngineState.Error) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().animateContentSize(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                ) {
                    PowerButton(
                        state = state.toPowerState(),
                        onClick = {
                            when {
                                running -> ProxyController.stop()
                                state is EngineState.Starting -> {}
                                else -> onStartClicked()
                            }
                        },
                        contentDescription = stringResource(
                            if (running) R.string.proxy_stop else R.string.proxy_start,
                        ),
                    )
                    Text(
                        text = when (val s = state) {
                            is EngineState.Running -> stringResource(R.string.proxy_running)
                            is EngineState.Starting -> stringResource(R.string.proxy_starting)
                            is EngineState.Error -> s.message.ifBlank {
                                stringResource(R.string.proxy_error)
                            }
                            else -> stringResource(R.string.proxy_stopped)
                        },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    if (state is EngineState.Error) {
                        TextButton(onClick = { onStartClicked() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                    if (heroAddress != null) {
                        Text(
                            text = heroAddress,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Health: one row summarising the checks; tapping
            // expands the list in place.
            SectionCard(title = stringResource(R.string.health_title)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .clickable { healthExpanded = !healthExpanded },
                ) {
                    Icon(
                        imageVector = if (healthWorst == HealthStatus.OK) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Filled.Warning
                        },
                        contentDescription = null,
                        tint = healthTint(healthWorst),
                    )
                    Text(
                        text = if (healthIssues == 0) {
                            stringResource(R.string.health_ok)
                        } else {
                            pluralStringResource(
                                R.plurals.health_issues, healthIssues, healthIssues,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                    Icon(
                        imageVector = if (healthExpanded) {
                            Icons.Filled.KeyboardArrowUp
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = healthExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        health.forEach { item ->
                            HealthItemRow(
                                item = item,
                                onFix = {
                                    fixHealthItem(context, item.fix, onOpenSecuritySettings)
                                },
                            )
                        }
                    }
                }
            }

            // Address card: the primary address, with the rest under
            // "More addresses".
            AddressCard(
                primary = primary,
                others = others,
                mobileOnly = mobileOnly,
                onConnect = onOpenConnect,
                onCopy = { copyToClipboard(context, it) },
            )

            // Rule-set blocks this session (0.6.8): only when > 0.
            if (blockedBySet > 0) {
                Text(
                    text = stringResource(R.string.rulesets_blocked, blockedBySet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Outbound mode + exit-IP check.
            OutboundCard(
                mode = outboundMode,
                enabled = state is EngineState.Stopped,
                onModeSelected = { mode ->
                    scope.launch { ProxyController.setOutboundMode(mode) }
                },
                requireVpn = settings.requireVpn,
                onRequireVpnChange = { value ->
                    scope.launch { ProxyController.updateSettings { updateRequireVpn(value) } }
                },
                checking = exitIpChecking,
                exitIpText = exitIpText,
                exitIpOk = exitIpOk,
                exitIpUrl = settings.exitIpUrl,
                onExitIpUrlChange = { url ->
                    scope.launch { ProxyController.updateSettings { updateExitIpUrl(url) } }
                },
                onCheckExitIp = {
                    exitIpChecking = true
                    scope.launch {
                        when (val result = ProxyController.checkExitIp()) {
                            is ExitIpResult.Success -> {
                                exitIpOk = true
                                exitIpText = "${result.ip} · " +
                                    context.getString(modeLabel(outboundMode))
                            }
                            is ExitIpResult.Failure -> {
                                exitIpOk = false
                                exitIpText = context.getString(
                                    when (result.failure) {
                                        ExitIpFailure.DNS_BLOCKED -> R.string.exit_ip_dns_blocked
                                        ExitIpFailure.TIMEOUT -> R.string.exit_ip_timeout
                                        ExitIpFailure.UNREACHABLE -> R.string.exit_ip_unreachable
                                    },
                                )
                            }
                        }
                        exitIpChecking = false
                    }
                },
            )

        }
    }
}

private fun startProxy(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, ProxyService::class.java),
    )
}

@Composable
private fun AddressCard(
    primary: String?,
    others: List<String>,
    mobileOnly: Boolean,
    onConnect: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.addresses_title)) {
        if (mobileOnly) {
            // Carrier address only: no address rows, just the hint
            // and a button to open the hotspot settings.
            Text(
                text = stringResource(R.string.mobile_only_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                onClick = { openHotspotSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.open_hotspot_settings))
            }
        } else if (primary == null) {
            Text(
                text = stringResource(R.string.addresses_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = primary,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onCopy(primary) }) {
                    Text(stringResource(R.string.copy))
                }
            }
        }
        FilledTonalButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connect_device))
        }
        if (others.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(R.string.more_addresses, others.size))
            }
            AnimatedVisibility(expanded) {
                Column {
                    others.forEach {
                        Text(it, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/** Open the hotspot (tethering) settings, falling back to wider settings pages. */
fun openHotspotSettings(context: Context) {
    val intents = listOf(
        Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (i in intents) {
        runCatching {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutboundCard(
    mode: OutboundMode,
    enabled: Boolean,
    onModeSelected: (OutboundMode) -> Unit,
    requireVpn: Boolean,
    onRequireVpnChange: (Boolean) -> Unit,
    checking: Boolean,
    exitIpText: String?,
    exitIpOk: Boolean,
    exitIpUrl: String,
    onExitIpUrlChange: (String) -> Unit,
    onCheckExitIp: () -> Unit,
) {
    var showMore by rememberSaveable { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.outbound_title)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutboundMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { onModeSelected(m) },
                    label = { Text(stringResource(modeLabel(m))) },
                    enabled = enabled,
                )
            }
        }
        TextButton(onClick = onCheckExitIp, enabled = !checking) {
            Text(stringResource(R.string.check_exit_ip))
        }
        if (checking || exitIpText != null) {
            Text(
                text = if (checking) {
                    stringResource(R.string.check_exit_ip_checking)
                } else {
                    exitIpText.orEmpty()
                },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                color = if (!checking && !exitIpOk) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        TextButton(onClick = { showMore = !showMore }) {
            Text(stringResource(R.string.more_options))
        }
        AnimatedVisibility(visible = showMore) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Default mode only: in VPN-only mode the kill switch
                // already stops the proxy when the VPN drops.
                if (mode == OutboundMode.DEFAULT) {
                    SettingRow(
                        title = stringResource(R.string.require_vpn_label),
                        subtitle = stringResource(R.string.require_vpn_subtitle),
                        trailing = {
                            Switch(
                                checked = requireVpn,
                                onCheckedChange = onRequireVpnChange,
                                enabled = enabled,
                            )
                        },
                    )
                }
                // Check URL (optional): empty means the built-in list.
                // Local state, so typing never jumps the cursor when
                // the DataStore emission lands mid-keystroke (0.7.2).
                LocalTextField(
                    value = exitIpUrl,
                    onValueChange = onExitIpUrlChange,
                    label = stringResource(R.string.check_url_label),
                    supportingText = { Text(stringResource(R.string.exit_ip_url_hint)) },
                )
            }
        }
    }
}

/** Map an outbound mode to its label string resource. Plain function (no composable APIs) so the exit-IP check can call it from a coroutine. */
private fun modeLabel(mode: OutboundMode): Int = when (mode) {
    OutboundMode.DEFAULT -> R.string.mode_default
    OutboundMode.VPN_ONLY -> R.string.mode_vpn_only
    OutboundMode.WIFI -> R.string.mode_wifi
    OutboundMode.MOBILE -> R.string.mode_mobile
}

/** Status icon tint: primary for OK, amber for WARN, error for FAIL. */
@Composable
private fun healthTint(status: HealthStatus): Color = when (status) {
    HealthStatus.OK -> MaterialTheme.colorScheme.primary
    HealthStatus.WARN -> Color(0xFFFFB300)
    HealthStatus.FAIL -> MaterialTheme.colorScheme.error
}

/** One health check row: status icon, label + detail, Fix button when it has one. */
@Composable
private fun HealthItemRow(item: HealthItem, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (item.status == HealthStatus.OK) {
                Icons.Filled.CheckCircle
            } else {
                Icons.Filled.Warning
            },
            contentDescription = null,
            tint = healthTint(item.status),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = stringResource(item.label),
                style = MaterialTheme.typography.bodyMedium,
            )
            val detailText = item.message
                ?: item.detail?.let { stringResource(it) }
            if (detailText != null) {
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (item.fix != HealthFix.NONE) {
            TextButton(onClick = onFix) {
                Text(stringResource(R.string.health_fix))
            }
        }
    }
}

/** Open the settings page that fixes one health item. */
private fun fixHealthItem(
    context: Context,
    fix: HealthFix,
    onOpenSecuritySettings: () -> Unit,
) {
    when (fix) {
        HealthFix.NOTIFICATIONS -> {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
        HealthFix.BATTERY -> {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
        HealthFix.HOTSPOT -> openHotspotSettings(context)
        HealthFix.SETTINGS_SECURITY -> onOpenSecuritySettings()
        HealthFix.NONE -> {}
    }
}

