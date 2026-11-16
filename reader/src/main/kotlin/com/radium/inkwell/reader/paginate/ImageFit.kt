package com.radium.inkwell.reader.paginate

/**
 * 分页时一张图的情况，由调用方（阅读页）在排版前量好交进来：分页要先知道图多高，才知道这一页装不装得下。
 * 不量就排，只能先按一个固定比例留框、图来了再重排 —— 读着读着字整页往下跳。
 */
sealed interface ImageInfo {
    /** 原图的像素尺寸 */
    data class Sized(val width: Int, val height: Int) : ImageInfo

    /** 没取到（超时、来源拒绝、解不开）：排一条可以点按重试的提示，见 [PageItem.ImageBox.failed] */
    data object Failed : ImageInfo
}

/** 排在页上的大小（px） */
data class ImageSize(val width: Float, val height: Float)

/**
 * 一张图在页上多大。规则和订阅文章的图一样：
 *
 * - **宽**：原图一像素算 1dp，比正文宽才缩到正文宽（浏览器 `max-width: 100%` 的意思）。小图按原大小居中，
 *   不放大成整屏宽的一团糊。
 * - **高**：最高一屏正文那么高，再高就等比缩 —— 图不跨页，比一屏还高的话没地方放。
 * - **比一个字还小的不排**（宽高都不到 [TINY_EM] 个字号）：多是装饰小点、分隔花纹，或网站拿图片替一个字防抓取。
 *   排成单独的一块反而把一句话从中间拆成两段（图在解析时就是段落边界），不如不排，和关着开关时一样。
 *
 * 返回 null = 不排。
 */
fun fitImage(info: ImageInfo.Sized, spec: LayoutSpec): ImageSize? {
    if (info.width <= 0 || info.height <= 0) return null
    val w = info.width * spec.density
    val h = info.height * spec.density
    val tiny = spec.fontSizePx * TINY_EM
    if (w <= tiny && h <= tiny) return null
    val maxW = spec.contentWidthPx.toFloat()
    val maxH = spec.contentHeightPx
    if (maxW <= 0f || maxH <= 0f) return null
    var scale = 1f
    if (w * scale > maxW) scale = maxW / w
    if (h * scale > maxH) scale = maxH / h
    return ImageSize((w * scale).coerceAtLeast(1f), (h * scale).coerceAtLeast(1f))
}

/** 「比一个字还小」的界线：宽高都不到这么多个字号 */
internal const val TINY_EM = 1.5f

/** 图没取到时那一行提示的字 */
const val IMAGE_FAILED_LABEL = "图片没加载出来 · 点按重试"

/** 提示条的最小高度（dp），同触控下限：它是可以点的 */
internal const val IMAGE_FAILED_MIN_DP = 48f

/** 提示条里的字比正文小一号（约 footnote） */
internal const val IMAGE_FAILED_TEXT_SCALE = 0.8f
