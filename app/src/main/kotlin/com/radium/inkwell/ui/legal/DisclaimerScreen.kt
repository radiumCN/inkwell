package com.radium.inkwell.ui.legal

import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.AppScaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 用户协议与免责声明（只读）。
 *
 * 正文来自 `assets/legal/disclaimer.txt`，与仓库根目录 [DISCLAIMER.md] 保持同文；
 * 改条文时两处一起改，避免应用内与 GitHub 文档各说各话。
 */
@Composable
fun DisclaimerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val body = remember { loadDisclaimer(context) }

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("用户协议与免责声明", topBarScroll, onBack = onBack, containerColor = pageColor) },
    ) { padding ->
        Text(
            text = body,
            modifier = Modifier
                .fillMaxSize()
                // 顶栏、导航栏的高度都垫在滚动内容首尾：内容能滚到玻璃顶栏和手势条下面（见 AppScaffold）
                .padding(padding.horizontalOnly())
                .pageScroll()
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(horizontal = Dimens.screenPadding)
                .padding(
                    top = padding.calculateTopPadding() + Dimens.gapM,
                    bottom = Dimens.gapXL + padding.calculateBottomPadding(),
                ),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

private fun loadDisclaimer(context: Context): String =
    runCatching {
        context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrElse {
        "暂时无法加载声明全文。请查阅开源仓库中的 DISCLAIMER.md。"
    }

private const val ASSET_PATH = "legal/disclaimer.txt"
