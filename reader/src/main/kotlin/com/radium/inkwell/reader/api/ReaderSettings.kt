package com.radium.inkwell.reader.api

/** 阅读器排版与交互设置；由 app 层从 DataStore 读取后传入 */
data class ReaderSettings(
    val fontSizeSp: Float = 18f,
    val fontId: String = FONT_SYSTEM,
    val lineSpacingMult: Float = 1.6f,
    val paragraphSpacingEm: Float = 0.6f,
    val marginHorizontalDp: Float = 24f,
    val marginVerticalDp: Float = 28f,
    val firstLineIndentEm: Float = 2f,
    val justify: Boolean = true,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val flipAnimation: FlipAnimation = FlipAnimation.COVER,
    /** null = 跟随系统 */
    val brightnessOverride: Float? = null,
    val keepScreenOn: Boolean = true,
    val volumeKeyFlip: Boolean = true,
    /** 自动翻页间隔（秒）；每页停留这么久后自动往下翻 */
    val autoFlipSeconds: Int = 15,
    /** 正文的简繁转换 */
    val chineseConvert: ChineseConvert = ChineseConvert.NONE,
) {
    companion object {
        const val FONT_SYSTEM = "system"
        const val FONT_SERIF = "serif"
        const val FONT_SANS = "sans"
        const val FONT_MONO = "mono"

        /** 预设系统字体（不做用户导入） */
        val FONT_PRESETS = listOf(
            FONT_SYSTEM to "默认",
            FONT_SERIF to "衬线",
            FONT_SANS to "无衬线",
            FONT_MONO to "等宽",
        )
    }
}

/**
 * 简繁转换。只换字形，不做词汇转换（「軟體」不会变成「软件」）——
 * 词汇映射要一整套词库，而读者要的通常只是"别让我看繁体"。
 */
enum class ChineseConvert(val label: String) {
    NONE("不转换"),
    TO_SIMPLIFIED("转简体"),
    TO_TRADITIONAL("转繁体"),
}

enum class FlipAnimation(val label: String) {
    CURL("仿真"),
    COVER("覆盖"),
    SLIDE("平移"),
    NONE("无"),
    /**
     * 上下滚动。它不是一种「翻页动画」，而是**另一条渲染路径**：
     * 正文连续排成一列，不再切页。所以别把它塞进翻页容器里 —— 页与页堆叠起来，
     * 每屏底部都会留一道参差的空隙（分页器按整行断页，剩多少空白取决于这一屏排了几行）。
     */
    SCROLL("滚动"),
}

/** 颜色用 ARGB Long，避免 reader 模块 API 依赖 Compose 类型 */
data class ReaderTheme(
    val id: String,
    val background: Long,
    val textColor: Long,
    val titleColor: Long,
    val footerColor: Long,
    val isDark: Boolean = false,
) {
    companion object {
        val PAPER = ReaderTheme("paper", 0xFFF5EFDC, 0xFF3A3226, 0xFF2A2418, 0xFF8A8069)
        val WHITE = ReaderTheme("white", 0xFFFFFFFF, 0xFF212121, 0xFF111111, 0xFF9E9E9E)
        val GREEN = ReaderTheme("green", 0xFFCCE8CF, 0xFF2E4033, 0xFF1F2E23, 0xFF6E8A74)
        val NIGHT = ReaderTheme("night", 0xFF121212, 0xFF9E9E9E, 0xFFBDBDBD, 0xFF616161, isDark = true)
        val ALL = listOf(PAPER, WHITE, GREEN, NIGHT)
    }
}
