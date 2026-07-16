package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.data.db.dao.BookSourceHitDao
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.CheckStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class BookSourceRepository(
    private val dao: BookSourceDao,
    private val hitDao: BookSourceHitDao,
) {

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
                    append("；跳过 ${skipped.size} 个")
                    // 按原因归类展示 Top3，便于定位（原因取冒号后的说明部分）
                    val byReason = skipped
                        .groupingBy { it.substringAfter(": ", it).take(24) }
                        .eachCount()
                        .entries.sortedByDescending { it.value }
                    append("（")
                    append(byReason.take(3).joinToString("，") { "${it.key}×${it.value}" })
                    if (byReason.size > 3) append("…")
                    append("）")
                }
            }
    }

    /**
     * 导入书源 JSON（Legado 原生格式，数组或单对象）。只收小说源（`bookSourceType == 0`）；
     * 音频/漫画/文件源按类型过滤并计入 skipped。规则原文原样入库，运行期由引擎直接求值。
     */
    suspend fun importJson(text: String): Result<ImportReport> = runCatching {
        val root = json.parseToJsonElement(text.trim())
        val objs = when (root) {
            is JsonArray -> root.toList()
            else -> listOf(root)
        }
        val rules = ArrayList<BookSourceRule>()
        val skipped = ArrayList<String>()
        objs.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val name = (obj["bookSourceName"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: (obj["bookSourceUrl"] as? JsonPrimitive)?.content ?: "未命名"
            val type = (obj["bookSourceType"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            if (type != 0) {
                skipped += "$name: 非文字书源（type=$type）不支持"
                return@forEach
            }
            val rule = runCatching { BookSourceRule.fromJson(obj.toString()) }.getOrNull()
            if (rule == null || rule.bookSourceUrl.isBlank()) {
                skipped += "$name: 解析失败或缺少 bookSourceUrl"
                return@forEach
            }
            rules += rule
        }
        upsertRules(rules, skipped)
    }

    /**
     * 批量入库：逐条构造 entity（失败只跳过该条），去重后单事务写入。
     * 单事务避免逐条写在真机上被中断只写进第一条的问题。
     */
    private suspend fun upsertRules(
        rules: List<BookSourceRule>,
        skipped: List<String> = emptyList(),
    ): ImportReport {
        // 同 id（= bookSourceUrl）保留最后一个，避免同事务重复主键
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
                val ruleJson = rule.toJson()
                // 规则没变才留着旧的校验结论；规则一变，上一版的"可用"就不能给新规则背书了
                val keepCheck = existing?.takeIf { it.json == ruleJson }
                toWrite += BookSourceEntity(
                    id = rule.id,
                    name = rule.name,
                    // 重复导入保留用户的启用/禁用与排序
                    enabled = existing?.enabled ?: rule.enabled,
                    sortOrder = existing?.sortOrder ?: 0,
                    json = ruleJson,
                    updatedAt = now,
                    groupName = rule.group,
                    checkStatus = keepCheck?.checkStatus ?: CheckStatus.UNCHECKED,
                    checkMessage = keepCheck?.checkMessage.orEmpty(),
                    respondTime = keepCheck?.respondTime ?: -1,
                    checkedAt = keepCheck?.checkedAt ?: 0,
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

    suspend fun delete(id: String) {
        dao.deleteById(id)
        // 换源记忆里指向这个源的行成了孤儿
        hitDao.deleteBySource(id)
    }

    /** 批量删除/启停：书源动辄几百个，逐条走会很慢也很吵 */
    suspend fun deleteAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        dao.deleteByIds(ids.toList())
        hitDao.deleteBySources(ids.toList())
    }

    suspend fun setEnabledAll(ids: Collection<String>, enabled: Boolean) {
        if (ids.isNotEmpty()) dao.setEnabledForIds(ids.toList(), enabled)
    }

    suspend fun getRule(id: String): BookSourceRule? =
        dao.getById(id)?.let { runCatching { json.decodeFromString<BookSourceRule>(it.json) }.getOrNull() }

    suspend fun getEntity(id: String): BookSourceEntity? = dao.getById(id)

    suspend fun getEnabledRules(): List<BookSourceRule> =
        dao.getEnabled().mapNotNull { entity ->
            runCatching { json.decodeFromString<BookSourceRule>(entity.json) }.getOrNull()
        }

    /**
     * 启用的书源，**连校验结果一起带出来**。
     *
     * [getEnabledRules] 只给 rule，把 entity 上的 checkStatus/respondTime/sortOrder 全丢了 ——
     * 自动换源要靠这些决定先试谁（校验通过的、响应快的优先占住并发名额）。
     */
    suspend fun getEnabledForSwitch(): List<EnabledSource> =
        dao.getEnabled().mapNotNull { entity ->
            val rule = runCatching { json.decodeFromString<BookSourceRule>(entity.json) }
                .getOrNull() ?: return@mapNotNull null
            EnabledSource(
                rule = rule,
                checkStatus = entity.checkStatus,
                respondTime = entity.respondTime,
                sortOrder = entity.sortOrder,
            )
        }

    data class EnabledSource(
        val rule: BookSourceRule,
        val checkStatus: Int,
        val respondTime: Long,
        val sortOrder: Int,
    )

    fun parseRule(text: String): Result<BookSourceRule> =
        runCatching { json.decodeFromString(text.trim()) }

    fun encodeRule(rule: BookSourceRule): String =
        json.encodeToString(BookSourceRule.serializer(), rule)

    // ---- 校验结果 / 排序 / 分组 ----

    suspend fun saveCheck(id: String, ok: Boolean, message: String, respondMs: Long) {
        dao.saveCheck(
            id = id,
            status = if (ok) CheckStatus.OK else CheckStatus.FAILED,
            message = message,
            respondTime = if (ok) respondMs else -1,
            checkedAt = System.currentTimeMillis(),
        )
    }

    /** 置顶：排到当前最小序号之前。递增分配（起点 = min - 个数），保持选中项之间的相对顺序 ——
     *  从前用 order-- 递减，会把多选置顶的顺序整体倒过来 */
    suspend fun moveToTop(ids: Collection<String>) {
        var order = (dao.minSortOrder() ?: 0) - ids.size
        ids.forEach { dao.setSortOrder(it, order++) }
    }

    suspend fun moveToBottom(ids: Collection<String>) {
        var order = (dao.maxSortOrder() ?: 0) + 1
        ids.forEach { dao.setSortOrder(it, order++) }
    }

    suspend fun setGroup(ids: Collection<String>, group: String) {
        dao.setGroupForIds(ids.toList(), group.trim())
    }

    /** 导出为 Legado 原生格式（库里存的就是原生 JSON） */
    suspend fun exportJson(ids: Collection<String>): String {
        val all = dao.getAll().filter { it.id in ids }
        val items = all.map { it.json }
        return items.joinToString(",\n", prefix = "[\n", postfix = "\n]")
    }
}
