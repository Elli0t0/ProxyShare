package io.github.elli0t0.proxyshare.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** All user settings, persisted in Preferences DataStore. */
data class AppSettings(
    val bindAddr: String = "0.0.0.0",
    val port: Int = 8080,
    /** Split mode: HTTP on httpPort, SOCKS on socksPort. False keeps the single `port`. */
    val separatePorts: Boolean = true,
    val httpPort: Int = 8080,
    val socksPort: Int = 1080,
    /** Serve the PAC / setup page (0.6.5). Off by default. Sent to the engine as pac_port (0 = off). */
    val pacEnabled: Boolean = false,
    val pacPort: Int = 8899,
    val protocols: Set<String> = setOf("http", "socks4", "socks5"),
    val authEnabled: Boolean = true,
    val authUser: String = "proxy",
    val authPass: String = "",
    val allowedClients: List<String> = emptyList(),
    /** Domains refused before any outbound connect ("ads.example.com" or "*.example.com"). Sent to the engine. */
    val blockedDomains: List<String> = emptyList(),
    val privateOnly: Boolean = true,
    /** Refuse targets that resolve only to private addresses (the phone's own network). Sent to the engine. */
    val blockPrivate: Boolean = false,
    val maxConnections: Int = 256,
    val idleTimeoutS: Int = 300,
    val connectTimeoutS: Int = 10,
    /** Download speed limit in KB/s, total across all connections. 0 = unlimited. Sent to the engine. */
    val limitDownKbps: Int = 0,
    /** Upload speed limit in KB/s, total across all connections. 0 = unlimited. Sent to the engine. */
    val limitUpKbps: Int = 0,
    val themeMode: String = "system",
    val language: String = "system",
    val keepScreenOn: Boolean = false,
    /** Refuse new connections while no VPN is active (Default mode). Sent to the engine. */
    val requireVpn: Boolean = false,
    /** Client names by IP (shown instead of the raw IP). Kotlin-only: never sent to the engine. */
    val clientNames: Map<String, String> = emptyMap(),
    /** Release the CPU wake lock when no client is connected (default ON). Kotlin-only: never sent to the engine. */
    val batterySaver: Boolean = true,
    /** Start the proxy automatically after boot (default OFF). Kotlin-only: never sent to the engine. */
    val startOnBoot: Boolean = false,
    /** Stop the proxy after this many idle minutes (0 = never). Kotlin-only: never sent to the engine. */
    val idleStopMinutes: Int = 0,
    /** Outbound network mode: "default", "vpn_only", "wifi" or "mobile". Kotlin-only: never sent to the engine. */
    val outboundMode: String = "default",
    /** URL used for the manual exit-IP check; empty = the built-in endpoint list. Kotlin-only: never sent to the engine. */
    val exitIpUrl: String = "",
)

/** Map settings to the engine's key=value config text (see docs/ENGINE_API.md). */
fun AppSettings.toEngineConfig(): String = buildString {
    appendLine("bind_addr=$bindAddr")
    if (separatePorts) {
        if ("http" in protocols) appendLine("http_port=$httpPort")
        if ("socks4" in protocols || "socks5" in protocols) appendLine("socks_port=$socksPort")
    } else {
        appendLine("port=$port")
    }
    // Always sent: the engine default is 8899 (on), so "off" must be explicit.
    appendLine("pac_port=${if (pacEnabled) pacPort else 0}")
    appendLine("protocols=${protocols.joinToString(",")}")
    if (authEnabled && authUser.isNotBlank() && authPass.isNotBlank()) {
        appendLine("auth_user=$authUser")
        appendLine("auth_pass=$authPass")
    }
    if (allowedClients.isNotEmpty()) appendLine("allowed_clients=${allowedClients.joinToString(",")}")
    if (blockedDomains.isNotEmpty()) appendLine("blocked_domains=${blockedDomains.joinToString(",")}")
    appendLine("private_only=$privateOnly")
    appendLine("max_connections=$maxConnections")
    appendLine("idle_timeout_s=$idleTimeoutS")
    appendLine("connect_timeout_s=$connectTimeoutS")
    if (limitDownKbps > 0) appendLine("limit_down_kbps=$limitDownKbps")
    if (limitUpKbps > 0) appendLine("limit_up_kbps=$limitUpKbps")
    if (requireVpn) appendLine("require_vpn=true")
    if (blockPrivate) appendLine("block_private=true")
    if (idleStopMinutes > 0) appendLine("idle_stop_secs=${idleStopMinutes * 60}")
}

