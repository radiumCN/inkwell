package com.radium.inkwell.ui.legal

import android.content.Context
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.ui.components.NO_BROWSER_MESSAGE
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.animationsEnabled
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.rememberExternalLinkOpener
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.topBarScroll
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.ui.theme.AppTheme
import com.radium.inkwell.update.UpdateChecker
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 用户协议与免责声明（只读）。关于页、欢迎页进的都是这一页。
 *
 * 正文来自 `assets/legal/disclaimer.txt`，与仓库根目录 [DISCLAIMER.md] 保持同文；
 * 改条文时两处一起改，避免应用内与 GitHub 文档各说各话。
 *
 * 从前整篇是一个 14sp 的 Text：一个样式从头排到尾，「一、」「1.」都埋在正文里，想找「隐私」那节只能从头扫；
 * 设置类页面都是灰底白卡，只有这页是一整块字。现在按 [parseDisclaimer] 认出的结构排（条文一个字不改）：
 * - 「特别提示」单独一张淡强调底的卡，「用了就算同意」那句一进来就看得到；
 * - 每一节一个小标题（序号放进小色块 —— 单独一个强调色的「一」看着像一道破折号），条款装进白卡，
 *   编号单独一列、换行后文字和第一行对齐（悬挂缩进），行高 1.7；
 * - 顶栏下一条节 chip：滚到哪节亮哪节，点一下跳过去（[jumpTo]）；
 * - 末尾注明与 DISCLAIMER.md 同文，「在 GitHub 上查看」走外链确认。正文可长按选中复制 —— 条文常要被引用。
 */
@Composable
fun DisclaimerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val doc = remember { parseDisclaimer(loadDisclaimer(context)) }
    val sections = doc.sections
    val messages = remember { MessageBus() }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(messages, snackbar)
    val links = rememberExternalLinkOpener { messages.emit(NO_BROWSER_MESSAGE) }

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val animate = animationsEnabled()
    val spatial = AppTheme.motionScheme.defaultSpatialSpec<Float>()
    val density = LocalDensity.current
    val topGapPx = with(density) { CONTENT_TOP_GAP.roundToPx() }
    val spyTolerancePx = with(density) { Dimens.gapS.roundToPx() }

    // 每一节在正文里的纵坐标（px，从正文顶端算，不含顶栏）。节 chip 据它跳过去、滚动时据它亮哪一节
    val sectionTops = remember { mutableStateMapOf<Int, Int>() }
    // 点 chip 跳过去的那一节：跳的途中（at = null）和跳完停着没动时（at = 停下的位置）亮它，别跟着滚动亮。
    // 末尾几节的标题滚不到栏底下（正文到底了），不钉住的话点了「协议变更」亮的却是「免责」
    var pinned by remember { mutableStateOf<PinnedSection?>(null) }
    val spied by remember {
        // 一节的上沿滚到栏底（差 gapS 以内）就算进了这一节；停的位置同 jumpTo 的算法
        derivedStateOf { activeSection(sectionTops, sections.size, scroll.value - topGapPx + spyTolerancePx) }
    }
    // 包进 derivedStateOf：滚动的每一帧只在这里算，亮的那颗真换了才重组（只有顶栏里那条 chip 读它）
    val selected by remember {
        derivedStateOf { pinned?.takeIf { it.at == null || it.at == scroll.value }?.index ?: spied }
    }

    /**
     * 跳到第 [index] 节：这一节停在收起后的窄栏底下。程序滚动不走 nestedScroll，顶栏不会自己收起，
     * 这里一起把它收了 —— 否则大标题还撑着，节标题落在它下面一大截。
     * 停的位置和顶栏高度无关：正文顶上垫的是「顶栏高度 + [CONTENT_TOP_GAP]」，滚过「节的纵坐标 + [CONTENT_TOP_GAP]」，
     * 这一节的上沿就正好贴着栏底（标题自己带着上边距）。
     */
    fun jumpTo(index: Int) {
        val top = sectionTops[index] ?: return
        pinned = PinnedSection(index, at = null)
        scope.launch {
            try {
                coroutineScope {
                    val bar = topBarScroll.state
                    launch {
                        if (animate) {
                            animate(bar.heightOffset, bar.heightOffsetLimit, animationSpec = spatial) { v, _ -> bar.heightOffset = v }
                        } else {
                            bar.heightOffset = bar.heightOffsetLimit
                        }
                    }
                    launch {
                        val target = top + topGapPx
                        if (animate) scroll.animateScrollTo(target, spatial) else scroll.scrollTo(target)
                    }
                }
            } finally {
                // 被手指打断也落在这里：停在哪就钉在哪，一滚动就交回给滚动去亮
                pinned = PinnedSection(index, at = scroll.value)
            }
        }
    }

    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            AppTopBar(
                "用户协议与免责声明",
                topBarScroll,
                onBack = onBack,
                containerColor = pageColor,
                bottomContent = {
                    if (sections.size > 1) {
                        ChipRow(
                            options = remember(sections) { sections.map { it.shortTitle } },
                            selectedIndex = selected,
                            onSelect = ::jumpTo,
                            contentPadding = PaddingValues(horizontal = Dimens.listHorizontal, vertical = Dimens.gapXS),
                            bringSelectedIntoView = true,
                        )
                    }
                },
            )
        },
    ) { padding ->
        // 顶栏、导航栏的高度都垫在滚动内容首尾：内容能滚到玻璃顶栏和手势条下面（见 AppScaffold）
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding.horizontalOnly())
                .pageScroll()
                .verticalScroll(scroll, overscrollEffect = null)
                .padding(
                    top = padding.calculateTopPadding() + CONTENT_TOP_GAP,
                    bottom = Dimens.gapXL + padding.calculateBottomPadding(),
                ),
        ) {
            SelectionContainer {
                Column {
                    var sectionIndex = 0
                    doc.blocks.forEach { block ->
                        when (block) {
                            is DisclaimerBlock.Notice -> NoticeCard(block)
                            is DisclaimerBlock.Paragraph -> Text(
                                block.text,
                                style = legalBody(),
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(
                                    horizontal = cardMargin() + Dimens.listHorizontal,
                                    vertical = Dimens.gapS,
                                ),
                            )
                            is DisclaimerBlock.Section -> {
                                val i = sectionIndex++
                                SectionBlock(
                                    section = block,
                                    modifier = Modifier.onPlaced { coords ->
                                        val y = coords.positionInParent().y.roundToInt()
                                        if (sectionTops[i] != y) sectionTops[i] = y
                                    },
                                )
                            }
                        }
                    }
                }
            }
            SettingFootnote(
                "本页与开源仓库中的 DISCLAIMER.md 同文。长按文字可以选中复制。",
                modifier = Modifier.padding(top = Dimens.gapL),
            )
            // 文字按钮自带 gapM 的左右内边距：往左让出这一截，字和上面的脚注对齐
            AppTextButton(
                "在 GitHub 上查看",
                onClick = { links.open(DISCLAIMER_URL) },
                modifier = Modifier.padding(start = cardMargin() + Dimens.listHorizontal - Dimens.gapM),
            )
        }
    }
}

