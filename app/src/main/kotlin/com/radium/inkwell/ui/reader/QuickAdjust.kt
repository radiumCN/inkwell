package com.radium.inkwell.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.ui.components.AppSurface
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.scrimEnter
import com.radium.inkwell.ui.components.scrimExit
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 不开菜单、直接在正文上调的那一下：双指缩放调字号、沿右边缘竖着滑调亮度。
 *
 * 阅读设置面板再顺手，也得「点开菜单 → 点设置 → 拖」三步；而读着读着觉得字小了、屋里暗了，是最常见的两件事。
 * 这两样在正文上一下就能调，效果当场就在整屏正文上（面板没盖住任何一行）。两个手势在「更多」里各有开关。
 */
@Immutable
sealed interface QuickAdjust {
    /** 正在双指缩放：当前字号（sp） */
    data class FontSize(val sp: Int) : QuickAdjust

    /** 正在沿右边缘滑：当前亮度（0~1，窗口亮度） */
    data class Brightness(val level: Float) : QuickAdjust
}

/**
 * 手势要读写的东西。由阅读页每次重组新建一份、经 rememberUpdatedState 交给 [quickAdjustGestures]：
 * 手势循环的 pointerInput 不以它为 key（调到一半重组不该把这一手拆掉），每次回调时取最新的那份。
 *
 * @param onFontSize / [onBrightness] 每过一档调一次，只预览不落盘（同面板滑块，见 ReaderViewModel.previewSettings）
 * @param onFinish 这一手结束（抬手、被系统收走）时调一次，落盘
 * @param onHud 浮层显示什么；null = 收起
 */
internal class QuickAdjustHandler(
    val fontSize: () -> Float,
    val brightness: () -> Float,
    val onFontSize: (Float) -> Unit,
    val onBrightness: (Float) -> Unit,
    val onFinish: () -> Unit,
    val onHud: (QuickAdjust?) -> Unit,
)

/**
 * 挂在翻页区的**父节点**上，在 Initial 阶段先于翻页、长按选字看到事件：认出是自己的手势就把事件消费掉，
 * 翻页容器见到「已消费」按取消收场（见 PageFlipContainer），滚动模式的 scrollable 也会让出。
 * 没认出来之前一概不消费 —— 单指点按、横划翻页、长按选字照旧。
 *
 * - **双指缩放**（[pinch]）：第二根手指一落下就接手。字号 = 起手字号 × 两指间距之比，四舍五入到整数、夹在上下限里；
 *   每过一档预览一次（重排一次正文）。放开一根手指就结束，剩下那根抬起前什么也不做。
 * - **右边缘调亮度**（[edge]）：落点在右边缘一个触控目标宽的带子里、竖着滑过 touch slop（竖向位移是横向的两倍以上）才接手；
 *   横着划仍是翻页。往上亮、往下暗，滑过 [EDGE_FULL_RANGE] 屏高走完整个范围。
 *   系统返回手势也从边缘起手，但它是横着往里划，和竖着滑分得开；被系统收走时按结束处理。
 *
 * 滚动翻页模式由调用方传 edge = false：上下滑在那里就是翻页。
 */
