package com.radium.inkwell.reader.api

/** 点击阅读区某一格要做的事 */
enum class TapAction(val label: String) {
    PREV("上一页"),
    NEXT("下一页"),
    MENU("菜单"),
    NONE("无动作"),
}

/**
 * 阅读区点击分区：屏幕按 3×3 九宫格切开，每格一个 [TapAction]，按行优先存（左上 → 右下）。
 *
 * **正中那格永远是 [TapAction.MENU]**，构造时强制改回去：菜单是退出阅读器、调设置的唯一入口
 *（系统返回键之外），让用户把九格全配成翻页，就再也打不开菜单把它改回来了。
 */
class TapZones private constructor(val cells: List<TapAction>) {

    /** [col]、[row] 都是 0..2 */
    fun at(col: Int, row: Int): TapAction = cells[row * 3 + col]

    /** 改一格；改正中那格无效（见类注释） */
    fun with(index: Int, action: TapAction): TapZones =
        of(cells.toMutableList().also { it[index] = action })

    /**
     * 点在 ([x], [y]) 上该做什么。落点按三等分落格；越界的（手指压在边上一两个像素外）夹回边上那格。
     *
     * 分界线归**右 / 下**那格：宽 900 时 x = 300 落中列、x = 600 落右列。从前写死的左右分区是两头开区间，
     * 正好压在 600 上算菜单 —— 只差这一个像素，手指分辨不出来，不值得为它把取整写成两套规则。
     */
    fun actionAt(x: Float, y: Float, width: Float, height: Float): TapAction {
        val col = (x / (width.coerceAtLeast(1f) / 3f)).toInt().coerceIn(0, 2)
        val row = (y / (height.coerceAtLeast(1f) / 3f)).toInt().coerceIn(0, 2)
        return at(col, row)
    }

    /** 存储 / 备份用：逗号分隔的 9 个枚举名 */
    fun encode(): String = cells.joinToString(",") { it.name }

    override fun equals(other: Any?): Boolean = other is TapZones && other.cells == cells
    override fun hashCode(): Int = cells.hashCode()
    override fun toString(): String = "TapZones(${encode()})"

    /** 预设：[label] 给设置面板的 chip 用 */
    enum class Preset(val label: String, val zones: TapZones) {
        /** 从前写死的那套：只看横向，左三分之一上一页、右三分之一下一页、中间一列开菜单 */
        LEFT_RIGHT(
            "左右分区",
            of(
                TapAction.PREV, TapAction.MENU, TapAction.NEXT,
                TapAction.PREV, TapAction.MENU, TapAction.NEXT,
                TapAction.PREV, TapAction.MENU, TapAction.NEXT,
            ),
        ),

        /** 单手持机：拇指够到哪儿点哪儿都是下一页，只留正中开菜单。回翻靠滑动 */
        ALL_NEXT(
            "全屏下一页",
            of(
                TapAction.NEXT, TapAction.NEXT, TapAction.NEXT,
                TapAction.NEXT, TapAction.MENU, TapAction.NEXT,
                TapAction.NEXT, TapAction.NEXT, TapAction.NEXT,
            ),
        ),

        /** 上半屏回翻、下半屏前翻：大屏手机单手时拇指更容易够到下半屏 */
        TOP_BOTTOM(
            "上下分区",
            of(
                TapAction.PREV, TapAction.PREV, TapAction.PREV,
                TapAction.PREV, TapAction.MENU, TapAction.NEXT,
                TapAction.NEXT, TapAction.NEXT, TapAction.NEXT,
            ),
        ),
    }

    companion object {
        const val CENTER = 4

        val DEFAULT: TapZones get() = Preset.LEFT_RIGHT.zones

        fun of(vararg cells: TapAction): TapZones = of(cells.toList())

        fun of(cells: List<TapAction>): TapZones {
            require(cells.size == 9) { "九宫格要 9 格，实际 ${cells.size}" }
            return TapZones(cells.toMutableList().also { it[CENTER] = TapAction.MENU })
        }

        /** [decodeOrNull] 读不懂就退回 [DEFAULT]；本机存储用它 */
        fun decode(raw: String?): TapZones = decodeOrNull(raw) ?: DEFAULT

        /**
         * [encode] 的逆。格数不对、有不认识的名字（新版本加了动作、又装回旧版）→ null，
         * 不去猜残缺的那几格 —— 半套猜出来的分区比默认更让人摸不着头脑。
         *
         * 备份导入要用这个而不是 [decode]：远端值读不懂时该保留本机的分区，而不是冲成默认。
         */
        fun decodeOrNull(raw: String?): TapZones? {
            val parts = raw?.split(",") ?: return null
            if (parts.size != 9) return null
            val cells = parts.map { name -> TapAction.entries.firstOrNull { it.name == name } ?: return null }
            return of(cells)
        }
    }
}
