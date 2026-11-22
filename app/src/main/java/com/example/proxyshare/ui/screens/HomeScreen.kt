package com.example.proxyshare.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.proxyshare.R
import com.example.proxyshare.data.NetworkWatcher
import com.example.proxyshare.data.enabledPorts
import com.example.proxyshare.data.IfKind
import com.example.proxyshare.data.NetIf
import com.example.proxyshare.data.rankInterfaces
import com.example.proxyshare.engine.EngineState
import com.example.proxyshare.service.OutboundMode
import com.example.proxyshare.service.ProxyController
import com.example.proxyshare.service.ProxyService
import com.example.proxyshare.service.toOutboundMode
import com.example.proxyshare.ui.components.CopyChip
import com.example.proxyshare.ui.components.InfoChip
import com.example.proxyshare.ui.components.PowerButton
import com.example.proxyshare.ui.components.SectionCard
import com.example.proxyshare.ui.components.StatTile
import com.example.proxyshare.ui.components.toPowerState
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ProxyController.state.collectAsStateWithLifecycle()
    val snapshot by ProxyController.snapshot.collectAsStateWithLifecycle()
    val settings by ProxyController.settings.collectAsStateWithLifecycle()
    val interfaces by NetworkWatcher.interfaces.collectAsStateWithLifecycle()
    var exitIpText by rememberSaveable { mutableStateOf<String?>(null) }
    var exitIpOk by rememberSaveable { mutableStateOf(false) }
    var exitIpChecking by rememberSaveable { mutableStateOf(false) }
    var showLaptopSheet by rememberSaveable { mutableStateOf(false) }

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

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
            )

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

            // Stat tiles, while running.
            AnimatedVisibility(visible = running) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                ) {
                    StatTile(
                        label = stringResource(R.string.stat_clients),
                        value = snapshot.active.toString(),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    StatTile(
                        label = stringResource(R.string.stat_upload),
                        value = formatBytes(snapshot.liveUp),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    StatTile(
                        label = stringResource(R.string.stat_download),
                        value = formatBytes(snapshot.liveDown),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }

            // Warning banner: open to the network with no auth.
            if (!settings.authEnabled && !settings.privateOnly) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.warning_open),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // Address card: first ranked interface is primary, with one
            // row per enabled protocol; the rest expand under "Other addresses".
            AddressCard(
                ranked = ranked,
                vpnInterfaces = interfaces.filter { it.kind == IfKind.VPN },
                httpPort = httpPort,
                socksPort = socksPort,
                socks5On = "socks5" in settings.protocols,
            )

            // Credentials card, only when auth is on; otherwise a compact chip.
            if (settings.authEnabled) {
                CredentialsCard(user = settings.authUser, password = settings.authPass)
            } else if (settings.privateOnly) {
                InfoChip(text = stringResource(R.string.no_password_private))
            }

            // Protocol chips: read-only, so only the enabled ones show,
            // each with a leading check.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if ("http" in settings.protocols) ProtocolChip("HTTP")
                if ("socks4" in settings.protocols) ProtocolChip("SOCKS4")
                if ("socks5" in settings.protocols) ProtocolChip("SOCKS5")
            }

            // Outbound mode + exit-IP check.
            OutboundCard(
                mode = settings.outboundMode.toOutboundMode(),
                enabled = state is EngineState.Stopped,
                onModeSelected = { mode ->
                    scope.launch { ProxyController.setOutboundMode(mode) }
                },
                checking = exitIpChecking,
                exitIpText = exitIpText,
                exitIpOk = exitIpOk,
                onCheckExitIp = {
                    exitIpChecking = true
                    scope.launch {
                        val result = ProxyController.checkExitIp()
                        exitIpOk = result.isSuccess
                        exitIpText = result.getOrNull()
                            ?: result.exceptionOrNull()?.message
                            ?: context.getString(R.string.check_exit_ip_failed)
                        exitIpChecking = false
                    }
                },
            )

            // Connect your laptop sheet.
            FilledTonalButton(
                onClick = { showLaptopSheet = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.laptop_button))
            }

            if (showLaptopSheet) {
                LaptopSheet(onDismiss = { showLaptopSheet = false })
            }
        }
    }
}

private fun startProxy(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, ProxyService::class.java),
    )
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText("ProxyShare", text))
}

