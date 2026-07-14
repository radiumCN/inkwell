package com.radium.inkwell.ui.components

import androidx.compose.ui.unit.dp

/**
 * 尺寸 token。
 *
 * 从前只有 6 个值，而全项目实际用到 20 多种 dp —— 内容列表行的内边距散成 16/8、12/8、
 * 24/10、24/4、20/8 五六种，弹层列表的高度上限散成 400/420/440，图标尺寸散成
 * 14/16/18/20/24。每一处单看都合理，合起来就是"这个 App 有点糙"：页面之间对不齐，
 * 而用户说不上来哪里不对。
 *
 * 全部落在 **4dp 栅格**上（Material 的间距体系）。新代码一律用这里的值；
 * 要加新值，先问一句"现有的哪个不够用"。
 */
object Dimens {

    // ---- 间距 ----
    /** 4dp。图标与文字之间这种贴身距离 */
    val gapXS = 4.dp
    /** 8dp。同组元素之间 */
    val gapS = 8.dp
    /** 12dp。相邻控件之间 */
    val gapM = 12.dp
    /** 16dp。分组之间 */
    val gapL = 16.dp
    /** 24dp。区块之间 */
    val gapXL = 24.dp
    /** 32dp。页面级留白（空状态四周之类） */
    val gapXXL = 32.dp

    // ---- 页面 / 行 ----
    /** 屏幕级左右留白（设置页、面板） */
    val screenPadding = 20.dp
    /** 设置行的左右内边距 */
    val rowHorizontal = 20.dp
    /** 设置行的上下内边距 */
    val rowVertical = 14.dp
    /** 内容列表行（书籍、书源、文章）的左右内边距。比设置行紧一点 —— 它一屏要放下更多条 */
    val listHorizontal = 16.dp
    /** 内容列表行的上下内边距 */
    val listVertical = 8.dp

    // ---- 高度上限 ----
    /**
     * 底部面板里可滚动列表的高度上限。从前四处各写各的（400/420/440），
     * 于是同样是"从底下弹出来的一个列表"，面板高度却参差不齐。
     */
    val sheetListMaxHeight = 420.dp
    /** 编辑类面板（净化规则）：要放下整张表单，比纯列表高 */
    val sheetEditorMaxHeight = 560.dp
    /** 对话框正文（更新日志之类）的滚动区上限 */
    val dialogBodyMaxHeight = 320.dp

    // ---- 图标 ----
    /** 18dp。行内的小标记（已隐藏、已缓存） */
    val iconSm = 18.dp
    /** 24dp。Material 的标准图标尺寸；顶栏、菜单一律用它 */
    val iconMd = 24.dp
    /** 32dp。较大的装饰性图标 */
    val iconLg = 32.dp
    /** 48dp。空状态正中那个 */
    val iconXL = 48.dp

    // ---- 控件 ----
    /** 触控目标下限（Material：48dp）。图标本身可以小，可点区域不能小于它 */
    val touchTarget = 48.dp
    /** 主题色板 / 图标预览的圆形色块 */
    val swatch = 52.dp
    /** 列表里的书封缩略图 */
    val coverThumbWidth = 48.dp
    val coverThumbHeight = 64.dp
    /** 按钮里的转圈。比默认的 40dp 小 —— 默认值会把按钮撑大 */
    val buttonSpinner = 18.dp
}
