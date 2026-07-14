package com.radium.inkwell.data.repo

import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.data.db.entity.BookSourceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookSourceImportTest {

    /** 内存版 DAO：记录 upsertAll 被调用的次数，验证单事务批量写 */
    private class FakeDao : BookSourceDao {
        val store = linkedMapOf<String, BookSourceEntity>()
        var upsertAllCalls = 0
        var singleUpsertCalls = 0

        override fun observeAll(): Flow<List<BookSourceEntity>> = flowOf(store.values.toList())
        override suspend fun getEnabled() = store.values.filter { it.enabled }
        override suspend fun getById(id: String) = store[id]
        override suspend fun upsert(source: BookSourceEntity) {
            singleUpsertCalls++; store[source.id] = source
        }
        override suspend fun upsertAll(sources: List<BookSourceEntity>) {
            upsertAllCalls++; sources.forEach { store[it.id] = it }
        }
        override suspend fun getAllIds() = store.keys.toList()
        override suspend fun setEnabled(id: String, enabled: Boolean) {}
        override suspend fun deleteById(id: String) { store.remove(id) }
        override suspend fun deleteByIds(ids: List<String>) { ids.forEach { store.remove(it) } }
        override suspend fun setEnabledForIds(ids: List<String>, enabled: Boolean) {
            ids.forEach { id -> store[id]?.let { store[id] = it.copy(enabled = enabled) } }
        }
        override suspend fun getAll() = store.values.toList()

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

        override suspend fun setSortOrder(id: String, sortOrder: Int) {
            store[id]?.let { store[id] = it.copy(sortOrder = sortOrder) }
        }

        override suspend fun setGroupForIds(ids: List<String>, group: String) {
            ids.forEach { id -> store[id]?.let { store[id] = it.copy(groupName = group) } }
        }

        override suspend fun minSortOrder() = store.values.minOfOrNull { it.sortOrder }
        override suspend fun maxSortOrder() = store.values.maxOfOrNull { it.sortOrder }
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

    @Test
    fun `batch import writes all sources in a single transaction`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao)
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
        val repo = BookSourceRepository(dao)
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

    /** 非文字书源（音频/漫画/文件）按 bookSourceType 过滤，计入 skipped，不入库 */
    @Test
    fun `非文字书源被过滤掉`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao)
        val audio = """
            {"bookSourceUrl":"https://audio.com","bookSourceName":"听书站","bookSourceType":1,
             "searchUrl":"/s","ruleSearch":{"bookList":"class.i","name":"tag.h3@text","bookUrl":"tag.a@href"}}
        """.trimIndent()
        val report = repo.importJson("[" + LEGADO_SRC + "," + audio + "]").getOrThrow()
        assertEquals(1, report.added)
        assertEquals(1, report.skipped.size)
        assertTrue(report.skipped.single().contains("非文字书源"))
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

    /** 书源动辄几百个，逐条删会很慢；批量删除/启停走单条 SQL */
    @Test
    fun `批量删除与批量启停`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao)
        repo.importJson("[" + LEGADO_SRC + "," + LEGADO_SRC2 + "]").getOrThrow()
        assertEquals(2, dao.store.size)

        val ids = dao.store.keys.toList()
        repo.setEnabledAll(ids, false)
        assertTrue(dao.store.values.none { it.enabled })

        repo.deleteAll(listOf(ids[0]))
        assertEquals(1, dao.store.size)
        assertEquals(ids[1], dao.store.keys.single())
    }

}
