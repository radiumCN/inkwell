package com.radium.inkwell.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplaceRuleDao {

    @Query("SELECT * FROM replace_rule WHERE deleted = 0 ORDER BY sortOrder ASC, updatedAt ASC")
    fun observeAll(): Flow<List<ReplaceRuleEntity>>

    @Query("SELECT * FROM replace_rule ORDER BY sortOrder ASC, updatedAt ASC")
    suspend fun getAll(): List<ReplaceRuleEntity>

    /** 通用规则（bookId 为空）+ 指定书的规则 */
    @Query("SELECT * FROM replace_rule WHERE (bookId = '' OR bookId = :bookId) AND deleted = 0 ORDER BY sortOrder ASC")
    fun observeForBook(bookId: String): Flow<List<ReplaceRuleEntity>>

    @Query("SELECT * FROM replace_rule WHERE id = :id")
    suspend fun getById(id: String): ReplaceRuleEntity?

    @Upsert
    suspend fun upsert(rule: ReplaceRuleEntity)

    /**
     * 软删除：打标记而不是删行，并**同时更新 updatedAt** —— 合并靠它做 LWW 裁决，
     * 不更新的话远端那份旧副本会比墓碑还"新"，删除又被覆盖回来。
     */
    @Query("UPDATE replace_rule SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE replace_rule SET deleted = 1, updatedAt = :now WHERE id IN (:ids)")
    suspend fun softDeleteByIds(ids: List<String>, now: Long)

    @Query("UPDATE replace_rule SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM replace_rule WHERE deleted = 0")
    suspend fun maxSortOrder(): Int
}
