package com.radium.inkwell.core.util

import com.radium.inkwell.core.source.detect.ChapterTitles

/** 从平铺章名里识别「第一卷 卷名 第一章 章名」，两段都保留原有编号和文字。 */
data class VolumeChapterTitle(val volume: String, val chapter: String) {
    companion object {
        private val volumeOrdinal = Regex("^\\s*第\\s*([${ChapterTitles.CN_DIGITS}]+)\\s*卷")
        private val chapterOrdinal = Regex("第\\s*([${ChapterTitles.CN_DIGITS}]+)\\s*[章节回话]")

        fun parse(title: String): VolumeChapterTitle? {
            val volume = volumeOrdinal.find(title) ?: return null
            val volumeEnd = volume.range.last + 1
            // 有卷名时要求可见分隔。出现多个符合条件的章号时，无法确定哪一个才是章名的起点。
            val chapter = chapterOrdinal.findAll(title, volumeEnd).filter {
                it.range.first == volumeEnd || isSeparator(title[it.range.first - 1])
            }.singleOrNull() ?: return null
            if (ChapterTitles.parseNumber(volume.groupValues[1]) == null ||
                ChapterTitles.parseNumber(chapter.groupValues[1]) == null
            ) return null

            val chapterStart = chapter.range.first
            return VolumeChapterTitle(
                volume = title.substring(0, chapterStart).trimStart().trimEnd(::isSeparator),
                chapter = title.substring(chapterStart).trimEnd(),
            )
        }

        private fun isSeparator(char: Char): Boolean = char.isWhitespace() || char in ":：·-－—"
    }
}
