package com.radium.inkwell.ui.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.prefs.LayoutScheme
import com.radium.inkwell.data.repo.FontImportStage
import com.radium.inkwell.data.repo.ReaderFontStore
import com.radium.inkwell.data.repo.UserFont
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.measure.FontRegistry
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.settings.formatSize
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 阅读设置面板里「字体」「排版方案」两块要的数据与动作。打成一包往下传，
 * 而不是在 ReaderMenu → TypographyPanel → 各页三层签名上各挂七个参数。
 *
 * [fontRegistry] 是排版用的同一个解析器：字体条上的字体名用它解析出的那款字写，和正文排出来的一模一样
 */
@Stable
class TypographyActions(
    val fonts: List<UserFont>,
    val fontRegistry: FontRegistry,
    val schemes: List<LayoutScheme>,
    val onImportFont: (Uri) -> Unit,
    val onAdjust: (ReaderSettings.() -> ReaderSettings) -> Unit,
    val fontImportStage: FontImportStage?,
    val fontImportError: String?,
    val onDismissFontImportError: () -> Unit,
    val onDeleteFont: (UserFont) -> Unit,
    val onApplyScheme: (LayoutScheme) -> Unit,
    val onSaveScheme: (String) -> Unit,
    val onDeleteScheme: (String) -> Unit,
    val bookLayoutEnabled: Boolean,
    val onSetBookLayoutEnabled: (Boolean) -> Unit,
)

@Composable
fun rememberTypographyActions(viewModel: ReaderViewModel): TypographyActions {
    val fonts by viewModel.userFonts.collectAsStateWithLifecycle()
    val schemes by viewModel.layoutSchemes.collectAsStateWithLifecycle()
    val bookLayoutEnabled by viewModel.bookLayoutEnabled.collectAsStateWithLifecycle()
    val importStage by viewModel.fontImportStage.collectAsStateWithLifecycle()
    val importError by viewModel.fontImportError.collectAsStateWithLifecycle()
    val fontRegistry = koinInject<ReaderFontStore>().registry
    return remember(fonts, schemes, bookLayoutEnabled, importStage, importError, viewModel, fontRegistry) {
        TypographyActions(
            fonts = fonts,
            fontRegistry = fontRegistry,
            schemes = schemes,
            onImportFont = viewModel::importFont,
            onAdjust = viewModel::adjustTypography,
            fontImportStage = importStage,
            fontImportError = importError,
            onDismissFontImportError = viewModel::dismissFontImportError,
            onDeleteFont = viewModel::deleteFont,
            onApplyScheme = viewModel::applyLayoutScheme,
            onSaveScheme = viewModel::saveLayoutScheme,
            onDeleteScheme = viewModel::deleteLayoutScheme,
            bookLayoutEnabled = bookLayoutEnabled,
            onSetBookLayoutEnabled = viewModel::setBookLayoutEnabled,
        )
    }
}

/**
 * 「排版方案」一组：卡里是已存的方案，一排 chip，点一下整套换上；动作在分组标题的行尾 ——
 * 当前排版正好是某套方案时是「删除」，否则是「存为方案」。
 *
 * 动作不再是 chip 条末尾的一个 chip：和方案名长得一模一样，看着像一套叫「存为方案」的方案（字体那组同理）。
 * 两个动作共用一个位置而不是并排：排版和某套方案一模一样时再存一份毫无意义；
 * 想改某套方案，就先调到满意（此时已不再匹配），再用同名存一次覆盖它。
 */
