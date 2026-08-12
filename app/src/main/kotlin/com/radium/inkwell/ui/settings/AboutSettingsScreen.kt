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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.settingsPageColor
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

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("关于", topBarScroll, onBack = onBack, containerColor = pageColor) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = Dimens.gapS),
        ) {
            SettingGroup {
                SettingRow(
                    title = "意见反馈",
                    onClick = onOpenFeedback,
                    position = SettingGroupPosition.First,
                )
                SettingRow(
                    title = "用户协议与免责声明",
                    onClick = onOpenDisclaimer,
                    position = SettingGroupPosition.Last,
                )
            }
            SettingGroup {
                SettingRow(
                    title = "版本",
                    value = "v$currentVersion",
                    position = SettingGroupPosition.First,
                )
                SettingRow(
                    title = "开源许可",
                    value = "MIT License",
                    position = SettingGroupPosition.Middle,
                )
                SettingRow(
                    title = "开源地址",
                    value = UpdateChecker.REPO_URL,
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.REPO_URL)))
                    },
                    position = SettingGroupPosition.Last,
                )
            }
            Spacer(Modifier.height(Dimens.gapXL))
        }
    }
}
