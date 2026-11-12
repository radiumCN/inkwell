package com.radium.inkwell.reader.api

/** 阅读器排版与交互设置；由 app 层从 DataStore 读取后传入 */
data class ReaderSettings(
    val fontSizeSp: Float = 18f,
    /** 章节标题相对正文的倍数（legado 的标题字号）。1.0 = 与正文同大 */
    val titleScale: Float = 1.4f,
    /** 章节标题上方的留白（dp）。让标题跟正文之间/跟页顶之间松紧可调 */
    val titleTopSpacingDp: Float = 24f,
    /** 顶部小标题（页眉章节名）离屏幕上边的留白（dp），独立于正文边距 */
    val headerTopSpacingDp: Float = 24f,
    val fontId: String = FONT_SYSTEM,
    val lineSpacingMult: Float = 1.6f,
    val paragraphSpacingEm: Float = 0.6f,
    val marginHorizontalDp: Float = 24f,
    val marginVerticalDp: Float = 28f,
    val firstLineIndentEm: Float = 2f,
    val justify: Boolean = true,
    /** 字距（em）。0 = 字体自带的间距；中文正文稍松一点（0.05）在大屏上更透气 */
    val letterSpacingEm: Float = 0f,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val flipAnimation: FlipAnimation = FlipAnimation.COVER,
    /** null = 跟随系统 */
    val brightnessOverride: Float? = null,
    val keepScreenOn: Boolean = true,
    /**
     * 页脚是否画电池图标。默认开，和从前「进度 · 时间 · 电量」三件套一致；
     * 关掉只少一个图标，页码和时间还在。
     */
    val showBattery: Boolean = true,
    val volumeKeyFlip: Boolean = true,
    /**
     * 翻页时震一下。**默认关** —— 读小说是连续翻页，每页都震很烦，而且费电。
     * 想要"落定"手感的人再开。
     */
    val flipHaptic: Boolean = false,
    /**
     * 滚动模式下，点屏幕左右三分之一是否跳整页。
     * **默认关** —— 滚动的主交互是上下滑；点边会丢掉当前页内偏移、整页跳走，容易误触。
     * 仿真/覆盖/平移不受这项影响（点左右翻页仍是它们的主操作）。
     */
    val scrollClickToFlip: Boolean = false,
    /**
     * 点击阅读区的九宫格分区。默认「左右分区」，与从前写死的行为一致。
     * 滚动模式只在 [scrollClickToFlip] 打开时才按它走，关着时点哪儿都是开菜单。
     */
    val tapZones: TapZones = TapZones.DEFAULT,
    /** 自动翻页间隔（秒）；每页停留这么久后自动往下翻 */
    val autoFlipSeconds: Int = 15,
    /**
     * 往后预加载多少章正文（0 = 关闭）。
     * 翻到章末才去抓下一章，网络书必然卡一下；提前抓好放进缓存就没有这个停顿。
     */
    val preloadChapters: Int = 3,
    /** 在正文上双指张开 / 捏合直接调字号，不用开菜单。默认开 */
    val pinchFontSize: Boolean = true,
    /**
     * 沿屏幕右边缘竖着滑直接调亮度（视频 App 那种），不用开菜单。默认开。
     * 滚动翻页模式下不生效：上下滑在那里是翻页本身。
     */
    val edgeBrightness: Boolean = true,
    /**
     * 正文里的插图排不排。**默认关** —— 和 0.2.9 以来一样只排文字；网上的书开了要另外下载图片、多费流量。
     * 书里的图解析时一律收下（见 core 的 HtmlToElements），这里只决定阅读页排不排，开关一拨就地重排、不用重抓。
     */
    val showImages: Boolean = false,
) {
    companion object {
        /** 字号上下限（sp）。面板的滑轨、双指缩放都夹在这个范围里 */
        const val FONT_SIZE_MIN = 12f
        const val FONT_SIZE_MAX = 32f

        const val FONT_SYSTEM = "system"
        const val FONT_SERIF = "serif"
        const val FONT_SANS = "sans"
        const val FONT_MONO = "mono"

        /**
         * 用户导入字体的 id 前缀：`file:<文件名>`，文件在 app 的字体目录里（见 [FileFontRegistry]）。
         * 存文件名而不是绝对路径 —— id 会随 WebDAV 同步到别的设备，路径换台机器就是死的。
         */
        const val FONT_FILE_PREFIX = "file:"

        fun isFileFont(fontId: String): Boolean = fontId.startsWith(FONT_FILE_PREFIX)

        /** 系统预设字体；用户导入的字体另列，见 [FONT_FILE_PREFIX] */
        val FONT_PRESETS = listOf(
            FONT_SYSTEM to "默认",
            FONT_SERIF to "衬线",
            FONT_SANS to "无衬线",
            FONT_MONO to "等宽",
        )
    }
}

