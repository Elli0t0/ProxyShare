package com.example.proxyshare.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.proxyshare.R
import com.example.proxyshare.data.IfKind
import com.example.proxyshare.data.SettingsRepository
import com.example.proxyshare.data.networkInterfaces
import com.example.proxyshare.data.randomPassword
import com.example.proxyshare.engine.EngineState
import com.example.proxyshare.service.ProxyController
import kotlinx.coroutines.launch

/**
 * Settings, saved on change. Server, protocols, security and limits
 * need a proxy restart to apply (banner + Restart button while
 * running); theme and keep-screen-on apply live. No language
 * selector (needs appcompat; revisit later).
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ProxyController.state.collectAsState()
    val settings by ProxyController.settings.collectAsState()
    val netFlow = remember(context) { networkInterfaces(context) }
    val interfaces by netFlow.collectAsState(initial = emptyList())
    var changedWhileRunning by rememberSaveable { mutableStateOf(false) }
    val running = state is EngineState.Running

    /** Save a change that needs a restart to apply. */
    fun saveRestart(block: suspend SettingsRepository.() -> Unit) {
        scope.launch { ProxyController.updateSettings(block) }
        if (running) changedWhileRunning = true
    }

    /** Save a change that applies live (theme, keep screen on). */
    fun saveLive(block: suspend SettingsRepository.() -> Unit) {
        scope.launch { ProxyController.updateSettings(block) }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Restart banner.
            if (running && changedWhileRunning) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.restart_banner),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = {
                            scope.launch { ProxyController.restart() }
                            changedWhileRunning = false
                        }) {
                            Text(stringResource(R.string.restart_button))
                        }
                    }
                }
            }

            // Server.
            SectionCard(title = stringResource(R.string.settings_server)) {
                NumberField(
                    label = stringResource(R.string.port_label),
                    value = settings.port,
                    errorText = stringResource(R.string.port_error),
                    validate = ::validatePort,
                    onValid = { saveRestart { updatePort(it) } },
                )
                Text(
                    text = stringResource(R.string.bind_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                val bindOptions = remember(interfaces, settings.bindAddr) {
                    val ips = interfaces
                        .filter { it.kind != IfKind.VPN }
                        .map { it.ip }
                        .distinct()
                    val all = listOf("0.0.0.0") + ips
                    if (settings.bindAddr in all) all else all + settings.bindAddr
                }
                for (ip in bindOptions) {
                    val label = if (ip == "0.0.0.0") {
                        "${stringResource(R.string.bind_all)} (0.0.0.0)"
                    } else {
                        interfaces.firstOrNull { it.ip == ip }
                            ?.let { "${it.ip} (${it.name})" } ?: ip
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { saveRestart { updateBindAddr(ip) } },
                    ) {
                        RadioButton(
                            selected = settings.bindAddr == ip,
                            onClick = null,
                        )
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Protocols.
            SectionCard(title = stringResource(R.string.settings_protocols)) {
                for ((key, label) in listOf("http" to "HTTP", "socks4" to "SOCKS4", "socks5" to "SOCKS5")) {
                    val isOn = key in settings.protocols
                    SwitchRow(
                        label = label,
                        checked = isOn,
                        // At least one protocol must stay on.
                        enabled = settings.protocols.size > 1 || !isOn,
                        onCheckedChange = { on ->
                            val new = if (on) settings.protocols + key else settings.protocols - key
                            if (new.isNotEmpty()) saveRestart { updateProtocols(new) }
                        },
                    )
                }
            }

            // Security.
            SectionCard(title = stringResource(R.string.settings_security)) {
                SwitchRow(
                    label = stringResource(R.string.auth_label),
                    checked = settings.authEnabled,
                    onCheckedChange = { saveRestart { updateAuthEnabled(it) } },
                )
                if (settings.authEnabled) {
                    OutlinedTextField(
                        value = settings.authUser,
                        onValueChange = { saveRestart { updateAuthUser(it) } },
                        label = { Text(stringResource(R.string.username_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = settings.authPass,
                        onValueChange = { saveRestart { updateAuthPass(it) } },
                        label = { Text(stringResource(R.string.password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        saveRestart { updateAuthPass(randomPassword()) }
                    }) {
                        Text(stringResource(R.string.regenerate))
                    }
                }
                SwitchRow(
                    label = stringResource(R.string.private_only_label),
                    checked = settings.privateOnly,
                    onCheckedChange = { saveRestart { updatePrivateOnly(it) } },
                )
                Text(
                    text = stringResource(R.string.allowed_clients_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                for (cidr in settings.allowedClients) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = cidr,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            saveRestart { updateAllowedClients(settings.allowedClients - cidr) }
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.remove_client),
                            )
                        }
                    }
                }
                CidrAddRow(onAdd = { saveRestart { updateAllowedClients(settings.allowedClients + it) } })
            }

            // Limits.
            SectionCard(title = stringResource(R.string.settings_limits)) {
                NumberField(
                    label = stringResource(R.string.max_connections_label),
                    value = settings.maxConnections,
                    errorText = stringResource(R.string.invalid_number),
                    validate = { validateIntInRange(it, 1..512) },
                    onValid = { saveRestart { updateMaxConnections(it) } },
                )
                NumberField(
                    label = stringResource(R.string.idle_timeout_label),
                    value = settings.idleTimeoutS,
                    errorText = stringResource(R.string.invalid_number),
                    validate = { validateIntInRange(it, 30..3600) },
                    onValid = { saveRestart { updateIdleTimeoutS(it) } },
                )
                NumberField(
                    label = stringResource(R.string.connect_timeout_label),
                    value = settings.connectTimeoutS,
                    errorText = stringResource(R.string.invalid_number),
                    validate = { validateIntInRange(it, 2..120) },
                    onValid = { saveRestart { updateConnectTimeoutS(it) } },
                )
            }

            // Appearance.
            SectionCard(title = stringResource(R.string.settings_appearance)) {
                Text(
                    text = stringResource(R.string.theme_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = listOf(
                        "system" to R.string.theme_system,
                        "light" to R.string.theme_light,
                        "dark" to R.string.theme_dark,
                    )
                    modes.forEachIndexed { index, (mode, labelRes) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { saveLive { updateThemeMode(mode) } },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = modes.size,
                            ),
                        ) {
                            Text(text = stringResource(labelRes))
                        }
                    }
                }
                SwitchRow(
                    label = stringResource(R.string.keep_screen_on_label),
                    checked = settings.keepScreenOn,
                    onCheckedChange = { saveLive { updateKeepScreenOn(it) } },
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    errorText: String,
    validate: (String) -> Int?,
    onValid: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = validate(text) != null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                text = new
                validate(new)?.let { onValid(it) }
            },
            label = { Text(label) },
            isError = !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!valid) {
            Text(
                text = errorText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CidrAddRow(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; invalid = false },
                label = { Text(stringResource(R.string.allowed_clients_hint)) },
                isError = invalid,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                if (validateCidr(text.trim())) {
                    onAdd(text.trim())
                    text = ""
                    invalid = false
                } else {
                    invalid = true
                }
            }) {
                Text(stringResource(R.string.allowed_clients_add))
            }
        }
        if (invalid) {
            Text(
                text = stringResource(R.string.allowed_clients_invalid),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
