package com.example.proxyshare.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.proxyshare.engine.EngineState

/** The power button's visual state, mapped from [EngineState]. */
enum class PowerState { Stopped, Starting, Running, Error }

/** Map an engine state to the power button state. Pure logic, unit-tested. */
fun EngineState.toPowerState(): PowerState = when (this) {
    is EngineState.Stopped -> PowerState.Stopped
    is EngineState.Starting -> PowerState.Starting
    is EngineState.Running -> PowerState.Running
    is EngineState.Error -> PowerState.Error
}

/**
 * A large round power button (144dp). Running shows a soft pulsing
 * ring; starting shows a progress ring; error uses the error
 * container. The pulse and the progress ring are static when the
 * system "remove animations" setting is on (animator duration scale
 * 0). A light haptic plays on every tap.
 */
@Composable
fun PowerButton(state: PowerState, onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    // Read once: the setting does not change while the screen is open.
    val animationsEnabled = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
    // The infinite transition only exists while animations are on.
    val pulse = if (animationsEnabled && state == PowerState.Running) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
            label = "pulse",
        ).value
    } else {
        0f
    }
    val container = when (state) {
        PowerState.Running -> MaterialTheme.colorScheme.primary
        PowerState.Error -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (state) {
        PowerState.Running -> MaterialTheme.colorScheme.onPrimary
        PowerState.Error -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(modifier.size(176.dp), contentAlignment = Alignment.Center) {
        if (state == PowerState.Running && animationsEnabled) {
            Box(
                Modifier
                    .size(144.dp + 32.dp * pulse)
                    .graphicsLayer { alpha = 0.35f * (1f - pulse) }
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        if (state == PowerState.Starting) {
            if (animationsEnabled) {
                CircularProgressIndicator(
                    modifier = Modifier.size(176.dp),
                    color = content,
                    strokeWidth = 4.dp,
                )
            } else {
                Canvas(Modifier.size(176.dp)) {
                    val w = 4.dp.toPx()
                    drawArc(
                        content,
                        -90f,
                        270f,
                        false,
                        style = Stroke(width = w, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Surface(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            shape = CircleShape,
            color = container,
            modifier = Modifier
                .size(144.dp)
                .semantics { this.contentDescription = contentDescription },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(56.dp)) {
                    val w = 6.dp.toPx()
                    drawArc(content, -60f, 300f, false, style = Stroke(width = w, cap = StrokeCap.Round))
                    drawLine(
                        content,
                        Offset(size.width / 2, 0f),
                        Offset(size.width / 2, size.height * 0.45f),
                        strokeWidth = w,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
