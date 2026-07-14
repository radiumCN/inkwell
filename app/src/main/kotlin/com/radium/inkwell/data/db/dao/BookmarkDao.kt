package com.radium.inkwell.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.radium.inkwell.data.db.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmark WHERE bookId = :bookId ORDER BY chapterIndex ASC, charOffset ASC")
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmark WHERE bookId = :bookId ORDER BY chapterIndex ASC, charOffset ASC")
    suspend fun getForBook(bookId: String): List<BookmarkEntity>

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmark WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM bookmark WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}
