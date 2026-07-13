package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.legado.LegadoConverter
import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.data.db.entity.BookSourceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class BookSourceRepository(private val dao: BookSourceDao) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    val sources: Flow<List<BookSourceEntity>> = dao.observeAll()

    data class ImportReport(
        val added: Int,
        val updated: Int,
        /** 转换阶段无法转换的书源原因 */
        val skipped: List<String>,
        /** 入库阶段失败的书源原因（正常应为空） */
        val failed: List<String> = emptyList(),
    ) {
        val imported: Int get() = added + updated

        /** 说清楚新增/更新/跳过/失败各多少，避免"看起来没反应" */
        val summary: String
            get() = buildString {
                when {
                    added > 0 && updated > 0 -> append("新增 $added 个、更新 $updated 个书源")
                    added > 0 -> append("新增 $added 个书源")
                    updated > 0 -> append("更新 $updated 个已有书源")
                    else -> append("没有可用书源")
                }
                if (failed.isNotEmpty()) append("；${failed.size} 个入库失败")
                if (skipped.isNotEmpty()) {
                    append("；跳过 ${skipped.size} 个（")
                    append(skipped.first().take(40))
                    if (skipped.size > 1) append(" 等")
                    append("）")
                }
            }
    }

    /** 导入书源 JSON：自动识别 Legado 格式并转换；自有格式高 version 覆盖 */
    suspend fun importJson(text: String): Result<ImportReport> = runCatching {
        if (LegadoConverter.looksLikeLegado(text)) {
            return@runCatching importLegado(text)
        }
        val trimmed = text.trim()
        val rules: List<BookSourceRule> = if (trimmed.startsWith("[")) {
            json.decodeFromString(trimmed)
        } else {
            listOf(json.decodeFromString(trimmed))
        }
        upsertRules(rules)
    }

    private suspend fun importLegado(text: String): ImportReport {
        val result = LegadoConverter.convert(text)
        return upsertRules(
            result.converted.map { it.rule },
            skipped = result.skipped.map { "${it.name}: ${it.reason}" },
        )
    }

    /**
     * 批量入库：逐条构造 entity（失败只跳过该条），去重后单事务写入。
     * 单事务避免逐条写在真机上被中断只写进第一条的问题。
     */
    private suspend fun upsertRules(
        rules: List<BookSourceRule>,
        skipped: List<String> = emptyList(),
    ): ImportReport {
        // 同 id 保留最后一个（同站多源撞 id 时避免同事务重复主键）
        val deduped = rules.associateBy { it.id }.values
        val existingById = dao.getAll().associateBy { it.id }
        val now = System.currentTimeMillis()

        val toWrite = ArrayList<BookSourceEntity>(deduped.size)
        val failed = ArrayList<String>()
        var added = 0
        var updated = 0
        deduped.forEach { rule ->
            try {
                val existing = existingById[rule.id]
                val existingVersion = existing?.let {
                    runCatching { json.decodeFromString<BookSourceRule>(it.json).version }.getOrDefault(0)
                } ?: -1
                if (rule.version < existingVersion) return@forEach // 保留更高版本
                toWrite += BookSourceEntity(
                    id = rule.id,
                    name = rule.name,
                    // 重复导入保留用户的启用/禁用与排序
                    enabled = existing?.enabled ?: rule.enabled,
                    sortOrder = existing?.sortOrder ?: 0,
                    json = json.encodeToString(BookSourceRule.serializer(), rule),
                    updatedAt = now,
                )
                if (existing == null) added++ else updated++
            } catch (e: Exception) {
                failed += "${rule.name}: ${e.message?.take(60)}"
            }
        }
        if (toWrite.isNotEmpty()) dao.upsertAll(toWrite)
        return ImportReport(added, updated, skipped, failed)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun getRule(id: String): BookSourceRule? =
        dao.getById(id)?.let { runCatching { json.decodeFromString<BookSourceRule>(it.json) }.getOrNull() }

    suspend fun getEntity(id: String): BookSourceEntity? = dao.getById(id)

    suspend fun getEnabledRules(): List<BookSourceRule> =
        dao.getEnabled().mapNotNull { entity ->
            runCatching { json.decodeFromString<BookSourceRule>(entity.json) }.getOrNull()
        }

    fun parseRule(text: String): Result<BookSourceRule> =
        runCatching { json.decodeFromString(text.trim()) }

    fun encodeRule(rule: BookSourceRule): String =
        json.encodeToString(BookSourceRule.serializer(), rule)
}
