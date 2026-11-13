package com.example.proxyshare.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.proxyshare.R
import com.example.proxyshare.data.AppSettings
import com.example.proxyshare.data.IfKind
import com.example.proxyshare.data.NetIf
import com.example.proxyshare.data.networkInterfaces
import com.example.proxyshare.data.rankInterfaces
import com.example.proxyshare.engine.EngineState
import com.example.proxyshare.service.OutboundMode
import com.example.proxyshare.service.ProxyController
import com.example.proxyshare.service.ProxyService
import com.example.proxyshare.service.toOutboundMode
import com.example.proxyshare.ui.components.PowerButton
import com.example.proxyshare.ui.components.toPowerState
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ProxyController.state.collectAsState()
    val snapshot by ProxyController.snapshot.collectAsState()
    val settings by ProxyController.settings.collectAsState()
    val netFlow = remember(context) { networkInterfaces(context) }
    val interfaces by netFlow.collectAsState(initial = emptyList())
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
    val primary = ranked.firstOrNull()
    val (httpPort, socksPort) = enabledPorts(settings)
    val heroAddress = primary?.let { nif ->
        val port = httpPort ?: socksPort
        if (port != null) "${nif.ip}:$port" else nif.ip
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
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
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatTile(
                        labelRes = R.string.stat_clients,
                        value = snapshot.active.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        labelRes = R.string.stat_upload,
                        value = formatBytes(snapshot.totalUp),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        labelRes = R.string.stat_download,
                        value = formatBytes(snapshot.totalDown),
                        modifier = Modifier.weight(1f),
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
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.no_password_private),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            // Protocol chips reflecting the settings.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProtocolChip("HTTP", "http" in settings.protocols)
                ProtocolChip("SOCKS4", "socks4" in settings.protocols)
                ProtocolChip("SOCKS5", "socks5" in settings.protocols)
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

/** The port each protocol listens on, or null when it is disabled. */
private fun enabledPorts(settings: AppSettings): Pair<Int?, Int?> {
    val httpPort = if (settings.separatePorts && "http" in settings.protocols) {
        settings.httpPort
    } else if (!settings.separatePorts) {
        settings.port
    } else {
        null
    }
    val socksPort = if (settings.separatePorts &&
        ("socks4" in settings.protocols || "socks5" in settings.protocols)
    ) {
        settings.socksPort
    } else if (!settings.separatePorts) {
        settings.port
    } else {
        null
    }
    return httpPort to socksPort
}

@Composable
private fun StatTile(labelRes: Int, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedContent(targetState = value, label = "statValue") { animatedValue ->
                Text(
                    text = animatedValue,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
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
            Text(
                text = stringResource(R.string.addresses_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (primary == null && vpnInterfaces.isEmpty()) {
                Text(
                    text = stringResource(R.string.addresses_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (primary != null) {
                Text(
                    text = "${stringResource(R.string.primary_address)} — " +
                        "${kindLabel(primary.kind)} (${primary.name})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (httpPort != null) {
                    ProtocolAddressRow(
                        label = "HTTP",
                        address = "${primary.ip}:$httpPort",
                        onCopy = { copyText(context, "${primary.ip}:$httpPort") },
                    )
                }
                if (socksPort != null) {
                    ProtocolAddressRow(
                        label = if (socks5On) "SOCKS5" else "SOCKS4",
                        address = "${primary.ip}:$socksPort",
                        onCopy = { copyText(context, "${primary.ip}:$socksPort") },
                    )
                }
                if (primary.kind == IfKind.MOBILE) {
                    Text(
                        text = stringResource(R.string.mobile_unreachable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ranked.size == 1 && primary.kind == IfKind.MOBILE) {
                    Text(
                        text = stringResource(R.string.mobile_only_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (others.isNotEmpty()) {
                TextButton(onClick = { showOthers = !showOthers }) {
                    Text(stringResource(R.string.other_addresses))
                }
                AnimatedVisibility(visible = showOthers) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (nif in others) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${kindLabel(nif.kind)} (${nif.name})",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = nif.ip,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                TextButton(onClick = { copyText(context, nif.ip) }) {
                                    Text(stringResource(R.string.copy))
                                }
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
                Text(
                    text = "${kindLabel(nif.kind)} (${nif.name}): ${nif.ip}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@Composable
private fun ProtocolAddressRow(label: String, address: String, onCopy: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "$label  $address",
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCopy) {
            Text(stringResource(R.string.copy))
        }
    }
}

@Composable
private fun kindLabel(kind: IfKind): String = stringResource(
    when (kind) {
        IfKind.WIFI -> R.string.if_wifi
        IfKind.HOTSPOT -> R.string.if_hotspot
        IfKind.ETHERNET -> R.string.if_ethernet
        IfKind.MOBILE -> R.string.if_mobile
        IfKind.VPN -> R.string.if_vpn
        IfKind.OTHER -> R.string.if_other
    }
)

@Composable
private fun CredentialsCard(user: String, password: String) {
    val context = LocalContext.current
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
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
            Text(
                text = stringResource(R.string.credentials_title),
                style = MaterialTheme.typography.titleMedium,
            )
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
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.outbound_title),
                style = MaterialTheme.typography.titleMedium,
            )
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
}

@Composable
private fun modeLabel(mode: OutboundMode): Int = when (mode) {
    OutboundMode.DEFAULT -> R.string.mode_default
    OutboundMode.VPN_ONLY -> R.string.mode_vpn_only
    OutboundMode.WIFI -> R.string.mode_wifi
    OutboundMode.MOBILE -> R.string.mode_mobile
}

@Composable
private fun ProtocolChip(label: String, enabled: Boolean) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
