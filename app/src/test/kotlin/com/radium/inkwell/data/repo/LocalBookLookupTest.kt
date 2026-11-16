package com.radium.inkwell.data.repo

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.radium.inkwell.data.db.dao.FIND_LOCAL_BOOKS_SQL
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalBookLookupTest {
    @Test fun `SQL trim character set equals Kotlin whitespace semantics`() {
        assertEquals(Char.MIN_VALUE.rangeTo(Char.MAX_VALUE).filter { it.isWhitespace() }.toSet(), BOOK_KEY_WHITESPACE.toSet())
    }

    @Test fun `SQL matches Kotlin Unicode whitespace and filters net deleted preview and removed books`() {
        BundledSQLiteDriver().open(":memory:").use { db ->
            db.execSQL("CREATE TABLE book (id TEXT, title TEXT, author TEXT, type INTEGER, deleted INTEGER, inShelf INTEGER, removedFromShelf INTEGER)")
            val variants = listOf(" ", "\t\n", "　", "\u00a0", "\u2003\u202f", "")
            variants.forEachIndexed { i, space ->
                db.prepare("INSERT INTO book VALUES (?, ?, ?, 0, 0, 1, 0)").use {
                    it.bindText(1, "v$i"); it.bindText(2, space + "书名" + space); it.bindText(3, space + "作者" + space); it.step()
                }
                assertEquals("书名" to "作者", bookKey(space + "书名" + space, space + "作者" + space))
            }
            db.execSQL("INSERT INTO book VALUES ('net', '书名', '作者', 3, 0, 1, 0), ('deleted', '书名', '作者', 0, 1, 1, 0), ('preview', '书名', '作者', 0, 0, 0, 0), ('removed', '书名', '作者', 0, 0, 0, 1), ('other', '另一书', '作者', 0, 0, 1, 0)")
            fun lookup(includeRemoved: Boolean): Set<String> = db.prepare(FIND_LOCAL_BOOKS_SQL).use {
                it.bindLong(1, if (includeRemoved) 1 else 0)
                it.bindText(2, BOOK_KEY_WHITESPACE); it.bindText(3, "书名"); it.bindText(4, "作者")
                buildSet { while (it.step()) add(it.getText(0)) }
            }
            val onShelf = variants.indices.mapTo(HashSet()) { "v$it" }
            assertEquals(onShelf, lookup(false))
            assertEquals(onShelf + "removed", lookup(true))
        }
    }
}
