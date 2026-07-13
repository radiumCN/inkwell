package com.radium.inkwell.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** 统一圆角刻度：小控件 8，卡片/封面 12，大面板 16 */
private val InkwellShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * Inkwell 全局主题：按用户主题配置解析配色（模式 + 预设/自定义日夜主题）。
 * 页面颜色一律走 MaterialTheme 语义令牌。
 */
@Composable
fun InkwellTheme(
    config: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit,
) {
    val (scheme, _) = AppThemes.resolve(config, systemDark = isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = scheme,
        shapes = InkwellShapes,
        content = content,
    )
}
