package com.radium.inkwell.core.source.detect

/**
 * 章节标题的两个判断：像不像章节名、能不能抠出章节序号。
 *
 * 目录识别拿前者给候选链接组打分（导航栏、推荐书单、页码条都不像章节名），
 * 拿后者判断目录是不是倒序排的（新章在上）。
 */
internal object ChapterTitles {

    internal const val CN_DIGITS = "0-9０-９零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟"

    /** 「第X章」可以出现在标题中间：「正文 第一章」「VIP 第12章 xxx」 */
    private val ORDINAL = Regex("第\\s*([$CN_DIGITS]+)\\s*[章节回话集卷部篇幕]")

    /**
     * 标题开头的数字序号：「001 xxx」「12、xxx」「1.xxx」「(3) xxx」。
     * 序号后面必须还有东西 —— 光秃秃一个「3」是页码条，不是章节。
     */
    private val LEADING_NUMBER = Regex("^\\s*[(（\\[【]?\\s*(\\d{1,5})\\s*[)）\\]】]?\\s*[.、:：\\-_\\s]\\s*\\S")

    private val SPECIAL = Regex(
        "^\\s*[(（\\[【]?\\s*(序章|序言|序|楔子|引子|引言|前言|番外|尾声|后记|终章|大结局|完本感言|上架感言|新书感言|chapter\\s*\\d+)",
        RegexOption.IGNORE_CASE,
    )

    fun isChapterLike(title: String): Boolean =
        ORDINAL.containsMatchIn(title) || LEADING_NUMBER.containsMatchIn(title) || SPECIAL.containsMatchIn(title)

    /**
     * 带序号的正文章节，且不是公告类（「第0章 上架感言」「12、请假条」）。
     * 比 [isChapterLike] 严：拿来挑识别正文用的样本章，宁缺毋滥。
     */
    fun isRegularChapter(title: String): Boolean =
        (ORDINAL.containsMatchIn(title) || LEADING_NUMBER.containsMatchIn(title)) && !NOTICE.containsMatchIn(title)

    private val NOTICE = Regex("感言|请假|公告|通知|说明|作品相关|新书|推荐|求票|月票|加更|致歉|抱歉")

    /** 章节序号；「第一百零三章」→ 103，「012 xxx」→ 12。抠不出来返回 null */
    fun numberOf(title: String): Int? {
        ORDINAL.find(title)?.let { m -> parseNumber(m.groupValues[1])?.let { return it } }
        return LEADING_NUMBER.find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** 阿拉伯数字（含全角）或中文数字（含大写；带单位「一百零五」、逐位「一〇五」都认）。混写、超出 Int 的返回 null */
    fun parseNumber(raw: String): Int? {
        val s = raw.map { c -> if (c in '０'..'９') '0' + (c - '０') else c }.joinToString("")
        if (s.all { it.isDigit() }) return s.toIntOrNull()
        // 没有十百千万：逐位写法「二〇五」「一二三」，一位一位读
        if (s.none { it in CN_UNIT_VALUES }) {
            return s.map { CN_DIGIT_VALUES[it] ?: return null }.joinToString("").toIntOrNull()
        }
        // 阿拉伯数字配单位（「12万」）说不清是多少，不猜
        if (s.any { it in '0'..'9' }) return null
        var total = 0L
        var section = 0L
        var number = 0L
        var pending = false
        for (c in s) {
            val digit = CN_DIGIT_VALUES[c]
            if (digit != null) {
                // 「一百零三」的零只是占位；两个非零数字连着、中间没有单位（「二十一二」）就不是个数
                if (digit == 0) continue
                if (pending) return null
                number = digit.toLong()
                pending = true
                continue
            }
            when (val unit = CN_UNIT_VALUES[c] ?: return null) {
                10_000 -> {
                    section += number
                    total += section * unit
                    section = 0
                }
                // 「十二」的十前面没有数字，按一十算
                else -> section += (if (number == 0L && unit == 10) 1L else number) * unit
            }
            number = 0
            pending = false
        }
        val v = total + section + number
        return if (v > Int.MAX_VALUE) null else v.toInt()
    }

    private val CN_DIGIT_VALUES = mapOf(
        '零' to 0, '〇' to 0,
        '一' to 1, '壹' to 1,
        '二' to 2, '两' to 2, '贰' to 2,
        '三' to 3, '叁' to 3,
        '四' to 4, '肆' to 4,
        '五' to 5, '伍' to 5,
        '六' to 6, '陆' to 6,
        '七' to 7, '柒' to 7,
        '八' to 8, '捌' to 8,
        '九' to 9, '玖' to 9,
    ) + ('0'..'9').associateWith { it - '0' }

    private val CN_UNIT_VALUES = mapOf(
        '十' to 10, '拾' to 10,
        '百' to 100, '佰' to 100,
        '千' to 1000, '仟' to 1000,
        '万' to 10_000,
    )
}
