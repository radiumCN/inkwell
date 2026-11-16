package com.radium.inkwell.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShelfRemovalMigrationTest {
    @Test
    fun `升级后原有书架文件路径与进度不变且不会被标成移出`() = runBlocking {
        BundledSQLiteDriver().open(":memory:").use { db ->
            db.execSQL("CREATE TABLE book (id TEXT NOT NULL PRIMARY KEY, localPath TEXT, readChapterIndex INTEGER NOT NULL, inShelf INTEGER NOT NULL)")
            db.execSQL("INSERT INTO book VALUES ('b', '/books/kept.txt', 128, 1)")
            InkwellDb.MIGRATION_21_22.migrate(db)
            db.prepare("SELECT localPath, readChapterIndex, inShelf, removedFromShelf FROM book WHERE id = 'b'").use {
                assertTrue(it.step())
                assertEquals("/books/kept.txt", it.getText(0))
                assertEquals(128L, it.getLong(1))
                assertEquals(1L, it.getLong(2))
                assertEquals(0L, it.getLong(3))
            }
        }
    }
}
