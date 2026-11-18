package com.radium.inkwell.data.repo

import android.util.Log
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.data.db.dao.SourceReadHealthDao
import com.radium.inkwell.data.db.entity.SourceReadHealthEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

enum class ReadStage(val weight: Int) { DETAIL(2), TOC(3), CONTENT(5) }

/** 只记录实际打开或阅读的请求；取消、缓存命中和书源校验都不计样本。 */
class SourceReadHealth(
    private val dao: SourceReadHealthDao,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class Sample(val rule: BookSourceRule, val stage: ReadStage, val ok: Boolean, val at: Long)
    private val samples = Channel<Sample>(Channel.BUFFERED)

    init {
        scope.launch {
            var lastPrune = 0L
            for (sample in samples) {
                try {
                    val since = sample.at - MAX_READ_HEALTH_AGE_MS
                    if (sample.at - lastPrune >= 86_400_000L) {
                        dao.prune(since)
                        lastPrune = sample.at
                    }
                    dao.record(sample.rule.id, sample.stage.name, sample.rule.hashCode(),
                        if (sample.ok) 1000 else 0, sample.at, since)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Log.w("SourceReadHealth", "书源可用性样本写入失败")
                }
            }
        }
    }

    suspend fun snapshot(now: Long): Map<String, List<SourceReadHealthEntity>> = try {
        dao.recent(now - MAX_READ_HEALTH_AGE_MS).groupBy { it.sourceId }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }

    suspend fun <T> measure(
        rule: BookSourceRule,
        stage: ReadStage,
        usable: (T) -> Boolean = { true },
        fetch: suspend () -> T,
    ): T {
        val result = try {
            fetch()
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            record(rule, stage, false)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            record(rule, stage, false)
            throw e
        }
        currentCoroutineContext().ensureActive()
        record(rule, stage, usable(result))
        return result
    }

    fun record(rule: BookSourceRule, stage: ReadStage, ok: Boolean) {
        samples.trySend(Sample(rule, stage, ok, System.currentTimeMillis()))
    }
}

private const val MAX_READ_HEALTH_AGE_MS = 7L * 24 * 60 * 60 * 1000

/** 回归到中性先验；少量或陈旧样本不能完全压过其他证据。 */
internal fun readingHealthScore(rule: BookSourceRule, observations: List<SourceReadHealthEntity>, now: Long): Int {
    if (observations.isEmpty()) return 0
    val hash = rule.hashCode()
    return ReadStage.entries.sumOf { stage ->
        val sample = observations.firstOrNull { it.stage == stage.name && it.ruleHash == hash }
            ?: return@sumOf 0
        val age = (now - sample.observedAt).coerceAtLeast(0)
        val freshness = (MAX_READ_HEALTH_AGE_MS - age).coerceAtLeast(0L)
        ((sample.score - 500L) * stage.weight * sample.samples.coerceAtMost(4) * freshness /
            (4 * MAX_READ_HEALTH_AGE_MS)).toInt()
    }
}
