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
     * 引擎的取规则钩子（抓取时应用，结果进缓存）。只给**通用规则** ——
     * 书内规则挂在具体某本书上，而引擎不知道自己在给哪本书抓正文。
     *
     * 作用域为空 = 对所有书源生效；否则逗号分隔，命中书源 id 或书源名的任一片段即应用。
     */
    fun purifyFor(source: BookSourceRule): List<PurifyRule> =
        snapshot.get()
            .filter {
                it.enabled && it.pattern.isNotEmpty() && it.bookId.isEmpty() &&
                    matchesScope(it.scope, source)
            }
            .map { it.toPurify() }

    /**
     * 某本书专属的净化规则（**渲染时**应用）。
     *
     * 之所以不走引擎那条路：引擎的净化发生在抓取时、结果直接进缓存。用户在阅读页
     * 长按选中一句话建规则，指望的是眼前这一页立刻干净 —— 而这一页早就缓存好了，
     * 抓取时的净化再也不会跑到它头上。渲染时应用则对已缓存的章节同样立刻生效。
     */
    fun purifyForBook(bookId: String): List<PurifyRule> =
        snapshot.get()
            .filter { it.enabled && it.pattern.isNotEmpty() && it.bookId == bookId }
            .map { it.toPurify() }

    /** 某本书的规则变了就重新分页；只关心影响这本书的那些 */
    fun observeForBook(bookId: String): Flow<List<ReplaceRuleEntity>> = dao.observeForBook(bookId)

    private fun ReplaceRuleEntity.toPurify() =
        PurifyRule(pattern = pattern, replacement = replacement, isRegex = isRegex)

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
        bookId: String = "",
    ): ReplaceRuleEntity {
        val rule = ReplaceRuleEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            pattern = pattern,
            replacement = replacement,
            isRegex = isRegex,
            scope = scope,
            bookId = bookId,
            sortOrder = dao.maxSortOrder() + 1,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(rule)
        return rule
    }

    /**
     * 阅读页长按选中一段文字 → 一条只对这本书生效的净化规则。
     *
     * 一律用**纯文本**而不是正则：用户选的是眼前的一句话，里面的 `.` `(` `*` 都是字面量，
     * 当正则解释八成会匹配错、甚至编译不过。要正则的人可以回规则页把开关拨过去。
     */
    suspend fun createFromSelection(bookId: String, selected: String, replacement: String = ""): ReplaceRuleEntity =
        create(
            name = selected.take(16).replace('\n', ' '),
            pattern = selected,
            replacement = replacement,
            isRegex = false,
            scope = "",
            bookId = bookId,
        )

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(ids: List<String>) = dao.softDeleteByIds(ids, System.currentTimeMillis())

    suspend fun getById(id: String): ReplaceRuleEntity? = dao.getById(id)

    /**
     * 首次启动塞一批常见的广告净化规则，省得用户对着空列表发呆。
     *
     * id 必须是**确定性**的，不能用随机 UUID：两台设备各自预置一套，一同步就变成
     * 两倍的重复规则（WebDAV 合并是按 id 走的，认不出它们是同一条）。
     */
    suspend fun seedIfEmpty() {
        if (dao.getAll().isNotEmpty()) return
        DEFAULTS.forEachIndexed { i, d ->
            dao.upsert(
                ReplaceRuleEntity(
                    id = "seed:${d.first}",
                    name = d.first,
                    pattern = d.second,
                    replacement = "",
                    isRegex = true,
                    enabled = false, // 默认不开：净化是有损的，得让用户自己点头
                    sortOrder = i,
                    // 预置规则的时间戳落在纪元原点：任何一台设备的真实改动都比它新，
                    // 同步时不会拿一份没人动过的默认值去覆盖对面的修改。
                    updatedAt = 0,
                )
            )
        }
    }

    // ---- WebDAV 备份 ----

    suspend fun getAll(): List<ReplaceRuleEntity> = dao.getAll()

    suspend fun upsertAll(rules: List<ReplaceRuleEntity>) {
        rules.forEach { dao.upsert(it) }
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
