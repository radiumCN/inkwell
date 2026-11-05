package com.radium.inkwell.core.util

import com.radium.inkwell.core.source.detect.ChapterTitles
import kotlin.math.abs

/**
 * 换源面板比「哪个源更新得多」用的章节序号。
 *
 * 和 `ChapterTitles.numberOf` 分开：那个给目录识别打分，认得宽 —— 「第五卷」算序号、标题开头的「04-18」也算。
 * 拿它比两个书源的最新章会比出笑话：「第五卷 第12章」读成 5，最新章字段里夹的日期读成 4。
 * 这里只认「第X章 / 节 / 回 / 话」，认不出就是 null —— 宁可不标，也不瞎标。
 */
object ChapterOrdinal {

    private val ORDINAL = Regex("第\\s*([${ChapterTitles.CN_DIGITS}]+)\\s*[章节回话]")

    /** 「第13370章 xxx」→ 13370，「VIP 第一千二百章」→ 1200；没有「第X章」（或是第 0 章）返回 null */
    fun of(title: String?): Int? {
        if (title.isNullOrBlank()) return null
        val m = ORDINAL.find(title) ?: return null
        return ChapterTitles.parseNumber(m.groupValues[1])?.takeIf { it > 0 }
    }

    /**
     * [candidate] 的最新章比 [current] 的多几章（负数 = 少几章）。任一边认不出序号、或差得不像同一套编号时返回 null。
     *
     * 「不像同一套编号」：有的站按卷重新编号（「第五卷 第12章」），拿 12 去比别家的 13362，会标出「少 13350 章」。
     * 差距超过两边较大那个的五分之一（短书至少容 [MIN_SLACK] 章）就当编号对不上，不标。
     */
    fun gap(candidate: String?, current: String?): Int? {
        val a = of(candidate) ?: return null
        val b = of(current) ?: return null
        val d = a - b
        return d.takeIf { abs(it) <= maxOf(MIN_SLACK, maxOf(a, b) / 5) }
    }

    private const val MIN_SLACK = 30
}
