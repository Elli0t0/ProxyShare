package io.github.elli0t0.proxyshare.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.data.GITHUB_REPO
import io.github.elli0t0.proxyshare.data.AppSettings
import io.github.elli0t0.proxyshare.data.IfKind
import io.github.elli0t0.proxyshare.data.LatestRelease
import io.github.elli0t0.proxyshare.data.SettingsRepository
import io.github.elli0t0.proxyshare.data.fetchLatestRelease
import io.github.elli0t0.proxyshare.data.isNewer
import io.github.elli0t0.proxyshare.data.networkInterfaces
import io.github.elli0t0.proxyshare.data.randomPassword
import io.github.elli0t0.proxyshare.engine.EngineState
import io.github.elli0t0.proxyshare.engine.NativeEngine
import io.github.elli0t0.proxyshare.service.ProxyController
import io.github.elli0t0.proxyshare.ui.components.OptionRow
import io.github.elli0t0.proxyshare.ui.components.SectionCard
import io.github.elli0t0.proxyshare.ui.components.SettingRow
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

    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }

    fun onRestart() {
        scope.launch { ProxyController.restart() }
        ProxyController.clearRestartNeeded()
    }

    fun checkForUpdates() {
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
    }

    Surface(modifier = modifier.fillMaxSize()) {
        val current = page
        if (current == null) {
            SettingsList(
                settings = settings,
                running = running,
                restartNeeded = restartNeeded,
                onRestart = ::onRestart,
                appVersion = appVersion,
                engineVersion = engineVersion,
                updateState = updateState,
                latestRelease = latestRelease,
                onCheckUpdate = ::checkForUpdates,
                onOpen = { page = it },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SettingsDetail(
                page = current,
                settings = settings,
                interfaces = interfaces,
                running = running,
                restartNeeded = restartNeeded,
                onRestart = ::onRestart,
                onBack = { page = null },
                saveRestart = ::saveRestart,
                saveLive = ::saveLive,
                onShowIdleDialog = { showIdleDialog = true },
                onShowPasteList = { showPasteList = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
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

    if (showPasteList) {
        PasteListDialog(
            existing = settings.blockedDomains,
            onDismiss = { showPasteList = false },
            onAdd = { saveRestart { updateBlockedDomains((settings.blockedDomains + it).distinct()) } },
        )
    }
}

/** Which sub-page of Settings is open, if any. Backup and Rules will add rows here. */
enum class SettingsPage { SERVER, SECURITY, LIMITS, BATTERY, APPEARANCE }

@Composable
private fun SettingsList(
    settings: AppSettings,
    running: Boolean,
    restartNeeded: Boolean,
    onRestart: () -> Unit,
    appVersion: String,
    engineVersion: String,
    updateState: UpdateCheckState,
    latestRelease: LatestRelease?,
    onCheckUpdate: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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
        SettingsBanners(
            running = running,
            restartNeeded = restartNeeded,
            settings = settings,
            onRestart = onRestart,
        )
        for (page in SettingsPage.entries) {
            SettingsRow(
                page = page,
                summary = pageSummary(page, settings),
                onOpen = { onOpen(page) },
            )
        }
        // About stays on the list: versions and the manual update check.
        AboutSettings(
            appVersion = appVersion,
            engineVersion = engineVersion,
            updateState = updateState,
            latestRelease = latestRelease,
            onCheckUpdate = onCheckUpdate,
        )
    }
}

@Composable
private fun SettingsDetail(
    page: SettingsPage,
    settings: AppSettings,
    interfaces: List<NetIf>,
    running: Boolean,
    restartNeeded: Boolean,
    onRestart: () -> Unit,
    onBack: () -> Unit,
    saveRestart: (suspend SettingsRepository.() -> Unit) -> Unit,
    saveLive: (suspend SettingsRepository.() -> Unit) -> Unit,
    onShowIdleDialog: () -> Unit,
    onShowPasteList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                text = stringResource(pageTitle(page)),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        SettingsBanners(
            running = running,
            restartNeeded = restartNeeded,
            settings = settings,
            onRestart = onRestart,
        )
        when (page) {
            SettingsPage.SERVER -> {
                ServerSettings(
                    settings = settings,
                    interfaces = interfaces,
                    saveRestart = saveRestart,
                )
                ProtocolsSettings(settings = settings, saveRestart = saveRestart)
            }
            SettingsPage.SECURITY -> SecuritySettings(
                settings = settings,
                saveRestart = saveRestart,
                onShowPasteList = onShowPasteList,
            )
            SettingsPage.LIMITS -> LimitsSettings(
                settings = settings,
                saveRestart = saveRestart,
            )
            SettingsPage.BATTERY -> BatterySettings(
                settings = settings,
                saveLive = saveLive,
                onShowIdleDialog = onShowIdleDialog,
            )
            SettingsPage.APPEARANCE -> AppearanceSettings(
                settings = settings,
                saveLive = saveLive,
            )
        }
    }
}

/** The restart-needed and open-proxy banners, shown on the list and on every detail page. */
@Composable
private fun SettingsBanners(
    running: Boolean,
    restartNeeded: Boolean,
    settings: AppSettings,
    onRestart: () -> Unit,
) {
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
                Button(onClick = onRestart) {
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
}

@Composable
private fun SettingsRow(page: SettingsPage, summary: String, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(vertical = 8.dp),
    ) {
        Icon(
            pageIcon(page),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                text = stringResource(pageTitle(page)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun pageIcon(page: SettingsPage): ImageVector = when (page) {
    SettingsPage.SERVER -> Icons.Filled.Build
    SettingsPage.SECURITY -> Icons.Filled.Lock
    SettingsPage.LIMITS -> Icons.Filled.List
    SettingsPage.BATTERY -> Icons.Filled.PlayArrow
    SettingsPage.APPEARANCE -> Icons.Filled.Face
}

private fun pageTitle(page: SettingsPage): Int = when (page) {
    SettingsPage.SERVER -> R.string.settings_server
    SettingsPage.SECURITY -> R.string.settings_security
    SettingsPage.LIMITS -> R.string.settings_limits
    SettingsPage.BATTERY -> R.string.settings_battery_startup
    SettingsPage.APPEARANCE -> R.string.settings_appearance
}

private fun pageSummary(page: SettingsPage, settings: AppSettings): String = when (page) {
    SettingsPage.SERVER -> serverSummary(settings)
    SettingsPage.SECURITY -> securitySummary(settings)
    SettingsPage.LIMITS -> limitsSummary(settings)
    SettingsPage.BATTERY -> batterySummary(settings)
    SettingsPage.APPEARANCE -> appearanceSummary(settings)
}

@Composable
private fun ServerSettings(
    settings: AppSettings,
    interfaces: List<NetIf>,
    saveRestart: (suspend SettingsRepository.() -> Unit) -> Unit,
) {
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
}

@Composable
private fun ProtocolsSettings(
    settings: AppSettings,
    saveRestart: (suspend SettingsRepository.() -> Unit) -> Unit,
) {
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
}

@Composable
private fun SecuritySettings(
    settings: AppSettings,
    saveRestart: (suspend SettingsRepository.() -> Unit) -> Unit,
    onShowPasteList: () -> Unit,
) {
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
        TextButton(onClick = { onShowPasteList() }) {
            Text(stringResource(R.string.paste_list))
        }
    }
}

@Composable
private fun LimitsSettings(
    settings: AppSettings,
    saveRestart: (suspend SettingsRepository.() -> Unit) -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_limits)) {
        NumberField(
            label = stringResource(R.string.max_connections_label),
            value = settings.maxConnections,
            errorText = stringResource(R.string.invalid_number),
            validate = { validateIntInRange(it, 1..2000) },
            onValid = { saveRestart { updateMaxConnections(it) } },
        )
        Text(
            text = stringResource(R.string.max_connections_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}

@Composable
private fun BatterySettings(
    settings: AppSettings,
    saveLive: (suspend SettingsRepository.() -> Unit) -> Unit,
    onShowIdleDialog: () -> Unit,
) {
    val context = LocalContext.current
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
            modifier = Modifier.clickable { onShowIdleDialog() },
        )
    }
}

@Composable
private fun AppearanceSettings(
    settings: AppSettings,
    saveLive: (suspend SettingsRepository.() -> Unit) -> Unit,
) {
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
}

@Composable
private fun AboutSettings(
    appVersion: String,
    engineVersion: String,
    updateState: UpdateCheckState,
    latestRelease: LatestRelease?,
    onCheckUpdate: () -> Unit,
) {
    val context = LocalContext.current
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
                onClick = onCheckUpdate,
                enabled = updateState != UpdateCheckState.CHECKING,
            ) {
                Text(stringResource(R.string.check_updates))
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
    var errorCode by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; errorCode = null },
                label = { Text(stringResource(R.string.allowed_clients_hint)) },
                isError = errorCode != null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                val code = validate(VALIDATE_CIDR, text.trim())
                if (code.isEmpty()) {
                    onAdd(text.trim())
                    text = ""
                    errorCode = null
                } else {
                    errorCode = code
                }
            }) {
                Text(stringResource(R.string.allowed_clients_add))
            }
        }
        if (errorCode != null) {
            Text(
                text = stringResource(
                    validationErrorRes[errorCode] ?: R.string.allowed_clients_invalid,
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DomainAddRow(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var errorCode by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; errorCode = null },
                label = { Text(stringResource(R.string.blocked_domains_hint)) },
                isError = errorCode != null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                val code = validate(VALIDATE_DOMAIN, text.trim())
                if (code.isEmpty()) {
                    onAdd(text.trim())
                    text = ""
                    errorCode = null
                } else {
                    errorCode = code
                }
            }) {
                Text(stringResource(R.string.blocked_domains_add))
            }
        }
        if (errorCode != null) {
            Text(
                text = stringResource(
                    validationErrorRes[errorCode] ?: R.string.blocked_domains_invalid,
                ),
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