internal fun Modifier.quickAdjustGestures(
    enabled: Boolean,
    pinch: Boolean,
    edge: Boolean,
    handler: State<QuickAdjustHandler>,
): Modifier {
    if (!enabled || (!pinch && !edge)) return this
    return pointerInput(pinch, edge) {
        val edgeZone = Dimens.touchTarget.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val fromEdge = edge && down.position.x >= size.width - edgeZone
            var mode = Mode.Watching
            var startDistance = 1f
            var startSize = 0f
            var lastSize = 0
            var startLevel = 0f
            var startY = 0f
            var lastLevel = -1f
            var finished = false
            fun finish() {
                if (finished || mode == Mode.Watching) return
                finished = true
                handler.value.onHud(null)
                handler.value.onFinish()
            }
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    when (mode) {
                        Mode.Watching -> {
                            if (pinch && pressed.size >= 2) {
                                mode = Mode.Pinch
                                startDistance = distance(pressed[0], pressed[1]).coerceAtLeast(1f)
                                startSize = handler.value.fontSize()
                                lastSize = startSize.roundToInt()
                                handler.value.onHud(QuickAdjust.FontSize(lastSize))
                                event.changes.forEach { it.consume() }
                            } else if (fromEdge && pressed.size == 1) {
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val d: Offset = change.position - down.position
                                when {
                                    abs(d.y) > slop && abs(d.y) > abs(d.x) * 2f -> {
                                        mode = Mode.Edge
                                        startLevel = handler.value.brightness()
                                        startY = change.position.y
                                        lastLevel = startLevel
                                        handler.value.onHud(QuickAdjust.Brightness(startLevel))
                                        change.consume()
                                    }
                                    // 先横着走出去了：是翻页，这一手不再看（双指也不接 —— 别半路把翻页掐了）
                                    abs(d.x) > slop -> return@awaitEachGesture
                                }
                            } else if (!fromEdge && !pinch) {
                                return@awaitEachGesture
                            }
                        }

                        Mode.Pinch -> {
                            event.changes.forEach { it.consume() }
                            if (finished) continue
                            if (pressed.size < 2) {
                                finish()
                                continue
                            }
                            val target = pinchFontSize(startSize, distance(pressed[0], pressed[1]) / startDistance)
                            if (target != lastSize) {
                                lastSize = target
                                handler.value.onFontSize(target.toFloat())
                                handler.value.onHud(QuickAdjust.FontSize(target))
                            }
                        }

                        Mode.Edge -> {
                            event.changes.forEach { it.consume() }
                            if (finished) continue
                            // 起手那根手指抬了（另一根还按着）：这一手到此为止
                            val change = pressed.firstOrNull { it.id == down.id }
                            if (change == null) {
                                finish()
                                continue
                            }
                            val level = edgeBrightness(startLevel, change.position.y - startY, size.height.toFloat())
                            if (abs(level - lastLevel) >= BRIGHTNESS_STEP) {
                                lastLevel = level
                                handler.value.onBrightness(level)
                                handler.value.onHud(QuickAdjust.Brightness(level))
                            }
                        }
                    }
                }
            } finally {
                // 抬手、系统收走（合成的抬手）、pointerInput 被掐（菜单弹出、换页模式）都走这里：浮层别晾着，调到的值也落盘
                finish()
            }
        }
    }
}

private enum class Mode { Watching, Pinch, Edge }

private fun distance(a: PointerInputChange, b: PointerInputChange): Float = (a.position - b.position).getDistance()

/** 双指缩放后的字号：起手字号 × 两指间距之比，取整、夹在上下限里 */
internal fun pinchFontSize(startSp: Float, scale: Float): Int =
    (startSp * scale).roundToInt()
        .coerceIn(ReaderSettings.FONT_SIZE_MIN.toInt(), ReaderSettings.FONT_SIZE_MAX.toInt())

/**
 * 右边缘竖滑后的亮度。[dy] 是相对起手点的竖向位移（往下为正）：往上亮、往下暗，滑过 [EDGE_FULL_RANGE] 屏高走完 0~1。
 * 下限 0.01 而不是 0：窗口亮度给 0 在一些机型上是「熄屏级」的黑，和面板滑轨同一个下限
 */
internal fun edgeBrightness(start: Float, dy: Float, height: Float): Float {
    if (height <= 0f) return start
    return (start - dy / (height * EDGE_FULL_RANGE)).coerceIn(MIN_BRIGHTNESS, 1f)
}

/** 从屏幕一端滑到另一端的多少算走完整个亮度范围 */
private const val EDGE_FULL_RANGE = 0.6f

