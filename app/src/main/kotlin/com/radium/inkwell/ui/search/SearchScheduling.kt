package com.radium.inkwell.ui.search

import com.radium.inkwell.data.db.entity.CheckStatus
import com.radium.inkwell.data.repo.BookSourceRepository.EnabledSource
import com.radium.inkwell.data.repo.TitleMatch
import kotlin.math.abs
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** 单个书源这一次搜索的结局；取消不算结局（不计入测速），由调用方原样往外抛 */
internal sealed interface SearchOutcome {
    data class Ok(val ms: Long) : SearchOutcome
    /** 等满了 [limitMs] 还没回来 */
    data class TimedOut(val limitMs: Long) : SearchOutcome
    data class RateLimited(val retryAfter: String?) : SearchOutcome
    /** 网络 / 规则出错 */
    data object Failed : SearchOutcome
}

/**
 * 搜索调度：先发谁、每个源等多久、测到的速度怎么记。抽成纯函数是为了能直接测。
 *
 * 一轮搜索的总耗时约等于「所有源耗时之和 ÷ 并发数」，真正拖时间的是坏源：一个吊死的源占着一个并发名额
 * 等满超时，几十个这样的源就是几分钟，健康的源只能排在后面干等。所以：
 * - 快的、活的先发（[order]）—— 最可能有结果的源先占住名额，结果早出来；
 * - 每个源按自己的历史定超时（[timeoutMs]），不再统一等 30 秒；
 * - 每次搜索都把耗时与成败记下来（[next]），用户不点「校验」也有数据可用。
 *
 * 测速口径只认搜索自己的 [EnabledSource.searchMs]；还没搜成功过的源才借校验的全链路耗时当估计 ——
 * 那是四个请求的总和，偏大，拿来定超时只会更宽松，不会误杀。
 */
internal object SearchScheduling {

    /** 连续失败这么多次就排到最后。不剔除：站点抽风过后会恢复，搜成功一次就清零回到正常位置 */
    const val DEMOTE_AFTER_FAILS = 2

    /** 没有任何测速数据的源按这个耗时排队：排在测过的快源之后、测过的慢源之前 */
    const val UNKNOWN_LATENCY_MS = 5_000L

    /** 没有测速数据时的超时。比从前统一的 30 秒短一半：真慢的源第一次超时后会被放宽，见 [next] */
    const val UNKNOWN_TIMEOUT_MS = 15_000L
    const val MIN_TIMEOUT_MS = 8_000L
    const val MAX_TIMEOUT_MS = 30_000L
    const val MAX_SAMPLE_AGE_MS = 7L * 24 * 60 * 60 * 1000
    const val RECOVERY_INTERVAL_MS = 15L * 60 * 1000
    const val CONCURRENCY = 8
    private const val PROBE_POSITION = 3
    private const val DEFAULT_COOLDOWN_MS = 120_000L
    private const val MIN_COOLDOWN_MS = 30_000L
    private const val MAX_COOLDOWN_MS = 900_000L
    private const val PERSIST_INTERVAL_MS = 60L * 60 * 1000
    private const val TIMEOUT_FACTOR = 3

    /**
     * 提前结束：同一本精确命中的书已有这么多个独立站点，剩下的就先不搜了（界面上可「继续搜索」）。
     * 绝大多数搜索是奔着某一本书去的；五个源都有了，预览页换源也够用，再等几百个源回来只是让用户干等。
     */
    const val EARLY_STOP_SOURCES = 5

    /** 书源总共就这么几个时不提前结束：省不了多少时间，反倒让人以为没搜完 */
    const val EARLY_STOP_MIN_SOURCES = 20

    /** 排队顺序：没被降级的在前；同档按估计耗时从快到慢；再同则保持书源管理里的手动顺序（稳定排序） */
    fun order(sources: List<EnabledSource>, now: Long = System.currentTimeMillis()): List<EnabledSource> {
        val ranked = sources.sortedWith(compareBy<EnabledSource>(
            { coolingDown(it, now) }, { demoted(it, now) },
            { latencyEstimate(it, now) ?: UNKNOWN_LATENCY_MS }, { it.sortOrder },
        ))
        // 每轮最多把一个过了冷静期的失败源放进首批；留前三个位置优先服务健康源。
        val probe = ranked.asSequence().filter {
            !coolingDown(it, now) && demoted(it, now) && age(it.searchAt, now) >= RECOVERY_INTERVAL_MS
        }.minByOrNull { it.searchAt } ?: return ranked
        val index = ranked.indexOf(probe)
        return if (index <= PROBE_POSITION) ranked else ranked.toMutableList().apply {
            add(PROBE_POSITION, removeAt(index))
        }
    }

    fun coolingDown(source: EnabledSource, now: Long): Boolean = source.searchRetryAt - now in 1..MAX_COOLDOWN_MS

    /** 这个源这次最多等多久：历史耗时的三倍，夹在 [MIN_TIMEOUT_MS]..[MAX_TIMEOUT_MS] 之间 */
    fun timeoutMs(source: EnabledSource?, now: Long = System.currentTimeMillis()): Long {
        if (source == null) return UNKNOWN_TIMEOUT_MS
        val current = latencyEstimate(source, now)
        if (current != null) return (current * TIMEOUT_FACTOR).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        // 过期记录不再抢首批槽位，但已知快源首轮不必为它重新付出完整冷启动超时。
        // 若实际超时，next 会把本次上限记作新观测，再给恢复的慢源更宽的时限。
        if (source.searchAt > 0 && source.searchMs >= 0) {
            return (source.searchMs * TIMEOUT_FACTOR).coerceIn(MIN_TIMEOUT_MS, UNKNOWN_TIMEOUT_MS)
        }
        return UNKNOWN_TIMEOUT_MS
    }