enum class FlipAnimation(val label: String) {
    CURL("仿真"),
    COVER("覆盖"),
    SLIDE("平移"),
    NONE("无"),
    /**
     * 上下滚动。分页与仿真/覆盖同一套（prev/cur/next），用页偏移越过内容高再翻页。
     * 页与页按正文实际高度相接，不按视口叠，避免每屏底部一道空白。
     */
    SCROLL("滚动"),
}

/**
 * 纸张主题。颜色用 ARGB Long，避免 reader 模块 API 依赖 Compose 类型。
 *
 * 每一款的正文对比度都 **≥ 7:1（WCAG AAA）**，不是 4.5:1 就算过 —— 阅读器上一坐就是一小时，
 * AA 那条线是给「看一眼就走」的界面定的。ReaderThemeContrastTest 会把这条线钉死，
 * 以后再加主题，配色不合格直接测试挂掉，而不是等用户读得眼睛疼。
 *
 * 页眉页脚、提示这类小字（[footerColor]、[secondaryTextColor]）弱于正文，但也得 **≥ 4.5:1（AA）**：
 * 它们是 11~12sp 的小字，比正文更吃对比度。从前页脚色是凭眼缘挑的，暖纸、夜色一档只有 3.4:1 上下。
 */
data class ReaderTheme(
    val id: String,
    /** 界面上显示的名字。从前只有 id，色块底下没字 —— 六款浅色纸缩成 48dp 圆点后根本分不出谁是谁 */
    val label: String,
    val background: Long,
    val textColor: Long,
    val titleColor: Long,
    /** 页眉章节名、页脚页码/时间，以及纸面上的提示小字。预设值都是「够 4.5:1 的前提下尽量往纸色靠」挑的 */
    val footerColor: Long,
    val isDark: Boolean = false,
) {
    /**
     * 阅读页浮层里的次要文字（面板副标题、说明、计数），即 ReaderThemeScope 的 onSurfaceVariant。
     *
     * 从前是「正文色 × 0.7 透明度」一刀切：浅纸上还行，深色纸（夜色、墨蓝、纯黑）和偏黄的宣纸、牛皮上
     * 只剩 3.9~4.2:1，小字读起来发虚。现在仍从「往纸色退三成」出发，只在不够 4.5:1 的主题上少退一点 ——
     * 够线的主题颜色与从前一模一样（半透明叠在纸上与这里的不透明混色是同一个值）。
     */
    val secondaryTextColor: Long
        get() = readableBlend(textColor, background, SECONDARY_TEXT_FADE)

    companion object {
        /** 小字（页脚、提示、面板次要文字）的对比度下限：WCAG AA */
        const val MIN_SMALL_TEXT_CONTRAST = 4.5

        /** 次要文字往纸色退多少（= 从前 0.7 透明度的另一种写法） */
        private const val SECONDARY_TEXT_FADE = 0.3f

        // ---- 浅色纸 ----
        /** 默认。微暖的米，长时间读不刺眼，也是 App 的品牌底色 */
        val PAPER = ReaderTheme("paper", "暖纸", 0xFFF5EFDC, 0xFF3A3226, 0xFF2A2418, 0xFF736A56)
        val WHITE = ReaderTheme("white", "净白", 0xFFFFFFFF, 0xFF212121, 0xFF111111, 0xFF757575)
        /** 比暖纸更黄，接近宣纸；夜里开小灯看不容易累 */
        val RICE = ReaderTheme("rice", "宣纸", 0xFFF2E8D5, 0xFF4A3F30, 0xFF362D20, 0xFF716651)
        /** 更深的旧纸色，白天强光下反光最轻 */
        val KRAFT = ReaderTheme("kraft", "牛皮", 0xFFE4D3B0, 0xFF413425, 0xFF2E2417, 0xFF675943)
        val GREEN = ReaderTheme("green", "青竹", 0xFFCCE8CF, 0xFF2E4033, 0xFF1F2E23, 0xFF506856)
        /** 中性灰，一点黄都不带 —— 有人就是觉得暖色纸「脏」 */
        val STONE = ReaderTheme("stone", "灰岩", 0xFFE9E7E2, 0xFF33302B, 0xFF23211D, 0xFF696660)
        /** 微暖的本白纸，正文墨绿灰 —— 比暖纸更素净、比净白更耐看 */
        val IVORY = ReaderTheme("ivory", "象牙", 0xFFEEECDF, 0xFF3C3A2E, 0xFF323026, 0xFF6B695D)

        // ---- 深色纸 ----
        val NIGHT = ReaderTheme("night", "夜色", 0xFF121212, 0xFFA3A3A3, 0xFFC4C4C4, 0xFF7E7E7E, isDark = true)
        /** 比夜色柔和，字偏暖 —— 纯灰在夜里发青 */
        val CHARCOAL = ReaderTheme("charcoal", "深灰", 0xFF1E1E1E, 0xFFB5AFA4, 0xFFD0CABE, 0xFF8B867D, isDark = true)
        val INKBLUE = ReaderTheme("inkblue", "墨蓝", 0xFF10151F, 0xFF8CA6C9, 0xFFA9BEDA, 0xFF6D829F, isDark = true)
        /** 真·纯黑：OLED 屏上黑像素不点亮，省电，且没有暗角 */
        val OLED = ReaderTheme("oled", "纯黑", 0xFF000000, 0xFF999999, 0xFFBBBBBB, 0xFF767676, isDark = true)
        /** 近黑底 + 中性亮灰字，不带任何色偏 —— 纯黑太硬、夜色偏暖时的中间选择 */
        val OBSIDIAN = ReaderTheme("obsidian", "曜石", 0xFF1C1C1C, 0xFFE9E9E9, 0xFFECECEC, 0xFF8D8D8D, isDark = true)

        val LIGHT = listOf(PAPER, RICE, KRAFT, GREEN, STONE, IVORY, WHITE)
        val DARK = listOf(NIGHT, CHARCOAL, INKBLUE, OBSIDIAN, OLED)
        val ALL = LIGHT + DARK

        /** 自定义纸张的 id；配色存在 ReaderPrefs 里，不在这张表上 */
        const val CUSTOM_ID = "custom"

        /**
         * 由「纸色 + 字色」推导一整套。标题比正文再深/亮一档，页眉页脚则往纸色靠 ——
         * 页码和章节名是**辅助信息**，跟正文一样重会抢戏。往纸色靠也有底线：退到不够 4.5:1 就停。
         */
        fun custom(background: Long, textColor: Long): ReaderTheme {
            val dark = relLuminance(background) < 0.5
            return ReaderTheme(
                id = CUSTOM_ID,
                label = "自定义",
                background = background,
                textColor = textColor,
                titleColor = blend(textColor, if (dark) 0xFFFFFFFF else 0xFF000000, 0.18f),
                footerColor = readableBlend(textColor, background, 0.45f),
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

        /**
         * 把 [fg] 往 [bg] 退 [fade]，但对比度不低于 [minRatio]：不够线就一点点少退，直到够为止。
         * 字色本身都不够线（用户自定义了一套低对比配色）时只能原样返回字色 —— 比它更清楚的颜色就不是「这套配色」了。
         */
        internal fun readableBlend(
            fg: Long,
            bg: Long,
            fade: Float,
            minRatio: Double = MIN_SMALL_TEXT_CONTRAST,
        ): Long {
            var t = fade
            while (t > 0f) {
                val c = blend(fg, bg, t)
                if (contrastRatio(bg, c) >= minRatio) return c
                t -= 0.01f
            }
            return 0xFF000000L or (fg and 0xFFFFFF)
        }
    }
}
