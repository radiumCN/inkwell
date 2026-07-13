package com.radium.inkwell.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.theme.AppThemes
import com.radium.inkwell.ui.theme.ThemeConfig
import com.radium.inkwell.ui.theme.ThemeMode
import com.radium.inkwell.ui.theme.ThemePreset
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) {
    val prefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    val config by prefs.themeConfig.collectAsStateWithLifecycle(initialValue = ThemeConfig())

    fun update(new: ThemeConfig) {
        scope.launch { prefs.setThemeConfig(new) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("主题外观") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel("模式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = config.mode == mode,
                        onClick = { update(config.copy(mode = mode)) },
                        label = { Text(mode.label) },
                    )
                }
            }

            SectionLabel("日间主题")
            PresetRow(
                presets = AppThemes.lightPresets,
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
                                customLightSeed = seed.toArgb().toLong() and 0xFFFFFFFFL,
                                customLightBg = bg.toArgb().toLong() and 0xFFFFFFFFL,
                            )
                        )
                    },
                )
            }

            SectionLabel("夜间主题")
            PresetRow(
                presets = AppThemes.darkPresets,
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
                                customDarkSeed = seed.toArgb().toLong() and 0xFFFFFFFFL,
                                customDarkBg = bg.toArgb().toLong() and 0xFFFFFFFFL,
                            )
                        )
                    },
                )
            }

            Box(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(top = 20.dp, bottom = 10.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 预设色板行 + 「自定义」入口 */
@Composable
private fun PresetRow(
    presets: List<ThemePreset>,
    selectedId: String,
    customAccent: Color,
    customBg: Color,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
        Box(
            Modifier
                .size(52.dp)
                .background(bg, CircleShape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(18.dp).background(accent, CircleShape))
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = label,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(2.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Text(
            label,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val seedHsv = remember(seed) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(seed.toArgb(), it) }
    }
    var hue by remember { androidx.compose.runtime.mutableFloatStateOf(seedHsv[0]) }
    var sat by remember { androidx.compose.runtime.mutableFloatStateOf(seedHsv[1]) }
    var value by remember { androidx.compose.runtime.mutableFloatStateOf(seedHsv[2]) }
    val bgHsv = remember(background) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(background.toArgb(), it) }
    }
    // 背景：色相固定暖色系，用「暖度=饱和度」「明度」两个参数
    var bgWarmth by remember { androidx.compose.runtime.mutableFloatStateOf(bgHsv[1]) }
    var bgValue by remember { androidx.compose.runtime.mutableFloatStateOf(bgHsv[2]) }

    fun currentSeed() = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    fun currentBg(): Color {
        val v = if (dark) bgValue.coerceIn(0f, 0.25f) else bgValue.coerceIn(0.85f, 1f)
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(45f, bgWarmth.coerceIn(0f, 0.12f), v)))
    }

    val previewScheme = AppThemes.schemeFrom(currentSeed(), currentBg(), dark)

    Surface(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(16.dp)) {
            // 实时预览
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = previewScheme.background,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("预览标题", color = previewScheme.onBackground)
                        Text(
                            "正文文字效果",
                            style = MaterialTheme.typography.bodySmall,
                            color = previewScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .background(previewScheme.primary, MaterialTheme.shapes.small)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "按钮",
                            style = MaterialTheme.typography.labelMedium,
                            color = previewScheme.onPrimary,
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
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.width(120.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
    }
}
