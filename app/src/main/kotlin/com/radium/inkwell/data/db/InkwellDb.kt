package com.radium.inkwell.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.dao.ReplaceRuleDao
import com.radium.inkwell.data.db.dao.RssSourceDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.ChapterEntity
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import com.radium.inkwell.data.db.entity.RssSourceEntity

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        BookSourceEntity::class,
        ReplaceRuleEntity::class,
        RssSourceEntity::class,
    ],
    version = 12,
    exportSchema = false,
)
abstract class InkwellDb : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookSourceDao(): BookSourceDao
    abstract fun replaceRuleDao(): ReplaceRuleDao
    abstract fun rssSourceDao(): RssSourceDao

    companion object {
        /**
         * 书源脚本的变量落库（Legado 的 book.variable / chapter.variable）：
         * 目录规则 @put 存的参数，正文规则 @get 要取得到。
         * 只加列、带默认值 —— 老用户的书架与阅读进度原样保留。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book ADD COLUMN variable TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chapter ADD COLUMN variable TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 留存书源的 legado 原文 + 转换器版本号。书源在导入时就被转换成我们的规则格式存库了，
         * 升级 App 不会重转 —— 于是我们修的每个转换器 bug 都只对「新导入的书源」生效。
         * 有了原文与版本号，升级后就能把旧转换器转出来的书源重新转一遍。
         *
         * 老数据的 sourceJson 为空（当时没存），重转不了，只能让用户重新导入一次。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book_source ADD COLUMN sourceJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE book_source ADD COLUMN converterVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 用户自定义的净化替换规则。纯新增表，不动任何既有数据 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS replace_rule (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        pattern TEXT NOT NULL,
                        replacement TEXT NOT NULL DEFAULT '',
                        isRegex INTEGER NOT NULL DEFAULT 1,
                        scope TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /** 书签。位置存 (章, 章内字符偏移)，与阅读进度同一套坐标 —— 存页码会被改字号毁掉 */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bookmark (
                        id TEXT NOT NULL PRIMARY KEY,
                        bookId TEXT NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        charOffset INTEGER NOT NULL,
                        chapterTitle TEXT NOT NULL,
                        excerpt TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_bookId ON bookmark(bookId)")
            }
        }

        /**
         * 书架分组；同时把 beta.26 短暂存在过的 bookmark 表丢掉 —— 书签功能撤了，
         * 留个没人读的空表在库里只会让下一个看 schema 的人困惑。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS bookmark")
                db.execSQL("ALTER TABLE book ADD COLUMN groupName TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 书籍隐藏。只加列 */
        /** 追更红点：刷新目录时新增了几章、且还没打开过 */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book ADD COLUMN newChapterCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 书源引擎改为原生 Legado：库里旧的 `json` 是自研格式，新模型（bookSourceUrl/ruleSearch…）
         * 解不动，留着只会静默失效。**清空 book_source**，让升级用户看到空列表、主动重新导入
         * （订阅源 rss_source 存的本就是原生 Legado 规则，不受影响）。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM book_source")
                // 历史列随原生化下线（minSdk 35 的 SQLite 支持 DROP COLUMN）。
                // 只删 book_source 的；rss_source 的同名列仍在用，不动。
                db.execSQL("ALTER TABLE book_source DROP COLUMN sourceJson")
                db.execSQL("ALTER TABLE book_source DROP COLUMN converterVersion")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 订阅源。纯新增表 */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rss_source (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        icon TEXT NOT NULL DEFAULT '',
                        groupName TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        json TEXT NOT NULL,
                        sourceJson TEXT NOT NULL DEFAULT '',
                        converterVersion INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /** 净化规则挂到具体某本书上（阅读页长按选字建的规则）；只加列 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE replace_rule ADD COLUMN bookId TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 书源分组 + 校验结果落库。
         * 校验一轮几百个源要好几分钟，结果只存内存的话退出页面就没了 ——
         * 用户根本没机会拿它做后续处理（筛选失效、按响应时间排序、批量禁用）。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book_source ADD COLUMN groupName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE book_source ADD COLUMN checkStatus INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE book_source ADD COLUMN checkMessage TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE book_source ADD COLUMN respondTime INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE book_source ADD COLUMN checkedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
