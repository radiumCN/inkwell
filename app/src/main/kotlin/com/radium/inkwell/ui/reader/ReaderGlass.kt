package com.radium.inkwell.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
