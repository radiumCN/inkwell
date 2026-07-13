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
        override suspend fun getAll() = store.values.toList()
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
        // 用户禁用站A
        dao.store["a.com"] = dao.store["a.com"]!!.copy(enabled = false)

        val report = repo.importJson(legadoArray).getOrThrow()
        assertEquals(0, report.added)
        assertEquals(3, report.updated)
        assertTrue(report.summary.contains("更新"))
        // 禁用状态保留
        assertEquals(false, dao.store["a.com"]!!.enabled)
    }
}
