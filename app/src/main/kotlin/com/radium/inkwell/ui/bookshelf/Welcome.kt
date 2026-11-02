package com.radium.inkwell.ui.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow

/**
 * 第一次打开、书架还是空的时候，代替「书架空空如也」出现的欢迎页。
 *
 * 应用默认不带任何内容，新用户第一眼看到的就是一个空书架和一个「导入」按钮 —— 可换设备的老用户
 * 要的其实是「把那边的书架接过来」，而这条路藏在设置 → WebDAV 里，第一次来的人根本想不到。
 * 所以把三条路摆平：导入本地书、从 WebDAV 恢复、先看看。
 *
 * 只出现在**从没用过**的空书架上（见 AppPrefs.onboardedState）：书全藏进隐藏区的空书架仍是普通空态，
 * 两者在外人眼里必须一模一样。
 */
@Composable
internal fun WelcomeState(
    onImport: () -> Unit,
    onRestore: () -> Unit,
    onSkip: () -> Unit,
    onOpenDisclaimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Dimens.gapXXL),
        verticalArrangement = Arrangement.spacedBy(Dimens.gapL),
    ) {
        Column(
            Modifier.padding(horizontal = Dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            Icon(
                Icons.Default.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(Dimens.iconXL),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "欢迎使用墨水",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "一个安静读书的地方。书架还是空的，从下面任选一条开始：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingGroup {
            SettingRow(
                title = "导入本地书",
                subtitle = "txt / EPUB / MOBI，书只存在这台设备上",
                onClick = onImport,
                position = SettingGroupPosition.First,
            )
            SettingRow(
                title = "从 WebDAV 恢复",
                subtitle = "在别的设备上用过墨水？把书架、进度和设置接过来",
                onClick = onRestore,
                position = SettingGroupPosition.Middle,
            )
            SettingRow(
                title = "先随便看看",
                subtitle = "之后随时可以从右上角导入",
                onClick = onSkip,
                position = SettingGroupPosition.Last,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.screenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "使用即表示同意",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppTextButton(onClick = onOpenDisclaimer) {
                Text("用户协议与免责声明", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
