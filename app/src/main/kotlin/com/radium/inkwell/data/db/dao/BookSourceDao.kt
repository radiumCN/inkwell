package com.radium.inkwell.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.radium.inkwell.data.db.entity.BookSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookSourceDao {
    @Query("SELECT * FROM book_source WHERE deleted = 0 ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<BookSourceEntity>>

    @Query("SELECT * FROM book_source WHERE enabled = 1 AND deleted = 0 ORDER BY sortOrder, name")
    suspend fun getEnabled(): List<BookSourceEntity>

    @Query("SELECT * FROM book_source WHERE id = :id")
    suspend fun getById(id: String): BookSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: BookSourceEntity)

    /** 批量写在单个事务内完成，避免逐条写在真机上又慢又易被中断 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<BookSourceEntity>)

    @Query("SELECT id FROM book_source WHERE deleted = 0")
    suspend fun getAllIds(): List<String>

    @Query("UPDATE book_source SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    /**
     * 软删除：打标记而不是删行，并**同时更新 updatedAt** —— 合并靠它做 LWW 裁决，
     * 不更新的话远端那份旧副本会比墓碑还"新"，删除又被覆盖回来。
     */
    @Query("UPDATE book_source SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDeleteById(id: String, now: Long)

    @Query("UPDATE book_source SET deleted = 1, updatedAt = :now WHERE id IN (:ids)")
    suspend fun softDeleteByIds(ids: List<String>, now: Long)

    @Query("UPDATE book_source SET enabled = :enabled WHERE id IN (:ids)")
    suspend fun setEnabledForIds(ids: List<String>, enabled: Boolean)

    @Query("SELECT * FROM book_source")
    suspend fun getAll(): List<BookSourceEntity>

    @Query(
        """
        UPDATE book_source
           SET checkStatus = :status, checkMessage = :message,
               respondTime = :respondTime, checkedAt = :checkedAt
         WHERE id = :id
        """
    )
    suspend fun saveCheck(id: String, status: Int, message: String, respondTime: Long, checkedAt: Long)

    @Query("UPDATE book_source SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int)

    @Query("UPDATE book_source SET groupName = :group WHERE id IN (:ids)")
    suspend fun setGroupForIds(ids: List<String>, group: String)

    @Query("SELECT MIN(sortOrder) FROM book_source WHERE deleted = 0")
    suspend fun minSortOrder(): Int?

    @Query("SELECT MAX(sortOrder) FROM book_source WHERE deleted = 0")
    suspend fun maxSortOrder(): Int?
}
