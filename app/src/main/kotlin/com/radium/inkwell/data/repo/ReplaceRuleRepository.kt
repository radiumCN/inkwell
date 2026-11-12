package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.ImportedPurifyRule
import com.radium.inkwell.core.source.PurifyRule
import com.radium.inkwell.core.source.PurifyScope
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

/** 导入面板上一条规则的状态（见 [ReplaceRuleRepository.classifyImport]） */
enum class RuleImportStatus { NEW, UPDATE, SAME, UNSUPPORTED }

data class RuleImportItem(val rule: ImportedPurifyRule, val status: RuleImportStatus) {
    /** 「已有」导了也没变化、「不支持」导不了：这两种勾不上 */
    val selectable: Boolean get() = status == RuleImportStatus.NEW || status == RuleImportStatus.UPDATE
}

data class RuleImportResult(val added: Int, val updated: Int)

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
     * 作用范围见 [PurifyScope]：空 = 所有来源；否则命中来源地址 / 名字的任一片段，或者就是这本书的书名。
     */
    fun purifyFor(source: BookSourceRule, bookTitle: String): List<PurifyRule> =
        snapshot.get()
            .filter {
                it.enabled && it.pattern.isNotEmpty() && it.bookId.isEmpty() &&
                    PurifyScope.matches(it.scope, source.id, source.name, bookTitle)
            }
            .map { it.toPurify() }

    /**
     * 本地书（txt / EPUB / MOBI）该跑的通用规则（**渲染时**应用，同 [purifyForBook]）。
     *
     * 本地书不经过引擎，从前通用规则对它一条都不跑 —— 从阅读导进来一批去广告的规则，本地书毫无反应。
     * 本地书没有来源：作用范围空着的、或者写的就是这本书书名的才算（写来源的那些管的是网上抓的正文）。
     */
    fun purifyForLocalBook(bookTitle: String): List<PurifyRule> =
        snapshot.get()
            .filter {
                it.enabled && it.pattern.isNotEmpty() && it.bookId.isEmpty() &&
                    PurifyScope.matches(it.scope, sourceId = null, sourceName = null, bookTitle = bookTitle)
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

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled, System.currentTimeMillis())

    /** 多选批量启停（净化替换页的底部操作条） */
    suspend fun setEnabled(ids: Collection<String>, enabled: Boolean) =
        dao.setEnabledByIds(ids.toList(), enabled, System.currentTimeMillis())

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

    /** 最近一次改动的时间（含墓碑），见 [ReplaceRuleDao.lastChangeAt] */
    suspend fun lastChangeAt(): Long = dao.lastChangeAt()

    suspend fun upsertAll(rules: List<ReplaceRuleEntity>) {
        rules.forEach { dao.upsert(it) }
    }

    // ---- 从文件 / 链接 / 剪贴板导入（阅读的净化规则） ----

    /** 读出来的规则和库里比一比，供导入面板逐条标「新增 / 更新 / 已有 / 不支持」 */
    suspend fun previewImport(rules: List<ImportedPurifyRule>): List<RuleImportItem> =
        classifyImport(rules, dao.getAll())

    /**
     * 把勾上的规则写进库，一条语句批量写（中途被杀不留半套）。
     *
     * 写之前重读一遍库：预览面板开着的这段时间里，同步可能拉下来了新规则、别处可能刚改过，
     * 拿开面板那一刻的快照去覆盖会把那些改动冲掉。
     * - 库里没有这个 id（或者是删掉的墓碑）→ 新增，开关照文件里的，排在最后；
     * - 有、内容不同 → 更新内容，**开关和排序留用户自己的**：关掉的那条，重导一次订阅不该又被打开；
     * - 有、内容一样 → 不写（不白盖一个 updatedAt，免得同步时拿它去覆盖别的设备）。
     */
    suspend fun importRules(rules: List<ImportedPurifyRule>): RuleImportResult {
        val byId = dao.getAll().associateBy { it.id }
        val now = System.currentTimeMillis()
        var order = dao.maxSortOrder() + 1
        var added = 0
        var updated = 0
        val rows = rules.filter { it.unsupported == null }.mapNotNull { r ->
            val existing = byId[r.id]?.takeUnless { it.deleted }
            when {
                existing == null -> {
                    added++
                    ReplaceRuleEntity(
                        id = r.id,
                        name = r.name,
                        pattern = r.pattern,
                        replacement = r.replacement,
                        isRegex = r.isRegex,
                        scope = r.scope,
                        enabled = r.enabled,
                        sortOrder = order++,
                        updatedAt = now,
                    )
                }
                existing.sameAs(r, compareName = true) -> null
                else -> {
                    updated++
                    existing.copy(
                        name = r.name,
                        pattern = r.pattern,
                        replacement = r.replacement,
                        isRegex = r.isRegex,
                        scope = r.scope,
                        updatedAt = now,
                    )
                }
            }
        }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
        return RuleImportResult(added = added, updated = updated)
    }

    companion object {
        /**
         * 逐条定状态：
         * - 同一个 id 的规则库里有：内容一样是「已有」，不一样是「更新」；
         * - 没有这个 id，但有一条通用规则和它**内容**一样（名字不算）—— 手建过同一条、或者预置规则就是它：「已有」，
         *   不然导进来就是两条一模一样的规则，各替换一遍；
         * - 其余「新增」。删掉的规则（墓碑）不算有：重新导进来就是新增。
         */
        internal fun classifyImport(rules: List<ImportedPurifyRule>, existing: List<ReplaceRuleEntity>): List<RuleImportItem> {
            val live = existing.filterNot { it.deleted }
            val byId = live.associateBy { it.id }
            val globals = live.filter { it.bookId.isEmpty() }
            return rules.map { r ->
                val same = byId[r.id]
                val status = when {
                    r.unsupported != null -> RuleImportStatus.UNSUPPORTED
                    same != null ->
                        if (same.sameAs(r, compareName = true)) RuleImportStatus.SAME else RuleImportStatus.UPDATE
                    globals.any { it.sameAs(r, compareName = false) } -> RuleImportStatus.SAME
                    else -> RuleImportStatus.NEW
                }
                RuleImportItem(r, status)
            }
        }

        private fun ReplaceRuleEntity.sameAs(r: ImportedPurifyRule, compareName: Boolean): Boolean =
            pattern == r.pattern && replacement == r.replacement && isRegex == r.isRegex &&
                scope.trim() == r.scope && (!compareName || name == r.name)

        /** 都是各站最常见的植入语。默认关闭，用户自己决定开哪条 */
        private val DEFAULTS = listOf(
            "去掉「请记住本站域名」" to "请记住本[站书]域名[^\\n]*",
            "去掉「最新章节请到…」" to "(最新章节|全文阅读|无弹窗)(请|尽在|上)[^\\n]*",
            "去掉「手机阅读」提示" to "(手机(用户)?请?(访问|阅读|观看)|M版首页)[^\\n]*",
            "去掉「本章未完」" to "本章未完[，,]?点击下一页继续阅读[^\\n]*",
            "去掉网址" to "(https?://)?(www\\.)?[a-zA-Z0-9-]+\\.(com|net|org|cc|cn|xyz|top)[^\\s\\n]*",
        )
    }
}
