package com.radium.inkwell.core.parser.txt

/**
 * txt 拆章：多套内置规则逐一评估，命中最多且分布合理者胜出；
 * 全部失败则按固定字数强切。产出的是字符区间（相对全文解码后的字符串）。
 */
object TxtChapterSplitter {

    data class Split(
        val title: String,
        /** 章节内容（含标题行）的字符区间，end 开区间 */
        val start: Int,
        val end: Int,
        val level: Int = 1,
    )

    data class Config(
        val customPattern: Regex? = null,
        val maxTitleLength: Int = 40,
        val fallbackSliceChars: Int = 10_000,
        val minMatches: Int = 3,
    )

    val VOLUME = Regex("""^\s*第\s*[0-9零〇一二三四五六七八九十百千两]+\s*[卷部集]\s*\S{0,30}\s*$""")
    val CHAPTER = Regex("""^\s*第\s*[0-9零〇一二三四五六七八九十百千万两]+\s*[章节回篇话]\s*.{0,30}$""")
    val SPECIAL = Regex("""^\s*(序章?|楔子|引子|番外\S{0,10}|尾声|后记|终章|終章)\s*\S{0,20}\s*$""")
    val EN = Regex("""^\s*Chapter\s+([0-9]+|[IVXLCivxlc]+)\b.{0,40}$""")
    val NUMERIC = Regex("""^\s*[(（]?[0-9零一二三四五六七八九十百]{1,6}[)）、.．]\s*\S{0,25}\s*$""")

    private val candidateRules = listOf(
        listOf(CHAPTER, VOLUME, SPECIAL),   // 主规则：章 + 卷 + 特殊章
        listOf(EN),
        listOf(NUMERIC),
    )

    fun split(text: String, config: Config = Config()): List<Split> {
        val lines = scanLines(text)

        config.customPattern?.let { custom ->
            val hits = lines.filter { it.content.length <= config.maxTitleLength && custom.matches(it.content) }
            if (hits.isNotEmpty()) return buildSplits(text, hits, emptySet())
        }

        for (rules in candidateRules) {
            val main = rules.first()
            val hits = lines.filter { line ->
                line.content.length <= config.maxTitleLength && rules.any { it.matches(line.content) }
            }
            val mainHits = hits.count { main.matches(it.content) }
            if (mainHits >= config.minMatches && distributionSane(hits, text.length)) {
                val volumeLines = if (VOLUME in rules) {
                    hits.filter { VOLUME.matches(it.content) }.map { it.start }.toSet()
                } else emptySet()
                return buildSplits(text, hits, volumeLines)
            }
        }

        return fallbackSplit(text, config.fallbackSliceChars)
    }

    private data class Line(val content: String, val start: Int, val end: Int)

    private fun scanLines(text: String): List<Line> {
        val lines = mutableListOf<Line>()
        var start = 0
        while (start < text.length) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            val raw = text.substring(start, end)
            val trimmed = raw.trim().trimEnd('\r')
            if (trimmed.isNotEmpty()) lines += Line(trimmed, start, end)
            start = end + 1
        }
        return lines
    }

    /** 命中标题分布检查：平均章长不小于 200 字，防止把对话列表当章节 */
    private fun distributionSane(hits: List<Line>, totalLength: Int): Boolean {
        if (hits.isEmpty()) return false
        return totalLength / hits.size >= 200
    }

    private fun buildSplits(text: String, hits: List<Line>, volumeStarts: Set<Int>): List<Split> {
        val splits = mutableListOf<Split>()
        // 第一个标题前的内容作为"前言"章
        if (hits.first().start > 0) {
            val head = text.substring(0, hits.first().start)
            if (head.isNotBlank()) splits += Split("前言", 0, hits.first().start)
        }
        hits.forEachIndexed { i, line ->
            val end = if (i + 1 < hits.size) hits[i + 1].start else text.length
            splits += Split(
                title = line.content,
                start = line.start,
                end = end,
                level = if (line.start in volumeStarts) 0 else 1,
            )
        }
        return splits
    }

    private fun fallbackSplit(text: String, sliceChars: Int): List<Split> {
        if (text.isBlank()) return emptyList()
        val splits = mutableListOf<Split>()
        var start = 0
        var index = 1
        while (start < text.length) {
            var end = (start + sliceChars).coerceAtMost(text.length)
            if (end < text.length) {
                // 在目标点之后找最近的换行，别把段落切碎
                val nl = text.indexOf('\n', end)
                end = if (nl in end until end + 2000) nl + 1 else end
            }
            splits += Split("第 $index 部分", start, end)
            start = end
            index++
        }
        return splits
    }
}
