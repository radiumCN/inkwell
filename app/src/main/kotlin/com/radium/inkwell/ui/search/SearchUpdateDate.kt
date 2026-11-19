package com.radium.inkwell.ui.search

import com.radium.inkwell.core.source.SearchResult
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal const val UNKNOWN_UPDATE_DATE = Long.MIN_VALUE

// 日期只来自明确的规则字段或格式明确的分类状态，不从简介或章节名猜测。
internal fun searchUpdateEpoch(results: List<SearchResult>): Long {
    val latestAllowed = Instant.now().plusSeconds(86_400).toEpochMilli()
    val explicit = results.maxOf { parseSearchDate(it.updateTime, latestAllowed) }
    if (explicit != UNKNOWN_UPDATE_DATE) return explicit
    return results.maxOf {
        val kind = it.kind?.trim().orEmpty()
        val candidate = KIND_UPDATE.find(kind)?.groupValues?.get(1)
            ?: STATUS_DATE.matchEntire(kind)?.groupValues?.get(1)
            ?: kind
        parseSearchDate(candidate, latestAllowed)
    }
}

private fun parseSearchDate(raw: String?, latestAllowed: Long): Long {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return UNKNOWN_UPDATE_DATE
    val epoch = when {
        (text.length == 10 || text.length == 13) && text.all { it in '0'..'9' } -> {
            val number = text.toLong()
            if (text.length == 10) number * 1000 else number
        }
        else -> parseCalendarDate(text)
    }
    return epoch.takeIf { it in EARLIEST_UPDATE_MS..latestAllowed } ?: UNKNOWN_UPDATE_DATE
}

private fun parseCalendarDate(text: String): Long {
    DATE.matchEntire(text)?.destructured?.let { (year, month, day) ->
        return try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
                .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeException) {
            UNKNOWN_UPDATE_DATE
        }
    }
    if (text.length < 16 || !text[0].isDigit() || ('T' !in text && ' ' !in text)) return UNKNOWN_UPDATE_DATE
    val iso = text.replace(' ', 'T')
    return try {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (_: DateTimeException) {
        try {
            LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeException) {
            UNKNOWN_UPDATE_DATE
        }
    }
}

private const val EARLIEST_UPDATE_MS = 631_152_000_000L // 1990-01-01 UTC
private const val DATE_PART = """\d{4}[-/.年]\d{1,2}[-/.月]\d{1,2}日?"""
private val DATE = Regex("""(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?""")
private val KIND_UPDATE = Regex("""(?:^|[·|｜;；,，])\s*(?:最后更新(?:时间)?|更新时间|更新日期|更新于|更新)\s*[:：]?\s*($DATE_PART)(?!\d)""")
private val STATUS_DATE = Regex("""(?:连载中?|完结|已完结)\s*[·|｜]\s*($DATE_PART)""")
