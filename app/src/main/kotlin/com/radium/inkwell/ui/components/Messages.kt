package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.ui.theme.AppThemes
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarData
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一次性提示事件流。
 * 不能用 StateFlow：它会对相同值去重，连续两次结果相同的操作（如重复导入）
 * 第二次提示会被静默吞掉，用户以为操作没生效。
 */
class MessageBus {
    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: Flow<String> = _messages

    fun emit(message: String) {
        _messages.tryEmit(message)
    }
}

/**
 * 订阅提示事件并弹 Snackbar；新消息到达时替换正在显示的那条。
 *
 * 必须是 collectLatest：showSnackbar 要挂到这条提示消失才返回，普通 collect 在这期间根本不取下一条 ——
 * 新消息只会排队，一条条等满 4 秒（连点 7 次版本号，「已开启开发者模式」要十几秒后才轮到）。
 *
 * 但光取消还撤不下旧的那条：Miuix 的 SnackbarHostState 被取消时不收回已经加上去的那条，宿主又把每一条都画出来，
 * 连点七次就是七颗药丸叠在一起。所以新消息先 dismiss 掉屏上那条，再弹自己。
 */
@Composable
fun CollectMessages(bus: MessageBus, snackbar: SnackbarHostState) {
    LaunchedEffect(bus, snackbar) {
        bus.messages.collectLatest { msg ->
            snackbar.newestSnackbarData()?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }
}

/**
 * 全应用统一的一次性提示。
 *
 * 不走 Miuix 默认的 `Snackbar`：那条左右只留 12dp、通栏铺开，短到「已是最新版本」六个字也会被拉成贴底横杠。
 * 这里是**居中、随文案收窄的浮起胶囊**（HyperOS 的 Toast 就是这个样子）：
 * - 短提示是一颗药丸，长提示才横向展开（上限吃满宿主宽度）；
 * - 底色/字色取 Miuix Snackbar 自己读的那一对（`onSecondaryVariant` 底、`secondaryVariant` 字 ——
 *   浅色主题下是深底浅字、深色主题下自动对调），对比度自带；
 * - 圆角走 [AppShapes.extraLarge]；**不投影**，靠深浅对比浮起；
 * - 正文用 body2，操作键用强调色 —— 但要**推到胶囊上读得清**：浅色主题的强调色本就是深色（暖纸的墨褐），
 *   直接放在近黑的胶囊上只有约 1.9:1。M3 时代用 inversePrimary 解决，Miuix 没有这个槽，这里现场推一档。
 */
@Composable
fun AppSnackbar(
    data: SnackbarData,
    modifier: Modifier = Modifier,
) {
    val visuals = data.visuals
    val scheme = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    val container = scheme.onSecondaryVariant
    val action = remember(scheme.primary, container) { AppThemes.readableOn(scheme.primary, container) }
    Surface(
        modifier = modifier,
        shape = AppShapes.extraLarge,
        color = container,
        contentColor = scheme.secondaryVariant,
    ) {
        Row(
            Modifier.padding(
                start = Dimens.gapL,
                // 有操作按钮时右侧少留一点，避免按钮外侧空一截
                end = if (visuals.actionLabel != null) Dimens.gapXS else Dimens.gapL,
                top = Dimens.gapM,
                bottom = Dimens.gapM,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            Text(
                text = visuals.message,
                // fill=false：短文案按自身宽度收，别被 Row 撑满
                modifier = Modifier.weight(1f, fill = false),
                style = MiuixTheme.textStyles.body2,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            visuals.actionLabel?.let { label ->
                AppTextButton(
                    text = label,
                    onClick = { scope.launch { data.performAction() } },
                    contentColor = action,
                )
            }
        }
    }
}

/**
 * 替代裸 `SnackbarHost`：把 [AppSnackbar] 居中托起（Miuix [SnackbarHost]，[SnackbarHostState] 也是 Miuix 的那个）。
 * 两侧/底边留白走 [Dimens.screenPadding]，像浮在页面上，不是焊在底边。
 * 页面只通过这里挂 host，别自己写 `snackbarHost = { SnackbarHost(...) }`。
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(
        state = hostState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.screenPadding)
            .padding(bottom = Dimens.gapS),
    ) { data ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AppSnackbar(
                data = data,
                modifier = Modifier.wrapContentWidth(align = Alignment.CenterHorizontally),
            )
        }
    }
}