/** The port each protocol listens on, or null when it is disabled. */
fun AppSettings.enabledPorts(): Pair<Int?, Int?> {
    val httpPort = if (separatePorts && "http" in protocols) {
        httpPort
    } else if (!separatePorts) {
        port
    } else {
        null
    }
    val socksPort = if (separatePorts &&
        ("socks4" in protocols || "socks5" in protocols)
    ) {
        socksPort
    } else if (!separatePorts) {
        port
    } else {
        null
    }
    return httpPort to socksPort
}

/** A random password from unambiguous characters (no 0/O/1/l). Never log it. */
fun randomPassword(length: Int = 12): String {
    val chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val rnd = java.security.SecureRandom()
    return String(CharArray(length) { chars[rnd.nextInt(chars.length)] })
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings"
)

/** Persists [AppSettings] and exposes them as a [Flow]. */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            bindAddr = prefs[Keys.BIND_ADDR] ?: defaults.bindAddr,
            port = prefs[Keys.PORT] ?: defaults.port,
            separatePorts = prefs[Keys.SEPARATE_PORTS] ?: defaults.separatePorts,
            httpPort = prefs[Keys.HTTP_PORT] ?: defaults.httpPort,
            socksPort = prefs[Keys.SOCKS_PORT] ?: defaults.socksPort,
            pacEnabled = prefs[Keys.PAC_ENABLED] ?: defaults.pacEnabled,
            pacPort = prefs[Keys.PAC_PORT] ?: defaults.pacPort,
            protocols = prefs[Keys.PROTOCOLS] ?: defaults.protocols,
            authEnabled = prefs[Keys.AUTH_ENABLED] ?: defaults.authEnabled,
            authUser = prefs[Keys.AUTH_USER] ?: defaults.authUser,
            authPass = prefs[Keys.AUTH_PASS] ?: defaults.authPass,
            allowedClients = prefs[Keys.ALLOWED_CLIENTS]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
            blockedDomains = prefs[Keys.BLOCKED_DOMAINS]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
            privateOnly = prefs[Keys.PRIVATE_ONLY] ?: defaults.privateOnly,
            blockPrivate = prefs[Keys.BLOCK_PRIVATE] ?: defaults.blockPrivate,
            maxConnections = prefs[Keys.MAX_CONNECTIONS] ?: defaults.maxConnections,
            idleTimeoutS = prefs[Keys.IDLE_TIMEOUT_S] ?: defaults.idleTimeoutS,
            connectTimeoutS = prefs[Keys.CONNECT_TIMEOUT_S] ?: defaults.connectTimeoutS,
            limitDownKbps = prefs[Keys.LIMIT_DOWN_KBPS] ?: defaults.limitDownKbps,
            limitUpKbps = prefs[Keys.LIMIT_UP_KBPS] ?: defaults.limitUpKbps,
            themeMode = prefs[Keys.THEME_MODE] ?: defaults.themeMode,
            language = prefs[Keys.LANGUAGE] ?: defaults.language,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            requireVpn = prefs[Keys.REQUIRE_VPN] ?: defaults.requireVpn,
            clientNames = parseClientNames(prefs[Keys.CLIENT_NAMES] ?: ""),
            batterySaver = prefs[Keys.BATTERY_SAVER] ?: defaults.batterySaver,
            startOnBoot = prefs[Keys.START_ON_BOOT] ?: defaults.startOnBoot,
            idleStopMinutes = prefs[Keys.IDLE_STOP_MINUTES] ?: defaults.idleStopMinutes,
            outboundMode = prefs[Keys.OUTBOUND_MODE] ?: defaults.outboundMode,
            exitIpUrl = prefs[Keys.EXIT_IP_URL] ?: defaults.exitIpUrl,
        )
    }

    /** Generate and store a random password on first run, when auth is on and none is set. */
    suspend fun ensurePassword() {
        dataStore.edit { prefs ->
            val enabled = prefs[Keys.AUTH_ENABLED] ?: true
            val pass = prefs[Keys.AUTH_PASS]
            if (enabled && pass.isNullOrBlank()) {
                prefs[Keys.AUTH_PASS] = randomPassword()
            }
        }
    }

    suspend fun updateBindAddr(value: String) {
        dataStore.edit { it[Keys.BIND_ADDR] = value }
    }

    suspend fun updatePort(value: Int) {
        dataStore.edit { it[Keys.PORT] = value }
    }

    suspend fun updateSeparatePorts(value: Boolean) {
        dataStore.edit { it[Keys.SEPARATE_PORTS] = value }
    }

    suspend fun updateHttpPort(value: Int) {
        dataStore.edit { it[Keys.HTTP_PORT] = value }
    }

    suspend fun updateSocksPort(value: Int) {
        dataStore.edit { it[Keys.SOCKS_PORT] = value }
    }

    suspend fun updatePacEnabled(value: Boolean) {
        dataStore.edit { it[Keys.PAC_ENABLED] = value }
    }

    suspend fun updatePacPort(value: Int) {
        dataStore.edit { it[Keys.PAC_PORT] = value }
    }

    suspend fun updateProtocols(value: Set<String>) {
        dataStore.edit { it[Keys.PROTOCOLS] = value }
    }

    suspend fun updateAuthEnabled(value: Boolean) {
        dataStore.edit { it[Keys.AUTH_ENABLED] = value }
    }

    suspend fun updateAuthUser(value: String) {
        dataStore.edit { it[Keys.AUTH_USER] = value }
    }

    suspend fun updateAuthPass(value: String) {
        dataStore.edit { it[Keys.AUTH_PASS] = value }
    }

    suspend fun updateAllowedClients(value: List<String>) {
        dataStore.edit { it[Keys.ALLOWED_CLIENTS] = value.joinToString(",") }
    }

    suspend fun updateBlockedDomains(value: List<String>) {
        dataStore.edit { it[Keys.BLOCKED_DOMAINS] = value.joinToString(",") }
    }

    suspend fun updatePrivateOnly(value: Boolean) {
        dataStore.edit { it[Keys.PRIVATE_ONLY] = value }
    }

    suspend fun updateBlockPrivate(value: Boolean) {
        dataStore.edit { it[Keys.BLOCK_PRIVATE] = value }
    }

    suspend fun updateMaxConnections(value: Int) {
        dataStore.edit { it[Keys.MAX_CONNECTIONS] = value }
    }

    suspend fun updateIdleTimeoutS(value: Int) {
        dataStore.edit { it[Keys.IDLE_TIMEOUT_S] = value }
    }

    suspend fun updateConnectTimeoutS(value: Int) {
        dataStore.edit { it[Keys.CONNECT_TIMEOUT_S] = value }
    }

    suspend fun updateLimitDownKbps(value: Int) {
        dataStore.edit { it[Keys.LIMIT_DOWN_KBPS] = value }
    }

    suspend fun updateLimitUpKbps(value: Int) {
        dataStore.edit { it[Keys.LIMIT_UP_KBPS] = value }
    }

    suspend fun updateThemeMode(value: String) {
        dataStore.edit { it[Keys.THEME_MODE] = value }
    }

    suspend fun updateLanguage(value: String) {
        dataStore.edit { it[Keys.LANGUAGE] = value }
    }

    suspend fun updateKeepScreenOn(value: Boolean) {
        dataStore.edit { it[Keys.KEEP_SCREEN_ON] = value }
    }

    suspend fun updateRequireVpn(value: Boolean) {
        dataStore.edit { it[Keys.REQUIRE_VPN] = value }
    }

    suspend fun updateClientName(ip: String, name: String) {
        dataStore.edit { prefs ->
            val names = putClientName(
                parseClientNames(prefs[Keys.CLIENT_NAMES] ?: ""), ip, name
            )
            prefs[Keys.CLIENT_NAMES] = clientNamesJson(names)
        }
    }

    suspend fun updateBatterySaver(value: Boolean) {
        dataStore.edit { it[Keys.BATTERY_SAVER] = value }
    }

    suspend fun updateStartOnBoot(value: Boolean) {
        dataStore.edit { it[Keys.START_ON_BOOT] = value }
    }

    suspend fun updateIdleStopMinutes(value: Int) {
        dataStore.edit { it[Keys.IDLE_STOP_MINUTES] = value }
    }

    suspend fun updateOutboundMode(value: String) {
        dataStore.edit { it[Keys.OUTBOUND_MODE] = value }
    }

    suspend fun updateExitIpUrl(value: String) {
        dataStore.edit { it[Keys.EXIT_IP_URL] = value }
    }

    /**
     * Replace every stored setting in one edit (backup import).
     * Never touches the auth secrets: authUser/authPass keep their
     * current values, because a backup file never contains them.
     */
    suspend fun replaceAll(s: AppSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.BIND_ADDR] = s.bindAddr
            prefs[Keys.PORT] = s.port
            prefs[Keys.SEPARATE_PORTS] = s.separatePorts
            prefs[Keys.HTTP_PORT] = s.httpPort
            prefs[Keys.SOCKS_PORT] = s.socksPort
            prefs[Keys.PAC_ENABLED] = s.pacEnabled
            prefs[Keys.PAC_PORT] = s.pacPort
            prefs[Keys.PROTOCOLS] = s.protocols
            prefs[Keys.AUTH_ENABLED] = s.authEnabled
            prefs[Keys.ALLOWED_CLIENTS] = s.allowedClients.joinToString(",")
            prefs[Keys.BLOCKED_DOMAINS] = s.blockedDomains.joinToString(",")
            prefs[Keys.PRIVATE_ONLY] = s.privateOnly
            prefs[Keys.BLOCK_PRIVATE] = s.blockPrivate
            prefs[Keys.MAX_CONNECTIONS] = s.maxConnections
            prefs[Keys.IDLE_TIMEOUT_S] = s.idleTimeoutS
            prefs[Keys.CONNECT_TIMEOUT_S] = s.connectTimeoutS
            prefs[Keys.LIMIT_DOWN_KBPS] = s.limitDownKbps
            prefs[Keys.LIMIT_UP_KBPS] = s.limitUpKbps
            prefs[Keys.THEME_MODE] = s.themeMode
            prefs[Keys.LANGUAGE] = s.language
            prefs[Keys.KEEP_SCREEN_ON] = s.keepScreenOn
            prefs[Keys.REQUIRE_VPN] = s.requireVpn
            prefs[Keys.CLIENT_NAMES] = clientNamesJson(s.clientNames)
            prefs[Keys.BATTERY_SAVER] = s.batterySaver
            prefs[Keys.START_ON_BOOT] = s.startOnBoot
            prefs[Keys.IDLE_STOP_MINUTES] = s.idleStopMinutes
            prefs[Keys.OUTBOUND_MODE] = s.outboundMode
            prefs[Keys.EXIT_IP_URL] = s.exitIpUrl
        }
    }

    private object Keys {
        val BIND_ADDR = stringPreferencesKey("bind_addr")
        val PORT = intPreferencesKey("port")
        val SEPARATE_PORTS = booleanPreferencesKey("separate_ports")
        val HTTP_PORT = intPreferencesKey("http_port")
        val SOCKS_PORT = intPreferencesKey("socks_port")
        val PAC_ENABLED = booleanPreferencesKey("pac_enabled")
        val PAC_PORT = intPreferencesKey("pac_port")
        val PROTOCOLS = stringSetPreferencesKey("protocols")
        val AUTH_ENABLED = booleanPreferencesKey("auth_enabled")
        val AUTH_USER = stringPreferencesKey("auth_user")
        val AUTH_PASS = stringPreferencesKey("auth_pass")
        val ALLOWED_CLIENTS = stringPreferencesKey("allowed_clients")
        val BLOCKED_DOMAINS = stringPreferencesKey("blocked_domains")
        val PRIVATE_ONLY = booleanPreferencesKey("private_only")
        val BLOCK_PRIVATE = booleanPreferencesKey("block_private")
        val MAX_CONNECTIONS = intPreferencesKey("max_connections")
        val IDLE_TIMEOUT_S = intPreferencesKey("idle_timeout_s")
        val CONNECT_TIMEOUT_S = intPreferencesKey("connect_timeout_s")
        val LIMIT_DOWN_KBPS = intPreferencesKey("limit_down_kbps")
        val LIMIT_UP_KBPS = intPreferencesKey("limit_up_kbps")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val REQUIRE_VPN = booleanPreferencesKey("require_vpn")
        val CLIENT_NAMES = stringPreferencesKey("client_names")
        val BATTERY_SAVER = booleanPreferencesKey("battery_saver")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val IDLE_STOP_MINUTES = intPreferencesKey("idle_stop_minutes")
        val OUTBOUND_MODE = stringPreferencesKey("outbound_mode")
        val EXIT_IP_URL = stringPreferencesKey("exit_ip_url")
    }
}
