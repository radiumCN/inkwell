package com.radium.inkwell.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Inkwell 设计令牌（ui-ux-pro-max: Swiss Modernism + 暖纸白/石墨/书卷棕/琥珀）。
 * 原则：内容优先、少装饰；颜色一律走 MaterialTheme 语义令牌，不在页面里写裸 hex。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF78716C),          // 石墨暖灰：主操作
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEAE5DC),
    onPrimaryContainer = Color(0xFF2A2622),
    secondary = Color(0xFF92400E),        // 书卷棕：次级强调
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF4E3D0),
    onSecondaryContainer = Color(0xFF4A2405),
    tertiary = Color(0xFFD97706),         // 琥珀：CTA/进度
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFBEB),       // 暖纸白
    onBackground = Color(0xFF1C1917),
    surface = Color(0xFFFFFDF4),
    onSurface = Color(0xFF1C1917),
    surfaceVariant = Color(0xFFF1ECE1),
    onSurfaceVariant = Color(0xFF57534E),
    outline = Color(0xFF8C8680),
    outlineVariant = Color(0xFFE2DDD2),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBDB6AE),
    onPrimary = Color(0xFF2A2622),
    primaryContainer = Color(0xFF3B3733),
    onPrimaryContainer = Color(0xFFE8E2D9),
    secondary = Color(0xFFD79B66),
    onSecondary = Color(0xFF3B1F08),
    secondaryContainer = Color(0xFF54300F),
    onSecondaryContainer = Color(0xFFF4E3D0),
    tertiary = Color(0xFFE8A33D),
    onTertiary = Color(0xFF3D2703),
    background = Color(0xFF17140F),       // 暖黑
    onBackground = Color(0xFFE7E1D7),
    surface = Color(0xFF1D1A15),
    onSurface = Color(0xFFE7E1D7),
    surfaceVariant = Color(0xFF2A2620),
    onSurfaceVariant = Color(0xFFBBB4A9),
    outline = Color(0xFF867F75),
    outlineVariant = Color(0xFF3D3931),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
)

/** 统一圆角刻度：小控件 8，卡片/封面 12，大面板 16 */
private val InkwellShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun InkwellTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = InkwellShapes,
        content = content,
    )
}
