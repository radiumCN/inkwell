package com.radium.inkwell.ui.settings

import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingBlock
import com.radium.inkwell.ui.components.SegmentedChoice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.radium.inkwell.ui.components.AppSlider
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.settingsPageColor
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.theme.AppThemes
import com.radium.inkwell.ui.theme.ThemeConfig
import com.radium.inkwell.ui.theme.ThemeMode
import com.radium.inkwell.ui.theme.ThemePreset
import com.radium.inkwell.ui.theme.hsvToColor
import com.radium.inkwell.ui.theme.toArgbLong
import com.radium.inkwell.ui.theme.toHsv
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.Icon
import com.radium.inkwell.ui.components.AppScaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import com.radium.inkwell.ui.components.AppSurface
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.radium.inkwell.ui.components.sinkOnPress

@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) {
    val prefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    // 预热好的那份：冷流配 initialValue 的话，进页先按默认画一帧，选中态与预览跟着跳一下
    val config = prefs.themeState.collectAsStateWithLifecycle().value ?: ThemeConfig()

    fun update(new: ThemeConfig) {
        scope.launch { prefs.setThemeConfig(new) }
    }

    // 「跟随壁纸」色块按系统此刻给的那套画：底色 + 强调色，跟别的预设同一种预览。
    // 与 rememberResolvedScheme 同一个来源（Miuix ThemeController 的 Monet 模式、不给 keyColor），
    // 色块和选中后整个 App 换上的颜色才是同一套
    val wallpaperLightController = remember { ThemeController(ColorSchemeMode.MonetLight) }
    val wallpaperDarkController = remember { ThemeController(ColorSchemeMode.MonetDark) }
    val wallpaperLight = wallpaperLightController.currentColors()
    val wallpaperDark = wallpaperDarkController.currentColors()

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("主题外观", topBarScroll, onBack = onBack, containerColor = pageColor) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                // 顶栏、导航栏的高度都垫在滚动内容首尾：内容能滚到玻璃顶栏和手势条下面（见 AppScaffold）
                .padding(padding.horizontalOnly())
                .pageScroll()
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(top = padding.calculateTopPadding() + Dimens.gapS, bottom = padding.calculateBottomPadding()),
        ) {
            // 与别的设置页同一副骨架：分组标题 + 卡片。从前整页内缩 20dp、chip 和色板直接铺在灰底上
            SectionHeader("模式")
            SettingGroup {
                SettingBlock {
                    SegmentedChoice(
                        options = ThemeMode.entries.map { it.label },
                        selectedIndex = ThemeMode.entries.indexOf(config.mode),
                        onSelect = { update(config.copy(mode = ThemeMode.entries[it])) },
                    )
                }
            }

            SectionHeader("日间主题")
            ThemeCard {
                PresetRow(
                presets = AppThemes.lightPresets,
                wallpaper = wallpaperLight.background to wallpaperLight.primary,
                selectedId = config.lightPreset,
                customAccent = Color(config.customLightSeed),
                customBg = Color(config.customLightBg),
                onSelect = { update(config.copy(lightPreset = it)) },
                )
                if (config.lightPreset == AppThemes.CUSTOM) {
                    CustomThemeEditor(
                        dark = false,
                        seed = Color(config.customLightSeed),
                        background = Color(config.customLightBg),
                        onChange = { seed, bg ->
                            update(
                                config.copy(
                                    customLightSeed = seed.toArgbLong(),
                                    customLightBg = bg.toArgbLong(),
                                )
                            )
                        },
                    )
                }
            }

            SectionHeader("夜间主题")
            ThemeCard {
                PresetRow(
                presets = AppThemes.darkPresets,
                wallpaper = wallpaperDark.background to wallpaperDark.primary,
                selectedId = config.darkPreset,
                customAccent = Color(config.customDarkSeed),
                customBg = Color(config.customDarkBg),
                onSelect = { update(config.copy(darkPreset = it)) },
                )
                if (config.darkPreset == AppThemes.CUSTOM) {
                    CustomThemeEditor(
                        dark = true,
                        seed = Color(config.customDarkSeed),
                        background = Color(config.customDarkBg),
                        onChange = { seed, bg ->
                            update(
                                config.copy(
                                    customDarkSeed = seed.toArgbLong(),
                                    customDarkBg = bg.toArgbLong(),
                                )
                            )
                        },
                    )
                }
            }

            Box(Modifier.height(Dimens.gapXXL))
        }
    }
}

/**
 * 日间 / 夜间主题那张卡：上面一排色板（横滚到卡的边缘，见 [PresetRow]），选中自定义时下面展开编辑器。
 * 编辑器和色板一样在卡内、左右与行内文字对齐。
 */
