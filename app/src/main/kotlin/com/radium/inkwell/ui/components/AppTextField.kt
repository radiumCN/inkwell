package com.radium.inkwell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction

/**
 * 紧凑输入框的两个变体（[SearchField] / [CompactTextField]）共用的 Expressive 视觉。
 *
 * 这两个框是**手搓的** `BasicTextField`，不是 M3 `TextField` —— 后者最小高度 56dp，顶栏和
 * 对话框行里放不下（这也是当初手搓的原因，别照着「统一用 M3 TextField」改回去）。
 * 但「装不下 M3 的组件」不等于「配色也自己发明」：容器形状取 [TextFieldDefaults.roundedShape]、
 * 容器/光标/文字色取 [TextFieldDefaults.tonalColors]，也就是 Expressive 那套 tonal 填充框的
 * 官方取值。从前这里写的是 `surfaceVariant` + 自定义圆角，主题换强调色时它不跟着走。
 *
 * 容器色**随聚焦切换**（focused / unfocused 两个槽位），这是 tonal 填充框的既有行为：
 * 光标在哪个框里，那个框的底色更实一点。不给 `BasicTextField` 传 interactionSource 就拿不到
 * 焦点态，只能一直用 unfocused 色 —— 那就丢了这半个反馈。
 */
private class TonalFieldStyle(
    val shape: Shape,
    val container: Color,
    val cursor: Color,
    val content: Color,
    val placeholder: Color,
)

@Composable
private fun rememberTonalFieldStyle(
    interactionSource: MutableInteractionSource,
    shape: Shape = TextFieldDefaults.roundedShape,
): TonalFieldStyle {
    val colors = TextFieldDefaults.tonalColors()
    val focused by interactionSource.collectIsFocusedAsState()
    return TonalFieldStyle(
        shape = shape,
        container = if (focused) colors.focusedContainerColor else colors.unfocusedContainerColor,
        cursor = colors.cursorColor,
        content = if (focused) colors.focusedTextColor else colors.unfocusedTextColor,
        placeholder = if (focused) colors.focusedPlaceholderColor else colors.unfocusedPlaceholderColor,
    )
}

/**
 * 紧凑搜索框：40dp 高、bodyMedium 字号、Expressive tonal 底色。
 * 顶栏/抽屉等空间敏感处用它，替代默认 56dp 的 OutlinedTextField。
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val style = rememberTonalFieldStyle(interactionSource)
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = style.content)
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
            .height(Dimens.searchFieldHeight)
            .background(style.container, style.shape),
        decorationBox = { innerTextField ->
            Row(
                Modifier.padding(start = Dimens.gapM, end = Dimens.gapXS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    Modifier.size(Dimens.iconSm),
                    tint = style.placeholder,
                )
                Box(Modifier.weight(1f).padding(horizontal = Dimens.gapS)) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = textStyle,
                            color = style.placeholder,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    // 不再钉死 32dp —— 让 IconButton 保持默认可点区，图标本身缩到 16dp 即可
                    AppIconButton(onClick = { onValueChange("") }) {
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

/** 紧凑单行输入框：40dp 高、bodyMedium 字号、Expressive tonal 底色，对话框/表单行内使用 */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val style = rememberTonalFieldStyle(interactionSource)
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = style.content)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(style.cursor),
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.compactFieldHeight)
            .background(style.container, style.shape),
        decorationBox = { innerTextField ->
            Box(
                Modifier.padding(horizontal = Dimens.gapM),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = textStyle,
                        color = style.placeholder,
                        maxLines = 1,
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * 多行紧凑输入：与 [CompactTextField] 同一套 tonal 填充，高度取 [Dimens.textAreaMinHeight]。
 *
 * 整页长文从前用 M3 `OutlinedTextField`：描边框和对话框/搜索框的填充框对不齐。
 * 也不能把 [CompactTextField] 拉高凑合 —— 那条是按 40dp 垂直居中的单行框，拉高后
 * placeholder 浮在正中，长文输入看起来像个被撑开的搜索框。
 *
 * 圆角故意走 [MaterialTheme.shapes.large]，不用 [TextFieldDefaults.roundedShape]：
 * `roundedShape` 按高度一半算胶囊，160dp 高的框会变成两头大圆。
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
    val style = rememberTonalFieldStyle(
        interactionSource,
        shape = MaterialTheme.shapes.large,
    )
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = style.content)
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
                .background(style.container, style.shape),
            decorationBox = { innerTextField ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.textAreaMinHeight)
                        .padding(Dimens.gapM),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = textStyle,
                            color = style.placeholder,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (supportingText != null) {
            // 字数之类跟在框外，对齐 M3 的 supportingText，避免和正文抢框内最后一行
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Dimens.gapM, top = Dimens.gapXS),
            )
        }
    }
}