    /**
     * 记下一次结局后的测速。
     *
     * 超时不是「没有数据」：它说明这个源至少要这么久。把估计抬到不低于这次的上限，下回的超时就随之放宽
     * （8 秒超时 → 估计 8 秒 → 下回等 24 秒）。否则一个变慢了的源会每次都在同一个上限上超时，永远翻不了身。
     */
    fun next(
        source: EnabledSource,
        outcome: SearchOutcome,
        now: Long = System.currentTimeMillis(),
    ): EnabledSource {
        val recent = hasRecentSample(source, now)
        val failures = if (recent) source.searchFails else 0
        val observedAt = maxOf(now, source.searchAt + 1)
        return when (outcome) {
            is SearchOutcome.Ok -> source.copy(
                // 普通波动用滑动平均；恢复的源按这次实际耗时重新估计。
                searchMs = if (!recent || source.searchMs < 0 || failures >= DEMOTE_AFTER_FAILS) outcome.ms
                    else (source.searchMs * 7 + outcome.ms * 3) / 10,
                searchFails = 0,
                searchAt = observedAt,
                searchRetryAt = 0,
            )
            is SearchOutcome.TimedOut -> source.copy(
                searchMs = if (recent) maxOf(source.searchMs, outcome.limitMs) else outcome.limitMs,
                searchFails = (failures + 1).coerceAtMost(MAX_FAILS),
                searchAt = observedAt,
            )
            SearchOutcome.Failed -> source.copy(
                searchMs = if (recent) source.searchMs else -1,
                searchFails = (failures + 1).coerceAtMost(MAX_FAILS),
                searchAt = observedAt,
            )
            is SearchOutcome.RateLimited -> source.copy(
                searchRetryAt = now + retryDelay(outcome.retryAfter, now),
            )
        }
    }

    private fun retryDelay(header: String?, now: Long): Long {
        val value = header?.trim() ?: return DEFAULT_COOLDOWN_MS
        val delay = value.toLongOrNull()?.let { it.coerceIn(0L, MAX_COOLDOWN_MS / 1000) * 1000 }
            ?: try {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now
            } catch (_: DateTimeParseException) {
                DEFAULT_COOLDOWN_MS
            }
        return delay.coerceIn(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS)
    }

    /**
     * 值不值得写库。每轮几百个源，滑动平均每次都会挪一点；只有成败变了、或耗时变化超过一成（至少 200ms）
     * 才落库 —— 小抖动只活在这一轮的内存里，省掉成百上千次无意义的 UPDATE。
     */
    fun worthPersisting(before: EnabledSource, after: EnabledSource): Boolean {
        if (before.searchRetryAt != after.searchRetryAt) return true
        if (after.searchAt > 0 && (before.searchAt == 0L || after.searchAt - before.searchAt >= PERSIST_INTERVAL_MS)) return true
        if (before.searchFails != after.searchFails) return true
        if (before.searchMs < 0) return after.searchMs >= 0
        return abs(after.searchMs - before.searchMs) >= maxOf(200L, before.searchMs / 10)
    }

    /** 书名与关键词归一化后完全一致（剥书名号与空白，同 [TitleMatch.normalize]） */
    fun isExactTitle(title: String, normalizedKeyword: String): Boolean =
        normalizedKeyword.isNotEmpty() && TitleMatch.normalize(title).equals(normalizedKeyword, ignoreCase = true)

    /**
     * 有搜索自己的数据就只信它：校验失败可能败在正文那一步，而搜索一直好好的，不该因此排到最后。
     * 从没搜过时才看校验结论。
     */
    private fun demoted(s: EnabledSource, now: Long): Boolean =
        if (hasRecentSample(s, now)) s.searchFails >= DEMOTE_AFTER_FAILS
        else hasRecentCheck(s, now) && s.checkStatus == CheckStatus.FAILED

    private fun hasRecentSample(s: EnabledSource, now: Long): Boolean =
        s.searchAt > 0 && age(s.searchAt, now) < MAX_SAMPLE_AGE_MS

    private fun age(at: Long, now: Long): Long = (now - at).coerceAtLeast(0)

    private fun hasRecentCheck(s: EnabledSource, now: Long): Boolean =
        s.checkedAt == 0L || age(s.checkedAt, now) < MAX_SAMPLE_AGE_MS

    private fun latencyEstimate(s: EnabledSource, now: Long): Long? = when {
        hasRecentSample(s, now) && s.searchMs >= 0 -> {
            val age = age(s.searchAt, now)
            (s.searchMs * (MAX_SAMPLE_AGE_MS - age) + UNKNOWN_LATENCY_MS * age) / MAX_SAMPLE_AGE_MS
        }
        s.checkStatus == CheckStatus.OK && s.respondTime > 0 && !hasRecentSample(s, now) && hasRecentCheck(s, now) -> s.respondTime
        else -> null
    }

    private const val MAX_FAILS = 99
}
