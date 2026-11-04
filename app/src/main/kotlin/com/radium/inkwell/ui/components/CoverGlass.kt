package com.radium.inkwell.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.ui.theme.AppTheme
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.rememberTiltLight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 封面毛玻璃卡：这本书的封面放大铺满整张卡，上面盖一层 HyperOS 的玻璃。书架「继续阅读」、设置页概览、
 * 阅读记录「今天」三张卡用它 —— 一页上唯一带颜色的面，颜色就是这本书自己的。
 *
 * 一层一层往上（Miuix 示例 App 模糊页「Texture Blur」那一节的做法）：
 * 1. **封面**：[BookCover] 裁切铺满，录进这张卡自己的图层。没封面 / 抓不下来时是按书名取色的默认封面，
 *    糊开是一整片那个颜色，每本书照样有自己的色。
 * 2. **玻璃**：整片一样糊（[CARD_BLUR_RADIUS]，示例「Texture Blur」的 100），**不渐变**。混色用示例的
 *    Colored Thick，边缘高光用它的 Small Container 那档。0.5.1-beta.2 之前这里是渐进模糊（顶上糊、底边透出
 *    封面原图），被指出「这个卡片不需要渐变，直接用模糊」—— 渐变只给顶栏和详情页头部。
 * 3. **薄底**：一层卡片色（[coverGlassVeil]）。糊得再狠，一张几乎纯黑 / 纯白的封面糊开还是那个颜色，
 *    压上 Colored Thick 也只是提亮或压暗一点：深色封面上的黑字、白封面上的白字照样读不清。
 *    字永远压在「封面 + 至少这么多卡片色」上；对比度在 CoverGlassTest 里按最坏的封面
 *    （浅色主题纯黑、深色主题纯白）钉住 ≥ 4.5:1。
 *
 * 字色是主题的 onSurface（经 [LocalContentColor] 给下去，卡里的字读它）：薄底就是卡片色，字也就是卡片上的字。
 * 层级靠字号拉开，别在卡里用 onSurfaceVariantSummary —— 它是给不透明的卡片底算的，压在半透明的玻璃上对比度没保证。
 *
 * [coverTitle] 为 null（设置页、阅读记录里还没读过任何书）时退回实色卡：primaryContainer 底、onPrimaryContainer 字，
 * 也就是这三张卡换玻璃之前的样子。**还在查是哪本书**时传 [pending]：先画成底下没有封面的玻璃（看上去就是一张普通的卡），
 * 查到了封面淡进来；查完了确实没书才换实色卡。不分这一步的话，每次进页面卡片先按「没书」画成实色卡、
 * 下一帧再变成封面玻璃，闪一下。
 *
 * 圆角是与 Miuix 卡片同档的 squircle：外面 squircleClip 裁封面与按压反馈，玻璃本身给同一条曲线的 [squircleShape] ——
 * 边缘高光按 shape 报的圆角半径画，给直角的话高光在四个角被裁掉一截，给认不出半径的 Shape 则多出一个椭圆（见 squircleShape）。
 *
 * @param tiltHighlight 边缘高光跟着手机倾斜移动（Miuix `rememberTiltLight`，陀螺仪驱动，示例 App 的 Texture Blur
 *   默认就开着）。只给书架「继续阅读」这一张开：它是首页最显眼的一块，其余两张在设置里，不值得为它们开传感器。
 *   监听跟着生命周期走（退到后台就停）；系统关了动画时高光不动 —— 那个开关的意思就是「别让界面自己动」
 */
