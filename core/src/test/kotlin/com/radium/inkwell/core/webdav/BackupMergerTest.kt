package com.radium.inkwell.core.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupMergerTest {

    private fun book(
        id: String,
        readChapter: Int = 0,
        readAt: Long = 0,
        title: String = "书$id",
        updatedAt: Long = 0,
    ) = BackupBook(
        id = id, type = 0, title = title,
        readChapterIndex = readChapter, readAt = readAt, updatedAt = updatedAt,
    )

    private fun payload(vararg books: BackupBook, sources: List<BackupSource> = emptyList()) =
        BackupPayload(deviceId = "test", exportedAt = 0, books = books.toList(), sources = sources)

    @Test
    fun `newer progress wins regardless of side`() {
        val local = payload(book("a", readChapter = 10, readAt = 200))
        val remote = payload(book("a", readChapter = 5, readAt = 100))
        val result = BackupMerger.merge(local, remote)
        assertEquals(10, result.books.single().readChapterIndex)
        assertTrue(result.changedBooks.isEmpty()) // 本地已是最新，无需写回

        val result2 = BackupMerger.merge(remote, local)
        assertEquals(10, result2.books.single().readChapterIndex)
        assertEquals(1, result2.changedBooks.size) // 远端更新，需要写回本地
    }

    @Test
    fun `metadata and progress merge independently`() {
        // 本地进度新、远端元数据新 → 各取所长
        val local = payload(book("a", readChapter = 20, readAt = 300, title = "旧名", updatedAt = 100))
        val remote = payload(book("a", readChapter = 3, readAt = 100, title = "新名", updatedAt = 500))
        val merged = BackupMerger.merge(local, remote).books.single()
        assertEquals("新名", merged.title)
        assertEquals(20, merged.readChapterIndex)
        assertEquals(300, merged.readAt)
    }

    @Test
    fun `union of both sides - no tombstones`() {
        val local = payload(book("a"), book("b"))
        val remote = payload(book("b"), book("c"))
        val result = BackupMerger.merge(local, remote)
        assertEquals(setOf("a", "b", "c"), result.books.map { it.id }.toSet())
        assertEquals(listOf("c"), result.changedBooks.map { it.id })
    }

    @Test
    fun `sources merge by updatedAt`() {
        val s1old = BackupSource("s1", "源1", enabled = true, json = "{}", updatedAt = 1)
        val s1new = BackupSource("s1", "源1改", enabled = false, json = "{\"v\":2}", updatedAt = 9)
        val result = BackupMerger.merge(
            payload(sources = listOf(s1old)),
            payload(sources = listOf(s1new)),
        )
        assertEquals("源1改", result.sources.single().name)
        assertEquals(1, result.changedSources.size)
    }

    @Test
    fun `codec roundtrip with gzip`() {
        val p = payload(book("a", readChapter = 7, readAt = 42))
        val decoded = BackupCodec.decode(BackupCodec.encode(p))
        assertEquals(p, decoded)
    }
}
