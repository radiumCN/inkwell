package com.radium.inkwell.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.radium.inkwell.ui.theme.AppThemes
import com.radium.inkwell.ui.theme.hsvToColor
import com.radium.inkwell.ui.theme.toHsv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.radium.inkwell.ui.theme.AppTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 从封面像素里挑一个强调色。整张图几乎没有颜色（黑白封面、灰底默认封面、纯文字封面）就给 null，
 * 详情页照 App 主题走 —— 硬从灰图里挑出来的「强调色」是一种脏灰。
 *
 * 不取平均色：封面的平均色几乎总是泥褐色。按色相分 12 桶，每个像素按「饱和度 × 明度」加权投票，
 * 取得票最多的那一桶、再取桶内的加权平均 —— 也就是封面上「最鲜明、面积又不小」的那种颜色。
 */
internal fun pickCoverSeed(pixels: IntArray): Int? {
    if (pixels.isEmpty()) return null
    val weight = FloatArray(HUE_BUCKETS)
    val red = FloatArray(HUE_BUCKETS)
    val green = FloatArray(HUE_BUCKETS)
    val blue = FloatArray(HUE_BUCKETS)
    for (p in pixels) {
        if ((p ushr 24) < 128) continue
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val s = if (max == 0f) 0f else delta / max
        // 太暗的像素色相不可靠；低饱和的是灰
        if (max < MIN_VALUE || s < MIN_SATURATION) continue
        val h = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val bucket = (h / (360f / HUE_BUCKETS)).toInt().coerceIn(0, HUE_BUCKETS - 1)
        val w = s * max
        weight[bucket] += w
        red[bucket] += r * w
        green[bucket] += g * w
        blue[bucket] += b * w
    }
    val best = weight.indices.maxBy { weight[it] }
    // 有颜色的部分太小（一张灰封面上一行红字）就不算：那点颜色撑不起一整页
    if (weight[best] < pixels.size * MIN_COLORED_SHARE) return null
    val w = weight[best]
    fun channel(sum: Float) = (sum / w * 255f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(red[best]) shl 16) or (channel(green[best]) shl 8) or channel(blue[best])
}

/**
 * 把封面色压进能当强调色用的范围。强调色在页面上要当字色用（「定位当前章」这类文字按钮），
 * 浅色主题上太亮的黄、深色主题上太暗的蓝都读不清：浅色压低明度、深色抬高明度，饱和度掐掉两头。
 */
internal fun seedForTheme(seed: Color, dark: Boolean): Color {
    val (h, s, v) = seed.toHsv()
    return if (dark) {
        hsvToColor(h, s.coerceIn(0.25f, 0.7f), v.coerceIn(0.6f, 0.9f))
    } else {
        hsvToColor(h, s.coerceIn(0.35f, 0.85f), v.coerceIn(0.3f, 0.5f))
    }
}

/** 封面的强调色；还没取到 / 没有封面 / 封面是灰的都是 null */
@Composable
internal fun rememberCoverSeed(coverModel: Any?): Color? {
    val context = LocalPlatformContext.current
    var seed by remember(coverModel) { mutableStateOf<Color?>(null) }
    LaunchedEffect(coverModel) {
        if (coverModel == null) return@LaunchedEffect
        // 取色只要几十个像素：按小尺寸解码，别为了一个颜色把整张封面解进内存。
        // 软件位图才能 getPixels（硬件位图读像素会抛）
        val request = ImageRequest.Builder(context)
            .data(coverModel)
            .size(SAMPLE_PX)
            .allowHardware(false)
            .build()
        val bitmap = try {
            (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        seed = withContext(Dispatchers.Default) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pickCoverSeed(pixels)?.let(::Color)
        }
    }
    return seed
}

/**
 * 用封面色给这一页换强调色：primary 一族（按钮、选中章节、文字按钮、标题栏渐变）跟着封面走，
 * 底色、卡片、文字这些 surface 一族**原样沿用** App 主题 —— 换掉的话，从书架点进来整页底色一变，
 * 像进了另一个 App。
 *
 * 局部换的是 [MiuixTheme]（colors 版）。动效方案不在 MiuixTheme 里，外层的照常透传，「移除动画」不受影响。
 *
 * 不论有没有封面色都包这一层：取色是异步的，若「没取到时不包、取到了再包」，取到的那一帧整页换一层
 * 组合 —— 目录滚动位置之类的状态全丢。颜色到了用 effects 令牌渐变过去，不是一帧之内跳色。
 *
 * remember 的 key 是外层的**槽位值**而不是 Colors 对象本身：MiuixTheme 只持有一个 Colors 实例、换主题时原地改值，
 * 拿对象当 key 永远不失效 —— 切了日夜，这一页的强调色还停在上一套主题推出来的那个。
 */
@Composable
internal fun CoverTintedTheme(seed: Color?, content: @Composable () -> Unit) {
    val base = MiuixTheme.colorScheme
    val card = base.surfaceContainer
    val dark = base.surface.luminance() < 0.5f
    val target = remember(seed, card, dark) {
        seed?.let { AppThemes.schemeFrom(seedForTheme(it, dark), card, dark) }
    }
    val spec = AppTheme.motionScheme.defaultEffectsSpec<Color>()
    val primary by animateColorAsState(target?.primary ?: base.primary, spec, label = "coverPrimary")
    val onPrimary by animateColorAsState(target?.onPrimary ?: base.onPrimary, spec, label = "coverOnPrimary")
    val container by animateColorAsState(target?.primaryContainer ?: base.primaryContainer, spec, label = "coverContainer")
    val onContainer by animateColorAsState(target?.onPrimaryContainer ?: base.onPrimaryContainer, spec, label = "coverOnContainer")
    val tertiary by animateColorAsState(target?.tertiaryContainer ?: base.tertiaryContainer, spec, label = "coverTertiary")
    val onTertiary by animateColorAsState(target?.onTertiaryContainer ?: base.onTertiaryContainer, spec, label = "coverOnTertiary")
    MiuixTheme(
        colors = base.copy(
            primary = primary,
            onPrimary = onPrimary,
            primaryVariant = primary,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            tertiaryContainer = tertiary,
            onTertiaryContainer = onTertiary,
            tertiaryContainerVariant = tertiary,
            disabledPrimary = target?.disabledPrimary ?: base.disabledPrimary,
            disabledOnPrimary = target?.disabledOnPrimary ?: base.disabledOnPrimary,
            disabledPrimaryButton = target?.disabledPrimaryButton ?: base.disabledPrimaryButton,
            disabledOnPrimaryButton = target?.disabledOnPrimaryButton ?: base.disabledOnPrimaryButton,
            disabledPrimarySlider = target?.disabledPrimarySlider ?: base.disabledPrimarySlider,
            sliderKeyPoint = primary.copy(alpha = 0.3f),
            sliderKeyPointForeground = primary,
        ),
        textStyles = MiuixTheme.textStyles,
        content = content,
    )
}

private const val HUE_BUCKETS = 12
private const val MIN_VALUE = 0.15f
private const val MIN_SATURATION = 0.2f
/** 「有颜色」的像素按权重至少要占这么多，才算这张封面有主色 */
private const val MIN_COLORED_SHARE = 0.04f
private const val SAMPLE_PX = 32
