package com.example.proxyshare.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Fallback palette, used on Android 11 and below where dynamic color is
// unavailable. On Android 12+ the system dynamic palette wins.
private val LightPrimary = Color(0xFF00696B)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFF9DE7E6)
private val LightOnPrimaryContainer = Color(0xFF002020)
private val LightSecondary = Color(0xFF4A6363)
private val LightTertiary = Color(0xFF4B6076)
private val LightBackground = Color(0xFFFAFDFC)
private val LightSurface = Color(0xFFFAFDFC)

private val DarkPrimary = Color(0xFF4CDADA)
private val DarkOnPrimary = Color(0xFF003737)
private val DarkPrimaryContainer = Color(0xFF004F52)
private val DarkOnPrimaryContainer = Color(0xFF9DE7E6)
private val DarkSecondary = Color(0xFFB0CBCB)
private val DarkTertiary = Color(0xFFB4C4DC)
private val DarkBackground = Color(0xFF191C1C)
private val DarkSurface = Color(0xFF191C1C)

private val ProxyShareShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun ProxyShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = DarkPrimary,
            onPrimary = DarkOnPrimary,
            primaryContainer = DarkPrimaryContainer,
            onPrimaryContainer = DarkOnPrimaryContainer,
            secondary = DarkSecondary,
            tertiary = DarkTertiary,
            background = DarkBackground,
            surface = DarkSurface
        )
        else -> lightColorScheme(
            primary = LightPrimary,
            onPrimary = LightOnPrimary,
            primaryContainer = LightPrimaryContainer,
            onPrimaryContainer = LightOnPrimaryContainer,
            secondary = LightSecondary,
            tertiary = LightTertiary,
            background = LightBackground,
            surface = LightSurface
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ProxyShareShapes,
        content = content
    )
}
