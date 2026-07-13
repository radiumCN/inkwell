package com.radium.inkwell.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.radium.inkwell.data.db.entity.BookSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookSourceDao {
    @Query("SELECT * FROM book_source ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<BookSourceEntity>>

    @Query("SELECT * FROM book_source WHERE enabled = 1 ORDER BY sortOrder, name")
    suspend fun getEnabled(): List<BookSourceEntity>

    @Query("SELECT * FROM book_source WHERE id = :id")
    suspend fun getById(id: String): BookSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: BookSourceEntity)

    @Query("UPDATE book_source SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM book_source WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM book_source")
    suspend fun getAll(): List<BookSourceEntity>
}
