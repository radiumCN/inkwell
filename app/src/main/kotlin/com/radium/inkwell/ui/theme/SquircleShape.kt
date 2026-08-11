package com.radium.inkwell.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * 圆角外扩倍率：角上那块「圆」占用的边长 = 半径 × 本值。
 *
 * 1.0 就退回普通圆弧（[androidx.compose.foundation.shape.RoundedCornerShape] 的效果）。
 * 取 1.1 的意思是**让转弯提前一成开始**：直边不再一路走到切点才猛地拐，而是早一点就开始弯，
 * 于是曲率是渐变上去的而非阶跃 —— 这就是「看着圆润但说不出哪里不一样」的来源。
 *
 * 再往上会明显发胖（2.0 时角几乎吃掉半条边），所以 [SquircleDefaults] 里按 1..2 夹住。
 */
private const val SQUIRCLE_EXTENSION = 1.1f

/**
 * 三次贝塞尔控制柄比例：控制点落在距角点 `tile × (1 - 本值)` 处。
 *
 * 0.643 不是随手取的圆弧近似值（那个数是 0.5523，即 kappa）。把控制柄收得比圆弧更短，
 * 曲线在接近直边的两端会更「贴」着边走、只在中段集中弯折，逼近真正超椭圆
 * `|x|^n + |y|^n = 1` 的轮廓。改这个数会直接改变胖瘦，别当成可调参数。
 */
private const val SQUIRCLE_CONTROL = 0.643f

/**
 * 平滑圆角（超椭圆 / squircle）——[CornerBasedShape] 的实现，可直接进 `MaterialTheme.shapes`。
 *
 * 与普通圆角的差别在**曲率连续性**：`RoundedCornerShape` 是直线段接圆弧，接缝处曲率从 0 突跳到
 * 1/r，眼睛读得出那个「硬接」；这里每个角用一条三次贝塞尔从直边平滑过渡，曲率是连着的。
 * 单看一个按钮几乎分辨不出，铺满一屏卡片和对话框之后差别就出来了。
 *
 * 参数（[SQUIRCLE_EXTENSION] / [SQUIRCLE_CONTROL]）取自 Apache-2.0 的
 * [Miuix](https://github.com/compose-miuix-ui/miuix)（`miuix-squircle` 的 `SquirclePath.kt`），
 * 它是社区对 HyperOS 形态的实现。这里是照着数值重写而非引依赖 —— Miuix 用 Kotlin 2.4 编译，
 * 而本项目卡在 2.3.21（KSP 没有 2.4.x，见 `libs.versions.toml`），根本消费不了。
 *
 * **必须是 [CornerBasedShape] 而不是裸 `Shape`**：M3 Expressive 的按钮/图标按钮按下时会做形状
 * 形变（`ButtonDefaults.shapes()`），那套动画靠 `copy()` 换角半径来插值；只实现 `Shape` 的话
 * 形变会整个失效，按下去就没有 Expressive 的手感了。
 *
 * 代价记一笔：产出的是 [Outline.Generic]（一条 Path），不是 `Outline.Rounded`。带阴影的
 * `Surface` / `Card` 要靠底层 `android.graphics.Outline.setPath` 投影，而那条路只接受**凸**路径。
 * 本形状是凸的，所以阴影应当正常 —— 但这是需要真机确认的一项，不是编译期能保证的事。
 */
class SquircleShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        if (topStart + topEnd + bottomEnd + bottomStart == 0f) {
            return Outline.Rectangle(size.toRect())
        }
        val isLtr = layoutDirection == LayoutDirection.Ltr
        // start/end 是书写方向相关的，落到几何上要先换成左右
        val topLeft = if (isLtr) topStart else topEnd
        val topRight = if (isLtr) topEnd else topStart
        val bottomRight = if (isLtr) bottomEnd else bottomStart
        val bottomLeft = if (isLtr) bottomStart else bottomEnd

        // 外扩之后每个角要吃掉 tile 这么长的边。父类只保证「半径」两两之和不超过短边，
        // 乘上 1.1 仍可能越界 —— 各自夹到短边的一半，最坏情况下相邻两块恰好相切而不交叠，
        // 交叠出来的是自相交路径，填充时会出现空洞。
        val limit = size.minDimension / 2f
        val tlTile = (topLeft * SQUIRCLE_EXTENSION).coerceIn(0f, limit)
        val trTile = (topRight * SQUIRCLE_EXTENSION).coerceIn(0f, limit)
        val brTile = (bottomRight * SQUIRCLE_EXTENSION).coerceIn(0f, limit)
        val blTile = (bottomLeft * SQUIRCLE_EXTENSION).coerceIn(0f, limit)

        val handle = 1f - SQUIRCLE_CONTROL
        val tlHandle = tlTile * handle
        val trHandle = trTile * handle
        val brHandle = brTile * handle
        val blHandle = blTile * handle

        val w = size.width
        val h = size.height

        val path = Path().apply {
            moveTo(tlTile, 0f)
            lineTo(w - trTile, 0f)
            cubicTo(w - trHandle, 0f, w, trHandle, w, trTile)
            lineTo(w, h - brTile)
            cubicTo(w, h - brHandle, w - brHandle, h, w - brTile, h)
            lineTo(blTile, h)
            cubicTo(blHandle, h, 0f, h - blHandle, 0f, h - blTile)
            lineTo(0f, tlTile)
            cubicTo(0f, tlHandle, tlHandle, 0f, tlTile, 0f)
            close()
        }
        return Outline.Generic(path)
    }

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): SquircleShape = SquircleShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SquircleShape) return false
        return topStart == other.topStart &&
            topEnd == other.topEnd &&
            bottomEnd == other.bottomEnd &&
            bottomStart == other.bottomStart
    }

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        return result
    }

    override fun toString(): String =
        "SquircleShape(topStart=$topStart, topEnd=$topEnd, bottomEnd=$bottomEnd, bottomStart=$bottomStart)"
}

/** 四角同尺寸的平滑圆角。 */
fun SquircleShape(size: Dp): SquircleShape = SquircleShape(CornerSize(size))

/** 四角同尺寸的平滑圆角。 */
fun SquircleShape(size: CornerSize): SquircleShape =
    SquircleShape(size, size, size, size)
