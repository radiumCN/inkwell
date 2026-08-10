package com.radium.inkwell.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.update.UpdateChecker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    onOpenFeedback: () -> Unit,
    onOpenDisclaimer: () -> Unit,
) {
    val context = LocalContext.current
    val currentVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        topBar = { AppTopBar("关于", topBarScroll, onBack = onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingRow(
                title = "意见反馈",
                subtitle = "问题与建议直接提给开发者",
                onClick = onOpenFeedback,
            )
            SettingRow(
                title = "用户协议与免责声明",
                subtitle = "软件性质、使用责任与隐私说明",
                onClick = onOpenDisclaimer,
            )
            SettingRow(title = "版本", subtitle = "v$currentVersion")
            SettingRow(title = "开源许可", subtitle = "MIT License")
            SettingRow(
                title = "开源地址",
                subtitle = UpdateChecker.REPO_URL,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.REPO_URL)))
                },
            )
            Spacer(Modifier.height(Dimens.gapXL))
        }
    }
}