@Composable
private fun ThemeCard(content: @Composable () -> Unit) {
    SettingGroup {
        Column(Modifier.padding(vertical = Dimens.gapM)) { content() }
    }
}

/**
 * 「跟随壁纸」+ 预设色板行 + 「自定义」入口。
 *
 * 壁纸放在最前：它是「不用挑」的那个选项，跟系统别的 App 一个颜色；挑不中意再往后看预设、最后自己调。
 */
@Composable
private fun PresetRow(
    presets: List<ThemePreset>,
    wallpaper: Pair<Color, Color>,
    selectedId: String,
    customAccent: Color,
    customBg: Color,
    onSelect: (String) -> Unit,
) {
    // 横向可滚：壁纸 + 5 个预设 + 自定义 = 7 个 52dp 色块，固定 Row 里会被挤扁或溢出屏幕
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            // 内缩放在滚动内容里：色板随内容滚到卡片边缘，而不是在内缩线上被截断
            .padding(horizontal = Dimens.listHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapM),
    ) {
        ThemeSwatch(
            bg = wallpaper.first,
            accent = wallpaper.second,
            label = "跟随壁纸",
            selected = selectedId == AppThemes.DYNAMIC,
            onClick = { onSelect(AppThemes.DYNAMIC) },
        )
        presets.forEach { preset ->
            ThemeSwatch(
                bg = preset.previewBg,
                accent = preset.previewAccent,
                label = preset.label,
                selected = selectedId == preset.id,
                onClick = { onSelect(preset.id) },
            )
        }
        ThemeSwatch(
            bg = customBg,
            accent = customAccent,
            label = "自定义",
            selected = selectedId == AppThemes.CUSTOM,
            onClick = { onSelect(AppThemes.CUSTOM) },
        )
    }
}

