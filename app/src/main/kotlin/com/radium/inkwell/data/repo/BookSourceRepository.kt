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

    data class ImportReport(val imported: Int, val skipped: List<String>) {
        val summary: String
            get() = buildString {
                append("导入 $imported 个书源")
                if (skipped.isNotEmpty()) {
                    append("，跳过 ${skipped.size} 个：")
                    append(skipped.take(3).joinToString("；"))
                    if (skipped.size > 3) append(" 等")
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
        ImportReport(upsertRules(rules), emptyList())
    }

    private suspend fun importLegado(text: String): ImportReport {
        val result = LegadoConverter.convert(text)
        val imported = upsertRules(result.converted.map { it.rule })
        return ImportReport(
            imported = imported,
            skipped = result.skipped.map { "${it.name}(${it.reason})" },
        )
    }

    private suspend fun upsertRules(rules: List<BookSourceRule>): Int {
        var imported = 0
        rules.forEach { rule ->
            val existing = dao.getById(rule.id)
            val existingVersion = existing?.let {
                runCatching { json.decodeFromString<BookSourceRule>(it.json).version }.getOrDefault(0)
            } ?: -1
            if (rule.version >= existingVersion) {
                dao.upsert(
                    BookSourceEntity(
                        id = rule.id,
                        name = rule.name,
                        enabled = existing?.enabled ?: rule.enabled,
                        sortOrder = existing?.sortOrder ?: 0,
                        json = json.encodeToString(BookSourceRule.serializer(), rule),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                imported++
            }
        }
        return imported
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
