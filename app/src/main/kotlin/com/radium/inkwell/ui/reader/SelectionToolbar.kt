package com.radium.inkwell.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import com.radium.inkwell.ui.components.AppFormField
import com.radium.inkwell.ui.components.AppTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Replace
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.squircle.squircleClip

/**
 * 选中文字后的操作条。
 *
 * 动作行用 Miuix [FloatingToolbar]（底部居中的浮动药丸）；
 * 替换表单仍用贴底 [Surface]（表单宽度与输入需要全宽，不适合塞进药丸）。
 *
 * 两者的底都取浮层色（[readerOverlayColor]）而不是卡片色：它们直接压在纸面上，
 * 而浅色纸的卡片色就是纸本身 —— 用卡片色等于纸上放一张看不见的纸。
 * 也不投影：同色纸上的阴影是一道脏灰线，层次只靠色阶（见 ReaderThemeScope）。
 * 给了 [pageBackdrop] 时和阅读菜单一样是毛玻璃（[readerGlass]）：浮层色只叠八成，底下的正文隐约透出来。
 */
@Composable
fun SelectionToolbar(
    selectedText: String,
    onCopy: () -> Unit,
    onPurify: () -> Unit,
    onReplace: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 录着翻页区的图层（见 ReaderScreen）；null 时退回实色底 */
    pageBackdrop: LayerBackdrop? = null,
) {
    val overlayColor = readerOverlayColor()
    val fill = if (pageBackdrop != null) Color.Transparent else overlayColor
    var replacing by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf("") }

    if (replacing) {
        Surface(
            // 贴底的输入表单：键盘弹起时整块让上去。导航栏与键盘取并集（谁大让谁），
            // 不是两个 padding 叠加 —— 叠加会把导航栏那截再算一遍
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                // 圆角用 squircle 遮罩裁（和 Miuix 卡片同一种弯法），玻璃本身铺成直角、被这层一起裁进去
                .squircleClip(AppShapes.radiusLarge)
                .readerGlass(pageBackdrop, RectangleShape, overlayColor)
                // 点在表单空白处别漏到纸面：纸面那层的「点空白取消选区」会把正在填的表单连同选区一起收掉
                .blockTouchesBelow(),
            color = fill,
            contentColor = MiuixTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.padding(horizontal = Dimens.listHorizontal, vertical = Dimens.listVertical)) {
                Text(
                    "「$selectedText」",
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                AppFormField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = "替换成",
                    placeholder = "留空即删除",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.gapS),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = Dimens.gapS),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AppTextButton("取消", onClick = {
                        replacing = false
                        replacement = ""
                    })
                    PrimaryButton(
                        text = "保存规则",
                        onClick = {
                            onReplace(replacement)
                            replacing = false
                            replacement = ""
                        },
                    )
                }
            }
        }
    } else {
        Column(
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.listVertical),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "「$selectedText」",
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(bottom = Dimens.gapS)
                    .fillMaxWidth(),
            )
            FloatingToolbar(
                // 药丸是 50dp 圆角，比自身高度一半还大，等于两头全圆：模糊区用同一个胶囊形
                modifier = Modifier.readerGlass(pageBackdrop, CircleShape, overlayColor),
                color = fill,
                // 外层 Column 已经留了边距；默认的 12/8dp 外边距再叠一层，药丸就离选中的那行字太远
                outSidePadding = PaddingValues(0.dp),
                shadowElevation = 0.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = Dimens.gapXS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconButton(onClick = onCopy) {
                        Icon(MiuixIcons.Copy, contentDescription = "复制")
                    }
                    // 最常用的动作：把这句话从本书里删掉
                    AppIconButton(onClick = onPurify) {
                        Icon(MiuixIcons.Clear, contentDescription = "净化")
                    }
                    AppIconButton(onClick = { replacing = true }) {
                        Icon(MiuixIcons.Replace, contentDescription = "替换")
                    }
                    AppIconButton(onClick = onDismiss) {
                        Icon(MiuixIcons.Close, contentDescription = "取消")
                    }
                }
            }
        }
    }
}
