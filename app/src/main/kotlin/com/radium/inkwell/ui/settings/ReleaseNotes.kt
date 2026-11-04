package com.radium.inkwell.ui.settings

/**
 * 更新说明里的一组：「新功能」「修复」「优化」…；[title] 为 null 是没带类型前缀的那些行。
 */
internal data class NoteSection(val title: String?, val items: List<String>)

/**
 * 把 Release 正文（= tag 注解，格式见 CLAUDE.md「发行说明格式」）拆成分组，给更新弹窗按组排版。
 *
 * 正文是写给纯文本渲染的：第一行固定「更新内容」，每条一行、以 `feat：` / `fix：` / `优化：` / `文档：` 开头。
 * 原样居中铺在弹窗里，就是一大段前缀露在外面的字。这里按前缀归组（组的先后照第一次出现的顺序，发版时已经是
 * feat → fix → 优化），前缀本身去掉，换成组标题。
 *
 * 老版本的说明不一定守这个格式（Markdown 列表、半角冒号、英文前缀），尽量认：行首的 `- ` / `* ` / `• ` 去掉，
 * 冒号全角半角都行，`perf` / `docs` 归进「优化」「文档」。认不出前缀的行不丢，按原顺序放进 title 为 null 的组 ——
 * 一条都认不出时整份说明就是一组无标题的行，弹窗照样能排。
 */
internal fun parseReleaseNotes(notes: String): List<NoteSection> {
    val order = ArrayList<String?>()
    val groups = HashMap<String?, MutableList<String>>()
    for (raw in notes.lines()) {
        val line = raw.trim().removeBullet().trim()
        if (line.isEmpty() || line.trimEnd('：', ':') == HEADING) continue
        val match = PREFIX.find(line)
        val title = match?.let { SECTION_TITLES[it.groupValues[1].lowercase()] }
        val text = if (match != null && title != null) line.substring(match.range.last + 1).trim() else line
        if (text.isEmpty()) continue
        if (title !in groups) order += title
        groups.getOrPut(title) { ArrayList() } += text
    }
    return order.map { NoteSection(it, groups.getValue(it)) }
}

private fun String.removeBullet(): String =
    if (length >= 2 && this[0] in "-*•·" && this[1] == ' ') substring(2) else this

private const val HEADING = "更新内容"

/** 行首的类型前缀：英文不分大小写，冒号全角半角都认 */
private val PREFIX = Regex("""^(feat|fix|perf|docs|优化|文档)\s*[：:]""", RegexOption.IGNORE_CASE)

private val SECTION_TITLES = mapOf(
    "feat" to "新功能",
    "fix" to "修复",
    "perf" to "优化",
    "优化" to "优化",
    "docs" to "文档",
    "文档" to "文档",
)
