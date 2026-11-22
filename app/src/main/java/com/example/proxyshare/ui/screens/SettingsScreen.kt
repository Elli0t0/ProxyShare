package com.example.proxyshare.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.proxyshare.R
import com.example.proxyshare.data.GITHUB_REPO
import com.example.proxyshare.data.IfKind
import com.example.proxyshare.data.LatestRelease
import com.example.proxyshare.data.SettingsRepository
import com.example.proxyshare.data.fetchLatestRelease
import com.example.proxyshare.data.isNewer
import com.example.proxyshare.data.networkInterfaces
import com.example.proxyshare.data.randomPassword
import com.example.proxyshare.engine.EngineState
import com.example.proxyshare.engine.NativeEngine
import com.example.proxyshare.engine.redactLog
import com.example.proxyshare.service.ProxyController
import com.example.proxyshare.ui.components.OptionRow
import com.example.proxyshare.ui.components.SectionCard
import com.example.proxyshare.ui.components.SettingRow
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
    val state by ProxyController.state.collectAsStateWithLifecycle()
    val settings by ProxyController.settings.collectAsStateWithLifecycle()
    val netFlow = remember(context) { networkInterfaces(context) }
    val interfaces by netFlow.collectAsState(initial = emptyList())
    // The restart flag lives in ProxyController so other screens (e.g.
    // the session log's "Block this domain") can set it too.
    val restartNeeded by ProxyController.restartNeeded.collectAsStateWithLifecycle()
    var showPasteList by rememberSaveable { mutableStateOf(false) }
    var showIdleDialog by rememberSaveable { mutableStateOf(false) }
    // Engine diagnostics log: transient, loaded once, refreshed on tap.
    var diagText by remember { mutableStateOf("") }
    val diagScrollState = rememberScrollState()
    // Manual update check: transient state, reset on rotation.
    var updateState by remember { mutableStateOf(UpdateCheckState.IDLE) }
    var latestRelease by remember { mutableStateOf<LatestRelease?>(null) }
    val running = state is EngineState.Running
    val appVersion = remember {
        runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName
        }.getOrNull() ?: "?"
    }
    val engineVersion = remember {
        runCatching { NativeEngine.nativeVersion() }.getOrNull() ?: "?"
    }

    /** Save a change that needs a restart to apply. */
    fun saveRestart(block: suspend SettingsRepository.() -> Unit) {
        scope.launch { ProxyController.updateSettings(block) }
        if (running) ProxyController.markRestartNeeded()
    }

    /** Save a change that applies live (theme, keep screen on). */
    fun saveLive(block: suspend SettingsRepository.() -> Unit) {
        scope.launch { ProxyController.updateSettings(block) }
    }

    /** Reload the engine diagnostics log. */
    fun loadDiag() {
        scope.launch {
            diagText = runCatching { ProxyController.diagLog() }.getOrDefault("")
        }
    }

    /** Share the diagnostics log as text, with credentials redacted. */
    fun shareDiag() {
        val redacted = redactLog(diagText)
        if (redacted.isBlank()) return
        ShareCompat.IntentBuilder(context)
            .setType("text/plain")
            .setText(redacted)
            .setChooserTitle(R.string.diag_share)
            .startChooser()
    }

    LaunchedEffect(Unit) { loadDiag() }

    // Auto-scroll the log view to the bottom on every load (one frame
    // so the layout has measured the new text first).
    LaunchedEffect(diagText) {
        withFrameNanos { }
        diagScrollState.scrollTo(diagScrollState.maxValue)
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.titleMedium,
            )
            // Restart banner.
            if (running && restartNeeded) {
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
                            ProxyController.clearRestartNeeded()
                        }) {
                            Text(stringResource(R.string.restart_button))
                        }
                    }
                }
            }

            // Open to everyone: no password and not private-only.
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

            // Server.
            SectionCard(title = stringResource(R.string.settings_server)) {
                SettingRow(
                    title = stringResource(R.string.separate_ports_label),
                    subtitle = stringResource(R.string.separate_ports_subtitle),
                    trailing = {
                        Switch(
                            checked = settings.separatePorts,
                            onCheckedChange = { saveRestart { updateSeparatePorts(it) } },
                        )
                    },
                )
                if (settings.separatePorts) {
                    val portsDifferError = settings.httpPort == settings.socksPort &&
                        validatePort(settings.httpPort.toString()) != null
                    NumberField(
                        label = stringResource(R.string.http_port_label),
                        value = settings.httpPort,
                        errorText = if (portsDifferError) {
                            stringResource(R.string.ports_differ_error)
                        } else {
                            stringResource(R.string.port_error)
                        },
                        validate = { text ->
                            validatePort(text)?.takeIf { it != settings.socksPort }
                        },
                        onValid = { saveRestart { updateHttpPort(it) } },
                    )
                    NumberField(
                        label = stringResource(R.string.socks_port_label),
                        value = settings.socksPort,
                        errorText = if (portsDifferError) {
                            stringResource(R.string.ports_differ_error)
                        } else {
                            stringResource(R.string.port_error)
                        },
                        validate = { text ->
                            validatePort(text)?.takeIf { it != settings.httpPort }
                        },
                        onValid = { saveRestart { updateSocksPort(it) } },
                    )
                } else {
                    NumberField(
                        label = stringResource(R.string.port_label),
                        value = settings.port,
                        errorText = stringResource(R.string.port_error),
                        validate = ::validatePort,
                        onValid = { saveRestart { updatePort(it) } },
                    )
                }
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

            // Battery & startup.
            SectionCard(title = stringResource(R.string.settings_battery_startup)) {
                // Battery saver applies live (no restart banner).
                SettingRow(
                    title = stringResource(R.string.battery_saver_label),
                    subtitle = stringResource(R.string.battery_saver_subtitle),
                    trailing = {
                        Switch(
                            checked = settings.batterySaver,
                            onCheckedChange = { saveLive { updateBatterySaver(it) } },
                        )
                    },
                )
                SettingRow(
                    title = stringResource(R.string.battery_optimization_label),
                    trailing = {
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                )
                            }
                        ) {
                            Text(stringResource(R.string.battery_optimization_open))
                        }
                    },
                )
                Text(
                    text = stringResource(R.string.battery_optimization_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Start on boot applies live (no restart banner).
                SettingRow(
                    title = stringResource(R.string.start_on_boot_label),
                    subtitle = stringResource(R.string.start_on_boot_subtitle),
                    trailing = {
                        Switch(
                            checked = settings.startOnBoot,
                            onCheckedChange = { saveLive { updateStartOnBoot(it) } },
                        )
                    },
                )
                // Idle auto-stop applies live (no restart banner).
                SettingRow(
                    title = stringResource(R.string.idle_stop_label),
                    subtitle = stringResource(idleStopLabelRes(settings.idleStopMinutes)),
                    modifier = Modifier.clickable { showIdleDialog = true },
                )
            }

            if (showIdleDialog) {
                AlertDialog(
                    onDismissRequest = { showIdleDialog = false },
                    title = { Text(stringResource(R.string.idle_stop_label)) },
                    text = {
                        Column {
                            for (minutes in listOf(0, 15, 30, 60, 120)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            saveLive { updateIdleStopMinutes(minutes) }
                                            showIdleDialog = false
                                        },
                                ) {
                                    RadioButton(
                                        selected = settings.idleStopMinutes == minutes,
                                        onClick = null,
                                    )
                                    Text(
                                        text = stringResource(idleStopLabelRes(minutes)),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showIdleDialog = false }) {
                            Text(stringResource(R.string.paste_list_done))
                        }
                    },
                )
            }

            // Protocols.
            SectionCard(title = stringResource(R.string.settings_protocols)) {
                for ((key, label) in listOf("http" to "HTTP", "socks4" to "SOCKS4", "socks5" to "SOCKS5")) {
                    val isOn = key in settings.protocols
                    SettingRow(
                        title = label,
                        subtitle = stringResource(protocolSubtitle(key)),
                        trailing = {
                            Switch(
                                checked = isOn,
                                // At least one protocol must stay on.
                                enabled = settings.protocols.size > 1 || !isOn,
                                onCheckedChange = { on ->
                                    val new = if (on) settings.protocols + key else settings.protocols - key
                                    if (new.isNotEmpty()) saveRestart { updateProtocols(new) }
                                },
                            )
                        },
                    )
                }
                if (settings.protocols.size == 1) {
                    Text(
                        text = stringResource(R.string.protocol_at_least_one),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Security.
            SectionCard(title = stringResource(R.string.settings_security)) {
                OptionRow(
                    options = listOf(
                        true to stringResource(R.string.password_label),
                        false to stringResource(R.string.auth_no_password),
                    ),
                    selected = settings.authEnabled,
                    onSelect = { saveRestart { updateAuthEnabled(it) } },
                )
                if (!settings.authEnabled) {
                    Text(
                        text = stringResource(R.string.auth_no_password_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                SettingRow(
                    title = stringResource(R.string.private_only_label),
                    subtitle = stringResource(R.string.private_only_subtitle),
                    trailing = {
                        Switch(
                            checked = settings.privateOnly,
                            onCheckedChange = { saveRestart { updatePrivateOnly(it) } },
                        )
                    },
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
                Text(
                    text = stringResource(R.string.blocked_domains_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.blocked_domains_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (domain in settings.blockedDomains) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = domain,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            saveRestart { updateBlockedDomains(settings.blockedDomains - domain) }
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.remove_domain),
                            )
                        }
                    }
                }
                DomainAddRow(onAdd = { saveRestart { updateBlockedDomains(settings.blockedDomains + it) } })
                TextButton(onClick = { showPasteList = true }) {
                    Text(stringResource(R.string.paste_list))
                }
            }

            if (showPasteList) {
                PasteListDialog(
                    existing = settings.blockedDomains,
                    onDismiss = { showPasteList = false },
                    onAdd = { saveRestart { updateBlockedDomains((settings.blockedDomains + it).distinct()) } },
                )
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
                NumberField(
                    label = stringResource(R.string.limit_down_label),
                    value = settings.limitDownKbps,
                    errorText = stringResource(R.string.invalid_number),
                    validate = { validateIntInRange(it, 0..1_000_000) },
                    onValid = { saveRestart { updateLimitDownKbps(it) } },
                )
                NumberField(
                    label = stringResource(R.string.limit_up_label),
                    value = settings.limitUpKbps,
                    errorText = stringResource(R.string.invalid_number),
                    validate = { validateIntInRange(it, 0..1_000_000) },
                    onValid = { saveRestart { updateLimitUpKbps(it) } },
                )
                Text(
                    text = stringResource(R.string.limit_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Appearance.
            SectionCard(title = stringResource(R.string.settings_appearance)) {
                Text(
                    text = stringResource(R.string.theme_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                OptionRow(
                    options = listOf(
                        "system" to stringResource(R.string.theme_system),
                        "light" to stringResource(R.string.theme_light),
                        "dark" to stringResource(R.string.theme_dark),
                    ),
                    selected = settings.themeMode,
                    onSelect = { saveLive { updateThemeMode(it) } },
                )
                SettingRow(
                    title = stringResource(R.string.keep_screen_on_label),
                    subtitle = stringResource(R.string.keep_screen_on_subtitle),
                    trailing = {
                        Switch(
                            checked = settings.keepScreenOn,
                            onCheckedChange = { saveLive { updateKeepScreenOn(it) } },
                        )
                    },
                )
            }

            // About.
            SectionCard(title = stringResource(R.string.about_title)) {
                SettingRow(
                    title = stringResource(R.string.about_app_version),
                    subtitle = appVersion,
                )
                SettingRow(
                    title = stringResource(R.string.about_engine_version),
                    subtitle = engineVersion,
                )
                SettingRow(
                    title = stringResource(R.string.about_repo),
                    subtitle = "github.com/$GITHUB_REPO",
                )
                // Manual update check: one tap, no background polling.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val statusText = when (updateState) {
                        UpdateCheckState.CHECKING -> stringResource(R.string.update_checking)
                        UpdateCheckState.UP_TO_DATE ->
                            stringResource(R.string.update_up_to_date, appVersion)
                        UpdateCheckState.AVAILABLE ->
                            stringResource(
                                R.string.update_available,
                                latestRelease?.tag?.removePrefix("v") ?: "?",
                            )
                        UpdateCheckState.FAILED -> stringResource(R.string.update_failed)
                        UpdateCheckState.IDLE -> null
                    }
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (updateState == UpdateCheckState.AVAILABLE) {
                        OutlinedButton(onClick = {
                            val url = latestRelease?.url ?: return@OutlinedButton
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }) {
                            Text(stringResource(R.string.update_open))
                        }
                    }
                    Button(
                        onClick = {
                            updateState = UpdateCheckState.CHECKING
                            scope.launch {
                                val release = fetchLatestRelease(GITHUB_REPO)
                                if (release == null) {
                                    updateState = UpdateCheckState.FAILED
                                } else if (isNewer(release.tag, appVersion)) {
                                    latestRelease = release
                                    updateState = UpdateCheckState.AVAILABLE
                                } else {
                                    updateState = UpdateCheckState.UP_TO_DATE
                                }
                            }
                        },
                        enabled = updateState != UpdateCheckState.CHECKING,
                    ) {
                        Text(stringResource(R.string.check_updates))
                    }
                }
            }

            // Diagnostics: the engine's own log (last 300 lines).
            SectionCard(title = stringResource(R.string.settings_diagnostics)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.diag_engine_log),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { loadDiag() }) {
                        Text(stringResource(R.string.diag_refresh))
                    }
                    TextButton(onClick = { shareDiag() }) {
                        Text(stringResource(R.string.diag_share))
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(diagScrollState),
                ) {
                    SelectionContainer {
                        Text(
                            text = diagText.ifEmpty {
                                stringResource(R.string.diag_empty)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun protocolSubtitle(key: String): Int = when (key) {
    "http" -> R.string.protocol_http_subtitle
    "socks4" -> R.string.protocol_socks4_subtitle
    else -> R.string.protocol_socks5_subtitle
}

/** Transient state of the manual update check (not saved across rotations). */
private enum class UpdateCheckState { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, FAILED }

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

@Composable
private fun DomainAddRow(onAdd: (String) -> Unit) {
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
                label = { Text(stringResource(R.string.blocked_domains_hint)) },
                isError = invalid,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                if (validateBlockedDomain(text.trim())) {
                    onAdd(text.trim())
                    text = ""
                    invalid = false
                } else {
                    invalid = true
                }
            }) {
                Text(stringResource(R.string.blocked_domains_add))
            }
        }
        if (invalid) {
            Text(
                text = stringResource(R.string.blocked_domains_invalid),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Bulk-add blocked domains: one per line, blanks and #-comments ignored. */
@Composable
private fun PasteListDialog(
    existing: List<String>,
    onDismiss: () -> Unit,
    onAdd: (List<String>) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    // Transient: the result of the last Add, reset on rotation.
    var result by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paste_list)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; result = null },
                    label = { Text(stringResource(R.string.paste_list_hint)) },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                result?.let { (added, invalid) ->
                    Text(
                        text = stringResource(R.string.paste_list_result, added, invalid),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (added, invalid) = parseBlockedList(text, existing)
                if (added.isNotEmpty()) onAdd(added)
                result = added.size to invalid
                text = ""
            }) {
                Text(stringResource(R.string.blocked_domains_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.paste_list_done))
            }
        },
    )
}
