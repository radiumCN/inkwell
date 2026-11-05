package com.radium.inkwell.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import com.radium.inkwell.ui.components.AppSurface
import com.radium.inkwell.ui.components.DeterminateProgressBar
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.ui.theme.AppTheme
import com.radium.inkwell.update.UpdateInfo
import com.radium.inkwell.util.AppIconManager
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 更新弹窗的三块：头部（[UpdateHero]）、更新说明（[ReleaseNotesPanel]）、下载进度（[UpdateDownloadStatus]）。
 * 关于页点「版本」看这一版的说明，用的是同一套：头部换成 [CurrentVersionHero]，说明面板原样复用。
 * 外壳仍是 AppAlertDialog（标题传 null、内容区不替我们滚动），收起、按钮、模糊背景都照旧走封装。
 *
 * 从前是 Miuix 弹窗的默认排法：居中标题 + 一整段居中的纯文本。Release 正文是给纯文本渲染写的
 * （「更新内容」一行、每条带 feat：/ fix：前缀），原样居中铺出来是一大片前缀露在外面的字，
 * 说明一长就在弹窗底下齐刷刷截断，看不出还能往下滚。
 */

/**
 * 头部：当前桌面上那款图标、「发现新版本」、从哪个版本到哪个版本、正式版还是测试版、安装包多大。
 *
 * 图标取 [AppIconManager.current]（桌面上摆着的那个），不是固定的默认款 —— 换过图标的人看见的是自己桌面上那个。
 * 裁成与 Miuix 卡片同档的平滑圆角，再描一圈细线：几款图标的底色都很浅（米白、浅灰），白弹窗上没有这圈就看不出边。
 */
@Composable
internal fun UpdateHero(currentVersion: String, info: UpdateInfo, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HeroIcon()
        Text(
            "发现新版本",
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = Dimens.gapM)
                .semantics { heading() },
        )
        val latest = "v${info.latestVersion}"
        Row(
            Modifier
                .padding(top = Dimens.gapXS)
                // 读屏念一句完整的话，而不是「v0.4.0」「v0.5.0」两段加一个没有名字的箭头
                .clearAndSetSemantics { contentDescription = "从 v$currentVersion 更新到 $latest" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("v$currentVersion", style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            Icon(
                MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .padding(horizontal = Dimens.gapXS)
                    .size(Dimens.iconSm)
                    .graphicsLayer { scaleX = if (rtl) -1f else 1f },
            )
            Text(latest, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, color = scheme.onTertiaryContainer)
        }
        Row(
            Modifier.padding(top = Dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            UpdateTag(if (info.isPrerelease) "测试版" else "正式版", accent = true)
            info.directInstall?.size?.takeIf { it > 0 }?.let { UpdateTag(formatSize(it), accent = false) }
        }
    }
}

/**
 * 关于页点「版本」弹出的头部：和 [UpdateHero] 同一个样子（一看就是一家的），写的是这一版本身 ——
 * 「v0.5.1-beta.9 更新内容」、测试版 / 正式版、发布日期（[dateLabel] 为 null 不画）。
 */
@Composable
internal fun CurrentVersionHero(version: String, dateLabel: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HeroIcon()
        Text(
            "v$version 更新内容",
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = Dimens.gapM)
                .semantics { heading() },
        )
        Row(
            Modifier.padding(top = Dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            // 同发版约定：版本号带 '-' 的是预发布（0.5.1-beta.9）
            UpdateTag(if ('-' in version) "测试版" else "正式版", accent = true)
            dateLabel?.let { UpdateTag(it, accent = false) }
        }
    }
}

/**
 * 头部的图标：取 [AppIconManager.current]（桌面上摆着的那个），裁成与 Miuix 卡片同档的平滑圆角，再描一圈细线 ——
 * 几款图标的底色都很浅（米白、浅灰），白弹窗上没有这圈就看不出边。
 */
@Composable
private fun HeroIcon() {
    val context = LocalContext.current
    // 查组件状态是一次跨进程调用：弹窗开着时桌面图标不会变，查一次就够
    val icon = remember(context) { AppIconManager.current(context) }
    Image(
        painter = painterResource(icon.preview),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(Dimens.dialogHeroIcon)
            .squircleClip(AppShapes.radiusLarge)
            .squircleBorder(Dimens.strokeThin, MiuixTheme.colorScheme.dividerLine, AppShapes.radiusLarge),
    )
}

/**
 * 小标签：头部的「正式版」「9.2 MB」、更新说明的组标题。强调的那种是淡强调底 + 强调色字（与选中 chip 同一对槽，
 * 对比度在 AppThemesTest 里钉着），不强调的是页面底 + 摘要字。8dp 圆角的小面，按 CLAUDE.md 用普通 Shape 不开 squircle。
 */
@Composable
private fun UpdateTag(text: String, accent: Boolean) {
    val scheme = MiuixTheme.colorScheme
    Text(
        text,
        style = MiuixTheme.textStyles.footnote2,
        fontWeight = FontWeight.Medium,
        color = if (accent) scheme.onTertiaryContainer else scheme.onSurfaceVariantSummary,
        modifier = Modifier
            .background(if (accent) scheme.tertiaryContainer else scheme.surface, AppShapes.small)
            .padding(horizontal = Dimens.gapS, vertical = Dimens.gapXS),
    )
}

