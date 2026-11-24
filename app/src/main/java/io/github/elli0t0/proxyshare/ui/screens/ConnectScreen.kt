package io.github.elli0t0.proxyshare.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.data.NetIf
import io.github.elli0t0.proxyshare.data.NetworkWatcher
import io.github.elli0t0.proxyshare.data.enabledPorts
import io.github.elli0t0.proxyshare.data.rankInterfaces
import io.github.elli0t0.proxyshare.engine.EngineState
import io.github.elli0t0.proxyshare.service.ProxyController
import java.net.URLEncoder

/** One copyable command shown on the Connect page. */
data class Cmd(val title: String, val text: String)

/** Copyable laptop commands for [host]. Credentials are URL-encoded when given. A null port omits those commands. */
fun laptopCommands(
    host: String,
    httpPort: Int?,
    socksPort: Int?,
    user: String?,
    pass: String?,
): List<Cmd> {
    val auth = if (user != null && pass != null) {
        URLEncoder.encode(user, "UTF-8") + ":" + URLEncoder.encode(pass, "UTF-8") + "@"
    } else {
        ""
    }
    val cmds = mutableListOf<Cmd>()
    if (httpPort != null) {
        cmds += Cmd("curl (HTTP)", "curl -x http://$auth$host:$httpPort https://ifconfig.me")
        cmds += Cmd(
            "Linux / macOS terminal",
            "export http_proxy=http://$auth$host:$httpPort https_proxy=http://$auth$host:$httpPort",
        )
    }
    if (socksPort != null) {
        cmds += Cmd(
            "curl (SOCKS5)",
            "curl --proxy socks5h://$auth$host:$socksPort https://ifconfig.me",
        )
        cmds += Cmd(
            "Firefox",
            "Enable \"Proxy DNS when using SOCKS v5\" in Settings → Network Settings",
        )
        cmds += Cmd(
            "Chromium / Chrome",
            "chromium --proxy-server=\"socks5://$host:$socksPort\"",
        )
    }
    if (httpPort != null || socksPort != null) {
        val lines = mutableListOf(
            "gsettings set org.gnome.system.proxy mode 'manual'",
        )
        if (socksPort != null) {
            lines += "gsettings set org.gnome.system.proxy.socks host '$host'"
            lines += "gsettings set org.gnome.system.proxy.socks port $socksPort"
            if (user != null && pass != null) {
                lines += "gsettings set org.gnome.system.proxy.socks authentication-user '$user'"
                lines += "gsettings set org.gnome.system.proxy.socks authentication-password '$pass'"
            }
        }
        if (httpPort != null) {
            lines += "gsettings set org.gnome.system.proxy.http host '$host'"
            lines += "gsettings set org.gnome.system.proxy.http port $httpPort"
            lines += "gsettings set org.gnome.system.proxy.https host '$host'"
            lines += "gsettings set org.gnome.system.proxy.https port $httpPort"
            if (user != null && pass != null) {
                lines += "gsettings set org.gnome.system.proxy.http authentication-user '$user'"
                lines += "gsettings set org.gnome.system.proxy.http authentication-password '$pass'"
                lines += "gsettings set org.gnome.system.proxy.https authentication-user '$user'"
                lines += "gsettings set org.gnome.system.proxy.https authentication-password '$pass'"
            }
        }
        cmds += Cmd("GNOME proxy settings", lines.joinToString("\n"))
    }
    return cmds
}

/**
 * Resolve the address the page uses: the user's pick when it is still
 * in the list, else the first ranked interface. Null when there is no
 * usable address.
 */
fun resolveSheetHost(ranked: List<NetIf>, selectedIp: String?): String? =
    selectedIp?.takeIf { ip -> ranked.any { it.ip == ip } } ?: ranked.firstOrNull()?.ip

/**
 * The host the commands render with: the real address, or "PHONE_IP"
 * when the only usable address is the carrier one — the carrier
 * address is not reachable from the laptop, so it must never be
 * presented as usable.
 */
fun sheetCommandHost(ranked: List<NetIf>, selectedIp: String?): String? {
    val host = resolveSheetHost(ranked, selectedIp) ?: return null
    return if (isMobileOnly(ranked)) "PHONE_IP" else host
}

/**
 * Split laptopCommands output by Connect tab: 0 = quick-test curl
 * commands, 1 = Linux/macOS setup commands. Tab 2 (Windows) shows
 * plain-text steps instead, so it gets no commands.
 */
fun commandsForTab(cmds: List<Cmd>, tab: Int): List<Cmd> = when (tab) {
    0 -> cmds.filter { it.title.startsWith("curl ") }
    1 -> cmds.filter { !it.title.startsWith("curl ") }
    else -> emptyList()
}

