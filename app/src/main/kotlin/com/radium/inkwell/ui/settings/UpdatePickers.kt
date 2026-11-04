package com.radium.inkwell.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SpinnerSettingRow
import com.radium.inkwell.update.UpdateChannel
import com.radium.inkwell.update.UpdateSource

/*
 * 设置主页「更新」组里的更新源 / 更新渠道两行。
 *
 * 从前这两行单独占一个二级页「更新源与渠道」：整页就两行，点进去各自再弹选择面板 —— 为两个选项多跳了一层。
 * 后来挪回主页、点行弹底部面板；现在是 HyperOS 的选择行（SpinnerSettingRow）：行尾就是当前值，
 * 点一下选项从这一行上弹出来，选完就收。
 */

@Composable
internal fun UpdateChannelRow(
    current: UpdateChannel,
    onPick: (UpdateChannel) -> Unit,
    icon: ImageVector,
    position: SettingGroupPosition,
) {
    SpinnerSettingRow(
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
        position = position,
        icon = icon,
    )
}

@Composable
internal fun UpdateSourceRow(
    current: UpdateSource,
    onPick: (UpdateSource) -> Unit,
    icon: ImageVector,
    position: SettingGroupPosition,
) {
    SpinnerSettingRow(
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
        position = position,
        icon = icon,
    )
}
