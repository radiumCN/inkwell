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
    /** 设置行的左右内边距（少数尚未迁到 ContentListItem 的表单行仍用） */
    val rowHorizontal = 20.dp
    /**
     * 设置行上下内边距（遗留）。圆角列表行请用 [listVertical] ——
     * Expressive 容器时代 14dp 会叠出行外缝，显得虚高。
     */
    val rowVertical = 10.dp
    /** 内容列表行（书籍、书源、文章、设置卡片）的左右内边距 */
    val listHorizontal = 16.dp
    /**
     * 内容列表 / 设置卡片行的上下内边距（Compact 默认密度）。
     *
     * 取 4 而非 8：两行字（标题 + 副标题）再叠 8+8，卡片相对字重显得虚高，
     * 设置页一屏少看一组。触控高度仍靠 ListItem 自身下限与两行字撑住 ≥48dp。
     */
    val listVertical = 4.dp
    /** 设置分组小标题上方间距（比 gapXL 矮一档，一屏多看一组） */
    val sectionHeaderTop = gapL
    /** Chip 横排间距（比 gapS 更紧） */
    val chipSpacing = gapXS

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

    // ---- 高度下限 ----
    /**
     * 多行文本输入的初始高度。给足下限是**在提示这里期待长文** ——
     * 多行输入框默认只有一行高，看着和单行框一模一样，用户写两句就停了。
     */
    val textAreaMinHeight = 160.dp

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
    /** 详情 / 预览页书封（3:4） */
    val coverDetailWidth = 96.dp
    val coverDetailHeight = 128.dp
    /** 书架网格单元最小边 —— Adaptive 栅格用 */
    val bookshelfGridMin = 96.dp
    /** 进书 splash 居中显示的书封。3:4，比列表缩略图大一档，但**远不到铺满**（铺满会被拉糊） */
    val readerSplashCoverWidth = 120.dp
    val readerSplashCoverHeight = 160.dp
    /** 顶栏 / 工具条搜索框高度 */
    val searchFieldHeight = 40.dp
    /** 对话框 / 表单行内紧凑输入高度（与搜索框同高） */
    val compactFieldHeight = 40.dp
    /** 主按钮 / 次按钮最小高度（对齐 M3 ButtonDefaults.MinHeight，走令牌不裸读） */
    val buttonMinHeight = 40.dp
    /** 按钮里的转圈。比默认的 40dp 小 —— 默认值会把按钮撑大 */
    val buttonSpinner = 18.dp
    /**
     * 滑块布局槽高度。必须对齐 M3 `SliderTokens.InactiveTrackHeight`（16dp）——
     * [androidx.compose.material3.Slider] 内部 `requiredSizeIn(minHeight = TrackHeight)`，
     * 自定义拇指/轨若矮于它，槽位与绘制中心会对不齐（圆点看起来飘在线上/线下）。
     */
    val sliderSlot = 16.dp
    /** 滑块轨道厚度。Expressive 默认轨 16dp 太厚，阅读底栏改画细线 */
    val sliderTrack = 4.dp
    /** 滑块圆点拇指。远矮于 Expressive 竖条 Handle（4×44） */
    val sliderThumb = 12.dp
}
