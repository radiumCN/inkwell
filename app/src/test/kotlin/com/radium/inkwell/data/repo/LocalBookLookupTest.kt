package com.radium.inkwell.data.repo

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.radium.inkwell.data.db.dao.FIND_LOCAL_BOOKS_SQL
import com.radium.inkwell.data.db.dao.FIND_SHELF_BOOK_ID_SQL
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
            db.execSQL("""
                INSERT INTO book VALUES
                    ('punctuation', '《书名》', '作者', 0, 0, 1, 0),
                    ('other-author', '书名', '另一作者', 0, 0, 1, 0),
                    ('lowercase', 'fog harbor', '', 0, 0, 1, 0),
                    ('uppercase', 'Fog Harbor', '', 0, 0, 1, 0)
            """.trimIndent())
            fun lookup(includeRemoved: Boolean, title: String = "书名", author: String = "作者"): Set<String> = db.prepare(FIND_LOCAL_BOOKS_SQL).use {
                it.bindLong(1, if (includeRemoved) 1 else 0)
                it.bindText(2, BOOK_KEY_WHITESPACE)
                it.bindText(3, title)
                it.bindText(4, author)
                buildSet { while (it.step()) add(it.getText(0)) }
            }
            val onShelf = variants.indices.mapTo(HashSet()) { "v$it" }
            assertEquals(onShelf, lookup(false))
            assertEquals(onShelf + "removed", lookup(true))
            assertEquals(setOf("lowercase"), lookup(false, "fog harbor", ""))
            assertEquals(setOf("uppercase"), lookup(false, "Fog Harbor", ""))
            assertEquals(setOf("punctuation"), lookup(false, "《书名》"))
            assertEquals(setOf("other-author"), lookup(false, author = "另一作者"))
        }
    }

    @Test fun shelfLookupUsesTitleAndAuthorAndHonorsHiddenState() {
        BundledSQLiteDriver().open(":memory:").use { db ->
            db.execSQL("CREATE TABLE book (id TEXT, title TEXT, author TEXT, hidden INTEGER, deleted INTEGER, inShelf INTEGER)")
            db.execSQL("""
                INSERT INTO book VALUES
                    ('tombstone', 'Fog Harbor', '作者', 0, 1, 1),
                    ('preview', 'Fog Harbor', '作者', 0, 0, 0),
                    ('hidden', 'Fog Harbor', '作者', 1, 0, 1),
                    ('visible', '　Fog Harbor　', ' 作者 ', 0, 0, 1),
                    ('anonymous', '佚名书', '', 0, 0, 1)
            """.trimIndent())

            fun lookup(title: String, author: String?, includeHidden: Boolean = false): String? {
                val key = bookKey(title, author)
                return db.prepare(FIND_SHELF_BOOK_ID_SQL).use {
                    it.bindLong(1, if (includeHidden) 1 else 0)
                    it.bindText(2, BOOK_KEY_WHITESPACE)
                    it.bindText(3, key.first)
                    it.bindText(4, key.second)
                    if (it.step()) it.getText(0) else null
                }
            }

            assertEquals("visible", lookup(" Fog Harbor", "作者\t"))
            assertEquals("anonymous", lookup("佚名书", null))
            assertEquals(null, lookup("fog harbor", "作者"))
            assertEquals(null, lookup("《Fog Harbor》", "作者"))
            assertEquals(null, lookup("Fog Harbor", "另一作者"))
            db.execSQL("DELETE FROM book WHERE id = 'visible'")
            assertEquals(null, lookup("Fog Harbor", "作者"))
            assertEquals("hidden", lookup("Fog Harbor", "作者", includeHidden = true))
        }
    }
}
