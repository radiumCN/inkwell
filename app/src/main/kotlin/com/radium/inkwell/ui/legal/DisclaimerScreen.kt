package com.radium.inkwell.ui.legal

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.settingsPageColor

/**
 * 用户协议与免责声明（只读）。
 *
 * 正文来自 `assets/legal/disclaimer.txt`，与仓库根目录 [DISCLAIMER.md] 保持同文；
 * 改条文时两处一起改，避免应用内与 GitHub 文档各说各话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisclaimerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val body = remember { loadDisclaimer(context) }

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("用户协议与免责声明", topBarScroll, onBack = onBack, containerColor = pageColor) },
    ) { padding ->
        Text(
            text = body,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenPadding)
                .padding(top = Dimens.gapM, bottom = Dimens.gapXL),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
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
