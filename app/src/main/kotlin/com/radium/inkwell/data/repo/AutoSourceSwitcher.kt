package com.radium.inkwell.data.repo

import com.radium.inkwell.data.db.entity.CheckStatus
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * 自动换源：在其他书源里找一个**真的能把当前这一章读出来**的。
 *
 * 判据是端到端跑通，而不是"搜得到书名"：
 *   搜索 → 书名(可选作者)匹配 → 详情 → 目录 → **把当前章的正文抓下来**
 * 少一环都不算数。只验到目录就切的话，切过去照样白屏 —— 那正是自动换源要消灭的场景，
 * 结果自己又制造一遍。
 *
 * 选谁：**并发跑，谁第一个整条链路跑通就用谁**，不比较质量、不等所有源回来。
 * 这天然偏向快的源 —— "书源网络很差"本身就是要解决的问题之一，让慢的源自己出局最省事。
 */
class AutoSourceSwitcher(
    private val sourceRepo: BookSourceRepository,
    private val engine: BookSourceEngine,
) {

    data class Probe(val result: SearchResult, val rule: BookSourceRule)

    /** 要找的那一章。带上标题是为了在新源的目录里按标题对齐，而不是傻按序号 —— 各源章节数常有出入 */
    data class Target(val chapterIndex: Int, val chapterTitle: String?)

    /**
     * @param exclude 当前源；它已经证明自己不行了，不必再试
     * @param onProgress (已回来的源数, 总数)，用于界面上显示进度
     * @return 第一个跑通的源；一个都没有则 null
     */
    suspend fun findWorkingSource(
        title: String,
        author: String,
        exclude: String?,
        target: Target,
        checkAuthor: Boolean,
        bookHits: Map<String, Boolean> = emptyMap(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Probe? = coroutineScope {
        val candidates = rankedCandidates(exclude, bookHits)
        if (candidates.isEmpty()) return@coroutineScope null

        val limiter = Semaphore(CONCURRENCY)
        val inbox = Channel<Probe?>(Channel.UNLIMITED)

        val jobs = candidates.map { rule ->
            launch {
                val probe = runCatching {
                    limiter.withPermit {
                        withTimeout(PROBE_TIMEOUT_MS) {
                            probe(rule, title, author, target, checkAuthor)
                        }
                    }
                }.getOrNull()
                inbox.send(probe)
            }
        }

        var done = 0
        var winner: Probe? = null
        while (done < candidates.size) {
            val probe = inbox.receive()
            done++
            onProgress(done, candidates.size)
            if (probe != null) {
                winner = probe
                break // 先到先得：剩下的不必再等
            }
        }
        jobs.forEach { it.cancel() }
        inbox.close()
        winner
    }

    private suspend fun rankedCandidates(
        exclude: String?,
        bookHits: Map<String, Boolean>,
    ): List<BookSourceRule> = rank(sourceRepo.getEnabledForSwitch(), exclude, bookHits)

    /** 端到端跑一遍。任何一环没过就返回 null —— 抛异常也一样，调用方 runCatching 吞掉。 */
    private suspend fun probe(
        rule: BookSourceRule,
        title: String,
        author: String,
        target: Target,
        checkAuthor: Boolean,
    ): Probe? {
        val hit = engine.search(rule, title).items
            .firstOrNull {
                TitleMatch.matches(it.title, title) &&
                    (!checkAuthor || TitleMatch.authorMatches(it.author, author))
            }
            ?: return null

        val detail = engine.getDetail(rule, hit.bookUrl)
        val toc = engine.getToc(rule, detail.tocUrl.ifBlank { hit.bookUrl })
        if (toc.isEmpty()) return null

        // 在新源的目录里定位"同一章"：优先按标题，对不上再按序号夹取
        val chapter = target.chapterTitle
            ?.let { want ->
                val norm = TitleMatch.normalize(want)
                toc.filter { TitleMatch.normalize(it.title) == norm }
                    .minByOrNull { kotlin.math.abs(it.index - target.chapterIndex) }
            }
            ?: toc[target.chapterIndex.coerceIn(0, toc.lastIndex)]

        // 最后这一步才是重点：正文真的抓得下来吗
        val content = engine.getContent(
            source = rule,
            chapterUrl = chapter.url,
            otherChapterUrls = toc.asSequence().map { it.url }.toSet(),
            chapterVariable = chapter.variable,
        )
        if (content.elements.isEmpty()) return null

        return Probe(result = hit.copy(bookUrl = hit.bookUrl), rule = rule)
    }

    companion object {
        /**
         * 试源的顺序。**校验过的书源数据在这儿终于派上用场了** —— 先试校验通过的、响应快的，
         * 让最可能赢的源占住那几个并发名额。（Legado 没有这份数据，只能按库里的原顺序硬试。）
         *
         * 校验失败的排最后，而**不是直接剔除**：校验结果会过期（站点当时抽风、后来恢复），
         * 而这里已经是"读不了书"的兜底路径，宁可多试一个也不要空手而归。
         */
        internal fun rank(
            sources: List<BookSourceRepository.EnabledSource>,
            exclude: String?,
            bookHits: Map<String, Boolean> = emptyMap(),
        ): List<BookSourceRule> = sources
            // 没有搜索规则的源找不到书；当前源已经证明自己不行了
            .filter { it.rule.search != null && it.rule.id != exclude }
            .sortedWith(
                compareBy(
                    { hitRank(bookHits[it.rule.id]) },
                    { statusRank(it.checkStatus) },
                    // 没测过耗时的排在测过的后面，而不是当成 0 排最前
                    { if (it.respondTime > 0) it.respondTime else Long.MAX_VALUE },
                    { it.sortOrder },
                )
            )
            .map { it.rule }

        /**
         * 按书维度的信号**压在全局健康度之前**：一个又快又健康、但根本没有这本书的源，
         * 对这次换源毫无用处 —— checkStatus/respondTime 回答的是"源活着吗、快吗"，
         * 回答不了"有没有**这本书**"。
         *
         * 搜过但没有的（false）只垫底、**不剔除**，同 [statusRank] 那套理由：站点会上新书，
         * 而且用户点换源本就是想看全部候选，凭空少几个源只会让人纳闷。
         */
        private fun hitRank(hit: Boolean?) = when (hit) {
            true -> 0
            null -> 1 // 没搜过，居中
            false -> 2 // 搜索跑通了、确实没这本书
        }

        private fun statusRank(status: Int) = when (status) {
            CheckStatus.OK -> 0
            CheckStatus.UNCHECKED -> 1
            else -> 2 // FAILED
        }

        /** 与换源搜索同样限流：几百个源一起发请求只会集体超时或被站点限流 */
        const val CONCURRENCY = 8

        /**
         * 单个源跑完整条链路的上限。比正文加载的 15s 宽 —— 这里要跑 4 个请求（搜索/详情/目录/正文），
         * 卡得太死会把本来能用的源判死。
         */
        const val PROBE_TIMEOUT_MS = 40_000L
    }
}
