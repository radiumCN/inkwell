package com.radium.inkwell.data.repo

import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.core.source.legado.LegadoConverter
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

    /**
     * 书源在导入时就被转换成我们的规则格式存库了，升级 App 不会重转 —— 于是转换器的每个修复
     * 都只对「新导入的书源」生效，老用户永远踩着旧坑（追书神器的目录地址少了 /toc/ 前缀，
     * 装了新版依然 404，就是这么来的）。留存 legado 原文 + 转换器版本号即可在启动时重转。
     */
    @Test
    fun `升级后重新转换旧转换器转过的书源`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao)
        repo.importJson(LEGADO_SRC).getOrThrow()

        val imported = dao.store.values.single()
        // 导入时留了 legado 原文与转换器版本
        assertTrue(imported.sourceJson.contains("bookSourceUrl"))
        assertEquals(LegadoConverter.VERSION, imported.converterVersion)
        // 已是最新版 → 不重转
        assertEquals(0, repo.reconvertOutdated())

        // 模拟「旧转换器转出来的书源」：版本号退回 0，规则是旧的（错的）
        dao.store[imported.id] = imported.copy(
            converterVersion = 0,
            json = imported.json.replace("legado:", "css:"), // 假装旧转换器的产物
        )
        assertEquals(1, repo.reconvertOutdated())

        val fixed = dao.store.values.single()
        assertEquals(LegadoConverter.VERSION, fixed.converterVersion)
        assertEquals(imported.json, fixed.json, "规则应被重新转换回正确产物")
        // 用户的启用状态与排序不受影响
        assertEquals(imported.enabled, fixed.enabled)
        assertEquals(imported.sortOrder, fixed.sortOrder)
    }

    /** 老数据没存原文（当时没这列），重转不了，只能让用户重新导入 —— 但不能把书源弄没 */
    @Test
    fun `没有原文的旧书源保持原样`() = runTest {
        val dao = FakeDao()
        val repo = BookSourceRepository(dao)
        repo.importJson(LEGADO_SRC).getOrThrow()
        val imported = dao.store.values.single()
        dao.store[imported.id] = imported.copy(converterVersion = 0, sourceJson = "")

        assertEquals(0, repo.reconvertOutdated())
        assertEquals(1, dao.store.size, "书源不该被弄丢")
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
