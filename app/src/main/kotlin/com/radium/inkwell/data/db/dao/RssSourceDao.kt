package com.radium.inkwell.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.radium.inkwell.data.db.entity.RssSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RssSourceDao {

    @Query("SELECT * FROM rss_source WHERE deleted = 0 ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<RssSourceEntity>>

    @Query("SELECT * FROM rss_source WHERE enabled = 1 AND deleted = 0 ORDER BY sortOrder ASC, name ASC")
    suspend fun getEnabled(): List<RssSourceEntity>

    @Query("SELECT * FROM rss_source WHERE id = :id")
    suspend fun getById(id: String): RssSourceEntity?

    @Query("SELECT * FROM rss_source")
    suspend fun getAll(): List<RssSourceEntity>

    @Upsert
    suspend fun upsertAll(sources: List<RssSourceEntity>)

    @Query("UPDATE rss_source SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    /**
     * 软删除：打标记而不是删行，并**同时更新 updatedAt** —— 合并靠它做 LWW 裁决，
     * 不更新的话远端那份旧副本会比墓碑还"新"，删除又被覆盖回来。
     */
    @Query("UPDATE rss_source SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDeleteById(id: String, now: Long)
}
