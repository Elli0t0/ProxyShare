package com.example.proxyshare.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.proxyshare.R
import com.example.proxyshare.engine.EngineState
import com.example.proxyshare.engine.NativeEngine
import com.example.proxyshare.service.ProxyController
import com.example.proxyshare.service.ProxyService

// Temporary home screen: Start/Stop buttons plus the live state text.
// Replaced by the real home screen in 0.1.5.
@Composable
fun HomeScreen() {
    val engineVersion = remember {
        runCatching { NativeEngine.nativeVersion() }.getOrNull()
    }
    val state by ProxyController.state.collectAsState()
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = when (val s = state) {
                    is EngineState.Running -> stringResource(R.string.proxy_running)
                    is EngineState.Starting -> stringResource(R.string.proxy_starting)
                    is EngineState.Error -> s.message.ifBlank {
                        stringResource(R.string.proxy_error)
                    }
                    else -> stringResource(R.string.proxy_stopped)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Button(
                    onClick = {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, ProxyService::class.java)
                        )
                    },
                    enabled = state is EngineState.Stopped || state is EngineState.Error,
                ) {
                    Text(stringResource(R.string.proxy_start))
                }
                OutlinedButton(
                    onClick = { ProxyController.stop() },
                    enabled = state !is EngineState.Stopped,
                ) {
                    Text(stringResource(R.string.proxy_stop))
                }
            }
            Text(
                text = engineVersion ?: stringResource(R.string.engine_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
