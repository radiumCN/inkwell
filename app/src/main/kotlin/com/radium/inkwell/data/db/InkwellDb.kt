package com.radium.inkwell.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.ChapterEntity

@Database(
    entities = [BookEntity::class, ChapterEntity::class, BookSourceEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class InkwellDb : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookSourceDao(): BookSourceDao
}
