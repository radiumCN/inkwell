package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.PurifyRule
import com.radium.inkwell.data.db.dao.ReplaceRuleDao
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 净化替换规则。
 *
 * 引擎里的 `globalPurify` 钩子是**同步**的（正文解析是纯计算，不该为了读几条规则去挂起），
 * 所以这里维护一份内存快照：数据库一变就刷新。规则总共几十条，全量重读毫无压力。
 */
class ReplaceRuleRepository(private val dao: ReplaceRuleDao) {

    private val snapshot = AtomicReference<List<ReplaceRuleEntity>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        dao.observeAll()
            .onEach { snapshot.set(it) }
            .launchIn(scope)
    }

    fun observeAll(): Flow<List<ReplaceRuleEntity>> = dao.observeAll()

    /**
     * 引擎的取规则钩子。作用域为空 = 对所有书源生效；
     * 否则逗号分隔，命中书源 id 或书源名的任一片段即应用。
     */
    fun purifyFor(source: BookSourceRule): List<PurifyRule> =
        snapshot.get()
            .filter { it.enabled && it.pattern.isNotEmpty() && matchesScope(it.scope, source) }
            .map { PurifyRule(pattern = it.pattern, replacement = it.replacement, isRegex = it.isRegex) }

    private fun matchesScope(scope: String, source: BookSourceRule): Boolean {
        if (scope.isBlank()) return true
        return scope.split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { source.id.contains(it, true) || source.name.contains(it, true) }
    }

    suspend fun save(rule: ReplaceRuleEntity) {
        dao.upsert(rule.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun create(
        name: String,
        pattern: String,
        replacement: String,
        isRegex: Boolean,
        scope: String,
    ): ReplaceRuleEntity {
        val rule = ReplaceRuleEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            pattern = pattern,
            replacement = replacement,
            isRegex = isRegex,
            scope = scope,
            sortOrder = dao.maxSortOrder() + 1,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(rule)
        return rule
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(ids: List<String>) = dao.deleteByIds(ids)

    suspend fun getById(id: String): ReplaceRuleEntity? = dao.getById(id)

    /** 首次启动塞一批常见的广告净化规则，省得用户对着空列表发呆 */
    suspend fun seedIfEmpty() {
        if (dao.getAll().isNotEmpty()) return
        DEFAULTS.forEachIndexed { i, d ->
            dao.upsert(
                ReplaceRuleEntity(
                    id = UUID.randomUUID().toString(),
                    name = d.first,
                    pattern = d.second,
                    replacement = "",
                    isRegex = true,
                    enabled = false, // 默认不开：净化是有损的，得让用户自己点头
                    sortOrder = i,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private companion object {
        /** 都是各站最常见的植入语。默认关闭，用户自己决定开哪条 */
        val DEFAULTS = listOf(
            "去掉「请记住本站域名」" to "请记住本[站书]域名[^\\n]*",
            "去掉「最新章节请到…」" to "(最新章节|全文阅读|无弹窗)(请|尽在|上)[^\\n]*",
            "去掉「手机阅读」提示" to "(手机(用户)?请?(访问|阅读|观看)|M版首页)[^\\n]*",
            "去掉「本章未完」" to "本章未完[，,]?点击下一页继续阅读[^\\n]*",
            "去掉网址" to "(https?://)?(www\\.)?[a-zA-Z0-9-]+\\.(com|net|org|cc|cn|xyz|top)[^\\s\\n]*",
        )
    }
}
