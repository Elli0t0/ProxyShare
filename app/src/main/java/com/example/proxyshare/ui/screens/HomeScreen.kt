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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.proxyshare.engine.NativeEngine
import com.example.proxyshare.service.OutboundMode
import com.example.proxyshare.service.ProxyController
import com.example.proxyshare.service.ProxyService
import com.example.proxyshare.service.toOutboundMode
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
    val engineVersion = remember {
        runCatching { NativeEngine.nativeVersion() }.getOrNull()
    }
    var exitIpText by rememberSaveable { mutableStateOf<String?>(null) }
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
    val buttonColor by animateColorAsState(
        targetValue = if (running) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary,
        label = "startStopColor",
    )

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Status card.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = when (val s = state) {
                            is EngineState.Running -> stringResource(R.string.proxy_running)
                            is EngineState.Starting -> stringResource(R.string.proxy_starting)
                            is EngineState.Error -> s.message.ifBlank {
                                stringResource(R.string.proxy_error)
                            }
                            else -> stringResource(R.string.proxy_stopped)
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (running) {
                        Text(
                            text = stringResource(
                                R.string.totals,
                                formatBytes(snapshot.totalUp),
                                formatBytes(snapshot.totalDown),
                                snapshot.active,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            // Large animated Start/Stop button.
            Button(
                onClick = { if (running) ProxyController.stop() else onStartClicked() },
                enabled = state !is EngineState.Starting,
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                AnimatedContent(
                    targetState = running,
                    label = "startStopLabel",
                ) { isRunning ->
                    Text(
                        text = stringResource(
                            if (isRunning) R.string.proxy_stop else R.string.proxy_start
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Connect your laptop sheet.
            OutlinedButton(
                onClick = { showLaptopSheet = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.laptop_button))
            }

            if (showLaptopSheet) {
                LaptopSheet(onDismiss = { showLaptopSheet = false })
            }

            // Warning banner: open to the network with no auth.
            if (!settings.authEnabled && !settings.privateOnly) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
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
                ranked = rankInterfaces(interfaces),
                vpnInterfaces = interfaces.filter { it.kind == IfKind.VPN },
                settings = settings,
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
                onCheckExitIp = {
                    exitIpChecking = true
                    scope.launch {
                        val result = ProxyController.checkExitIp()
                        exitIpText = result.getOrNull()
                            ?: result.exceptionOrNull()?.message
                            ?: context.getString(R.string.check_exit_ip_failed)
                        exitIpChecking = false
                    }
                },
            )
            if (exitIpChecking || exitIpText != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (exitIpChecking) {
                            stringResource(R.string.check_exit_ip_checking)
                        } else {
                            exitIpText.orEmpty()
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Text(
                text = engineVersion ?: stringResource(R.string.engine_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText("ProxyShare", text))
}

@Composable
private fun AddressCard(
    ranked: List<NetIf>,
    vpnInterfaces: List<NetIf>,
    settings: AppSettings,
) {
    val context = LocalContext.current
    var showOthers by rememberSaveable { mutableStateOf(false) }
    val primary = ranked.firstOrNull()
    val others = ranked.drop(1)
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
    Card(modifier = Modifier.fillMaxWidth()) {
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
                        label = if ("socks5" in settings.protocols) "SOCKS5" else "SOCKS4",
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
                if (showOthers) {
                    for (nif in others) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "${kindLabel(nif.kind)} (${nif.name}): ${nif.ip}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
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
    Card(modifier = Modifier.fillMaxWidth()) {
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
    onCheckExitIp: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
