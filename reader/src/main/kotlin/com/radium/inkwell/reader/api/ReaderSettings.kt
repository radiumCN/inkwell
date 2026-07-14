package com.radium.inkwell.reader.api

/** 阅读器排版与交互设置；由 app 层从 DataStore 读取后传入 */
data class ReaderSettings(
    val fontSizeSp: Float = 18f,
    /** 章节标题相对正文的倍数（legado 的标题字号）。1.0 = 与正文同大 */
    val titleScale: Float = 1.4f,
    /** 章节标题上方的留白（dp）。让标题跟正文之间/跟页顶之间松紧可调 */
    val titleTopSpacingDp: Float = 24f,
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
    /**
     * 翻页时震一下。**默认关** —— 读小说是连续翻页，每页都震很烦，而且费电。
     * 想要"落定"手感的人再开。
     */
    val flipHaptic: Boolean = false,
    /** 自动翻页间隔（秒）；每页停留这么久后自动往下翻 */
    val autoFlipSeconds: Int = 15,
    /**
     * 往后预加载多少章正文（0 = 关闭）。
     * 翻到章末才去抓下一章，网络书必然卡一下；提前抓好放进缓存就没有这个停顿。
     */
    val preloadChapters: Int = 3,
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

/**
 * 纸张主题。颜色用 ARGB Long，避免 reader 模块 API 依赖 Compose 类型。
 *
 * 每一款的正文对比度都 **≥ 7:1（WCAG AAA）**，不是 4.5:1 就算过 —— 阅读器上一坐就是一小时，
 * AA 那条线是给「看一眼就走」的界面定的。ReaderThemeContrastTest 会把这条线钉死，
 * 以后再加主题，配色不合格直接测试挂掉，而不是等用户读得眼睛疼。
 */
data class ReaderTheme(
    val id: String,
    /** 界面上显示的名字。从前只有 id，色块底下没字 —— 六款浅色纸缩成 48dp 圆点后根本分不出谁是谁 */
    val label: String,
    val background: Long,
    val textColor: Long,
    val titleColor: Long,
    val footerColor: Long,
    val isDark: Boolean = false,
) {
    companion object {
        // ---- 浅色纸 ----
        /** 默认。微暖的米，长时间读不刺眼，也是 App 的品牌底色 */
        val PAPER = ReaderTheme("paper", "暖纸", 0xFFF5EFDC, 0xFF3A3226, 0xFF2A2418, 0xFF8A8069)
        val WHITE = ReaderTheme("white", "净白", 0xFFFFFFFF, 0xFF212121, 0xFF111111, 0xFF8A8A8A)
        /** 比暖纸更黄，接近宣纸；夜里开小灯看不容易累 */
        val RICE = ReaderTheme("rice", "宣纸", 0xFFF2E8D5, 0xFF4A3F30, 0xFF362D20, 0xFF857A62)
        /** 更深的旧纸色，白天强光下反光最轻 */
        val KRAFT = ReaderTheme("kraft", "牛皮", 0xFFE4D3B0, 0xFF413425, 0xFF2E2417, 0xFF7D6F55)
        val GREEN = ReaderTheme("green", "青竹", 0xFFCCE8CF, 0xFF2E4033, 0xFF1F2E23, 0xFF5F7A66)
        /** 中性灰，一点黄都不带 —— 有人就是觉得暖色纸「脏」 */
        val STONE = ReaderTheme("stone", "灰岩", 0xFFE9E7E2, 0xFF33302B, 0xFF23211D, 0xFF87847D)
        /** 微暖的本白纸，正文墨绿灰 —— 比暖纸更素净、比净白更耐看 */
        val IVORY = ReaderTheme("ivory", "象牙", 0xFFEEECDF, 0xFF3C3A2E, 0xFF323026, 0xFF838174)

        // ---- 深色纸 ----
        val NIGHT = ReaderTheme("night", "夜色", 0xFF121212, 0xFFA3A3A3, 0xFFC4C4C4, 0xFF6B6B6B, isDark = true)
        /** 比夜色柔和，字偏暖 —— 纯灰在夜里发青 */
        val CHARCOAL = ReaderTheme("charcoal", "深灰", 0xFF1E1E1E, 0xFFB5AFA4, 0xFFD0CABE, 0xFF77726A, isDark = true)
        val INKBLUE = ReaderTheme("inkblue", "墨蓝", 0xFF10151F, 0xFF8CA6C9, 0xFFA9BEDA, 0xFF5C6E88, isDark = true)
        /** 真·纯黑：OLED 屏上黑像素不点亮，省电，且没有暗角 */
        val OLED = ReaderTheme("oled", "纯黑", 0xFF000000, 0xFF999999, 0xFFBBBBBB, 0xFF636363, isDark = true)
        /** 近黑底 + 中性亮灰字，不带任何色偏 —— 纯黑太硬、夜色偏暖时的中间选择 */
        val OBSIDIAN = ReaderTheme("obsidian", "曜石", 0xFF1C1C1C, 0xFFE9E9E9, 0xFFECECEC, 0xFF8D8D8D, isDark = true)

        val LIGHT = listOf(PAPER, RICE, KRAFT, GREEN, STONE, IVORY, WHITE)
        val DARK = listOf(NIGHT, CHARCOAL, INKBLUE, OBSIDIAN, OLED)
        val ALL = LIGHT + DARK

        /** 自定义纸张的 id；配色存在 ReaderPrefs 里，不在这张表上 */
        const val CUSTOM_ID = "custom"

        /**
         * 由「纸色 + 字色」推导一整套。标题比正文再深/亮一档，页眉页脚则往纸色靠 ——
         * 页码和章节名是**辅助信息**，跟正文一样重会抢戏。
         */
        fun custom(background: Long, textColor: Long): ReaderTheme {
            val dark = relLuminance(background) < 0.5
            return ReaderTheme(
                id = CUSTOM_ID,
                label = "自定义",
                background = background,
                textColor = textColor,
                titleColor = blend(textColor, if (dark) 0xFFFFFFFF else 0xFF000000, 0.18f),
                footerColor = blend(textColor, background, 0.45f),
                isDark = dark,
            )
        }

        /** WCAG 相对亮度。自己算，免得 reader 模块为了一个亮度去依赖 Compose */
        internal fun relLuminance(argb: Long): Double {
            fun channel(c: Int): Double {
                val s = c / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            val r = ((argb shr 16) and 0xFF).toInt()
            val g = ((argb shr 8) and 0xFF).toInt()
            val b = (argb and 0xFF).toInt()
            return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
        }

        /** 正文对比度。低于 4.5 就是读不清，低于 7 就是读久了眼睛疼 */
        fun contrastRatio(bg: Long, fg: Long): Double {
            val a = relLuminance(bg)
            val b = relLuminance(fg)
            return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        }

        private fun blend(from: Long, to: Long, t: Float): Long {
            fun mix(shift: Int): Long {
                val a = (from shr shift) and 0xFF
                val b = (to shr shift) and 0xFF
                return (a + ((b - a) * t).toLong()).coerceIn(0, 255) shl shift
            }
            return 0xFF000000L or mix(16) or mix(8) or mix(0)
        }
    }
}
