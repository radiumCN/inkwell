package com.radium.inkwell.ui.reader

/**
 * 选字「替换」弹窗里那一行：原文在本章出现几处，挑一处截出前后文，给「改完是什么样」。
 *
 * 数法照规则真正执行时的语义（core 的 `Purifier`，选字建的是纯文本规则）：**逐段**、从左往右、**不重叠** ——
 * 和 `String.replace` 换掉的是同样几处。按整章拼起来数的话，跨段的「命中」会被算进去，可规则永远换不到它。
 */
internal data class ReplacePreview(
    val count: Int,
    /** 展示的那一处前面一截；截短了的开头带「…」 */
    val before: String,
    val match: String,
    /** 展示的那一处后面一截；截短了的末尾带「…」 */
    val after: String,
)

/**
 * @param paragraphs 本章各段，下标同 `TextSelection.elementIndex`（空串 = 不参与：章标题、图片）
 * @param anchor 选区所在（段下标, 段内偏移）：优先展示用户选的那一处，而不是本章第一处。
 *   原文改过之后那一段里可能没有了，就退回本章第一处
 * @return 本章一处都没有时为 null（规则照样能建：别的章也许有）
 */
internal fun replacePreview(
    paragraphs: List<String>,
    find: String,
    anchor: Pair<Int, Int>? = null,
    context: Int = PREVIEW_CONTEXT_CHARS,
): ReplacePreview? {
    if (find.isEmpty()) return null
    var count = 0
    var shownPara = -1
    var shownAt = -1
    for (p in paragraphs.indices) {
        val text = paragraphs[p]
        var i = text.indexOf(find)
        while (i >= 0) {
            count++
            val better = when {
                shownPara < 0 -> true
                // 选区那一段里有：取离选区最近的一处
                anchor != null && p == anchor.first ->
                    shownPara != p || kotlin.math.abs(i - anchor.second) < kotlin.math.abs(shownAt - anchor.second)
                else -> false
            }
            if (better) {
                shownPara = p
                shownAt = i
            }
            i = text.indexOf(find, i + find.length)
        }
    }
    if (shownPara < 0) return null
    val text = paragraphs[shownPara]
    val from = (shownAt - context).coerceAtLeast(0)
    val end = shownAt + find.length
    val to = (end + context).coerceAtMost(text.length)
    return ReplacePreview(
        count = count,
        before = (if (from > 0) "…" else "") + text.substring(from, shownAt),
        match = find,
        after = text.substring(end, to) + (if (to < text.length) "…" else ""),
    )
}

/**
 * 这条规则多半会误伤：原文就一个字，或者本章一下命中好几处（选了个「他们」）。
 * 弹窗里那一行变红提醒，**不拦** —— 也许用户就是要把每一处都换掉。
 */
internal fun isRiskyReplace(find: String, count: Int): Boolean =
    find.length < RISKY_MIN_CHARS || count > RISKY_MAX_HITS

/** 前后各留多少字：弹窗里预览只有两行，选中的那段本身还要占地方 */
private const val PREVIEW_CONTEXT_CHARS = 14
private const val RISKY_MIN_CHARS = 2
private const val RISKY_MAX_HITS = 5
