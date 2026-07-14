package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.rss.LegadoRssConverter
import com.radium.inkwell.core.source.rss.RssSourceRule
import com.radium.inkwell.core.source.rss.RssXmlParser
import com.radium.inkwell.data.db.dao.RssSourceDao
import com.radium.inkwell.data.db.entity.RssSourceEntity
import kotlinx.coroutines.flow.Flow

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
    val sources: Flow<List<RssSourceEntity>> = dao.observeAll()

    suspend fun getRule(id: String): RssSourceRule? =
        dao.getById(id)?.let { runCatching { RssSourceRule.fromJson(it.json) }.getOrNull() }

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun importFromUrl(url: String): RssImportReport {
        val text = http.fetch(url).bodyText
        return import(text)
    }

    /**
     * 导入。
     *
     * 允许直接粘一个 feed 地址进来（而不是一份 JSON）—— 用户手里最常有的就是一个
     * `https://…/rss.xml`，逼他先去找一份 legado 格式的订阅源 JSON 毫无道理。
     */
    suspend fun import(text: String): RssImportReport {
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return importPlainFeed(trimmed)
        }
        if (!LegadoRssConverter.looksLikeLegadoRss(trimmed)) {
            return RssImportReport(0, 0, listOf("不是订阅源：既不是 legado 订阅源 JSON，也不是一个 feed 地址"))
        }
        val result = LegadoRssConverter.convert(trimmed)
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
                updatedAt = now,
            )
        }
        dao.upsertAll(toWrite)
        return RssImportReport(
            added = added,
            updated = updated,
            skipped = result.skipped.map { "${it.name.ifBlank { it.url }}: ${it.reason}" },
        )
    }

    /** 一个裸 feed 地址 → 一个订阅源。名字取 feed 自己的标题，取不到就用域名 */
    private suspend fun importPlainFeed(url: String): RssImportReport {
        val fetched = runCatching { http.fetch(url) }.getOrElse {
            return RssImportReport(0, 0, listOf("$url: 打不开（${it.message?.take(40)}）"))
        }
        val title = RssXmlParser.feedTitle(fetched.bodyText)
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
