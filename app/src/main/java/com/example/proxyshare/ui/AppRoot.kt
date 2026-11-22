package com.example.proxyshare.ui

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.proxyshare.R
import com.example.proxyshare.ui.screens.ConnectionsScreen
import com.example.proxyshare.ui.screens.HomeScreen
import com.example.proxyshare.ui.screens.SettingsScreen

/**
 * The navigation shell: a Scaffold with a bottom NavigationBar —
 * Home, Connections, Settings. Plain state (rememberSaveable), no
 * navigation library.
 */
@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
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
                        label = { Text(stringResource(R.string.nav_connections)) },
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
                0 -> HomeScreen(modifier = Modifier.padding(padding))
                1 -> ConnectionsScreen(
                    modifier = Modifier.padding(padding),
                    onGoHome = { tab = 0 },
                )
                else -> SettingsScreen(modifier = Modifier.padding(padding))
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
