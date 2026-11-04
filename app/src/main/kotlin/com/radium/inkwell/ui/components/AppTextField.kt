package com.radium.inkwell.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 紧凑输入框（[SearchField] / [CompactTextField] / [CompactTextArea]）共用的视觉。
 *
 * 这几个框是**手搓的** `BasicTextField`，不是 Miuix `TextField` —— 后者上下各 16dp 内边距、
 * 带浮动 label，最矮也有 50 多 dp，顶栏和对话框行里放不下（这也是当初手搓的原因，别照着
 * 「统一用库里的 TextField」改回去）。但「装不下库里的组件」不等于「配色也自己发明」：
 * 底色、占位字色、光标与聚焦描边全取 Miuix 输入框读的那几个槽位
 * （`secondaryContainer` / `onSecondaryContainer` / `primary`），换主题时跟着走。
 *
 * 聚焦时描一圈强调色 —— 这是 Miuix 输入框的既有反馈：光标在哪个框里，哪个框亮一圈。
 * 不给 `BasicTextField` 传 interactionSource 就拿不到焦点态，这半个反馈就丢了。
 */
private class FieldStyle(
    val container: Color,
    val cursor: Color,
    val content: Color,
    val placeholder: Color,
    val focusedBorder: Color?,
)

@Composable
private fun rememberFieldStyle(
    interactionSource: MutableInteractionSource,
    container: Color = MiuixTheme.colorScheme.secondaryContainer,
    placeholder: Color = MiuixTheme.colorScheme.onSecondaryContainer,
    isError: Boolean = false,
): FieldStyle {
    val scheme = MiuixTheme.colorScheme
    val focused by interactionSource.collectIsFocusedAsState()
    return FieldStyle(
        container = container,
        cursor = scheme.primary,
        content = scheme.onSurface,
        placeholder = placeholder,
        focusedBorder = when {
            isError -> scheme.error
            focused -> scheme.primary
            else -> null
        },
    )
}

/** squircle 底 + 聚焦描边。描边用普通圆角：1dp 的线上看不出连续曲率，squircle 描边反而要多一层着色器 */
@Composable
private fun Modifier.fieldChrome(style: FieldStyle, radius: Dp): Modifier {
    val base = squircleBackground(style.container, radius)
    val border = style.focusedBorder ?: return base
    return base.border(Dimens.strokeThin, border, RoundedCornerShape(radius))
}

/**
 * 紧凑搜索框：默认 40dp 高、body2 字号。顶栏/工具条等空间敏感处用它。
 *
 * 底色取 `surfaceContainerHigh`（Miuix 搜索栏读的那一档）：它坐在**页面**上，不是坐在卡片上 ——
 * 用输入框那档 `secondaryContainer` 的话，深色主题下和页面底几乎同色。两头是半高圆角的胶囊，HyperOS 的搜索栏就是这个形。
 *
 * 高度是**下限**不是定值：系统字体调大（Android 14 起最大 200%）时随字号长高。从前钉死 40dp，
 * 大字号下文字上下边直接被切掉。清空按钮的高度单独封顶在 40dp —— 它带 48dp 的最小可点区，
 * 不封顶的话一输入字整个框就从 40dp 跳到 48dp。
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    /**
     * 框的底色。默认「页面上的搜索栏」那一档；搜索框若坐在别的底上（阅读目录面板 —— 浅纸上浮层恰好也是
     * surfaceContainerHigh，框会整个隐身），传它所在那层的上一档，比如卡片色
     */
    containerColor: Color = MiuixTheme.colorScheme.surfaceContainerHigh,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val style = rememberFieldStyle(
        interactionSource,
        container = containerColor,
        placeholder = MiuixTheme.colorScheme.onSurfaceContainerHigh,
    )
    val textStyle = MiuixTheme.textStyles.body2.copy(color = style.content)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(style.cursor),
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions(
            imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.searchFieldHeight)
            .fieldChrome(style, Dimens.searchFieldHeight / 2),
        decorationBox = { innerTextField ->
            Row(
                Modifier.padding(start = Dimens.gapM, end = Dimens.gapXS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MiuixIcons.Search,
                    contentDescription = null,
                    Modifier.size(Dimens.iconSm),
                    tint = style.placeholder,
                )
                // 上下留一点：字号大到撑高整个框时，文字别贴着边
                Box(Modifier.weight(1f).padding(horizontal = Dimens.gapS, vertical = Dimens.gapXS)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = textStyle, color = style.placeholder, maxLines = 1)
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    AppIconButton(
                        onClick = { onValueChange("") },
                        modifier = Modifier.heightIn(max = Dimens.searchFieldHeight),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "清空",
                            Modifier.size(Dimens.iconSm),
                            tint = style.placeholder,
                        )
                    }
                }
            }
        },
    )
}