/**
 * 更新说明：按「新功能 / 修复 / 优化」分组（[parseReleaseNotes]），每组一个小标签，条目左对齐、带圆点。
 * 装在一块页面底色的平滑圆角面板里（弹窗底是 background，面板比它深 / 暗一档，看得出是一块「内容区」）。
 *
 * 面板自己滚，上下边缘做**渐进模糊**的渐隐（Miuix 示例「Progressive Blur」的半径 20）：下面还有内容时底边
 * 糊进面板底色，滚过了顶边也一样 —— 一眼看得出能滚，字也不会在面板边上被齐刷刷切断。滚到头的那一边不糊。
 */
@Composable
internal fun ReleaseNotesPanel(
    notes: String,
    modifier: Modifier = Modifier,
    /** 说明是空的时候写什么。关于页：本地编的包没带说明，说清是这个包的事，别像是这一版没改东西 */
    emptyText: String = "暂无更新说明",
) {
    val sections = remember(notes) { parseReleaseNotes(notes) }
    val panel = MiuixTheme.colorScheme.surface
    val scroll = rememberScrollState()
    // 录的是面板这一块（滚动后可见的那段）；透明处先垫面板色，糊开时边缘不渗黑
    val backdrop = rememberLayerBackdrop {
        drawRect(panel)
        drawContent()
    }
    AppSurface(cornerRadius = AppShapes.radiusLarge, modifier = modifier.fillMaxWidth(), color = panel) {
        Box {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = Dimens.dialogBodyMaxHeight)
                    .layerBackdrop(backdrop)
                    .verticalScroll(scroll)
                    .padding(Dimens.gapL),
                verticalArrangement = Arrangement.spacedBy(Dimens.gapL),
            ) {
                if (sections.isEmpty()) {
                    Text(
                        emptyText,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                sections.forEach { NoteGroup(it) }
            }
            ScrollEdgeFade(backdrop, panel, atTop = true, visible = scroll.canScrollBackward)
            ScrollEdgeFade(backdrop, panel, atTop = false, visible = scroll.canScrollForward)
        }
    }
}

@Composable
private fun NoteGroup(section: NoteSection) {
    val scheme = MiuixTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapS)) {
        section.title?.let {
            Box(Modifier.semantics { heading() }) { UpdateTag(it, accent = true) }
        }
        section.items.forEach { item ->
            Row {
                // 圆点用同字号的字符而不是画一个点：跟着第一行的基线走，长条目折行时也对得齐
                Text(
                    "•",
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .padding(end = Dimens.gapS)
                        .clearAndSetSemantics {},
                )
                Text(
                    item,
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onSurfaceSecondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 面板一条边上的渐隐：渐进模糊，边上满强度、往里淡到零；叠的颜色（面板色）随模糊一起淡出，
 * 所以最边上是一片面板色、往里慢慢露出字。[visible] 切换时淡入淡出（effects 令牌），不是一帧之内出现。
 */
@Composable
private fun BoxScope.ScrollEdgeFade(backdrop: LayerBackdrop, color: Color, atTop: Boolean, visible: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = AppTheme.motionScheme.defaultEffectsSpec(),
        label = "notesEdgeFade",
    )
    val colors = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(color = color)))
    if (alpha == 0f) return
    Box(
        Modifier
            .align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter)
            .fillMaxWidth()
            .height(NOTES_EDGE_FADE)
            .graphicsLayer { this.alpha = alpha }
            .progressiveTextureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = NOTES_EDGE_BLUR_RADIUS,
                gradient = if (atTop) ProgressiveBlur.Top else ProgressiveBlur.Bottom,
                colors = colors,
            ),
    )
}

/**
 * 下载中：进度条 + 「正在下载 4.1 MB / 9.2 MB」（不知道包多大时只报百分比）。没成的原因用 error 色写在这里 ——
 * 弹窗是模态的，Snackbar 落在它后面看不见（见 rememberUpdateCheckState）。
 */
@Composable
internal fun UpdateDownloadStatus(downloading: Boolean, progress: Float, size: Long?, error: String?) {
    val scheme = MiuixTheme.colorScheme
    if (downloading) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapS)) {
            DeterminateProgressBar(progress = progress)
            val percent = (progress * 100).toInt()
            Text(
                if (size != null && size > 0) {
                    "正在下载并校验 ${formatSize((size * progress).toLong())} / ${formatSize(size)}"
                } else {
                    "正在下载并校验… $percent%"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceVariantSummary,
            )
        }
    }
    error?.let {
        Text(it, style = MiuixTheme.textStyles.footnote1, color = scheme.error)
    }
}

/** 渐隐带的高度：面板内边距（16dp）再往里一行字 */
private val NOTES_EDGE_FADE = Dimens.gapXXL

/** dp。示例「Progressive Blur」的 20 */
private const val NOTES_EDGE_BLUR_RADIUS = 20f