/** 亮度变化小于这一格就不刷新（窗口亮度每改一次都要走一趟 WindowManager） */
private const val BRIGHTNESS_STEP = 0.01f

internal const val MIN_BRIGHTNESS = 0.01f

/** 读不到系统亮度时的起步值（面板滑轨、右边缘调亮度共用） */
internal const val HALF_BRIGHTNESS = 0.5f

/**
 * 系统此刻的屏幕亮度，折成窗口亮度那个 0~1。跟随系统时，面板滑轨和右边缘调亮度都从这儿起步 ——
 * 否则一上手就从系统亮度跳到一半亮度，夜里是一道刺眼的闪。
 *
 * 系统设置里存的是整数（多数机型 0~255，部分厂商更大），上限读框架的 `config_screenBrightnessSettingMaximum`
 * （没有公开 API，只能按名字查）。读不到、或读到的数不在 0~上限里时返回 null，由调用方退回一半亮度。
 * 整数和窗口亮度之间两头都是线性映射到同一段亮度上，这里的换算是近似，起步不跳就够了。
 */
@SuppressLint("DiscouragedApi")
internal fun systemBrightnessLevel(context: Context): Float? = runCatching {
    val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    val res = Resources.getSystem()
    val id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
    val max = if (id != 0) res.getInteger(id) else DEFAULT_BRIGHTNESS_MAX
    if (max <= 0 || raw < 0 || raw > max) null else (raw.toFloat() / max).coerceIn(MIN_BRIGHTNESS, 1f)
}.getOrNull()

private const val DEFAULT_BRIGHTNESS_MAX = 255

/**
 * 手势调到的读数：字号是顶上居中的一颗胶囊「字号 22」（不放在中间 —— 两根手指就在那儿，挡住了）；
 * 亮度是靠右的一根竖条，离右边缘让开一个手指宽，拇指不会盖住它。都跟着纸走（放在 ReaderThemeScope 里）。
 * 读屏：变化时念出来（liveRegion），手势本身读屏用不上，但万一开着读屏也不至于一声不吭。
 */
@Composable
internal fun BoxScope.QuickAdjustOverlay(adjust: QuickAdjust?) {
    // 退场动画播放期间 adjust 已经是 null，留住最后一次的读数
    var last by remember { mutableStateOf<QuickAdjust?>(null) }
    if (adjust != null) last = adjust
    AnimatedVisibility(
        visible = adjust is QuickAdjust.FontSize,
        enter = scrimEnter(),
        exit = scrimExit(),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = Dimens.gapXXL),
    ) {
        val sp = (last as? QuickAdjust.FontSize)?.sp ?: return@AnimatedVisibility
        AppSurface(
            color = readerOverlayColor(),
            cornerRadius = AppShapes.radiusLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                "字号 $sp",
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = Dimens.gapL, vertical = Dimens.gapS),
            )
        }
    }
    AnimatedVisibility(
        visible = adjust is QuickAdjust.Brightness,
        enter = scrimEnter(),
        exit = scrimExit(),
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = Dimens.touchTarget + Dimens.gapS),
    ) {
        val level = (last as? QuickAdjust.Brightness)?.level ?: return@AnimatedVisibility
        val scheme = MiuixTheme.colorScheme
        Box(
            Modifier
                .width(Dimens.touchTarget)
                .height(Dimens.brightnessHudHeight)
                .squircleBackground(readerOverlayColor(), AppShapes.radiusLarge)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(level)
                    .squircleBackground(scheme.primary, AppShapes.radiusLarge),
            )
            Text(
                "${(level * 100).roundToInt()}",
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                color = if (level > HUD_LABEL_FLIP) scheme.onPrimary else scheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Dimens.gapS),
            )
        }
    }
}

/** 竖条填过这么高时，底部的数字落在填色上，换成填色上的字色 */
private const val HUD_LABEL_FLIP = 0.12f