@Composable
fun CoverGlassCard(
    coverTitle: String?,
    coverModel: Any?,
    modifier: Modifier = Modifier,
    pending: Boolean = false,
    tiltHighlight: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    if (coverTitle == null && !pending) {
        Box(modifier.squircleSurface(scheme.primaryContainer, AppShapes.radiusLarge)) {
            CompositionLocalProvider(LocalContentColor provides scheme.onPrimaryContainer, content = content)
        }
        return
    }
    val card = scheme.surfaceContainer
    val dark = scheme.surface.luminance() < 0.5f
    // 录下来的封面四角是透明的（BookCover 自己切了小圆角）：先垫卡片色，模糊时才不往里渗黑边
    val backdrop = rememberLayerBackdrop {
        drawRect(card)
        drawContent()
    }
    val colors = BlurDefaults.blurColors(blendColors = if (dark) ColoredThickDark else ColoredThickLight)
    val shape = remember { squircleShape(AppShapes.radiusLarge) }
    // 一上来就有书（书架）时初值即终值，不淡；从 pending 等到书的才淡进来
    val reveal by animateFloatAsState(
        targetValue = if (coverTitle != null) 1f else 0f,
        animationSpec = AppTheme.motionScheme.defaultEffectsSpec(),
        label = "coverReveal",
    )
    Box(modifier.squircleClip(AppShapes.radiusLarge)) {
        Box(Modifier.matchParentSize().layerBackdrop(backdrop)) {
            if (coverTitle != null) {
                BookCover(
                    title = coverTitle,
                    coverModel = coverModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = reveal },
                    // 默认封面上那行书名放大铺满整张卡没有意义，只要它的底色
                    placeholderChars = 0,
                    contentDescription = null,
                )
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .textureBlur(
                    backdrop = backdrop,
                    shape = shape,
                    blurRadius = CARD_BLUR_RADIUS,
                    colors = colors,
                    highlight = if (tiltHighlight && animationsEnabled()) rememberTiltHighlight(dark) else smallGlassHighlight(dark),
                )
                // 薄底画在玻璃「里面」（玻璃节点的内容）：边缘高光画在内容之上，不会被这层底盖暗
                .background(coverGlassVeil(card, dark)),
        )
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

/**
 * 书籍详情页头部的底：封面放大铺满，渐进模糊（顶上最糊、往下渐清 —— 示例 App「Progressive Blur」那一节的
 * Top、半径 20、不混色），
 * 再盖一道从 [coverHeaderVeil] 到页面底色的竖向渐变：封面的颜色从顶栏底下流下来，到目录开始的地方化进页面底，
 * 与下面的目录之间不留边。
 *
 * 头部的字有好几种颜色（书名 onSurface、作者与章数 onSurfaceVariantSummary、简介 onSurfaceSecondary），
 * 所以渐变的起点比卡片的薄底厚：最淡的那种小字压在最坏的封面上也够 4.5:1（CoverGlassTest）。
 * 起点就在最顶上（顶栏底下），往下只会更厚，字所在的地方都不比它薄。
 */
@Composable
fun CoverHeaderBackground(title: String, coverModel: Any?, modifier: Modifier = Modifier) {
    val page = MiuixTheme.colorScheme.surface
    val dark = page.luminance() < 0.5f
    val backdrop = rememberLayerBackdrop {
        drawRect(page)
        drawContent()
    }
    val veil = coverHeaderVeil(page, dark)
    Box(modifier) {
        Box(Modifier.matchParentSize().layerBackdrop(backdrop)) {
            BookCover(
                title = title,
                coverModel = coverModel,
                modifier = Modifier.fillMaxSize(),
                placeholderChars = 0,
                contentDescription = null,
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .progressiveTextureBlur(
                    backdrop = backdrop,
                    shape = RectangleShape,
                    blurRadius = HEADER_BLUR_RADIUS,
                    gradient = ProgressiveBlur.Top,
                )
                .background(Brush.verticalGradient(listOf(veil, page))),
        )
    }
}

/**
 * 示例 App 的 Small Container 那档玻璃边缘高光：卡片、浮起来的药丸、提示胶囊这类小面都用这一档。
 * [dark] 是**玻璃本身**深不深（深色主题、或浅色主题里的深色胶囊），不是 App 在不在深色模式
 */
internal fun smallGlassHighlight(dark: Boolean): Highlight =
    if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight

/**
 * 同一档高光，两路光的位置跟着手机倾斜挪（Miuix 文档「传感器驱动的视差」的写法与灵敏度）。
 * 预设的 style 不是 BloomStroke（库将来换了实现）时原样返回，不动。
 */
@Composable
private fun rememberTiltHighlight(dark: Boolean): Highlight {
    val base = smallGlassHighlight(dark)
    val style = base.style as? BloomStroke ?: return base
    val primary = rememberTiltLight(
        basePosition = style.primaryLight.position,
        color = style.primaryLight.color,
        intensity = style.primaryLight.intensity,
        sensitivity = 0.15f,
    )
    val secondary = rememberTiltLight(
        basePosition = style.secondaryLight.position,
        color = style.secondaryLight.color,
        intensity = style.secondaryLight.intensity,
        sensitivity = 0.12f,
    )
    return base.copy(style = style.copy(primaryLight = primary, secondaryLight = secondary))
}

/** 封面玻璃卡上那层卡片色（为什么要有它见 [CoverGlassCard]） */
internal fun coverGlassVeil(card: Color, dark: Boolean): Color =
    card.copy(alpha = if (dark) CARD_VEIL_DARK else CARD_VEIL_LIGHT)

/** 详情页头部渐变的起点（见 [CoverHeaderBackground]） */
internal fun coverHeaderVeil(page: Color, dark: Boolean): Color =
    page.copy(alpha = if (dark) HEADER_VEIL_DARK else HEADER_VEIL_LIGHT)

/** dp（miuix-blur 按 dp 收半径，上限 150）。卡片小、字压在上面，糊得狠；示例「Texture Blur」默认就是 100 */
private const val CARD_BLUR_RADIUS = 100f

/**
 * 示例「Progressive Blur」的 20。头部比卡片大好几倍、封面放大到整屏宽，半径小也认不出字和细节，
 * 只剩大块的明暗；往下渐清时是「封面慢慢显出来」。
 */
private const val HEADER_BLUR_RADIUS = 20f

// 薄底的厚度：CoverGlassTest 对所有预设和几组极端自定义配色算「最难的那套最少要多厚」，这里是往上取整后的值，
// 改薄了测试会直接报出要多少。深色主题的字是浅色、底是深色，同样压在最坏的（纯白）封面上要更厚一层才压得住；
// 头部还有半透明的摘要小字，比卡片（只有 onSurface）又厚一档
internal const val CARD_VEIL_LIGHT = 0.55f
internal const val CARD_VEIL_DARK = 0.75f
internal const val HEADER_VEIL_LIGHT = 0.8f
internal const val HEADER_VEIL_DARK = 0.85f

// Miuix 示例 App 的 Colored Thick 混色（example/.../component/blend/ColorBlendToken.kt，Apache-2.0）。
// 库里只有混合模式，没有这几组成品参数，照抄过来
private val ColoredThickLight = listOf(
    BlendColorEntry(Color(0xE6BDBDBD), BlurBlendMode.Overlay),
    BlendColorEntry(Color(0x992B2B2B), BlurBlendMode.ColorDodge),
    BlendColorEntry(Color(0x339C9C9C), BlurBlendMode.SrcOver),
)
private val ColoredThickDark = listOf(
    BlendColorEntry(Color(0x667A7A7A), BlurBlendMode.ColorBurn),
    BlendColorEntry(Color(0x33747474), BlurBlendMode.Overlay),
    BlendColorEntry(Color(0x322B2B2B), BlurBlendMode.SrcOver),
)
