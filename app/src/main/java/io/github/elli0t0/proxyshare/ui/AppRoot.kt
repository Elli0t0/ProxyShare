package io.github.elli0t0.proxyshare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.ui.screens.ActivityScreen
import io.github.elli0t0.proxyshare.ui.screens.ConnectScreen
import io.github.elli0t0.proxyshare.ui.screens.HomeScreen
import io.github.elli0t0.proxyshare.ui.screens.SettingsPage
import io.github.elli0t0.proxyshare.ui.screens.SettingsScreen

/**
 * The navigation shell: a Scaffold with a bottom NavigationBar —
 * Home, Activity, Settings. Plain state (rememberSaveable), no
 * navigation library.
 */
@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showConnect by rememberSaveable { mutableStateOf(false) }
    // A Health "Fix" can ask for a Settings sub-page (Security):
    // the request is consumed by SettingsScreen once shown.
    var openSettingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    Box(Modifier.fillMaxSize()) {
        if (showConnect) {
            // Full-screen Connect page instead of the Scaffold: no bottom bar.
            ConnectScreen(onBack = { showConnect = false })
        } else {
            Scaffold(
                // No system insets from the Scaffold: the NavigationBar pads
                // the nav-bar inset itself, and each screen adds exactly one
                // status-bar inset. (Otherwise the status bar is counted twice.)
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_home)) },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.List, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_activity)) },
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_settings)) },
                        )
                    }
                }
            ) { padding ->
                when (tab) {
                    0 -> HomeScreen(
                        modifier = Modifier.padding(padding),
                        onOpenConnect = { showConnect = true },
                        onOpenSecuritySettings = {
                            openSettingsPage = SettingsPage.SECURITY
                            tab = 2
                        },
                    )
                    1 -> ActivityScreen(
                        modifier = Modifier.padding(padding),
                        onGoHome = { tab = 0 },
                    )
                    else -> SettingsScreen(
                        modifier = Modifier.padding(padding),
                        requestedPage = openSettingsPage,
                        onRequestedPageShown = { openSettingsPage = null },
                    )
                }
            }
        }
        // Solid cover over the status-bar area: content scrolls under
        // the bar, and without this the text overlaps the clock/icons.
        Spacer(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.surface)
        )
    }
}
