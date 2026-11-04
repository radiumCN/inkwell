package com.radium.inkwell.data.repo

import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.data.db.dao.BookSourceHitDao
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.BookSourceHitEntity
import com.radium.inkwell.data.db.entity.BookSourceListItem
import com.radium.inkwell.data.db.entity.CheckStatus
import com.radium.inkwell.data.db.entity.EnabledSourceMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BookSourceImportTest {

    /** 内存版 DAO：记录 upsertAll 被调用的次数，验证单事务批量写 */
    private class FakeDao : BookSourceDao {
        val store = linkedMapOf<String, BookSourceEntity>()
        var upsertAllCalls = 0
        var singleUpsertCalls = 0
        var getByIdCalls = 0

        private fun BookSourceEntity.toListItem() = BookSourceListItem(
            id = id, name = name, enabled = enabled, sortOrder = sortOrder,
            updatedAt = updatedAt, groupName = groupName, checkStatus = checkStatus,
            checkMessage = checkMessage, respondTime = respondTime, checkedAt = checkedAt,
            deleted = deleted,
        )

        override fun observeAll(): Flow<List<BookSourceListItem>> =
            flowOf(store.values.filter { !it.deleted }.map { it.toListItem() })
        override suspend fun getEnabledMeta() =
            store.values.filter { it.enabled && !it.deleted }.map {
                EnabledSourceMeta(
                    id = it.id, updatedAt = it.updatedAt, checkStatus = it.checkStatus,
                    respondTime = it.respondTime, sortOrder = it.sortOrder,
                    searchMs = it.searchMs, searchFails = it.searchFails,
                )
            }
        override suspend fun getById(id: String) = store[id].also { getByIdCalls++ }
        override suspend fun upsert(source: BookSourceEntity) {
            singleUpsertCalls++; store[source.id] = source
        }
        override suspend fun upsertAll(sources: List<BookSourceEntity>) {
            upsertAllCalls++; sources.forEach { store[it.id] = it }
        }
        override suspend fun getAllIds() = store.values.filter { !it.deleted }.map { it.id }
        override suspend fun getAllIdsIncludingDeleted() = store.keys.toList()
        override suspend fun setEnabled(id: String, enabled: Boolean, now: Long) {}
        // 软删除：打标记而不是移除 —— 墓碑得留着，否则 WebDAV 同步会把删掉的源拉回来
        override suspend fun softDeleteById(id: String, now: Long) {
            store[id]?.let { store[id] = it.copy(deleted = true, updatedAt = now) }
        }
        override suspend fun softDeleteByIds(ids: List<String>, now: Long) {
            ids.forEach { id -> store[id]?.let { store[id] = it.copy(deleted = true, updatedAt = now) } }
        }
        override suspend fun setEnabledForIds(ids: List<String>, enabled: Boolean, now: Long) {
            ids.forEach { id -> store[id]?.let { store[id] = it.copy(enabled = enabled, updatedAt = now) } }
        }

        override suspend fun saveCheck(
            id: String, status: Int, message: String, respondTime: Long, checkedAt: Long,
        ) {
            store[id]?.let {
                store[id] = it.copy(
                    checkStatus = status, checkMessage = message,
                    respondTime = respondTime, checkedAt = checkedAt,
                )
            }
        }

        override suspend fun saveSearchStats(id: String, searchMs: Long, searchFails: Int) {
            store[id]?.let { store[id] = it.copy(searchMs = searchMs, searchFails = searchFails) }
        }

        override suspend fun setSortOrder(id: String, sortOrder: Int, now: Long) {
            store[id]?.let { store[id] = it.copy(sortOrder = sortOrder, updatedAt = now) }
        }

        override suspend fun setGroupForIds(ids: List<String>, group: String, now: Long) {
            ids.forEach { id -> store[id]?.let { store[id] = it.copy(groupName = group, updatedAt = now) } }
        }

        override suspend fun minSortOrder() = store.values.minOfOrNull { it.sortOrder }
        override suspend fun maxSortOrder() = store.values.maxOfOrNull { it.sortOrder }
        override suspend fun lastChangeAt() = store.values.maxOfOrNull { it.updatedAt } ?: 0L
    }

    private val legadoArray = """
    [
      ${legadoSource("站A", "https://a.com")},
      ${legadoSource("站B", "https://b.com")},
      ${legadoSource("站C重复", "https://b.com")},
      ${legadoSource("站D", "https://d.com")}
    ]
    """.trimIndent()

    private fun legadoSource(name: String, url: String) = """
    {
      "bookSourceUrl": "$url",
      "bookSourceName": "$name",
      "searchUrl": "/s?q={{key}}",
      "ruleSearch": { "bookList": "class.item", "name": "tag.h3@text", "bookUrl": "tag.a@href" },
      "ruleToc": { "chapterList": "tag.dd@tag.a", "chapterName": "text", "chapterUrl": "href" },
      "ruleContent": { "content": "id.content@html" }
    }
    """.trimIndent()

    /** 换源记忆的内存版：这里只关心删源时有没有把指向它的行一起清掉 */
    private class FakeHitDao : BookSourceHitDao {
        val store = mutableListOf<BookSourceHitEntity>()
        override suspend fun getByBook(bookId: String) = store.filter { it.bookId == bookId }
        override suspend fun upsert(hit: BookSourceHitEntity) {
            store.removeAll { it.bookId == hit.bookId && it.sourceId == hit.sourceId }
            store += hit
        }
        override suspend fun deleteByBook(bookId: String) { store.removeAll { it.bookId == bookId } }
        override suspend fun deleteBySource(sourceId: String) { store.removeAll { it.sourceId == sourceId } }
        override suspend fun deleteBySources(sourceIds: List<String>) {
            store.removeAll { it.sourceId in sourceIds }
        }
    }

    @Test
    fun `batch import writes all sources in a single transaction`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao, FakeHitDao())
        val report = repo.importJson(legadoArray).getOrThrow()

        // 4 个源，站B/站C 撞同一域名 id → 去重后 3 个
        assertEquals(3, dao.store.size)
        assertEquals(3, report.added)
        assertEquals(0, report.failed.size)
        // 关键：批量写只调一次 upsertAll，而非逐条 upsert
        assertEquals(1, dao.upsertAllCalls)
        assertEquals(0, dao.singleUpsertCalls)
    }

    @Test
    fun `re-import reports updates and keeps user enabled state`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao, FakeHitDao())
        repo.importJson(legadoArray).getOrThrow()
        // 用户禁用站A（id = bookSourceUrl）
        dao.store["https://a.com"] = dao.store["https://a.com"]!!.copy(enabled = false)

        val report = repo.importJson(legadoArray).getOrThrow()
        assertEquals(0, report.added)
        assertEquals(3, report.updated)
        assertTrue(report.summary.contains("更新"))
        // 禁用状态保留
        assertEquals(false, dao.store["https://a.com"]!!.enabled)
    }

    private fun groupedSource(group: String, search: String = "/s?q={{key}}") = """
    {
      "bookSourceUrl": "https://g.com",
      "bookSourceName": "分组站",
      "bookSourceGroup": "$group",
      "searchUrl": "$search",
      "ruleSearch": { "bookList": "class.item", "name": "tag.h3@text", "bookUrl": "tag.a@href" }
    }
    """.trimIndent()

    /**
     * 同一份规则原样再导一遍不是「修改」—— 盖了新时间戳，这一行就会带着本机的旧启停/分组
     * 在 WebDAV 合并（按 updatedAt 做 LWW）里赢过别的设备刚做的修改。
     */
    @Test
    fun `原样重导不刷新 updatedAt，规则变了才刷新`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao, FakeHitDao())
        repo.importJson(groupedSource("玄幻")).getOrThrow()
        dao.store["https://g.com"] = dao.store["https://g.com"]!!.copy(updatedAt = 1_000)

        repo.importJson(groupedSource("玄幻")).getOrThrow()
        assertEquals(1_000, dao.store["https://g.com"]!!.updatedAt)

        repo.importJson(groupedSource("玄幻", search = "/search?q={{key}}")).getOrThrow()
        assertTrue(dao.store["https://g.com"]!!.updatedAt > 1_000)
    }

    /** 搜索测速跟校验结论同一套规矩：规则没变就留着，规则一变旧规则的快慢就不作数了 */
    @Test
    fun `原样重导保留搜索测速，规则变了清零`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao, FakeHitDao())
        repo.importJson(groupedSource("玄幻")).getOrThrow()
        dao.saveSearchStats("https://g.com", searchMs = 1_200, searchFails = 1)

        repo.importJson(groupedSource("玄幻")).getOrThrow()
        assertEquals(1_200, dao.store["https://g.com"]!!.searchMs)
        assertEquals(1, dao.store["https://g.com"]!!.searchFails)

        repo.importJson(groupedSource("玄幻", search = "/search?q={{key}}")).getOrThrow()
        assertEquals(-1, dao.store["https://g.com"]!!.searchMs)
        assertEquals(0, dao.store["https://g.com"]!!.searchFails)
    }

    @Test
    fun `墓碑被重新导入算加回来，要盖新时间戳`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao, FakeHitDao())
        repo.importJson(groupedSource("玄幻")).getOrThrow()
        repo.delete("https://g.com")
        dao.store["https://g.com"] = dao.store["https://g.com"]!!.copy(updatedAt = 1_000)

        repo.importJson(groupedSource("玄幻")).getOrThrow()
        val row = dao.store["https://g.com"]!!
        assertEquals(false, row.deleted)
        assertTrue(row.updatedAt > 1_000)
    }

    @Test
    fun `用户改过的分组在重导时保留，没改过的跟着新规则走`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao, FakeHitDao())
        repo.importJson(groupedSource("玄幻")).getOrThrow()

        // 没改过：作者在新版里把分组改成「都市」，用户拿到新分组
        repo.importJson(groupedSource("都市")).getOrThrow()
        assertEquals("都市", dao.store["https://g.com"]!!.groupName)

        // 用户自己整理成「常用」：再导哪一版都不该被冲掉
        repo.setGroup(listOf("https://g.com"), "常用")
        repo.importJson(groupedSource("言情")).getOrThrow()
        assertEquals("常用", dao.store["https://g.com"]!!.groupName)
    }

    /** 非小说书源（音频/漫画/文件/视频）按 bookSourceType 过滤，计入 skipped，不入库 */
    @Test
    fun `非小说书源被过滤掉`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao, FakeHitDao())
        val audio = """
            {"bookSourceUrl":"https://audio.com","bookSourceName":"听书站","bookSourceType":1,
             "searchUrl":"/s","ruleSearch":{"bookList":"class.i","name":"tag.h3@text","bookUrl":"tag.a@href"}}
        """.trimIndent()
        val manga = """
            {"bookSourceUrl":"https://manga.com","bookSourceName":"漫画站","bookSourceType":2,
             "searchUrl":"/s","ruleSearch":{"bookList":"class.i","name":"tag.h3@text","bookUrl":"tag.a@href"}}
        """.trimIndent()
        val file = """
            {"bookSourceUrl":"https://file.com","bookSourceName":"网盘站","bookSourceType":3,
             "searchUrl":"/s","ruleSearch":{"bookList":"class.i","name":"tag.h3@text","bookUrl":"tag.a@href"}}
        """.trimIndent()
        val video = """
            {"bookSourceUrl":"https://video.com","bookSourceName":"影视站","bookSourceType":"4",
             "searchUrl":"/s","ruleSearch":{"bookList":"class.i","name":"tag.h3@text","bookUrl":"tag.a@href"}}
        """.trimIndent()
        val report = repo.importJson(
            "[" + LEGADO_SRC + "," + audio + "," + manga + "," + file + "," + video + "]"
        ).getOrThrow()
        assertEquals(1, report.added)
        assertEquals(4, report.skipped.size)
        assertTrue(report.skipped.any { it.contains("音频源不支持") })
        assertTrue(report.skipped.any { it.contains("漫画源不支持") })
        assertTrue(report.skipped.any { it.contains("文件源不支持") })
        assertTrue(report.skipped.any { it.contains("视频源不支持") })
        assertEquals(1, dao.store.size)
    }

    private companion object {
        const val LEGADO_SRC2 = """
        {
          "bookSourceUrl": "https://re2.example.com",
          "bookSourceName": "重转站2",
          "searchUrl": "/s?q={{key}}",
          "ruleSearch": { "bookList": "class.list@tag.li", "name": "tag.h3@text", "bookUrl": "tag.a@href" },
          "ruleToc": { "chapterList": "id.c@tag.a", "chapterName": "text", "chapterUrl": "href" },
          "ruleContent": { "content": "id.txt@html" }
        }
        """

        const val LEGADO_SRC = """
        {
          "bookSourceUrl": "https://re.example.com",
          "bookSourceName": "重转站",
          "searchUrl": "/s?q={{key}}",
          "ruleSearch": { "bookList": "class.list@tag.li", "name": "tag.h3@text", "bookUrl": "tag.a@href" },
          "ruleToc": { "chapterList": "id.c@tag.a", "chapterName": "text", "chapterUrl": "href" },
          "ruleContent": { "content": "id.txt@html" }
        }
        """
    }

    /**
     * 书源动辄几百个，逐条删会很慢；批量删除/启停走单条 SQL。
     *
     * 删除是**软删除**：行留下、打 deleted 标记。从前是真删，于是「删过」这件事在
     * WebDAV 同步里无从表达 —— 本地行没了、远端还在，合并就把它当成「别的设备新加的」
     * 补回来，用户删一次它回来一次。行留着才有地方记这件事。
     */
    @Test
    fun `批量删除打墓碑而不是真删行`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao, FakeHitDao())
        repo.importJson("[" + LEGADO_SRC + "," + LEGADO_SRC2 + "]").getOrThrow()
        assertEquals(2, dao.store.size)

        val ids = dao.store.keys.toList()
        repo.setEnabledAll(ids, false)
        assertTrue(dao.store.values.none { it.enabled })

        repo.deleteAll(listOf(ids[0]))

        // 行还在（墓碑），但被标记为已删除
        assertEquals(2, dao.store.size, "软删除不该真的移除行")
        assertTrue(dao.store[ids[0]]!!.deleted, "删掉的那个要有墓碑")
        assertTrue(!dao.store[ids[1]]!!.deleted, "没删的不该被误伤")
        // updatedAt 必须一起更新，否则远端那份旧副本会比墓碑还「新」，删除又被覆盖回来
        assertTrue(dao.store[ids[0]]!!.updatedAt > 0, "打墓碑要同时更新 updatedAt")
    }

    /**
     * 换源记忆按 sourceId 存，源没了那些行就是孤儿：读不到、删不掉、只会越攒越多。
     * 删源和清记忆必须成对。
     */
    @Test
    fun `删源时一并清掉指向它的换源记忆`() = runTest {
        val hits = FakeHitDao()
        val repo = BookSourceRepository(FakeDao(), hits)
        hits.upsert(BookSourceHitEntity("book1", "srcA", hit = true, checkedAt = 0))
        hits.upsert(BookSourceHitEntity("book1", "srcB", hit = false, checkedAt = 0))

        repo.delete("srcA")

        assertEquals(listOf("srcB"), hits.store.map { it.sourceId })
    }

    /** 批量删走的是另一条 SQL，同样得清 */
    @Test
    fun `批量删源时一并清掉换源记忆`() = runTest {
        val hits = FakeHitDao()
        val repo = BookSourceRepository(FakeDao(), hits)
        hits.upsert(BookSourceHitEntity("book1", "srcA", hit = true, checkedAt = 0))
        hits.upsert(BookSourceHitEntity("book2", "srcB", hit = true, checkedAt = 0))
        hits.upsert(BookSourceHitEntity("book1", "srcC", hit = true, checkedAt = 0))

        repo.deleteAll(listOf("srcA", "srcB"))

        assertEquals(listOf("srcC"), hits.store.map { it.sourceId })
    }

    /**
     * 搜索、发现页、换源每次都要全部启用源的规则：updatedAt 没变就用解好的那份，不再逐条读库重解。
     * 规则变了（updatedAt 跟着变）只重解那一条。
     */
    @Test
    fun `启用源的规则按 updatedAt 缓存，变了的那条才重新解码`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao, FakeHitDao())
        repo.importJson(legadoArray).getOrThrow()

        val first = repo.getEnabledRules().associateBy { it.bookSourceUrl }
        val reads = dao.getByIdCalls
        val second = repo.getEnabledRules().associateBy { it.bookSourceUrl }
        // 没有任何一行变过：不读库，拿到的就是同一批实例
        assertEquals(reads, dao.getByIdCalls)
        first.forEach { (id, rule) -> assertSame(rule, second[id]) }

        val a = dao.store["https://a.com"]!!
        val changed = legadoSource("站A改名", "https://a.com")
        dao.store[a.id] = a.copy(
            json = repo.parseRule(changed).getOrThrow().let(repo::encodeRule),
            updatedAt = a.updatedAt + 1,
        )
        val third = repo.getEnabledRules().associateBy { it.bookSourceUrl }
        assertEquals("站A改名", third["https://a.com"]!!.bookSourceName)
        assertNotSame(first["https://a.com"], third["https://a.com"])
        // 只有变了的那条读了库
        assertEquals(reads + 1, dao.getByIdCalls)
        assertSame(first["https://d.com"], third["https://d.com"])
    }

    /** 校验结果、测速改了不动 updatedAt：它们不能跟着规则一起被缓存住，自动换源靠它们挑源 */
    @Test
    fun `换源取的校验结果每次现查，不被规则缓存挡住`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao, FakeHitDao())
        repo.importJson(legadoArray).getOrThrow()
        repo.getEnabledForSwitch()

        repo.saveCheck("https://a.com", ok = true, message = "", respondMs = 321)
        val a = repo.getEnabledForSwitch().single { it.rule.bookSourceUrl == "https://a.com" }
        assertEquals(CheckStatus.OK, a.checkStatus)
        assertEquals(321L, a.respondTime)
    }

    // ---- 新建书源 ----

    private fun created(url: String) = com.radium.inkwell.core.source.BookSourceRule(
        bookSourceUrl = url,
        bookSourceName = "自建",
        bookSourceGroup = BookSourceRepository.CREATED_GROUP,
        ruleToc = com.radium.inkwell.core.source.TocRuleSet(chapterList = "@css:#list dd a", chapterName = "text", chapterUrl = "href"),
        ruleContent = com.radium.inkwell.core.source.ContentRuleSet(content = "@css:#content@html"),
    )

    /** 同站已有导入的书源：新建的那个加后缀另存，导入的那个（连同它的搜索规则）原样不动 */
    @Test
    fun `新建书源撞上同站书源时加后缀，不覆盖`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao, FakeHitDao())
        repo.importJson(legadoArray).getOrThrow()

        val first = repo.addCreated(created("https://a.com"))
        assertEquals("https://a.com#自建", first.id)
        val second = repo.addCreated(created("https://a.com"))
        assertEquals("https://a.com#自建2", second.id)

        assertTrue(repo.getRule("https://a.com")!!.search != null, "导入的书源被覆盖了")
        assertEquals("自建", dao.store.getValue("https://a.com#自建").groupName)
        // 后缀不进请求地址
        assertEquals("https://a.com", first.baseUrl)
    }

    /** 删掉的书源只剩墓碑，不算占用：直接用站点地址，删除在同步里也不会赢回去（新时间戳） */
    @Test
    fun `新建书源可以复用已删除书源的地址`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao, FakeHitDao())
        repo.importJson(legadoArray).getOrThrow()
        repo.delete("https://a.com")

        val saved = repo.addCreated(created("https://a.com"))
        assertEquals("https://a.com", saved.id)
        assertTrue(!dao.store.getValue("https://a.com").deleted)
    }

    /** 按网址找已有书源只认同一主机：m. 与 www. 往往是两套页面，规则套不上 */
    @Test
    fun `按主机找已有书源`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao, FakeHitDao())
        repo.importJson(legadoArray).getOrThrow()
        repo.addCreated(created("https://a.com"))

        val found = repo.findByHost("https://a.com/book/1/").map { it.id }
        assertEquals(listOf("https://a.com", "https://a.com#自建"), found)
        assertTrue(repo.findByHost("https://m.a.com/book/1/").isEmpty())
        assertTrue(repo.findByHost("不是网址").isEmpty())
    }
}
