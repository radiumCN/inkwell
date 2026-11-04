package com.radium.inkwell.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * 阅读菜单的毛玻璃底（HyperOS 的玻璃栏）：对正文取样模糊，再叠一层八成的 [tint]。
 *
 * 正文是 ReaderScreen 录进 [backdrop] 的翻页区 —— 页面全是 Compose 自己画的（PageCanvas、仿真页的位图
 * 也画在 Compose 画布上），图层录得下来；菜单与面板都在录制区**外面**，不会自己糊自己。
 * 叠的颜色仍是浮层色（[readerOverlayColor]，比纸深 / 亮一档）：模糊只让纸上的字隐约透出来，
 * 「盖在纸上的一层」靠的还是那一档色阶和圆角，不是阴影或压暗（理由见 ReaderMenu）。
 *
 * 参数与 App 顶栏的毛玻璃一致（模糊 25、叠色 80%，Miuix 示例 App 的取值）。
 * [backdrop] 为 null 时原样返回，调用方给实色底。
 */
@Composable
internal fun Modifier.readerGlass(backdrop: LayerBackdrop?, shape: Shape, tint: Color): Modifier {
    if (backdrop == null) return this
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(color = tint.copy(alpha = READER_GLASS_TINT_ALPHA))),
    )
    return this.textureBlur(
        backdrop = backdrop,
        shape = shape,
        blurRadius = READER_GLASS_BLUR_RADIUS,
        colors = colors,
    )
}

/**
 * 落在浮层空白处（标题、进度那行字）的触摸到此为止，不漏给下面的纸面。
 *
 * M3 的 `Surface` 自带这一层（一个空的 pointerInput），Miuix 的 `Surface` 没有 —— 换过来之后，
 * 点阅读菜单顶栏的书名会关掉菜单，在进度条那行字上一滑会翻页。空的 pointerInput 不消费任何事件，
 * 栏里的按钮、滑块照常收到，只是命中测试停在这里、不再往下找兄弟节点。
 */
internal fun Modifier.blockTouchesBelow(): Modifier = this.pointerInput(Unit) {}

private const val READER_GLASS_BLUR_RADIUS = 25f
private const val READER_GLASS_TINT_ALPHA = 0.8f
