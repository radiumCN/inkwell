package com.radium.inkwell.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Miuix 主题**没有**的两样东西：圆角刻度与动效方案。颜色和字体直接读 Miuix 自己的
 * `MiuixTheme.colorScheme` / `MiuixTheme.textStyles`，别在这里再包一层别名 —— 两套入口
 * 迟早一边改了另一边没跟上。
 *
 * - [shapes]：圆角刻度。静态的，不随主题变；
 * - [motionScheme]：组件与浮层动效的唯一来源，系统「移除动画」时整套换成 0 时长的一份。
 */
object AppTheme {
    val shapes: AppShapes get() = AppShapes

    val motionScheme: AppMotionScheme
        @Composable @ReadOnlyComposable
        get() = LocalMotionScheme.current
}

/**
 * 圆角刻度。Miuix 组件自己按 Dp 收圆角（`cornerRadius = 16.dp` 这类参数），没有主题级的 Shapes，
 * 所以这里同时给两种形态：Shape（`clip` / `background` / `border` 用；类型是 RoundedCornerShape，能 `copy` 出切角版本）与 Dp 半径
 * （Miuix 组件的 `cornerRadius` 参数、`Modifier.squircleClip` / `squircleBackground` 用）。
 *
 * 卡片、列表行、按钮这类「面」优先走 squircle（HyperOS 的连续曲率圆角，API 33 以下自动退回普通圆角）；
 * 小尺寸的标签、缩略图用普通圆角就够，连续曲率在 4~8dp 上看不出来。
 *
 * 刻度与 M3 时代一致：4 / 8 / 12 / 16 / 28。16 对齐 Miuix 卡片（`CardDefaults.CornerRadius`），
 * 28 对齐 Miuix 底部面板（`BottomSheetDefaults.cornerRadius`）。
 */
object AppShapes {
    val radiusExtraSmall: Dp = 4.dp
    val radiusSmall: Dp = 8.dp
    val radiusMedium: Dp = 12.dp
    val radiusLarge: Dp = 16.dp
    val radiusExtraLarge: Dp = 28.dp

    val extraSmall: RoundedCornerShape = RoundedCornerShape(radiusExtraSmall)
    val small: RoundedCornerShape = RoundedCornerShape(radiusSmall)
    val medium: RoundedCornerShape = RoundedCornerShape(radiusMedium)
    val large: RoundedCornerShape = RoundedCornerShape(radiusLarge)
    val extraLarge: RoundedCornerShape = RoundedCornerShape(radiusExtraLarge)
}

/**
 * 动效方案：spatial（位移/尺寸）与 effects（alpha/颜色）两类、快中慢三档。
 *
 * 接口照搬 M3 的 `MotionScheme`，迁到 Miuix 时调用点只换了接收者（`AppTheme.motionScheme`）。
 * 分工照旧，别混：位移用 spatial、纯视觉属性用 effects；用 effects 做位移会发木，
 * 用 spatial 做淡入会让透明度过冲，看着像闪了一下。
 *
 * 只管**我们自己写**的动画（顶栏/底栏/蒙层/展开帮手、Tab 内容切换、封面淡入）。Miuix 组件内部的动画
 * （开关拇指、弹窗滑入、按压缩放）是它自己的 spring，不读这里 —— 系统「移除动画」时那一部分由框架的
 * `MotionDurationScale` 管。
 */
@Stable
interface AppMotionScheme {
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T>
    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T>
    fun <T> slowSpatialSpec(): FiniteAnimationSpec<T>
    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T>
    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T>
    fun <T> slowEffectsSpec(): FiniteAnimationSpec<T>

    companion object {
        /**
         * 默认方案。spatial 取 M3 **standard** 那组（阻尼 0.9，几乎不回弹），不是 Expressive 的 0.6~0.8：
         * HyperOS 的动效是「快、稳、落定干脆」，Miuix 自己的组件也基本不过冲；
         * 我们的帮手若还带 Expressive 那点回弹，同一屏上就是两种手感。
         * effects 一律不回弹（阻尼 1），刚度与 M3 一致。
         */
        val Default: AppMotionScheme = object : AppMotionScheme {
            override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(0.9f, 700f)
            override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spring(0.9f, 1400f)
            override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spring(0.9f, 300f)
            override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, 1600f)
            override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, 3800f)
            override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, 800f)
        }

        /**
         * 系统「移除动画」时顶替整套方案：六个 spec 全 `tween(0)`，读令牌的动画一律立刻到位。
         * 在 [com.radium.inkwell.ui.theme.InkwellTheme] 里按开关切换，帮手里不必再判 animationsEnabled()。
         */
        val Instant: AppMotionScheme = object : AppMotionScheme {
            override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = tween(0)
            override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = tween(0)
            override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = tween(0)
            override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(0)
            override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(0)
            override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(0)
        }
    }
}

/**
 * 默认给 [AppMotionScheme.Default] 而不是报错：Compose 预览、单个组件的截图测试里没有 InkwellTheme，
 * 有动效总比崩掉好。正式界面一律经 InkwellTheme 提供。
 */
internal val LocalMotionScheme = staticCompositionLocalOf { AppMotionScheme.Default }
