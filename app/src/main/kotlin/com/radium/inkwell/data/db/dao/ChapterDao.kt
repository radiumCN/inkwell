package com.radium.inkwell.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.radium.inkwell.data.db.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapter WHERE bookId = :bookId ORDER BY `index`")
    suspend fun getByBook(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapter WHERE bookId = :bookId AND `index` = :index")
    suspend fun get(bookId: String, index: Int): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("UPDATE chapter SET isCached = :cached WHERE bookId = :bookId AND `index` = :index")
    suspend fun markCached(bookId: String, index: Int, cached: Boolean)

    @Query("DELETE FROM chapter WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}
