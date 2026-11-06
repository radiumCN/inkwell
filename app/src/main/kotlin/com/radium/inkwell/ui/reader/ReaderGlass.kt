package com.radium.inkwell.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.radium.inkwell.ui.theme.AppThemes
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * 阅读菜单的毛玻璃底（HyperOS 的玻璃栏）：对正文取样模糊，再叠一层八成的 [tint]。
 *
 * 正文是 ReaderScreen 录进 [backdrop] 的翻页区 —— 页面全是 Compose 自己画的（PageCanvas、仿真页的位图
 * 也画在 Compose 画布上），图层录得下来；菜单与面板都在录制区**外面**，不会自己糊自己。
 * 叠的颜色仍是浮层色（[readerOverlayColor]，比纸深 / 亮一档）：模糊只让纸上的字隐约透出来，
 * 「盖在纸上的一层」靠的还是那一档色阶和圆角，不是阴影或压暗（理由见 ReaderMenu）。
 *
 * 叠色 80% 与 App 顶栏一致，**模糊半径却大得多**（[READER_GLASS_BLUR_RADIUS]，App 顶栏是 25dp）：
 * App 顶栏底下是滚过去的列表，一片一片的内容糊开是均匀的；阅读菜单底下是**不动**的一页纸，顶栏正好压在页眉那一行
 * 小字上。25dp 只够把那一行字糊成一块边界清楚的灰影，恰好垫在返回键和书名后面，装上后被看成「返回键外面套了个容器」。
 * 半径放大到一行字的好几倍，它就摊成看不出形状的一层薄雾；正文那几行在底栏下面也一样，摊成均匀的一层。
 * [backdrop] 为 null 时原样返回，调用方给实色底。
 *
 * [highlight] 是玻璃边缘那圈高光（Miuix 示例的 Small Container 档，[smallGlassHighlight]）：只给**浮起来的**玻璃
 * （选字后的药丸、替换表单），[shape] 要和看得见的轮廓同一条线，高光才不会在角上被裁掉。贴边的通栏（菜单顶栏、底栏）不给 ——
 * 一条通栏四周描一圈亮线，看上去像个框。
 */
@Composable
internal fun Modifier.readerGlass(
    backdrop: LayerBackdrop?,
    shape: Shape,
    tint: Color,
    highlight: Highlight? = null,
): Modifier {
    if (backdrop == null) return this
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(color = tint.copy(alpha = READER_GLASS_TINT_ALPHA))),
    )
    return this.textureBlur(
        backdrop = backdrop,
        shape = shape,
        blurRadius = READER_GLASS_BLUR_RADIUS,
        colors = colors,
        highlight = highlight,
    )
}

/** dp（miuix-blur 按 dp 收半径，上限 150） */
private const val READER_GLASS_BLUR_RADIUS = 60f
private const val READER_GLASS_TINT_ALPHA = 0.8f

/**
 * 阅读玻璃最坏时的底色：浮层色 [tint] 叠八成，压在一整片正文色 [ink] 上。
 *
 * 纸面上只有纸和字，糊得再狠，底下也不会比「一整片字的颜色」更深（浅纸）/ 更亮（深纸）——
 * 这比 App 那边按纯黑 / 纯白算（CoverGlassTest）贴实际，又仍是上界：真实的底是纸和字糊在一起，远到不了一整片字。
 * 玻璃上的强调色字（自动换源胶囊的「撤销」）按它推，见 [readerGlassAction]
 */
internal fun readerGlassFloor(tint: Color, ink: Color): Color {
    val a = READER_GLASS_TINT_ALPHA
    return Color(
        red = tint.red * a + ink.red * (1 - a),
        green = tint.green * a + ink.green * (1 - a),
        blue = tint.blue * a + ink.blue * (1 - a),
    )
}

/**
 * 阅读玻璃上的强调色字：纸上够 4.5:1 的强调色（浅纸上的褐），叠到更深一档的玻璃上就不够了（象牙纸约 4.2:1），
 * 按 [readerGlassFloor] 再推一档。
 *
 * 推用 [AppThemes.readableOn]（黑白谁在这块底上对比更高就往谁推），**不是**本包按纸面亮度过不过 0.5 定方向的 readableOn：
 * 浅纸的玻璃底透进了字色，亮度掉到 0.3~0.46，按 0.5 判会把褐往白里推，越推越看不清
 */
internal fun readerGlassAction(accent: Color, tint: Color, ink: Color): Color =
    AppThemes.readableOn(accent, readerGlassFloor(tint, ink))