/**
 * "Connect your laptop" as a full-screen page (replaces the laggy
 * ModalBottomSheet). A normal LazyColumn using the shared
 * NetworkWatcher.interfaces flow — nothing here re-emits every 15 s,
 * and no scrolling column sits inside a draggable sheet, so a drag
 * can no longer be cancelled mid-gesture.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val state by ProxyController.state.collectAsStateWithLifecycle()
    val settings by ProxyController.settings.collectAsStateWithLifecycle()
    val interfaces by NetworkWatcher.interfaces.collectAsStateWithLifecycle()
    val ranked = remember(interfaces) { rankInterfaces(interfaces) }
    var selectedIp by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val host = remember(ranked, selectedIp) { resolveSheetHost(ranked, selectedIp) }
    val cmdHost = remember(ranked, selectedIp) { sheetCommandHost(ranked, selectedIp) }
    val mobileOnly = remember(ranked) { isMobileOnly(ranked) }
    val user = settings.authUser.takeIf { settings.authEnabled }
    val pass = settings.authPass.takeIf { settings.authEnabled }
    val (httpPort, socksPort) = settings.enabledPorts()
    val commands = remember(cmdHost, httpPort, socksPort, user, pass, tab) {
        if (cmdHost == null) emptyList()
        else commandsForTab(laptopCommands(cmdHost, httpPort, socksPort, user, pass), tab)
    }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                stringResource(R.string.laptop_button),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            item(key = "status") {
                Text(
                    text = stringResource(
                        if (state is EngineState.Running) R.string.proxy_running
                        else R.string.laptop_start_first
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "address") {
                if (ranked.isEmpty()) {
                    Text(
                        text = stringResource(R.string.addresses_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (mobileOnly) {
                    // The carrier address is not reachable from the laptop:
                    // point at the hotspot instead of offering it.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.laptop_mobile_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = { openHotspotSettings(context) }) {
                            Text(stringResource(R.string.open_hotspot_settings))
                        }
                    }
                } else if (ranked.size == 1) {
                    // One address: no chips, just show it.
                    Text(
                        text = "${ranked[0].ip} (${ranked[0].name})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.laptop_pick_address),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (nif in ranked) {
                                FilterChip(
                                    selected = host == nif.ip,
                                    onClick = { selectedIp = nif.ip },
                                    label = { Text("${nif.ip} (${nif.name})") },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "endpoint") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cmdHost != null) {
                        if (httpPort != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "HTTP  $cmdHost:$httpPort",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { copyToClipboard(context, "$cmdHost:$httpPort") }) {
                                    Text(stringResource(R.string.copy))
                                }
                            }
                        }
                        if (socksPort != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "SOCKS5  $cmdHost:$socksPort",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { copyToClipboard(context, "$cmdHost:$socksPort") }) {
                                    Text(stringResource(R.string.copy))
                                }
                            }
                        }
                    }
                    // Credentials live here now, not in a card on Home.
                    if (settings.authEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${stringResource(R.string.user_label)}: ${settings.authUser}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { copyToClipboard(context, settings.authUser) }) {
                                Text(stringResource(R.string.copy))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${stringResource(R.string.password_label)}: " +
                                    if (passwordVisible) settings.authPass.ifEmpty { "—" } else "••••••",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(
                                    stringResource(
                                        if (passwordVisible) R.string.connect_hide
                                        else R.string.connect_show
                                    )
                                )
                            }
                            TextButton(onClick = { copyToClipboard(context, settings.authPass) }) {
                                Text(stringResource(R.string.copy))
                            }
                        }
                    }
                }
            }
            item(key = "os") {
                val tabLabels = listOf(
                    R.string.connect_tab_quick,
                    R.string.connect_tab_linux,
                    R.string.laptop_windows_title,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    tabLabels.forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = tab == i,
                            onClick = { tab = i },
                            shape = SegmentedButtonDefaults.itemShape(i, tabLabels.size),
                        ) { Text(stringResource(label), maxLines = 1) }
                    }
                }
            }
            if (tab == 2) {
                item(key = "windows") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.laptop_windows_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.laptop_windows_steps),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(commands, key = { it.title }) { cmd ->
                    CommandCard(cmd)
                }
            }
        }
    }
}

@Composable
private fun CommandCard(cmd: Cmd) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = cmd.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { copyToClipboard(context, cmd.text) }) {
                    Text(stringResource(R.string.copy))
                }
            }
            Text(
                text = cmd.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
}
