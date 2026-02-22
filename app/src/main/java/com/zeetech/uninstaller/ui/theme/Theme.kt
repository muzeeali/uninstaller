package com.zeetech.uninstaller.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldGreen,
    secondary = LogoPurple,
    background = DarkBackground,
    surface = DarkBackground,
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    error = DestructiveRed
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldGreen,
    secondary = LogoPurple,
    background = LightBackground,
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Charcoal,
    onSurface = Charcoal,
    error = DestructiveRed
)

@Composable
fun UninstallerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Set dynamicColor to false to maintain the premium custom brand look
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
