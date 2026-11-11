package com.radium.inkwell.ui.replace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.core.source.ImportedPurifyRule
import com.radium.inkwell.data.repo.RuleImportItem
import com.radium.inkwell.data.repo.RuleImportStatus
import com.radium.inkwell.ui.components.AppBottomSheet
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.CardViewport
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.InlineTag
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.pageScroll
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 导入净化规则：读出来先列给人看，勾了再入库（阅读的导入弹窗也是这个思路）。
 *
 * 净化是有损的 —— 一条贪婪的正则能把整章删空，而在阅读页只表现为「正文是空的」。一份订阅几十上百条，
 * 不过目就整批灌进去，出了事都不知道是哪条。所以：
 * - 概况行说清共几条、新增 / 更新 / 已有 / 不支持各几条，右边全选 / 全不选；
 * - 每条标状态（标题后的小标签）：「新增」「更新」默认勾上；「已有」（一模一样）和「不支持」勾不上，行变灰，原因写在副行；
 * - 主按钮写清数量「导入 N 条」。点了先收面板、再写库（取好勾选再收：面板收起时先清掉 ViewModel 里的状态）。
 *
 * 列表定高（[CardViewport]，同整理重复的书）：几百条的合集面板也不会顶满屏。
 */
@Composable
internal fun ReplaceRuleImportSheet(
    items: List<RuleImportItem>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onConfirm: (List<ImportedPurifyRule>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectable = remember(items) { items.count { it.selectable } }
    val chosen = items.filter { it.selectable && it.rule.id in selected }
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = "导入净化规则",
        startAction = {
            AppIconButton(onClick = { dismiss() }) {
                Icon(MiuixIcons.Close, contentDescription = "关闭")
            }
        },
    ) {
        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        val listMax = (windowHeight * LIST_HEIGHT_FRACTION).coerceAtMost(Dimens.sheetListMaxHeight)
        Column(Modifier.fillMaxWidth()) {
            // 概况的字和卡里行的字左边对齐；右边的「全选」按钮自带内边距，贴着卡的右边
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = cardMargin() + Dimens.listHorizontal, end = cardMargin()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    remember(items) { summary(items) },
                    Modifier.weight(1f),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (selectable > 0) {
                    val all = chosen.size == selectable
                    AppTextButton(if (all) "全不选" else "全选", onClick = { onSelectAll(!all) })
                }
            }
            // 视口裁成卡的圆上角：一滚，顶上切过的是卡中间的行，不裁的话卡顶是一刀直线（见 CardViewport）
            CardViewport(Modifier.padding(top = Dimens.gapS).heightIn(max = listMax)) {
                LazyColumn(modifier = Modifier.pageScroll(), overscrollEffect = null) {
                    itemsIndexed(items, key = { _, it -> it.rule.id }) { i, item ->
                        ImportRuleRow(
                            item = item,
                            checked = item.rule.id in selected,
                            onToggle = { onToggle(item.rule.id) },
                            modifier = Modifier.groupedCardItem(i, items.size),
                        )
                    }
                    item(key = "foot") {
                        SettingFootnote(
                            "导入后在列表里可以逐条开关、修改。本地书打开就生效；网上看的书，已经缓存的章节不会重新净化，" +
                                "要清除正文缓存后重读。",
                            Modifier.padding(top = Dimens.gapS, bottom = Dimens.gapM),
                        )
                    }
                }
            }
            PrimaryButton(
                text = if (chosen.isEmpty()) "导入" else "导入 ${chosen.size} 条",
                onClick = {
                    val rules = chosen.map { it.rule }
                    dismiss { onConfirm(rules) }
                },
                enabled = chosen.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = cardMargin())
                    .padding(top = Dimens.gapM, bottom = Dimens.gapL),
            )
        }
    }
}

/**
 * 一条：名字 + 状态标签；副行是规则本身（同规则列表的写法），更新的写「会覆盖」，勾不上的写原因。
 * 整行一个复选语义目标，行尾的勾只是展示（ContentListItem 的 checked 重载）。
 */
@Composable
private fun ImportRuleRow(
    item: RuleImportItem,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rule = item.rule
    val on = checked && item.selectable
    ContentListItem(
        checked = on,
        onCheckedChange = { onToggle() },
        enabled = item.selectable,
        modifier = modifier,
        // 勾没勾只看行尾的勾选框，行不变色（同多选行）
        colors = ContentListDefaults.plainColors(),
        trailingContent = {
            Checkbox(
                state = ToggleableState(on),
                onClick = null,
                enabled = item.selectable,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        supportingContent = {
            Text(
                supportingLine(item),
                style = ContentListDefaults.supportingStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                rule.name,
                style = ContentListDefaults.titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            when (item.status) {
                RuleImportStatus.NEW -> InlineTag("新增")
                RuleImportStatus.UPDATE -> InlineTag("更新")
                RuleImportStatus.SAME -> InlineTag("已有", neutral = true)
                RuleImportStatus.UNSUPPORTED -> Unit
            }
        }
    }
}

private fun supportingLine(item: RuleImportItem): String {
    val rule = item.rule
    return when (item.status) {
        RuleImportStatus.UNSUPPORTED -> rule.unsupported.orEmpty()
        RuleImportStatus.SAME -> "列表里已经有一模一样的"
        RuleImportStatus.UPDATE -> "内容有变，会覆盖列表里的那条"
        RuleImportStatus.NEW -> buildString {
            append(if (rule.isRegex) "正则  " else "文本  ")
            append(rule.pattern)
            if (rule.replacement.isNotEmpty()) append("  →  ${rule.replacement}")
            if (rule.scope.isNotBlank()) append("  ·  仅 ${rule.scope}")
            // 阅读里关着的导进来也关着：说一声，免得以为导进来就生效了
            if (!rule.enabled) append("  ·  导入后关闭")
        }
    }
}

/** 「共 36 条 · 新增 30 · 更新 4 · 已有 2」：是 0 的那几样不写 */
private fun summary(items: List<RuleImportItem>): String {
    val counts = items.groupingBy { it.status }.eachCount()
    return buildList {
        add("共 ${items.size} 条")
        counts[RuleImportStatus.NEW]?.let { add("新增 $it") }
        counts[RuleImportStatus.UPDATE]?.let { add("更新 $it") }
        counts[RuleImportStatus.SAME]?.let { add("已有 $it") }
        counts[RuleImportStatus.UNSUPPORTED]?.let { add("不支持 $it") }
    }.joinToString(" · ")
}

/** 列表最多占屏高的一半（同整理重复的书），再多就在卡里滚 */
private const val LIST_HEIGHT_FRACTION = 0.5f