@Composable
private fun ThemeSwatch(
    bg: Color,
    accent: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 外层只定尺寸、托住右下角的选中对勾；可点的圆在里层 —— 按压反馈（Miuix 的压暗）要裁成圆的，
        // 圆色板按下去冒出一个方块很难看；裁切若放在外层，会把伸出圆外的对勾一起裁掉
        // 下沉挂在外层：右下角的对勾跟着色板一起沉（见 sinkOnPress）
        val press = remember { MutableInteractionSource() }
        Box(Modifier.size(Dimens.swatch).sinkOnPress(press)) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(bg)
                    .border(
                        width = if (selected) Dimens.strokeThick else Dimens.strokeThin,
                        color = if (selected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.dividerLine,
                        shape = CircleShape,
                    )
                    // 整块色板作为单选目标带上名字与选中态：否则读屏聚焦未选中的色块什么都念不出，
                    // 十个色块无从区分（名字 Text 在可点区之外）
                    .selectable(
                        selected = selected,
                        interactionSource = press,
                        indication = LocalIndication.current,
                        role = Role.RadioButton,
                        onClick = onClick,
                    )
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(Dimens.iconSm).background(accent, CircleShape))
            }
            if (selected) {
                Icon(
                    MiuixIcons.Basic.Check,
                    contentDescription = null,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(Dimens.iconSm)
                        .background(MiuixTheme.colorScheme.primary, CircleShape)
                        .padding(Dimens.gapXS),
                    tint = MiuixTheme.colorScheme.onPrimary,
                )
            }
        }
        Text(
            label,
            Modifier.padding(top = Dimens.gapXS),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 自定义主题编辑：强调色 HSV 三滑条 + 背景（暖度 / 明度）+ 实时预览。
 * 滑动过程用本地状态，松手落库。
 */
@Composable
private fun CustomThemeEditor(
    dark: Boolean,
    seed: Color,
    background: Color,
    onChange: (seed: Color, bg: Color) -> Unit,
) {
    // 从当前颜色反解 HSV / 背景参数作为滑条初值
    val seedHsv = remember(seed) { seed.toHsv() }
    var hue by remember { androidx.compose.runtime.mutableFloatStateOf(seedHsv[0]) }
    var sat by remember { androidx.compose.runtime.mutableFloatStateOf(seedHsv[1]) }
    var value by remember { androidx.compose.runtime.mutableFloatStateOf(seedHsv[2]) }
    val bgHsv = remember(background) { background.toHsv() }
    // 背景：色相固定暖色系，用「暖度=饱和度」「明度」两个参数
    var bgWarmth by remember { androidx.compose.runtime.mutableFloatStateOf(bgHsv[1]) }
    var bgValue by remember { androidx.compose.runtime.mutableFloatStateOf(bgHsv[2]) }

    fun currentSeed() = hsvToColor(hue, sat, value)
    fun currentBg(): Color {
        val v = if (dark) bgValue.coerceIn(0f, 0.25f) else bgValue.coerceIn(0.85f, 1f)
        return hsvToColor(45f, bgWarmth.coerceIn(0f, 0.12f), v)
    }

    // 拖色相 / 明度滑块时每帧都会重组：输入没变就别重推整套配色
    val seed = currentSeed()
    val bg = currentBg()
    val previewScheme = remember(seed, bg, dark) { AppThemes.schemeFrom(seed, bg, dark) }

    AppSurface(
        cornerRadius = AppShapes.radiusMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.listHorizontal, end = Dimens.listHorizontal, top = Dimens.gapM),
        // 编辑器是卡里再凹一层的托盘：取页面底 surface（浅色比卡深一档、深色比卡暗一档），
        // 别用半透明色 —— 深色/自定义下会发灰发脏
        color = MiuixTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(Dimens.gapL)) {
            // 实时预览照 Miuix 的分层画三样：页面底（surface）上的标题与强调按钮、一张卡片（surfaceContainer）、
            // 一行选中项（tertiaryContainer）。只预览底色与强调色的话，换主题后卡片、选中行的颜色对不上预览
            AppSurface(
                cornerRadius = AppShapes.radiusMedium,
                modifier = Modifier.fillMaxWidth(),
                color = previewScheme.surface,
                borderColor = MiuixTheme.colorScheme.dividerLine,
            ) {
                Column(Modifier.padding(Dimens.gapM)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "预览标题",
                                style = MiuixTheme.textStyles.headline2,
                                fontWeight = FontWeight.Medium,
                                color = previewScheme.onSurface,
                            )
                            Text(
                                "正文文字效果",
                                style = MiuixTheme.textStyles.footnote1,
                                color = previewScheme.onSurfaceVariantSummary,
                            )
                        }
                        Surface(
                            shape = AppShapes.small,
                            color = previewScheme.primary,
                        ) {
                            Text(
                                "按钮",
                                Modifier.padding(
                                    horizontal = Dimens.gapM,
                                    vertical = Dimens.gapXS,
                                ),
                                style = MiuixTheme.textStyles.footnote1,
                                color = previewScheme.onPrimary,
                            )
                        }
                    }
                    AppSurface(
                        cornerRadius = AppShapes.radiusMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Dimens.gapS),
                        color = previewScheme.surfaceContainer,
                    ) {
                        Row(
                            Modifier.padding(
                                horizontal = Dimens.gapM,
                                vertical = Dimens.listVertical,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(width = Dimens.coverThumbWidth, height = Dimens.coverThumbHeight)
                                    .background(
                                        previewScheme.tertiaryContainer,
                                        AppShapes.small,
                                    ),
                            )
                            Column(Modifier.padding(start = Dimens.gapM).weight(1f)) {
                                Text(
                                    "列表行标题",
                                    style = MiuixTheme.textStyles.body1,
                                    color = previewScheme.onSurfaceContainer,
                                    maxLines = 1,
                                )
                                Text(
                                    "容器色与正文对比",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = previewScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    AppSurface(
                        cornerRadius = AppShapes.radiusMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Dimens.gapXS),
                        color = previewScheme.tertiaryContainer,
                    ) {
                        Text(
                            "选中的列表行",
                            Modifier.padding(
                                horizontal = Dimens.gapM,
                                vertical = Dimens.listVertical,
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = previewScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            // 拖动只更新本地预览，松手（onFinished）才落库：避免每帧写 DataStore 与全局主题抖动
            val commit = { onChange(currentSeed(), currentBg()) }
            LabeledSlider("强调色 · 色相", hue, 0f..360f, { hue = it }, commit)
            LabeledSlider("强调色 · 饱和度", sat, 0f..1f, { sat = it }, commit)
            LabeledSlider("强调色 · 明度", value, 0.2f..1f, { value = it }, commit)
            LabeledSlider("背景 · 暖度", bgWarmth, 0f..0.12f, { bgWarmth = it }, commit)
            LabeledSlider(
                if (dark) "背景 · 明度（纯黑 ↔ 深灰）" else "背景 · 明度",
                bgValue,
                if (dark) 0f..0.25f else 0.85f..1f,
                { bgValue = it }, commit,
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = Dimens.gapXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.width(Dimens.sliderLabelWidth),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        // 全项目最后一处裸 Slider。它和阅读菜单里的滑块本该长得一样
        AppSlider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            modifier = Modifier.weight(1f),
            label = label,
        )
    }
}
