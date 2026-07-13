package com.radium.inkwell.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2B3A55),
    secondary = Color(0xFF8A6D3B),
    tertiary = Color(0xFF4E6E58),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8BEE0),
    secondary = Color(0xFFD8BE8A),
    tertiary = Color(0xFFA3C2AD),
)

@Composable
fun InkwellTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
