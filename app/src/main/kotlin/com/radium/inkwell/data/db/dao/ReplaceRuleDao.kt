package com.radium.inkwell.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplaceRuleDao {

    @Query("SELECT * FROM replace_rule ORDER BY sortOrder ASC, updatedAt ASC")
    fun observeAll(): Flow<List<ReplaceRuleEntity>>

    @Query("SELECT * FROM replace_rule ORDER BY sortOrder ASC, updatedAt ASC")
    suspend fun getAll(): List<ReplaceRuleEntity>

    @Query("SELECT * FROM replace_rule WHERE id = :id")
    suspend fun getById(id: String): ReplaceRuleEntity?

    @Upsert
    suspend fun upsert(rule: ReplaceRuleEntity)

    @Delete
    suspend fun delete(rule: ReplaceRuleEntity)

    @Query("DELETE FROM replace_rule WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE replace_rule SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM replace_rule")
    suspend fun maxSortOrder(): Int
}
