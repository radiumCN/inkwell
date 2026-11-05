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
 * 全部落在 **4dp 栅格**上。新代码一律用这里的值；要加新值，先问一句"现有的哪个不够用"。
 *
 * 换到 Miuix（HyperOS）之后栅格没变：Miuix 自己的尺寸（卡片 16dp 圆角、按钮 40dp 下限、
 * 窄栏 52dp）也都落在 4dp 上，两边对得齐。Miuix 组件的默认值能用就用默认值，
 * 只有要跨页面对齐、或 Miuix 默认值不达标（图标按钮 40dp < 触控下限 48dp）时才在这里定。
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
    /** 设置行上下内边距（少数尚未迁到 ContentListItem 的表单行仍用）。列表行请用 ContentListItem */
    val rowVertical = 10.dp
    /** 内容列表行（书籍、书源、文章、设置卡片）的左右内边距 */
    val listHorizontal = 16.dp
    /**
     * 紧凑的上下内边距：列表上面压着的状态行（导入中、校验进度）、阅读页浮层里的小块。
     *
     * 列表行本身**不**用它：行的上下边距是 `ContentListDefaults.DefaultPadding` 的 16dp（同 Miuix `BasicComponent`）。
     * 从前列表行用的就是这 4dp，两三行字的行挤在卡里，和同一页上 Miuix 原生的设置行不是一个密度。
     */
    val listVertical = 4.dp
    /**
     * 列表行 / 设置行的最小高度。对齐 Miuix `BasicComponent` 的 56dp：同一张卡里混着
     * 我们的 [ContentListItem] 与 Miuix 原生行时，行高不会一高一矮。也远高于 48dp 触控下限。
     */
    val listItemMinHeight = 56.dp
    /** 设置分组小标题上方间距（比 gapXL 矮一档，一屏多看一组） */
    val sectionHeaderTop = gapL
    /** Chip 横排间距（比 gapS 更紧） */
    val chipSpacing = gapXS
    /**
     * Chip 胶囊的可见高度。可点区仍是 [touchTarget]：胶囊外上下各让出一截透明的触控余量，
     * 与 M3 FilterChip（32dp 可见 + 48dp 可点）同一个做法
     */
    val chipHeight = 32.dp

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
    /** 24dp。标准图标尺寸；顶栏、菜单一律用它 */
    val iconMd = 24.dp
    /** 48dp。空状态正中那个 */
    val iconXL = 48.dp
    /**
     * 64dp。弹窗头部的应用图标（更新弹窗）：比空状态的线条图标大一档 —— 它是一张有底色的图，
     * 48dp 放在弹窗顶上像个列表行的缩略图，撑不起「头部」
     */
    val dialogHeroIcon = 64.dp
    /** 88dp。关于页头部的应用图标：整页的主角，照 HyperOS「关于手机」与 Miuix 示例关于页的尺寸 */
    val aboutHeroIcon = 88.dp
    /** 整页加载态的转圈（[AppLoadingIndicator] 默认尺寸）。与 Miuix 圆形进度的 30dp 默认值相近、落在栅格上 */
    val loadingIndicator = 32.dp

    // ---- 描边 ----
    /**
     * 1dp。未选中色板的外圈、预览框这类细描边。
     *
     * 描边粗细不是间距，不走 4dp 栅格：4dp 的「细线」在 52dp 色板上就是一道粗环。
     * 从前各处写 `1.dp`、`Dimens.gapXS / 4`（为了躲栅格硬凑出来的 1dp），同一种线两种写法。
     */
    val strokeThin = 1.dp
    /** 2dp。选中色板的外圈、小号转圈的线宽 —— 比 [strokeThin] 粗一档才看得出「选中」 */
    val strokeThick = 2.dp
    /** 4dp。书封上的阅读进度条（[ReadingProgressMeter]）。与滑块细轨同粗，书架上看是同一种「线」 */
    val progressMeter = 4.dp
    /**
     * 「标签在左、控件在右」那种设置行的标签列宽（阅读设置面板）。五个汉字的 bodyMedium 放得下；
     * 定宽是为了同一张卡里各行的分段按钮左边对齐，而不是跟着标签长短参差
     */
    val settingLabelWidth = 80.dp

    // ---- 控件 ----
    /**
     * 触控目标下限（48dp，WCAG / Android 无障碍指南）。图标本身可以小，可点区域不能小于它。
     * Miuix `IconButton` 默认只有 40dp，[AppIconButton] 把它抬到这个数。
     */
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
    /** 主按钮 / 次按钮最小高度（对齐 Miuix ButtonDefaults.MinHeight，走令牌不裸读） */
    val buttonMinHeight = 40.dp
    /**
     * 窄顶栏高度（[AppToolbar]）。对齐 Miuix `TopAppBarDefaults.CollapsedHeight`：
     * 大标题顶栏收起后就是这个高度，搜索页 / 书架这类窄栏与它一致，页面之间切过去顶栏不跳。
     */
    val toolbarHeight = 52.dp
    /** 按钮里的转圈。比默认的 40dp 小 —— 默认值会把按钮撑大 */
    val buttonSpinner = 18.dp
    /**
     * 滑块高度。Miuix 默认 28dp 的胶囊粗轨（HyperOS 的亮度条那种）；阅读底栏的进度条
     * 夹在两行字中间，用矮一档的 [sliderCompact]。
     */
    val sliderCompact = 20.dp
    /** 主题页滑块左侧标签列宽，避免「字号」「行距」和滑块对不齐 */
    val sliderLabelWidth = 120.dp
    /** 阅读页右边缘调亮度时浮出的那根竖条（见 QuickAdjustOverlay）：手指滑过半屏多，竖条有个能读的长度就够 */
    val brightnessHudHeight = 160.dp
}
