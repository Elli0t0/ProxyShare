package com.example.proxyshare.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.proxyshare.R
import com.example.proxyshare.data.IfKind
import com.example.proxyshare.data.networkInterfaces
import com.example.proxyshare.engine.EngineState
import com.example.proxyshare.service.ProxyController
import java.net.URLEncoder

/** One copyable command shown in the laptop sheet. */
data class Cmd(val title: String, val text: String)

/** Copyable laptop commands for [host]:[port]. Credentials are URL-encoded when given. */
fun laptopCommands(host: String, port: Int, user: String?, pass: String?): List<Cmd> {
    val auth = if (user != null && pass != null) {
        URLEncoder.encode(user, "UTF-8") + ":" + URLEncoder.encode(pass, "UTF-8") + "@"
    } else {
        ""
    }
    return listOf(
        Cmd("curl (HTTP)", "curl -x http://$auth$host:$port https://ifconfig.me"),
        Cmd("curl (SOCKS5)", "curl --socks5-hostname $auth$host:$port https://ifconfig.me"),
        Cmd("Linux / macOS terminal", "export http_proxy=http://$auth$host:$port https_proxy=http://$auth$host:$port"),
    )
}

/**
 * "Connect your laptop" sheet: pick a non-VPN address, then copyable
 * commands plus plain-text Windows and Firefox steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaptopSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by ProxyController.state.collectAsState()
    val settings by ProxyController.settings.collectAsState()
    val netFlow = remember(context) { networkInterfaces(context) }
    val interfaces by netFlow.collectAsState(initial = emptyList())
    val nonVpn = interfaces.filter { it.kind != IfKind.VPN }
    var selectedIp by rememberSaveable { mutableStateOf<String?>(null) }
    val host = selectedIp?.takeIf { ip -> nonVpn.any { it.ip == ip } }
        ?: nonVpn.firstOrNull()?.ip
    val user = settings.authUser.takeIf { settings.authEnabled }
    val pass = settings.authPass.takeIf { settings.authEnabled }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.laptop_button),
                style = MaterialTheme.typography.titleLarge,
            )
            if (state !is EngineState.Running) {
                Text(
                    text = stringResource(R.string.laptop_start_first),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.laptop_pick_address),
                style = MaterialTheme.typography.titleMedium,
            )
            if (nonVpn.isEmpty()) {
                Text(
                    text = stringResource(R.string.addresses_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (nif in nonVpn) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedIp = nif.ip },
                    ) {
                        RadioButton(
                            selected = host == nif.ip,
                            onClick = null,
                        )
                        Text(
                            text = "${nif.ip} (${nif.name})",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (host != null) {
                for (cmd in laptopCommands(host, settings.port, user, pass)) {
                    CommandCard(cmd = cmd)
                }
            }
            Text(
                text = stringResource(R.string.laptop_windows_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.laptop_windows_steps),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.laptop_firefox_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.laptop_firefox_steps),
                style = MaterialTheme.typography.bodyMedium,
            )
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
