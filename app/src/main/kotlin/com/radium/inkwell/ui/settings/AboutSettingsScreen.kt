package com.radium.inkwell.ui.settings

import com.radium.inkwell.ui.components.exceptBottom
import com.radium.inkwell.ui.components.SectionHeader
import android.os.SystemClock
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.ui.components.NO_BROWSER_MESSAGE
import com.radium.inkwell.ui.components.openExternalLink
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.update.UpdateChecker
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 网页版 / 官网，与 README 里的 book.skylark.run 同一处 */
private const val SITE_URL = "https://book.skylark.run"

/** 连点版本号几下打开开发者模式（同系统设置的 7 下）；从第 [DEV_TAPS_HINT_FROM] 下起提示还差几下 */
private const val DEV_TAPS = 7
private const val DEV_TAPS_HINT_FROM = 3
/** 两下之间超过这个间隔就从头数：隔了半天又点一下，不该接着上次的数 */
private const val DEV_TAP_WINDOW_MS = 1500L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    onOpenFeedback: () -> Unit,
    onOpenDisclaimer: () -> Unit,
    onOpenDeveloper: () -> Unit,
) {
    val context = LocalContext.current
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    val developerMode = appPrefs.developerModeState.collectAsStateWithLifecycle().value ?: false
    val messages = remember { MessageBus() }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(messages, snackbar)
    var versionTaps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
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
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                // 内容滚到手势条下面，导航栏高度垫在末尾（见 exceptBottom）
                .padding(padding.exceptBottom())
                .verticalScroll(rememberScrollState())
                .padding(top = Dimens.gapS, bottom = padding.calculateBottomPadding()),
        ) {
            SectionHeader("支持")
            SettingGroup {
                SettingRow(
                    title = "意见反馈",
                    onClick = onOpenFeedback,
                    position = SettingGroupPosition.First,
                )
                SettingRow(
                    title = "用户协议与免责声明",
                    onClick = onOpenDisclaimer,
                    position = if (developerMode) SettingGroupPosition.Middle else SettingGroupPosition.Last,
                )
                if (developerMode) {
                    SettingRow(
                        title = "开发者选项",
                        onClick = onOpenDeveloper,
                        position = SettingGroupPosition.Last,
                    )
                }
            }
            SectionHeader("应用信息")
            SettingGroup {
                SettingRow(
                    title = "版本",
                    value = "v$currentVersion",
                    onClick = {
                        if (developerMode) {
                            messages.emit("已处于开发者模式")
                        } else {
                            val now = SystemClock.uptimeMillis()
                            versionTaps = if (now - lastTapAt > DEV_TAP_WINDOW_MS) 1 else versionTaps + 1
                            lastTapAt = now
                            val left = DEV_TAPS - versionTaps
                            when {
                                left <= 0 -> {
                                    versionTaps = 0
                                    scope.launch { appPrefs.setDeveloperMode(true) }
                                    messages.emit("已开启开发者模式")
                                }
                                versionTaps >= DEV_TAPS_HINT_FROM ->
                                    messages.emit("再点 $left 次进入开发者模式")
                            }
                        }
                    },
                    position = SettingGroupPosition.First,
                )
                SettingRow(
                    title = "开源许可",
                    value = "MIT License",
                    position = SettingGroupPosition.Middle,
                )
                SettingRow(
                    title = "官网（web）",
                    value = SITE_URL,
                    onClick = {
                        if (!context.openExternalLink(SITE_URL)) messages.emit(NO_BROWSER_MESSAGE)
                    },
                    position = SettingGroupPosition.Middle,
                )
                SettingRow(
                    title = "开源地址",
                    value = UpdateChecker.REPO_URL,
                    onClick = {
                        if (!context.openExternalLink(UpdateChecker.REPO_URL)) {
                            messages.emit(NO_BROWSER_MESSAGE)
                        }
                    },
                    position = SettingGroupPosition.Last,
                )
            }
            Spacer(Modifier.height(Dimens.gapXL))
        }
    }
}
