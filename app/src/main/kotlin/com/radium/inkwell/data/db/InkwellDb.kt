package com.radium.inkwell.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.ChapterEntity

@Database(
    entities = [BookEntity::class, ChapterEntity::class, BookSourceEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class InkwellDb : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookSourceDao(): BookSourceDao

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
    }
}
