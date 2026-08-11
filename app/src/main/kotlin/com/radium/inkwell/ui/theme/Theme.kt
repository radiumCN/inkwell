package com.radium.inkwell.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.animationsEnabled

/**
 * M3 形状刻度（含 Expressive Dialog / Sheet / Snackbar 用的 extraLarge）。
 * `extraLarge` 用 28dp —— 与 Material 3 `AlertDialogDefaults` / 大面板规范一致；
 * 从前 24dp 会让对话框看起来比官方 Expressive 样例「方」一截。
 *
 * 刻度值不变，换的是**角的画法**：全部走 [SquircleShape]（曲率连续的平滑圆角）而非
 * `RoundedCornerShape` 的「直边接圆弧」。半径读数一样，所以间距、对齐、既有的视觉重量都不动。
 *
 * 小半径那两档（4/8dp）肉眼几乎看不出差别，仍然一并换掉 —— 留着混用会让同一屏上出现两种角，
 * 而这类不一致恰恰是只在余光里察觉、却找不出原因的那种。
 */
private val InkwellShapes = Shapes(
    extraSmall = SquircleShape(4.dp),
    small = SquircleShape(8.dp),
    medium = SquircleShape(12.dp),
    large = SquircleShape(16.dp),
    extraLarge = SquircleShape(28.dp),
)

/**
 * 系统「移除动画」开启时顶替掉整套 [MotionScheme]：M3 组件内部动画（Sheet 的滑入、Switch 的
 * 拇指位移、Chip 的选中过渡……）全部拿到 0 时长的 spec，于是跟着一起静止。
 *
 * 从前这类框架内建动画是**逃出**无障碍约束的 —— `Motion.kt` 只管我们自己写的动画，组件内部
 * 没有公开定制口。M3 Expressive 把 `MotionScheme` 变成主题的一等参数，这个缺口才补上。
 *
 * 六个 spec 全给 `tween(0)`：spatial（位移/尺寸）与 effects（透明度/颜色）两类、快中慢三档，
 * 都是「立刻到位」。
 */
private object InstantMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = tween(0)
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = tween(0)
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = tween(0)
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(0)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(0)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(0)
}

/**
 * Inkwell 全局主题：按用户主题配置解析配色（模式 + 预设/自定义日夜主题）。
 * 页面颜色一律走 MaterialTheme 语义令牌。
 *
 * 用 [MaterialExpressiveTheme] 而非 `MaterialTheme`：它把 M3 Expressive 的组件默认值与
 * 动效方案打开（`LocalUsingExpressiveTheme`）。配色仍由 [AppThemes] 从「强调色 + 背景色」
 * 推导、圆角仍用 [InkwellShapes] —— Expressive 换的是组件形态与动效，不是我们的令牌体系。
 *
 * `motionScheme` **必须显式传**：它的默认值是 `null`，而 `null` 的含义是「沿用外层
 * `MaterialTheme` 的方案」，不是「填 expressive」—— 根节点没有外层，拿到的是
 * `MaterialTheme.Values` 的默认值 `MotionScheme.standard()`。省掉这个参数的结果是
 * 组件形态换成了 Expressive、动效还是 standard，编译和单测都看不出来。
 *
 * 注意：另一处主题入口 [com.radium.inkwell.ui.reader.ReaderThemeScope] 也必须是
 * Expressive 版，否则进阅读页会把这个开关重新关掉。
 *
 * 根上包一层 [Surface](color = background)：NavDisplay 的 shared-axis 旧页只让出 1/4，
 * 缝隙里没有别的 Compose 节点可画；若不铺底色，就会透到 Activity 的 windowBackground。
 * 以前那是纯白，深色主题下滑一下整条发白。
 */
@Composable
fun InkwellTheme(
    config: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit,
) {
    val (scheme, _) = AppThemes.resolve(config, systemDark = isSystemInDarkTheme())
    val animate = animationsEnabled()
    // remember 住实例：主题走的是 staticCompositionLocalOf，换实例会让全树重组。
    // 也别按 animate 拆成两个 MaterialExpressiveTheme 调用点 —— 那样用户在系统设置里切
    // 「移除动画」时子树换槽位重建，滚动位置、展开态之类全丢。
    val motion = remember(animate) {
        if (animate) MotionScheme.expressive() else InstantMotionScheme
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = motion,
        shapes = InkwellShapes,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.background,
            content = content,
        )
    }
}
