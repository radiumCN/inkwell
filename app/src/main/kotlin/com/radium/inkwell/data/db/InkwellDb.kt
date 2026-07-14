package com.radium.inkwell.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.dao.ReplaceRuleDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.ChapterEntity
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        BookSourceEntity::class,
        ReplaceRuleEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class InkwellDb : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookSourceDao(): BookSourceDao
    abstract fun replaceRuleDao(): ReplaceRuleDao

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

        /** 净化规则挂到具体某本书上（阅读页长按选字建的规则）；只加列 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE replace_rule ADD COLUMN bookId TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
