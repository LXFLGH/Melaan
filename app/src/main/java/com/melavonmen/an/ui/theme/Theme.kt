package com.melavonmen.an.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    error = Error,
    onError = OnError
)

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color(0xFF2F0A02),
    primaryContainer = Color(0xFF7C2416),
    onPrimaryContainer = Color(0xFFFFDAD2),
    secondary = Color(0xFFFF9A7E),
    onSecondary = Color(0xFF351007),
    secondaryContainer = Color(0xFF663323),
    onSecondaryContainer = Color(0xFFFFDBD0),
    tertiary = Color(0xFFD5B9FF),
    onTertiary = Color(0xFF301A4F),
    tertiaryContainer = Color(0xFF482F68),
    onTertiaryContainer = Color(0xFFEBDDFF),
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = Color(0xFF414857),
    error = Error,
    onError = OnError,
)

@Composable
fun MelavonmenAnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
