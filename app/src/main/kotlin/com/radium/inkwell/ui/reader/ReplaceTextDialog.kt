package com.radium.inkwell.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.LocalDialogConfirm
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 选字后点「替换」：App 统一的输入弹窗（[AppAlertDialog]，手机上贴底、跟着键盘升起）。
 *
 * 从前是选字工具条原地换成一块贴底铺满的表单：阅读页藏了系统栏，「让开导航栏」那一截是 0，
 * 卡片底边压在屏幕底沿、两个圆角被屏幕圆角吃掉；键盘还得再点一下输入框才起来；
 * 原文只能看不能改；按钮叫「保存规则」，空着就是删除可按钮不变 —— 被指出「操作极不方便」。现在：
 *
 * - **原文是能改的框**，预填选中的那段：选区多一个字、少一个标点，删掉就行。选区松手后没有把手可调，不能改就只能取消重来。
 * - **「换成」一打开就聚焦**，键盘直接起来；键盘上的「完成」等于点主按钮。
 * - **收起时键盘和弹窗一起落**：取消、点外面、返回键、点「删除 / 替换」都一样（[AppAlertDialog] 的 closeOnConfirm）——
 *   确认也是先收下去、动画播完才建规则，不然弹窗一下没了、键盘后脚才落。
 * - **主按钮说清会发生什么**：「换成」空着是「删除」，填了是「替换」。
 * - **本章几处 + 改完的样子**（删的划线、换上的强调色）：正文被键盘和弹窗挡住了，上下文只能在这里给。
 *   原文太短、命中太多时那行变红（[isRiskyReplace]），照样能点。
 * - 规则只对这本书生效（同从前）。没给「所有书」这个选项：通用规则是抓正文时跑的、结果直接进缓存，
 *   已经缓存的章节 —— 包括眼前这一章 —— 都不会变，点完页面纹丝不动，只会让人以为没生效。
 *
 * 两个框的标签都是两个字（「原文」「换成」）：中文字等宽，输入起点天然对齐。
 *
 * @param preview 原文 → 本章的命中数与预览（[replacePreview]）；原文每改一个字算一遍，一章的字数算起来是毫秒级
 * @param onConfirm (原文, 换成)；收起动画播完才回调
 * @param onDismiss 取消 / 点外面 / 返回键，退场动画播完才回调（回到选字药丸，选区还在）
 */
@Composable
internal fun ReplaceTextDialog(
    initial: String,
    preview: (String) -> ReplacePreview?,
    onConfirm: (find: String, replacement: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var find by remember { mutableStateOf(initial.trim()) }
    var replacement by remember { mutableStateOf("") }
    val target = find.trim()
    val result = remember(target) { preview(target) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = "替换文字",
        confirmText = if (replacement.isEmpty()) "删除" else "替换",
        onConfirm = { onConfirm(target, replacement) },
        confirmEnabled = target.isNotEmpty(),
        closeOnConfirm = true,
    ) {
        val focus = remember { FocusRequester() }
        // 放在弹窗内容里、等一帧再要焦点：弹窗是独立窗口，内容是它自己的一份组合，
        // 在外面要的话框还没挂上（FocusRequester 未初始化直接抛错）；第一帧窗口也还没拿到焦点，要了键盘不一定起来
        LaunchedEffect(Unit) {
            withFrameNanos { }
            focus.requestFocus()
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapS)) {
            CompactTextField(
                value = find,
                onValueChange = { find = it },
                placeholder = "要替换的文字",
                label = "原文",
            )
            CompactTextField(
                value = replacement,
                onValueChange = { replacement = it },
                placeholder = "留空即删除",
                label = "换成",
                // 和点主按钮同一条路：原文空着时不响应，先收弹窗再建规则
                onDone = LocalDialogConfirm.current,
                modifier = Modifier.focusRequester(focus),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapXS)) {
            HitCount(target, replacement, result)
            if (result != null) PreviewLine(result, replacement)
        }
    }
}

/** 「本章 N 处」；有风险时变红并说一句后果 */
@Composable
private fun HitCount(target: String, replacement: String, result: ReplacePreview?) {
    val scheme = MiuixTheme.colorScheme
    val risky = result != null && isRiskyReplace(target, result.count)
    val text = when {
        target.isEmpty() -> ""
        result == null -> "本章没有找到"
        risky -> "本章 ${result.count} 处 · " + if (replacement.isEmpty()) "可能删到不该删的" else "可能改到不该改的"
        else -> "本章 ${result.count} 处"
    }
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1,
        color = if (risky) scheme.error else scheme.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 改完的样子：删掉的划线（红）、换上的强调色，前后各带一截正文 */
@Composable
private fun PreviewLine(result: ReplacePreview, replacement: String) {
    val scheme = MiuixTheme.colorScheme
    val text = remember(result, replacement, scheme.error, scheme.primary) {
        buildAnnotatedString {
            append(result.before)
            withStyle(SpanStyle(color = scheme.error, textDecoration = TextDecoration.LineThrough)) {
                append(result.match)
            }
            if (replacement.isNotEmpty()) {
                withStyle(SpanStyle(color = scheme.primary, fontWeight = FontWeight.Medium)) { append(replacement) }
            }
            append(result.after)
        }
    }
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1,
        color = scheme.onSurfaceVariantSummary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}
