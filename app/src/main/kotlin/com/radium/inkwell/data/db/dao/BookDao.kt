package com.radium.inkwell.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.radium.inkwell.data.db.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM book ORDER BY readAt DESC, addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun getById(id: String): BookEntity?

    @Query("SELECT * FROM book WHERE id = :id")
    fun observeById(id: String): Flow<BookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("UPDATE book SET readChapterIndex = :chapterIndex, readCharOffset = :charOffset, readAt = :readAt WHERE id = :id")
    suspend fun updateProgress(id: String, chapterIndex: Int, charOffset: Int, readAt: Long)

    @Query("SELECT * FROM book")
    suspend fun getAll(): List<BookEntity>

    @Query("UPDATE book SET groupName = :group WHERE id = :id")
    suspend fun setGroup(id: String, group: String)

    @Query("UPDATE book SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: String, hidden: Boolean)

    /** 打开这本书 = 你已经知道它更新了，红点清零 */
    @Query("UPDATE book SET newChapterCount = 0 WHERE id = :id")
    suspend fun clearNewChapters(id: String)
}