@Composable
internal fun LayoutSchemeSection(
    settings: ReaderSettings,
    actions: TypographyActions,
) {
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LayoutScheme?>(null) }
    val schemes = actions.schemes
    val current = schemes.indexOfFirst { it.matches(settings) }

    ReaderGroup(
        title = "管理方案",
        trailing = {
            if (current >= 0) {
                AppTextButton("删除", onClick = { deleting = schemes[current] }, contentColor = MiuixTheme.colorScheme.error)
            } else {
                AppTextButton("存为方案", onClick = { naming = true })
            }
        },
    ) {
        Box(Modifier.padding(vertical = Dimens.gapS)) {
            if (schemes.isNotEmpty()) {
                ChipRow(
                    options = schemes.map { it.name },
                    selectedIndex = current,
                    onSelect = { actions.onApplyScheme(schemes[it]) },
                    // 左右内缩放在滚动内容里：chip 条随内容滚到卡的边缘，而不是在内缩线上被截断
                    contentPadding = PaddingValues(horizontal = Dimens.listHorizontal),
                    // 选中态与阅读设置面板里的分段按钮一致（实心强调色），见 ChipRow
                    solidSelection = true,
                )
            } else {
                Text(
                    "把现在的字号、字体、行距和边距存起来，换个场景一键切回。纸色不在方案里，仍按日夜各记一套",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = Dimens.listHorizontal),
                )
            }
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
 * 字体条：四款系统预设 + 用户导入的字体，一点就换，正文当场重排。
 *
 * - **字体名用那款字本身写**（[TypographyActions.fontRegistry]，和正文同一个解析器）：换之前就看得到长相，
 *   不用一款款点过去再看正文。
 * - 导入字体放在列表上方的标题行，横滑字体列表时仍可使用。
 * - **导入的字体长按删除**（删除收进长按，同书源、净化规则）：从前选中导入字体时条末尾多一颗「删除字体」，
 *   一会儿有一会儿没有，条的长度跟着跳。
 * - 不弹列表、不进二级：弹出来的列表会挡住正文，正好挡掉要看的效果。
 *
 * 选中的导入字体在本机不存在（别的设备同步来的 id）时，排版实际回落的是默认字体（见 FileFontRegistry），
 * 这里就亮「默认」—— 跟眼前的字对得上，而不是一个都不亮。
 */
@Composable
internal fun FontSection(
    settings: ReaderSettings,
    actions: TypographyActions,
    onSelect: (String) -> Unit,
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
    // 解析一次就够：导入字体的解析要碰一下文件（见 FileFontRegistry），别每次重组都去
    val families = remember(ids, actions.fontRegistry) { ids.map { actions.fontRegistry.resolve(it) } }

    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget).padding(horizontal = Dimens.listHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("字体", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (actions.fontImportStage == null) {
            AppTextButton(
                "导入字体",
                onClick = { picker.launch(arrayOf("*/*")) },
                contentPadding = PaddingValues(horizontal = Dimens.gapXS),
            )
        }
    }
    ChipRow(
        options = labels,
        selectedIndex = selected,
        onSelect = { onSelect(ids[it]) },
        contentPadding = PaddingValues(horizontal = Dimens.listHorizontal),
        solidSelection = true,
        fontFamilies = families,
        longClickActions = ReaderSettings.FONT_PRESETS.map { null } +
            actions.fonts.map { font -> { deleting = font } },
        longClickLabel = "删除字体",
    )

    actions.fontImportStage?.let { stage ->
        Row(
            modifier = Modifier.padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            AppLoadingIndicator(size = Dimens.iconSm)
            Text(
                when (stage) {
                    FontImportStage.READING -> "正在读取字体文件…"
                    FontImportStage.VALIDATING -> "正在校验字体…"
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MiuixTheme.textStyles.footnote1,
            )
        }
    }
    actions.fontImportError?.let { error ->
        AppAlertDialog(
            onDismissRequest = actions.onDismissFontImportError,
            title = "字体导入失败",
            text = error,
            confirmText = "重新选择",
            closeOnConfirm = true,
            onConfirm = {
                actions.onDismissFontImportError()
                picker.launch(arrayOf("*/*"))
            },
        )
    }

    deleting?.let { font ->
        val size = formatSize(font.sizeBytes)
        val deleteMessage = "将删除本机的字体文件（$size）。"
        AppAlertDialog(
            onDismissRequest = { deleting = null },
            title = "删除字体「${font.label}」？",
            // 长按删的不一定是正在用的那款：只有删的是它，正文才会换回默认字体
            text = if (font.id == settings.fontId) "$deleteMessage 正文将换回默认字体。" else deleteMessage,
            confirmText = "删除",
            onConfirm = {
                actions.onDeleteFont(font)
                deleting = null
            },
        )
    }
}
