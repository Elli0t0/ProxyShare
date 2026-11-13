package com.example.proxyshare.data

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
    val protocols: Set<String> = setOf("http", "socks4", "socks5"),
    val authEnabled: Boolean = true,
    val authUser: String = "proxy",
    val authPass: String = "",
    val allowedClients: List<String> = emptyList(),
    val privateOnly: Boolean = true,
    val maxConnections: Int = 64,
    val idleTimeoutS: Int = 300,
    val connectTimeoutS: Int = 10,
    val themeMode: String = "system",
    val language: String = "system",
    val keepScreenOn: Boolean = false,
    /** Outbound network mode: "default", "vpn_only", "wifi" or "mobile". Kotlin-only: never sent to the engine. */
    val outboundMode: String = "default",
    /** URL used for the manual exit-IP check. Kotlin-only: never sent to the engine. */
    val exitIpUrl: String = "https://api.ipify.org",
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
    appendLine("protocols=${protocols.joinToString(",")}")
    if (authEnabled && authUser.isNotBlank() && authPass.isNotBlank()) {
        appendLine("auth_user=$authUser")
        appendLine("auth_pass=$authPass")
    }
    if (allowedClients.isNotEmpty()) appendLine("allowed_clients=${allowedClients.joinToString(",")}")
    appendLine("private_only=$privateOnly")
    appendLine("max_connections=$maxConnections")
    appendLine("idle_timeout_s=$idleTimeoutS")
    appendLine("connect_timeout_s=$connectTimeoutS")
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
            protocols = prefs[Keys.PROTOCOLS] ?: defaults.protocols,
            authEnabled = prefs[Keys.AUTH_ENABLED] ?: defaults.authEnabled,
            authUser = prefs[Keys.AUTH_USER] ?: defaults.authUser,
            authPass = prefs[Keys.AUTH_PASS] ?: defaults.authPass,
            allowedClients = prefs[Keys.ALLOWED_CLIENTS]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
            privateOnly = prefs[Keys.PRIVATE_ONLY] ?: defaults.privateOnly,
            maxConnections = prefs[Keys.MAX_CONNECTIONS] ?: defaults.maxConnections,
            idleTimeoutS = prefs[Keys.IDLE_TIMEOUT_S] ?: defaults.idleTimeoutS,
            connectTimeoutS = prefs[Keys.CONNECT_TIMEOUT_S] ?: defaults.connectTimeoutS,
            themeMode = prefs[Keys.THEME_MODE] ?: defaults.themeMode,
            language = prefs[Keys.LANGUAGE] ?: defaults.language,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
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

    suspend fun updatePrivateOnly(value: Boolean) {
        dataStore.edit { it[Keys.PRIVATE_ONLY] = value }
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

    suspend fun updateThemeMode(value: String) {
        dataStore.edit { it[Keys.THEME_MODE] = value }
    }

    suspend fun updateLanguage(value: String) {
        dataStore.edit { it[Keys.LANGUAGE] = value }
    }

    suspend fun updateKeepScreenOn(value: Boolean) {
        dataStore.edit { it[Keys.KEEP_SCREEN_ON] = value }
    }

    suspend fun updateOutboundMode(value: String) {
        dataStore.edit { it[Keys.OUTBOUND_MODE] = value }
    }

    suspend fun updateExitIpUrl(value: String) {
        dataStore.edit { it[Keys.EXIT_IP_URL] = value }
    }

    private object Keys {
        val BIND_ADDR = stringPreferencesKey("bind_addr")
        val PORT = intPreferencesKey("port")
        val SEPARATE_PORTS = booleanPreferencesKey("separate_ports")
        val HTTP_PORT = intPreferencesKey("http_port")
        val SOCKS_PORT = intPreferencesKey("socks_port")
        val PROTOCOLS = stringSetPreferencesKey("protocols")
        val AUTH_ENABLED = booleanPreferencesKey("auth_enabled")
        val AUTH_USER = stringPreferencesKey("auth_user")
        val AUTH_PASS = stringPreferencesKey("auth_pass")
        val ALLOWED_CLIENTS = stringPreferencesKey("allowed_clients")
        val PRIVATE_ONLY = booleanPreferencesKey("private_only")
        val MAX_CONNECTIONS = intPreferencesKey("max_connections")
        val IDLE_TIMEOUT_S = intPreferencesKey("idle_timeout_s")
        val CONNECT_TIMEOUT_S = intPreferencesKey("connect_timeout_s")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val OUTBOUND_MODE = stringPreferencesKey("outbound_mode")
        val EXIT_IP_URL = stringPreferencesKey("exit_ip_url")
    }
}
