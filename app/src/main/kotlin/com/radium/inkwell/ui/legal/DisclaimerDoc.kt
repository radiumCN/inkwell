package com.radium.inkwell.ui.legal

/**
 * 用户协议与免责声明的结构。条文仍只放在 `assets/legal/disclaimer.txt` 那一份纯文本里（与仓库根目录 DISCLAIMER.md 同文），
 * 这里按**行首的格式**认出结构，页面照结构排版（见 [DisclaimerScreen]）—— 只认格式、不认内容，
 * 改条文、加一节、加一条都不用动代码。认的三种行：
 * - `【特别提示】`：提示卡的标题；它下面那一段（到空行为止）是卡里的字；
 * - `一、软件性质说明`：一节的标题（中文数字 + 顿号）；
 * - `1. ……`：节里的一条（阿拉伯数字 + 点或顿号）。
 *
 * 节里不带编号的行（「七、协议变更」底下那一段）照普通段落排进这一节的卡里；不在任何一节里、也认不出来的行
 * 退回普通段落 —— 格式改坏了顶多排得不好看，一个字都不会丢（`DisclaimerDocTest` 逐行核对过）。
 */
internal data class DisclaimerDoc(val blocks: List<DisclaimerBlock>) {
    val sections: List<DisclaimerBlock.Section> = blocks.filterIsInstance<DisclaimerBlock.Section>()
}

internal sealed interface DisclaimerBlock {
    /** 「特别提示」：单独一张淡强调色的卡 */
    data class Notice(val title: String, val text: String) : DisclaimerBlock

    /** 一节：「一」+ 标题，底下的条款装进一张卡 */
    data class Section(val numeral: String, val title: String, val items: List<DisclaimerItem>) : DisclaimerBlock {
        /** 节 chip 上的短名（见 [shortSectionTitle]） */
        val shortTitle: String get() = shortSectionTitle(title)
    }

    /** 认不出的行，原样一段 */
    data class Paragraph(val text: String) : DisclaimerBlock
}

/** 节里的一条。[number] 为 null 的是不带编号的段落 */
internal data class DisclaimerItem(val number: String?, val text: String)

internal fun parseDisclaimer(raw: String): DisclaimerDoc {
    val blocks = mutableListOf<DisclaimerBlock>()
    var noticeTitle: String? = null
    val noticeLines = mutableListOf<String>()
    var section: DisclaimerBlock.Section? = null
    val sectionItems = mutableListOf<DisclaimerItem>()

    fun closeNotice() {
        val title = noticeTitle ?: return
        blocks += DisclaimerBlock.Notice(title, noticeLines.joinToString("\n"))
        noticeTitle = null
        noticeLines.clear()
    }

    fun closeSection() {
        val open = section ?: return
        blocks += open.copy(items = sectionItems.toList())
        section = null
        sectionItems.clear()
    }

    for (line in raw.lines().map { it.trim() }) {
        if (line.isEmpty()) {
            // 提示卡里的字到空行为止；节不因空行结束（条与条之间本来就空着一行）
            closeNotice()
            continue
        }
        val notice = NOTICE.matchEntire(line)
        val heading = SECTION.matchEntire(line)
        val item = ITEM.matchEntire(line)
        when {
            notice != null -> {
                closeNotice()
                closeSection()
                noticeTitle = notice.groupValues[1].trim()
            }
            noticeTitle != null -> noticeLines += line
            heading != null -> {
                closeSection()
                section = DisclaimerBlock.Section(heading.groupValues[1], heading.groupValues[2].trim(), emptyList())
            }
            section != null && item != null -> sectionItems += DisclaimerItem(item.groupValues[1], item.groupValues[2].trim())
            section != null -> sectionItems += DisclaimerItem(null, line)
            else -> blocks += DisclaimerBlock.Paragraph(line)
        }
    }
    closeNotice()
    closeSection()
    return DisclaimerDoc(blocks)
}

/**
 * 节 chip 上的短名：整句标题放进 chip 太长（「第三方内容与社区说明」），取「与」前面那半、再去掉「说明」「保护」这种尾巴
 * ——「用户行为与规则说明」→「用户行为」、「知识产权保护」→「知识产权」。去完是空的就用原标题。
 */
internal fun shortSectionTitle(title: String): String {
    val head = title.substringBefore('与')
    val short = TITLE_SUFFIXES.firstOrNull { head.endsWith(it) && head.length > it.length }
        ?.let { head.removeSuffix(it) }
        ?: head
    return short.ifBlank { title }
}

private val NOTICE = Regex("""【(.+)】""")
private val SECTION = Regex("""([一二三四五六七八九十]+)、\s*(.+)""")
private val ITEM = Regex("""(\d+)[.．、]\s*(.+)""")
private val TITLE_SUFFIXES = listOf("说明", "保护")
