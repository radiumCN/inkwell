package com.radium.inkwell.data.repo

/**
 * "这是不是同一本书 / 同一章" 的判定。
 *
 * 抽出来共用，是因为手动换源和自动换源必须用**同一套**标准：两边各写一份，迟早写出分歧 ——
 * 手动换源列表里明明列着的源，自动换源却说找不到，这种 bug 没人查得动。
 */
object TitleMatch {

    private val NOISE = Regex("[《》\\s]")

    fun normalize(s: String): String = s.trim().replace(NOISE, "")

    /**
     * 书名判定。归一化掉书名号与空白：书源常返回「《武动乾坤》」，直接比字符串会判死。
     * 双向包含，「武动乾坤」与「武动乾坤（精校版）」互相认得。
     */
    fun matches(candidate: String, want: String): Boolean {
        val a = normalize(candidate)
        val b = normalize(want)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || (b.contains(a) && a.length >= 2)
    }

    /**
     * 作者判定与 Legado 对齐：用**包含**而非相等 —— 书源返回的作者常带前缀
     * （「作者：天蚕土豆」）或含多个作者，一律要求相等会把绝大多数源判死。
     * 任一边为空时不拿作者卡人。
     */
    fun authorMatches(candidate: String?, want: String): Boolean {
        val a = candidate?.trim().orEmpty()
        if (want.isBlank() || a.isBlank()) return true
        return a.contains(want) || want.contains(a)
    }
}