/** 「特别提示」：淡强调底的一张卡，标题带 ⓘ */
@Composable
private fun NoticeCard(notice: DisclaimerBlock.Notice) {
    val scheme = MiuixTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = cardMargin(), vertical = Dimens.gapS),
        cornerRadius = AppShapes.radiusLarge,
        insideMargin = PaddingValues(Dimens.listHorizontal),
        colors = CardDefaults.defaultColors(color = scheme.tertiaryContainer),
    ) {
        Row(
            Modifier.semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            Icon(
                MiuixIcons.Info,
                contentDescription = null,
                tint = scheme.onTertiaryContainer,
                modifier = Modifier.size(Dimens.iconSm),
            )
            Text(
                notice.title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = scheme.onTertiaryContainer,
            )
        }
        if (notice.text.isNotEmpty()) {
            Spacer(Modifier.height(Dimens.gapS))
            Text(notice.text, style = legalBody(), color = scheme.onSurface)
        }
    }
}

/** 一节：序号小色块 + 标题，底下的条款装进一张白卡 */
@Composable
private fun SectionBlock(section: DisclaimerBlock.Section, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    Column(modifier) {
        Row(
            Modifier
                .padding(start = cardMargin() + Dimens.listHorizontal, end = cardMargin(), top = Dimens.gapXL)
                .semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            Box(
                Modifier
                    .size(Dimens.iconMd)
                    .background(scheme.tertiaryContainer, AppShapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    section.numeral,
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onTertiaryContainer,
                )
            }
            Text(
                section.title,
                style = MiuixTheme.textStyles.headline2,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
        }
        if (section.items.isNotEmpty()) {
            SettingGroup {
                Column(Modifier.padding(vertical = Dimens.gapXS)) {
                    section.items.forEach { ItemRow(it) }
                }
            }
        }
    }
}

/** 一条：编号单独一列（强调色），文字换行后和第一行对齐 */
@Composable
private fun ItemRow(item: DisclaimerItem) {
    val scheme = MiuixTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapS),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
    ) {
        if (item.number != null) {
            Text(
                item.number,
                style = legalBody(),
                fontWeight = FontWeight.Medium,
                color = scheme.primary,
                modifier = Modifier.widthIn(min = Dimens.gapL),
            )
        }
        Text(item.text, style = legalBody(), color = scheme.onSurface, modifier = Modifier.weight(1f))
    }
}

/** 条文正文：body2（14sp），行高放到 1.7 倍 —— 大段条文挤在默认行高里一面墙似的，读两行就串行 */
@Composable
private fun legalBody(): TextStyle = MiuixTheme.textStyles.body2.copy(lineHeight = LEGAL_LINE_HEIGHT)

/** 纵坐标 [line] 以上（含）最后一节的序号；还没到第一节时算第一节 */
internal fun activeSection(tops: Map<Int, Int>, count: Int, line: Int): Int {
    var active = 0
    for (i in 0 until count) {
        val top = tops[i] ?: continue
        if (top <= line) active = i
    }
    return active
}

private data class PinnedSection(val index: Int, val at: Int?)

private fun loadDisclaimer(context: Context): String =
    runCatching {
        context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrElse {
        "暂时无法加载声明全文。请查阅开源仓库中的 DISCLAIMER.md。"
    }

private const val ASSET_PATH = "legal/disclaimer.txt"

private const val DISCLAIMER_URL = "${UpdateChecker.REPO_URL}/blob/main/DISCLAIMER.md"

/** 正文顶上、顶栏底下留的空 */
private val CONTENT_TOP_GAP = Dimens.gapM

private val LEGAL_LINE_HEIGHT = 1.7.em
