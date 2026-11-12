package com.radium.inkwell.data.db.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.Upsert
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

    /** 批量写（导入）：Room 把整组放进一个事务，中途被杀不留半套 */
    @Upsert
    suspend fun upsertAll(rules: List<ReplaceRuleEntity>)

    /**
     * 软删除：打标记而不是删行，并**同时更新 updatedAt** —— 合并靠它做 LWW 裁决，
     * 不更新的话远端那份旧副本会比墓碑还"新"，删除又被覆盖回来。
     */
    @Query("UPDATE replace_rule SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE replace_rule SET deleted = 1, updatedAt = :now WHERE id IN (:ids)")
    suspend fun softDeleteByIds(ids: List<String>, now: Long)

    /** 书删了：本书专属规则一并打墓碑，否则列表里会留下指着已删书的孤儿行 */
    @Query("UPDATE replace_rule SET deleted = 1, updatedAt = :now WHERE bookId = :bookId AND bookId != '' AND deleted = 0")
    suspend fun softDeleteByBook(bookId: String, now: Long)

    /** 启停同样要盖 updatedAt：合并按它做 LWW，不盖的话开关同步不出去、还会被旧副本改回来 */
    @Query("UPDATE replace_rule SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    /** 多选批量启停：一条语句，同样盖 updatedAt */
    @Query("UPDATE replace_rule SET enabled = :enabled, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setEnabledByIds(ids: List<String>, enabled: Boolean, now: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM replace_rule WHERE deleted = 0")
    suspend fun maxSortOrder(): Int

    /** 净化规则最近一次改动的时间（含墓碑）；空表为 0。同步「两边都没动就跳过」的本地一侧（见 WebDavRepository.syncLocked） */
    @Query("SELECT COALESCE(MAX(updatedAt), 0) FROM replace_rule")
    suspend fun lastChangeAt(): Long
}