/**
 * 紧凑单行输入框：默认 40dp 高、body2 字号，对话框/表单行内使用（坐在卡片或弹窗上，底色取输入框那档）。
 * 高度同 [SearchField] 是下限，大字号下随字号长高。
 */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val style = rememberFieldStyle(interactionSource)
    val textStyle = MiuixTheme.textStyles.body2.copy(color = style.content)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(style.cursor),
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.compactFieldHeight)
            .fieldChrome(style, AppShapes.radiusMedium),
        decorationBox = { innerTextField ->
            Box(
                Modifier.padding(horizontal = Dimens.gapM, vertical = Dimens.gapXS),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = textStyle, color = style.placeholder, maxLines = 1)
                }
                innerTextField()
            }
        },
    )
}

/**
 * 多行紧凑输入：与 [CompactTextField] 同一套底色，高度取 [Dimens.textAreaMinHeight]。
 *
 * 不能把 [CompactTextField] 拉高凑合 —— 那条是按 40dp 垂直居中的单行框，拉高后
 * placeholder 浮在正中，长文输入看起来像个被撑开的搜索框。
 * 圆角走 [AppShapes.radiusLarge]（与卡片同档），不用胶囊：160dp 高的框按半高收圆角会变成两头大圆。
 */
@Composable
fun CompactTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val style = rememberFieldStyle(interactionSource, isError = isError)
    val textStyle = MiuixTheme.textStyles.body2.copy(color = style.content)
    Column(modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = false,
            textStyle = textStyle,
            cursorBrush = SolidColor(style.cursor),
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.textAreaMinHeight)
                .fieldChrome(style, AppShapes.radiusLarge),
            decorationBox = { innerTextField ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.textAreaMinHeight)
                        .padding(Dimens.gapM),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = textStyle, color = style.placeholder)
                    }
                    innerTextField()
                }
            },
        )
        if (supportingText != null) {
            FieldSupportingText(supportingText, isError)
        }
    }
}

/**
 * 整页表单里带 label 的输入（WebDAV 地址/账号/密码、净化规则的名称/规则）：Miuix [TextField]。
 *
 * Miuix 的 label 是**浮动**的：空着时 label 就是框里那行灰字，一输入/一聚焦就缩到框的上沿 ——
 * 与 M3 `OutlinedTextField` 同一种交互，所以 M3 时代的 label 直接搬过来。
 * 它没有单独的 placeholder 槽：传了 [placeholder] 时 label 退到框外上方做标题、placeholder 进框当灰字提示
 * （例子写在 placeholder 里更有用，「https://dav.jianguoyun.com/dav/」比一行「地址」好懂）。
 *
 * 也没有 isError：报错时自己描一圈 error 色（Miuix 的描边只在聚焦时画，光换它的 borderColor 的话，
 * 焦点一离开红框就没了 —— 填错了地址再去点保存，框看着一切正常），[supportingText] 跟在框外、对齐 M3 的位置。
 * 对话框 / 表单行里空间紧的仍用 [CompactTextField]。
 */
@Composable
fun AppFormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val scheme = MiuixTheme.colorScheme
    val colors = if (isError) {
        TextFieldDefaults.textFieldColors(borderColor = scheme.error)
    } else {
        TextFieldDefaults.textFieldColors()
    }
    Column(modifier.fillMaxWidth()) {
        if (placeholder != null) {
            Text(
                label,
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = Dimens.gapM, bottom = Dimens.gapXS),
            )
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                // 线宽对齐 Miuix 聚焦描边（2dp）：聚焦时两圈叠在同一处，看着就是一圈
                .then(
                    if (isError) {
                        Modifier.squircleBorder(Dimens.strokeThick, scheme.error, TextFieldDefaults.CornerRadius)
                    } else {
                        Modifier
                    },
                ),
            colors = colors,
            label = placeholder ?: label,
            // 有 placeholder 时它只是框内提示，一输入就该消失，不该浮到上沿
            useLabelAsPlaceholder = placeholder != null,
            enabled = enabled,
            keyboardOptions = keyboardOptions,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
        )
        if (supportingText != null) {
            FieldSupportingText(supportingText, isError)
        }
    }
}

/** 字数、报错之类跟在框外，对齐 M3 的 supportingText，避免和正文抢框内最后一行 */
@Composable
private fun FieldSupportingText(text: String, isError: Boolean) {
    val scheme = MiuixTheme.colorScheme
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1,
        color = if (isError) scheme.error else scheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = Dimens.gapM, top = Dimens.gapXS),
    )
}
