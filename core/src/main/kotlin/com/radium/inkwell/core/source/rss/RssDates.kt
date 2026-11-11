package com.radium.inkwell.core.source.rss

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * 文章的发布时间（feed 里的 pubDate 原文）→ 时刻。订阅页把所有源的文章按它排成一条时间线。
 *
 * feed 里的时间五花八门，这里认常见的几类，认不出返回 null（那一篇按第一次取到的时刻排，见 app 的 RssFeedRepository）：
 * - RSS 的 RFC 822：`Wed, 11 Nov 2026 00:26:39 +0000`，时区也可能写成 `GMT` / `CST` / `+08:00`，星期可能没有
 * - Atom 的 ISO 8601：`2026-11-11T00:26:39Z`、`2026-11-11T08:26:39.123+08:00`
 * - 规则源（阅读的订阅源）抓网页抓出来的：`2026-11-11 08:26`、`2026/11/11`、`2026年11月11日 08:26`、
 *   `11-11 08:26`（没年份，取最近的那个）、`3 小时前`、`昨天 08:26`、`刚刚`
 *
 * 不带时区的按 [zone]（手机所在时区）算 —— 国内的网站写的就是北京时间，手机多半也在这个时区。
 */
object RssDates {

    fun parse(text: String?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        val s = text?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() } ?: return null
        return relative(s, now, zone)
            ?: rfc822(s)
            ?: iso(s)
            ?: localDateTime(s, now, zone)
    }

    // ---------- 相对时间：刚刚 / N 分钟前 / 昨天 08:26 ----------

    private fun relative(s: String, now: Long, zone: ZoneId): Long? {
        if (s == "刚刚" || s.equals("just now", ignoreCase = true)) return now
        AGO.matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toLong()
            val unitMs = when (m.groupValues[2]) {
                "秒" -> 1_000L
                "分钟", "分" -> 60_000L
                "小时", "个小时" -> 3_600_000L
                "天" -> 86_400_000L
                else -> return null
            }
            return now - n * unitMs
        }
        DAY_WORD.matchEntire(s)?.let { m ->
            val back = when (m.groupValues[1]) {
                "今天" -> 0L
                "昨天" -> 1L
                "前天" -> 2L
                else -> return null
            }
            val day = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(back)
            val time = m.groupValues[2].takeIf { it.isNotEmpty() }
                ?.let { runCatching { LocalTime.parse(it, HM) }.getOrNull() }
                ?: LocalTime.MIDNIGHT
            return day.atTime(time).atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }

    // ---------- RFC 822 ----------

    private fun rfc822(s: String): Long? {
        // 星期去掉（有的 feed 星期写错，严格解析会整条拒掉）；结尾的时区缩写换成偏移
        var t = s.replace(WEEKDAY_PREFIX, "")
        ZONE_ABBR.find(t)?.let { m ->
            val offset = ZONE_OFFSETS[m.groupValues[1].uppercase()] ?: return@let
            t = t.substring(0, m.range.first) + " " + offset
        }
        for (f in RFC822) {
            try {
                return ZonedDateTime.parse(t, f).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    // ---------- ISO 8601（带时区） ----------

    private fun iso(s: String): Long? {
        if (!ISO_HINT.containsMatchIn(s)) return null
        // 日期与时间之间的空格换成 T，偏移前的空格去掉，`+0800` 补成 `+08:00`（ISO_OFFSET_DATE_TIME 只认带冒号的）
        val t = s.replaceFirst(' ', 'T')
            .replace(" ", "")
            .replace(COMPACT_OFFSET, "$1:$2")
        return try {
            OffsetDateTime.parse(t, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    // ---------- 不带时区的日期 / 日期时间 ----------

    private fun localDateTime(s: String, now: Long, zone: ZoneId): Long? {
        val m = LOCAL.matchEntire(s) ?: return null
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val yearText = m.groupValues[1]
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        val hour = m.groupValues[4].toIntOrNull() ?: 0
        val minute = m.groupValues[5].toIntOrNull() ?: 0
        val second = m.groupValues[6].toIntOrNull() ?: 0
        val date = try {
            if (yearText.isNotEmpty()) {
                val year = yearText.toInt().let { if (it < 100) 2000 + it else it }
                LocalDate.of(year, month, day)
            } else {
                // 没写年份（「11-11 08:26」）：今年的这一天要是还没到，就是去年的
                LocalDate.of(today.year, month, day).let { if (it.isAfter(today)) it.minusYears(1) else it }
            }
        } catch (_: java.time.DateTimeException) {
            return null
        }
        return try {
            LocalDateTime.of(date, LocalTime.of(hour, minute, second)).atZone(zone).toInstant().toEpochMilli()
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    private val AGO = Regex("(\\d+)\\s*(秒|分钟|分|个小时|小时|天)前")
    private val DAY_WORD = Regex("(今天|昨天|前天)\\s*(\\d{1,2}:\\d{2})?")
    private val WEEKDAY_PREFIX = Regex("^[A-Za-z]{3,9},?\\s+")

    /** 末尾的时区缩写（偏移另有格式认，这里只管字母的） */
    private val ZONE_ABBR = Regex("\\s([A-Za-z]{1,4})$")
    private val ZONE_OFFSETS = mapOf(
        "GMT" to "+0000", "UT" to "+0000", "UTC" to "+0000", "Z" to "+0000",
        // 国内站点写的 CST 是北京时间（不是美国中部时间）
        "CST" to "+0800",
        "EST" to "-0500", "EDT" to "-0400", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700",
        "JST" to "+0900", "KST" to "+0900", "HKT" to "+0800", "SGT" to "+0800",
    )

    /** 日-月名-年 时:分[:秒] 偏移；偏移有 `+0800` 和 `+08:00` 两种写法 */
    private val RFC822: List<DateTimeFormatter> = listOf("Z", "XXX").map { offset ->
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d MMM ")
            .appendValueReduced(ChronoField.YEAR, 2, 4, 2000)
            .appendPattern(" H:mm")
            .optionalStart().appendPattern(":ss").optionalEnd()
            .appendPattern(" $offset")
            .toFormatter(Locale.ENGLISH)
    }

    /** 像 ISO 带时区的：日期、T 或空格、时间，结尾是 Z 或偏移 */
    private val ISO_HINT = Regex("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}.*(Z|[+-]\\d{2}:?\\d{2})$")
    private val COMPACT_OFFSET = Regex("([+-]\\d{2})(\\d{2})$")

    /** 年可缺；分隔符 - / . 年月日；时间可缺 */
    private val LOCAL = Regex(
        "^(?:(\\d{2,4})[-/.年])?(\\d{1,2})[-/.月](\\d{1,2})日?(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$",
    )
}
