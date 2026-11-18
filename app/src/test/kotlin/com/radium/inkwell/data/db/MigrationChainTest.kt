package com.radium.inkwell.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** v1 夹具取自 be399b89 的三个实体；实际执行应用注册的整条迁移，再与 Room 导出的 v23 结构核对。 */
class MigrationChainTest {
    private fun SQLiteConnection.seedV1() {
        execSQL("""CREATE TABLE book (
            id TEXT NOT NULL PRIMARY KEY, type INTEGER NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL,
            coverPath TEXT, intro TEXT, localPath TEXT, sourceId TEXT, bookUrl TEXT, tocUrl TEXT,
            latestChapterTitle TEXT, totalChapters INTEGER NOT NULL, readChapterIndex INTEGER NOT NULL,
            readCharOffset INTEGER NOT NULL, readAt INTEGER NOT NULL, addedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL
        )""")
        execSQL("""CREATE TABLE chapter (
            bookId TEXT NOT NULL, `index` INTEGER NOT NULL, title TEXT NOT NULL, url TEXT,
            start INTEGER NOT NULL, end INTEGER NOT NULL, isCached INTEGER NOT NULL, PRIMARY KEY(bookId, `index`)
        )""")
        execSQL("""CREATE TABLE book_source (
            id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, enabled INTEGER NOT NULL,
            sortOrder INTEGER NOT NULL, json TEXT NOT NULL, updatedAt INTEGER NOT NULL
        )""")
        execSQL("""INSERT INTO book VALUES (
            'local', 0, '旧书', '作者', '/covers/cover.jpg', '简介', '/books/kept.txt', NULL, NULL, NULL,
            '第一百章', 100, 42, 128, 123456, 100, 200
        )""")
        execSQL("INSERT INTO chapter VALUES ('local', 42, '第四十三章', NULL, 10, 1000, 1)")
        execSQL("INSERT INTO book_source VALUES ('legacy', '旧格式来源', 1, 0, '{}', 100)")
    }

    @Test fun `v1 through current schema keeps book chapter progress and subscription data`() = runBlocking {
        BundledSQLiteDriver().open(":memory:").use { db ->
            db.seedV1()
            val chain = InkwellDb.ALL_MIGRATIONS
            assertEquals((1 until 23).toList(), chain.map { it.startVersion })
            assertEquals((2..23).toList(), chain.map { it.endVersion })
            chain.forEach { migration ->
                if (migration.startVersion == 22) {
                    db.execSQL("""
                        INSERT INTO book_source
                        (id, name, enabled, sortOrder, json, updatedAt, groupName, checkStatus, checkMessage,
                         respondTime, checkedAt, searchMs, searchFails, deleted)
                        VALUES ('kept', '当前格式来源', 1, 8, '{}', 100, '藏书', 1, '', 400, 90, 500, 2, 0)
                    """.trimIndent())
                }
                migration.migrate(db)
                if (migration.endVersion == 9) {
                    db.execSQL("INSERT INTO rss_source (id, name, json, groupName, icon, sortOrder) VALUES ('rss', '订阅', '{}', '技术', 'icon.png', 7)")
                }
            }
            db.prepare("SELECT localPath, readChapterIndex, readCharOffset, readAt, inShelf, removedFromShelf FROM book WHERE id = 'local'").use {
                assertTrue(it.step())
                assertEquals("/books/kept.txt", it.getText(0))
                assertEquals(42L, it.getLong(1))
                assertEquals(128L, it.getLong(2))
                assertEquals(123456L, it.getLong(3))
                assertEquals(1L, it.getLong(4))
                assertEquals(0L, it.getLong(5))
            }
            db.prepare("SELECT title, start, end, isCached FROM chapter WHERE bookId = 'local' AND `index` = 42").use {
                assertTrue(it.step())
                assertEquals("第四十三章", it.getText(0))
                assertEquals(10L, it.getLong(1))
                assertEquals(1000L, it.getLong(2))
                assertEquals(1L, it.getLong(3))
            }
            db.prepare("SELECT groupName, icon, sortOrder, deleted FROM rss_source WHERE id = 'rss'").use {
                assertTrue(it.step())
                assertEquals("技术", it.getText(0))
                assertEquals("icon.png", it.getText(1))
                assertEquals(7L, it.getLong(2))
                assertEquals(0L, it.getLong(3))
            }
            // v11→12 是已发布的来源格式切换，明确清空旧格式来源；这不是本轮新增的数据删除。
            db.prepare("SELECT COUNT(*) FROM book_source WHERE id = 'legacy'").use {
                assertTrue(it.step())
                assertEquals(0L, it.getLong(0))
            }
            db.prepare("SELECT groupName, sortOrder, searchMs, searchFails, searchAt, searchRetryAt FROM book_source WHERE id = 'kept'").use {
                assertTrue(it.step())
                assertEquals("藏书", it.getText(0))
                assertEquals(listOf(8L, 500L, 2L, 0L, 0L), (1..5).map(it::getLong))
            }
            db.validateCurrentSchema()
        }
    }

    private fun SQLiteConnection.validateCurrentSchema() {
        val schema = Json.parseToJsonElement(File("schemas/com.radium.inkwell.data.db.InkwellDb/23.json").readText())
            .jsonObject.getValue("database").jsonObject
        assertEquals(23, schema.getValue("version").jsonPrimitive.int)
        val entities = schema.getValue("entities").jsonArray.map { it.jsonObject }
        val expectedTables = entities.map { it.string("tableName") }.toSet()
        val actualTables = prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { st ->
            buildSet { while (st.step()) add(st.getText(0)) }
        }
        assertEquals(expectedTables, actualTables)
        assertFalse("bookmark" in actualTables)
        entities.forEach { entity ->
            val table = entity.string("tableName")
            val pk = entity.getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content }
            val expected = entity.getValue("fields").jsonArray.associate { field ->
                val f = field.jsonObject
                val name = f.string("columnName")
                name to Column(f.string("affinity"), f["notNull"]?.jsonPrimitive?.boolean ?: false, pk.indexOf(name) + 1)
            }
            val actual = prepare("PRAGMA table_info(`$table`)").use { st ->
                buildMap { while (st.step()) put(st.getText(1), Column(st.getText(2), st.getLong(3) != 0L, st.getLong(5).toInt())) }
            }
            assertEquals(expected, actual, "columns of $table")
            val expectedIndexes = entity["indices"]?.jsonArray.orEmpty().associate { index ->
                val i = index.jsonObject
                i.string("name") to i.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content }
            }
            val actualIndexes = prepare("PRAGMA index_list(`$table`)").use { st ->
                buildList { while (st.step()) if (st.getText(3) != "pk") add(st.getText(1)) }
            }.associateWith { name ->
                prepare("PRAGMA index_info(`$name`)").use { st -> buildList { while (st.step()) add(st.getText(2)) } }
            }
            assertEquals(expectedIndexes, actualIndexes, "indexes of $table")
        }
    }

    private data class Column(val type: String, val notNull: Boolean, val pkPosition: Int)
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
