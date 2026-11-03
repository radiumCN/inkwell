package com.radium.inkwell.ui.settings

import androidx.compose.runtime.Composable
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.update.UpdateChannel
import com.radium.inkwell.update.UpdateSource

/*
 * 更新源 / 更新渠道的选择面板。
 *
 * 从前这两行单独占一个二级页「更新源与渠道」：整页就两行，点进去各自再弹这个面板 —— 为两个选项多跳了一层。
 * 现在两行直接放在设置主页的「更新」组里，面板挪到这里给主页用。
 */

@Composable
internal fun UpdateChannelPicker(
    current: UpdateChannel,
    onPick: (UpdateChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionPickerSheet(
        title = "更新渠道",
        options = UpdateChannel.entries.map {
            PickerOption(
                id = it.name,
                label = it.label,
                subtitle = when (it) {
                    UpdateChannel.STABLE -> "只接收正式版本"
                    UpdateChannel.BETA -> "抢先体验新功能，可能不稳定"
                },
            )
        },
        selectedId = current.name,
        onSelect = { opt -> runCatching { UpdateChannel.valueOf(opt.id) }.getOrNull()?.let(onPick) },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun UpdateSourcePicker(
    current: UpdateSource,
    onPick: (UpdateSource) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionPickerSheet(
        title = "更新源",
        options = UpdateSource.entries.map {
            PickerOption(
                id = it.name,
                label = it.label,
                subtitle = when (it) {
                    UpdateSource.GITHUB -> "直接从 GitHub Releases 检查（需能访问 GitHub）"
                    UpdateSource.SERVER -> "中转服务器镜像，GitHub 受限时用；应用内下载校验后直接安装"
                },
            )
        },
        selectedId = current.name,
        onSelect = { opt -> runCatching { UpdateSource.valueOf(opt.id) }.getOrNull()?.let(onPick) },
        onDismiss = onDismiss,
    )
}
