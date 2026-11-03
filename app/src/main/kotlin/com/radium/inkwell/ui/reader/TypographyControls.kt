package com.radium.inkwell.ui.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.prefs.LayoutScheme
import com.radium.inkwell.data.repo.UserFont
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppFilterChip
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.Dimens

/**
 * 阅读设置面板里「字体」「排版方案」两块要的数据与动作。打成一包往下传，
 * 而不是在 ReaderMenu → TypographyPanel → LayoutTab 三层签名上各挂七个参数。
 */
@Stable
class TypographyActions(
    val fonts: List<UserFont>,
    val schemes: List<LayoutScheme>,
    val onImportFont: (Uri) -> Unit,
    val onDeleteFont: (UserFont) -> Unit,
    val onApplyScheme: (LayoutScheme) -> Unit,
    val onSaveScheme: (String) -> Unit,
    val onDeleteScheme: (String) -> Unit,
)

@Composable
fun rememberTypographyActions(viewModel: ReaderViewModel): TypographyActions {
    val fonts by viewModel.userFonts.collectAsStateWithLifecycle()
    val schemes by viewModel.layoutSchemes.collectAsStateWithLifecycle()
    return remember(fonts, schemes, viewModel) {
        TypographyActions(
            fonts = fonts,
            schemes = schemes,
            onImportFont = viewModel::importFont,
            onDeleteFont = viewModel::deleteFont,
            onApplyScheme = viewModel::applyLayoutScheme,
            onSaveScheme = viewModel::saveLayoutScheme,
            onDeleteScheme = viewModel::deleteLayoutScheme,
        )
    }
}

/**
 * 排版方案：已存的方案一排 chip，点一下整套换上；尾巴上一个动作 chip ——
 * 当前排版正好是某套方案时是「删除方案」，否则是「存为方案」。
 *
 * 两个动作共用一个位置而不是并排：排版和某套方案一模一样时再存一份毫无意义；
 * 想改某套方案，就先调到满意（此时已不再匹配），再用同名存一次覆盖它。
 */
@Composable
internal fun LayoutSchemeSection(
    settings: ReaderSettings,
    actions: TypographyActions,
    /** 左右内缩。放进分组卡时由卡给：chip 条随内容滚到卡的边缘，而不是在内缩线上被截断 */
    horizontalPadding: Dp = 0.dp,
) {
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LayoutScheme?>(null) }
    val schemes = actions.schemes
    val current = schemes.indexOfFirst { it.matches(settings) }

    // chip 条与说明自己摞成一列，不靠调用方：放进 Box 里（分组卡就是这么放的）时，两样会叠在同一处，
    // 说明文字直接压在「存为方案」上
    Column {
        ChipRow(
            options = schemes.map { it.name },
            selectedIndex = current,
            onSelect = { actions.onApplyScheme(schemes[it]) },
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            trailing = {
                if (current >= 0) {
                    AppFilterChip(selected = false, onClick = { deleting = schemes[current] }, label = "删除方案")
                } else {
                    AppFilterChip(selected = false, onClick = { naming = true }, label = "存为方案")
                }
            },
        )
        if (schemes.isEmpty()) {
            Text(
                "把现在的字号、字体、行距和边距存起来，换个场景一键切回。纸色不在方案里，仍按日夜各记一套",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = horizontalPadding, end = horizontalPadding, top = Dimens.gapS),
            )
        }
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        val trimmed = name.trim()
        val overwrites = schemes.any { it.name == trimmed }
        AppAlertDialog(
            onDismissRequest = { naming = false },
            title = "存为排版方案",
            text = if (overwrites) "已有同名方案，保存会用当前排版覆盖它" else null,
            confirmText = if (overwrites) "覆盖" else "保存",
            confirmEnabled = trimmed.isNotEmpty(),
            onConfirm = {
                actions.onSaveScheme(trimmed)
                naming = false
            },
        ) {
            CompactTextField(
                value = name,
                onValueChange = { name = it.take(LayoutScheme.MAX_NAME_LENGTH) },
                placeholder = "方案名，如「白天紧凑」",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    deleting?.let { scheme ->
        AppAlertDialog(
            onDismissRequest = { deleting = null },
            title = "删除排版方案「${scheme.name}」？",
            text = "只删除这套方案，当前排版保持不变",
            confirmText = "删除",
            onConfirm = {
                actions.onDeleteScheme(scheme.name)
                deleting = null
            },
        )
    }
}

/**
 * 字体：四款系统预设 + 用户导入的字体，尾巴上「导入字体」；选中的是导入字体时多一个「删除字体」。
 *
 * 选中的导入字体在本机不存在（别的设备同步来的 id）时，排版实际回落的是默认字体（见 FileFontRegistry），
 * 这里就亮「默认」—— 跟眼前的字对得上，而不是一个都不亮。
 */
@Composable
internal fun FontSection(
    settings: ReaderSettings,
    actions: TypographyActions,
    onSelect: (String) -> Unit,
    /** 同 [LayoutSchemeSection] 的同名参数 */
    horizontalPadding: Dp = 0.dp,
) {
    var deleting by remember { mutableStateOf<UserFont?>(null) }
    // MIME 不设限：.ttf/.otf 在各家文件管理器里报的类型五花八门（font/ttf、application/x-font-ttf、
    // application/octet-stream……），按类型过滤常常把字体文件本身滤没了。选错的文件由导入时校验兜住
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(actions.onImportFont)
    }
    val ids = ReaderSettings.FONT_PRESETS.map { it.first } + actions.fonts.map { it.id }
    val labels = ReaderSettings.FONT_PRESETS.map { it.second } + actions.fonts.map { it.label }
    val selected = ids.indexOf(settings.fontId).takeIf { it >= 0 } ?: 0
    val currentUserFont = actions.fonts.firstOrNull { it.id == settings.fontId }

    ChipRow(
        options = labels,
        selectedIndex = selected,
        onSelect = { onSelect(ids[it]) },
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        trailing = {
            if (currentUserFont != null) {
                AppFilterChip(selected = false, onClick = { deleting = currentUserFont }, label = "删除字体")
            }
            AppFilterChip(selected = false, onClick = { picker.launch(arrayOf("*/*")) }, label = "导入字体")
        },
    )

    deleting?.let { font ->
        AppAlertDialog(
            onDismissRequest = { deleting = null },
            title = "删除字体「${font.label}」？",
            text = "字体文件会从本机删除，正文换回默认字体",
            confirmText = "删除",
            onConfirm = {
                actions.onDeleteFont(font)
                deleting = null
            },
        )
    }
}
