package com.radium.inkwell.data.repo

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.rss.LegadoRssConverter
import com.radium.inkwell.core.source.rss.RssPayload
import com.radium.inkwell.core.source.rss.RssSourceRule
import com.radium.inkwell.core.source.rss.RssXmlParser
import com.radium.inkwell.core.util.LegadoLinks
import com.radium.inkwell.data.db.dao.RssSourceDao
import com.radium.inkwell.data.db.entity.RssSourceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** 导入结果；与书源导入同样的口径：说清新增了几个、跳过了几个、为什么 */
data class RssImportReport(
    val added: Int,
    val updated: Int,
    val skipped: List<String>,
) {
    val total: Int get() = added + updated
}

class RssRepository(
    private val dao: RssSourceDao,
    private val http: SourceHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sources: Flow<List<RssSourceEntity>> = dao.observeAll()

    /**
     * 有没有开着的订阅源（没删、开关开着）；还没从库里读回来是 null。书架顶栏的「订阅」按钮只在 true 时出现。
     *
     * 预热版（Eagerly，冷启动时由 InkwellApp 在后台拉起）：订阅源在库里、比 DataStore 里的偏好晚到，
     * 书架自己去订的话，按钮要在首帧之后才冒出来，把搜索、发现两个按钮往左挤一下 ——
     * 「发现」开关从前就因为初值不对闪过一回（见 BookshelfViewModel.exploreEnabled）。
     */
    val hasEnabledSourceState: StateFlow<Boolean?> =
        dao.observeHasEnabled().stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 订阅源的规则。存着的转换结果比现在的转换器旧（见 [LegadoRssConverter.VERSION]）、又留着阅读的原始 JSON 的，
     * 按原始 JSON 重转一次再用 —— 新版能认的东西（公共脚本库 jsLib……）不用用户重新导入就生效。
     * 只在内存里换，不写回库：写回会盖新的 updatedAt，在 WebDAV 合并里平白赢过别的设备。
     */
    suspend fun getRule(id: String): RssSourceRule? {
        val e = dao.getById(id) ?: return null
        if (e.converterVersion < LegadoRssConverter.VERSION && e.sourceJson.isNotBlank()) {
            val fresh = withContext(Dispatchers.Default) {
                runCatching { LegadoRssConverter.convert(e.sourceJson).converted.singleOrNull()?.rule }.getOrNull()
            }
            if (fresh != null) return fresh.copy(enabled = e.enabled)
        }
        return runCatching { RssSourceRule.fromJson(e.json) }.getOrNull()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled, System.currentTimeMillis())

    suspend fun delete(id: String) = dao.softDeleteById(id, System.currentTimeMillis())

    /**
     * 导入：粘进来的、剪贴板里的、文件里的，三个入口都走这里。
     *
     * 允许直接粘一个地址进来（而不是一份 JSON）—— 用户手里最常有的就是一个 `https://…/rss.xml`，
     * 或者源仓库里一个订阅源合集的地址，逼他先把 JSON 下载下来毫无道理。地址见 [importFromAddress]。
     */
    suspend fun import(text: String): RssImportReport {
        val trimmed = text.trim()
        if (isAddress(trimmed)) return importFromAddress(LegadoLinks.unwrap(trimmed))
        if (!LegadoRssConverter.looksLikeLegadoRss(trimmed)) {
            return RssImportReport(0, 0, listOf("不是订阅源：既不是阅读的订阅源 JSON，也不是一个地址"))
        }
        return importSources(trimmed)
    }

    /** 一行网址，或者阅读的一键导入链接（legado://import/rssSource?src=…） */
    private fun isAddress(text: String): Boolean =
        text.none { it == '\n' } &&
            (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) ||
                LegadoLinks.isAppLink(text))

    /**
     * 一个地址：先下载，按里面装的是什么分（[RssPayload]）。
     *
     * 从前只要是 `http(s)://` 开头就当 feed 地址，不看内容：源仓库的订阅源合集地址被建成一个名字是域名、
     * 没有任何规则的订阅源，导入时还说「新增 1」，点开才报「既不是 RSS/Atom feed」（被指出过）。
     */
    private suspend fun importFromAddress(url: String): RssImportReport {
        val fetched = try {
            http.fetch(url)
        } catch (e: CancellationException) {
            // 用户离开页面是取消：吞掉的话会接着往下写库，还报一句「打不开」
            throw e
        } catch (e: Exception) {
            return RssImportReport(0, 0, listOf("$url: 打不开（${e.message?.take(40)}）"))
        }
        val body = fetched.bodyText
        return when (RssPayload.of(body)) {
            RssPayload.LEGADO_SOURCES -> importSources(body.trim())
            RssPayload.FEED -> addFeed(url, body)
            RssPayload.BOOK_SOURCES ->
                RssImportReport(0, 0, listOf("这是来源合集，不是订阅源：请到「来源管理」导入"))
            RssPayload.UNKNOWN ->
                RssImportReport(0, 0, listOf("这个地址既不是 RSS/Atom feed，也不是订阅源合集"))
        }
    }

    /** 阅读的订阅源 JSON（单条或合集） */
    private suspend fun importSources(trimmed: String): RssImportReport {
        // 转换整份订阅源合集是纯 CPU 活，调用方在主线程上
        val result = withContext(Dispatchers.Default) { LegadoRssConverter.convert(trimmed) }
        val existing = dao.getAll().associateBy { it.id }
        val now = System.currentTimeMillis()
        var added = 0
        var updated = 0
        val toWrite = result.converted.map { c ->
            if (existing.containsKey(c.rule.id)) updated++ else added++
            c.rule.toEntity(
                sourceJson = c.sourceJson,
                // 重复导入保留用户的启用/禁用与排序
                enabled = existing[c.rule.id]?.enabled ?: c.rule.enabled,
                sortOrder = existing[c.rule.id]?.sortOrder ?: 0,
                // 同书源导入：原样重导不算修改，盖 now 会让本机旧的启停在 WebDAV 合并里赢过别的设备；
                // 墓碑被重新导入是「加回来」，要盖新时间戳
                updatedAt = existing[c.rule.id]
                    ?.takeIf { !it.deleted && it.sourceJson == c.sourceJson }
                    ?.updatedAt
                    ?: now,
            )
        }
        dao.upsertAll(toWrite)
        return RssImportReport(
            added = added,
            updated = updated,
            skipped = result.skipped.map { "${it.name.ifBlank { it.url }}: ${it.reason}" },
        )
    }

    /** 一个 feed 地址 → 一个订阅源。名字取 feed 自己的标题，取不到就用域名 */
    private suspend fun addFeed(url: String, body: String): RssImportReport {
        val title = RssXmlParser.feedTitle(body)
            ?: url.substringAfter("://").substringBefore("/")
        val existing = dao.getById(url)
        val rule = RssSourceRule(id = url, name = title)
        dao.upsertAll(
            listOf(
                rule.toEntity(
                    sourceJson = "",
                    enabled = existing?.enabled ?: true,
                    sortOrder = existing?.sortOrder ?: 0,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        )
        return RssImportReport(
            added = if (existing == null) 1 else 0,
            updated = if (existing == null) 0 else 1,
            skipped = emptyList(),
        )
    }

    private fun RssSourceRule.toEntity(
        sourceJson: String,
        enabled: Boolean,
        sortOrder: Int,
        updatedAt: Long,
    ) = RssSourceEntity(
        id = id,
        name = name,
        icon = icon,
        groupName = group,
        enabled = enabled,
        sortOrder = sortOrder,
        json = copy(enabled = enabled).toJson(),
        sourceJson = sourceJson,
        converterVersion = LegadoRssConverter.VERSION,
        updatedAt = updatedAt,
    )
}
