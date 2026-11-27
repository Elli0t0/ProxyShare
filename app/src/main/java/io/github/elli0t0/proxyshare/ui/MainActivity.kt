package io.github.elli0t0.proxyshare.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.elli0t0.proxyshare.service.ProxyController
import io.github.elli0t0.proxyshare.ui.theme.ProxyShareTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by ProxyController.settings.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            ProxyShareTheme(darkTheme = darkTheme) {
                AppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ProxyController.setUiVisible(true)
    }

    override fun onStop() {
        ProxyController.setUiVisible(false)
        // The UI going to the background is one of the usage flush
        // points (the others are engine stop and service destroy):
        // persist the bytes moved since the last flush.
        lifecycleScope.launch { ProxyController.flushUsage() }
        super.onStop()
    }
}
