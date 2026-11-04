package com.radium.inkwell.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.data.prefs.WebDavConfig
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.formatRelativeTime
import com.radium.inkwell.ui.stats.ReadingSummary
import com.radium.inkwell.ui.stats.formatDuration
import androidx.compose.ui.text.font.FontWeight
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.CloudFill

/**
 * 设置主页顶上的概览：今天读了多久、同步得怎么样，外加一个「立即同步」。
 *
 * 这两样是设置页里最常来看的状态。从前一个是「阅读记录」那行行尾的小字，一个是「WebDAV 备份同步」行尾的
 * 「3 小时前同步」—— 看得到，却做不了什么：想同步还得先点进 WebDAV 页再点按钮。放到一张卡上，
 * 看和做在同一处。
 *
 * 用 primaryContainer 铺底（淡强调色，Miuix 组件不读这个槽，保留的是 M3 时代的含义），与书架「继续阅读」卡、
 * 阅读记录「今天」卡同一种：一页上唯一带颜色的面。四角是与 Miuix 卡片同档的连续曲率圆角。
 * 文字统一 onPrimaryContainer，层级靠字号拉开；同步失败靠图标标出来，原因仍用同一种文字色 ——
 * 这块底色上没有对应的「报错色」令牌，红字放在上面对比度没保证。
 */
@Composable
internal fun SettingsOverviewCard(
    reading: ReadingSummary?,
    sync: WebDavConfig?,
    syncing: Boolean,
    onOpenStats: () -> Unit,
    onOpenWebDav: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = MiuixTheme.colorScheme.onPrimaryContainer
    Column(
        modifier
            .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapS)
            .fillMaxWidth()
            // 底色 + 裁切一起做：两行的按压反馈也被圈在圆角里
            .squircleSurface(MiuixTheme.colorScheme.primaryContainer, AppShapes.radiusLarge),
    ) {
        // ---- 阅读 ----
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpenStats)
                .padding(Dimens.gapL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.gapXS)) {
                Text(
                    readingTitle(reading),
                    style = MiuixTheme.textStyles.headline2,
                    fontWeight = FontWeight.Medium,
                    color = content,
                )
                readingSubtitle(reading)?.let {
                    Text(it, style = MiuixTheme.textStyles.body2, color = content)
                }
            }
            OverviewChevron(tint = content)
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = Dimens.gapL),
            color = content.copy(alpha = 0.12f),
        )

        // ---- 同步 ----
        val configured = sync?.isConfigured == true
        val failed = sync != null && sync.lastSyncErrorAt > sync.lastSyncAt
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpenWebDav)
                .padding(start = Dimens.gapL, end = Dimens.gapM, top = Dimens.gapM, bottom = Dimens.gapM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when {
                    !configured -> MiuixIcons.UploadCloud
                    failed -> MiuixIcons.Report
                    else -> MiuixIcons.CloudFill
                },
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(Dimens.iconMd),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = Dimens.gapM),
            ) {
                Text(syncTitle(sync, syncing), style = MiuixTheme.textStyles.body1, color = content)
                syncSubtitle(sync, syncing)?.let {
                    Text(
                        it,
                        style = MiuixTheme.textStyles.footnote1,
                        color = content,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (configured) {
                PrimaryButton(
                    text = if (failed && !syncing) "重试" else "立即同步",
                    onClick = onSyncNow,
                    loading = syncing,
                )
            } else {
                OverviewChevron(tint = content)
            }
        }
    }
}

/**
 * 概览卡两行行尾的 ›：Miuix 设置行同一个细箭头（ArrowPreference 用的那张图），只是染成卡上的字色。
 * 纯装饰（整行可点、行文字就是名字），RTL 下镜像。
 */
@Composable
private fun OverviewChevron(tint: Color) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Icon(
        MiuixIcons.Basic.ArrowRight,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.graphicsLayer { scaleX = if (rtl) -1f else 1f },
    )
}

private fun readingTitle(s: ReadingSummary?): String = when {
    s == null -> "阅读记录"
    s.isEmpty -> "还没有阅读记录"
    s.todayMillis > 0 -> "今天读了 ${formatDuration(s.todayMillis)}"
    else -> "今天还没读"
}

private fun readingSubtitle(s: ReadingSummary?): String? = when {
    s == null -> null
    s.isEmpty -> "翻开一本书，读书时长会记在这里"
    else -> buildString {
        if (s.streakDays > 0) append("连续读了 ${s.streakDays} 天 · ")
        append("累计 ${formatDuration(s.totalMillis)}")
    }
}

private fun syncTitle(c: WebDavConfig?, syncing: Boolean): String = when {
    c == null -> "WebDAV 同步"
    !c.isConfigured -> "WebDAV 同步未开启"
    syncing -> "正在同步…"
    c.lastSyncErrorAt > c.lastSyncAt -> "上次同步失败"
    c.lastSyncAt > 0 -> "${formatRelativeTime(c.lastSyncAt)}同步"
    else -> "还没同步过"
}

private fun syncSubtitle(c: WebDavConfig?, syncing: Boolean): String? = when {
    c == null || syncing -> null
    !c.isConfigured -> "在多台设备间同步书架、进度和设置"
    c.lastSyncErrorAt > c.lastSyncAt -> c.lastSyncError.takeIf { it.isNotBlank() }
    else -> null
}