@Composable
private fun AddressCard(
    ranked: List<NetIf>,
    vpnInterfaces: List<NetIf>,
    httpPort: Int?,
    socksPort: Int?,
    socks5On: Boolean,
) {
    val context = LocalContext.current
    var showOthers by rememberSaveable { mutableStateOf(false) }
    val primary = ranked.firstOrNull()
    val others = ranked.drop(1)
    SectionCard(title = stringResource(R.string.addresses_title)) {
        if (isMobileOnly(ranked)) {
            // Carrier address only: no rows or copy chips, just the
            // hint and a button to open the hotspot settings.
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
        } else {
            if (primary == null && vpnInterfaces.isEmpty()) {
                Text(
                    text = stringResource(R.string.addresses_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (primary != null) {
                // Kind + raw name on one line, the name small and secondary.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = kindLabel(primary.kind),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = " · ${primary.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (httpPort != null) {
                    ProtocolAddressRow(
                        label = "HTTP",
                        address = "${primary.ip}:$httpPort",
                    )
                }
                if (socksPort != null) {
                    ProtocolAddressRow(
                        label = if (socks5On) "SOCKS5" else "SOCKS4",
                        address = "${primary.ip}:$socksPort",
                    )
                }
                if (primary.kind == IfKind.MOBILE) {
                    Text(
                        text = stringResource(R.string.mobile_unreachable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Toggle + VPN rows sit 8dp apart: the old TextButton's
            // 40dp min height plus the card's 12dp gaps read as ~40dp.
            if (others.isNotEmpty() || vpnInterfaces.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (others.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.other_addresses),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button) {
                                    showOthers = !showOthers
                                }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                        AnimatedVisibility(visible = showOthers) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (nif in others) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(
                                                text = kindLabel(nif.kind),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Text(
                                                text = " · ${nif.name}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        CopyChip(text = nif.ip)
                                    }
                                    if (nif.kind == IfKind.MOBILE) {
                                        Text(
                                            text = stringResource(R.string.mobile_unreachable),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    for (nif in vpnInterfaces) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${kindLabel(nif.kind)}: ${nif.ip}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = " · ${nif.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (vpnInterfaces.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.outbound_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

@Composable
private fun ProtocolAddressRow(label: String, address: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        CopyChip(text = address)
    }
}

@Composable
private fun kindLabel(kind: IfKind): String = stringResource(
    when (kind) {
        IfKind.WIFI -> R.string.if_wifi
        IfKind.HOTSPOT -> R.string.if_hotspot
        IfKind.ETHERNET -> R.string.if_ethernet
        IfKind.USB -> R.string.if_usb
        IfKind.MOBILE -> R.string.if_mobile
        IfKind.VPN -> R.string.if_vpn
        IfKind.OTHER -> R.string.if_other
    }
)

@Composable
private fun CredentialsCard(user: String, password: String) {
    val context = LocalContext.current
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.credentials_title)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${stringResource(R.string.user_label)}: $user",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { copyText(context, user) }) {
                    Text(stringResource(R.string.copy))
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(
                        text = "${stringResource(R.string.password_label)}: " +
                            if (passwordVisible) password.ifEmpty { "—" } else "••••••",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(onClick = { copyText(context, password) }) {
                    Text(stringResource(R.string.copy))
                }
            }
            Text(
                text = stringResource(R.string.password_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
private fun OutboundCard(
    mode: OutboundMode,
    enabled: Boolean,
    onModeSelected: (OutboundMode) -> Unit,
    checking: Boolean,
    exitIpText: String?,
    exitIpOk: Boolean,
    onCheckExitIp: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.outbound_title)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                OutboundMode.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = mode == item,
                        onClick = { onModeSelected(item) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = OutboundMode.entries.size,
                        ),
                        enabled = enabled,
                        // Explicit colors: the defaults read inverted
                        // here — the selected segment came out dim grey
                        // while the unselected ones showed a bright
                        // outline. (Theme.kt defines no
                        // secondaryContainer/outlineVariant of its own,
                        // so the defaults fall back to the baseline
                        // scheme; on dynamic color the wallpaper-derived
                        // secondaryContainer is dim against a near-black
                        // surface.) Selected keeps its container and
                        // blends its border into it; unselected uses the
                        // dimmer outlineVariant/onSurfaceVariant.
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            activeBorderColor = MaterialTheme.colorScheme.secondaryContainer,
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Text(text = stringResource(modeLabel(item)))
                    }
                }
            }
            Button(
                onClick = onCheckExitIp,
                enabled = !checking,
            ) {
                Text(text = stringResource(R.string.check_exit_ip))
            }
            if (checking || exitIpText != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (checking) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else if (exitIpOk) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    CircleShape,
                                ),
                        )
                        Text(
                            text = if (checking) {
                                stringResource(R.string.check_exit_ip_checking)
                            } else {
                                exitIpText.orEmpty()
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
    }
}

@Composable
private fun modeLabel(mode: OutboundMode): Int = when (mode) {
    OutboundMode.DEFAULT -> R.string.mode_default
    OutboundMode.VPN_ONLY -> R.string.mode_vpn_only
    OutboundMode.WIFI -> R.string.mode_wifi
    OutboundMode.MOBILE -> R.string.mode_mobile
}

@Composable
private fun ProtocolChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
