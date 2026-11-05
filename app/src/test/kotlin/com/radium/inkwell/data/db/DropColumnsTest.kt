package com.radium.inkwell.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 删列在老 SQLite（Android 13 的 3.32，没有 DROP COLUMN）上走「重建表」，结果必须和原生 DROP COLUMN 一模一样 ——
 * Room 迁移完逐列核对表结构，差一个默认值、主键位置都算迁移失败，App 启动就崩。
 *
 * 测试机上的 SQLite 都新，所以两条路都用 nativeDrop 强制走一遍，拿结果互相比。
 */
class DropColumnsTest {

    private val opened = ArrayList<SQLiteConnection>()

    private fun open(): SQLiteConnection = BundledSQLiteDriver().open(":memory:").also { opened += it }

    @AfterTest
    fun close() = opened.forEach { it.close() }

    /** 迁移 11→12 之前 book_source 那种样子：要删的两列带默认值，还有一个索引 */
    private fun SQLiteConnection.seed() {
        execSQL(
            """
            CREATE TABLE book_source (
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                json TEXT NOT NULL,
                sourceJson TEXT NOT NULL DEFAULT '',
                converterVersion INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 1,
                groupName TEXT NOT NULL DEFAULT '',
                respondTime INTEGER NOT NULL DEFAULT -1,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        execSQL("CREATE INDEX IF NOT EXISTS `idx_source_order` ON `book_source` (`enabled`, `name`)")
        execSQL("INSERT INTO book_source (id, name, json, sourceJson, enabled) VALUES ('https://a.com', '甲', '{}', 'x', 0)")
        execSQL("INSERT INTO book_source (id, name, json) VALUES ('https://b.com', '乙', '{}')")
    }

    private fun SQLiteConnection.indexes(table: String): List<Pair<String, List<String>>> =
        prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ? AND sql IS NOT NULL ORDER BY name").use { st ->
            st.bindText(1, table)
            buildList { while (st.step()) add(st.getText(0)) }
        }.map { name ->
            name to prepare("PRAGMA index_info(`$name`)").use { st -> buildList { while (st.step()) add(st.getText(2)) } }
        }

    private fun SQLiteConnection.rows(): List<String> =
        prepare("SELECT * FROM book_source ORDER BY id").use { st ->
            buildList {
                while (st.step()) add((0 until st.getColumnCount()).joinToString("|") { if (st.isNull(it)) "null" else st.getText(it) })
            }
        }

    @Test
    fun `重建表与原生 DROP COLUMN 结果一致：列、类型、非空、默认值、主键、索引、数据`() {
        val drop = setOf("sourceJson", "converterVersion")
        val native = open().apply { seed(); dropColumns("book_source", drop, nativeDrop = true) }
        val rebuilt = open().apply { seed(); dropColumns("book_source", drop, nativeDrop = false) }

        assertEquals(native.tableColumns("book_source"), rebuilt.tableColumns("book_source"))
        assertEquals(native.indexes("book_source"), rebuilt.indexes("book_source"))
        assertEquals(native.rows(), rebuilt.rows())
        assertFalse(rebuilt.tableColumns("book_source").any { it.name in drop })
        // 重建用的临时表不能留下
        val leftovers = rebuilt.prepare("SELECT count(*) FROM sqlite_master WHERE name LIKE '%_rebuild'").use { it.step(); it.getLong(0) }
        assertEquals(0L, leftovers)
    }

    @Test
    fun `认得出 SQLite 版本有没有 DROP COLUMN`() {
        // bundled 的 SQLite 是新的
        assertTrue(open().supportsDropColumn())
    }
}
