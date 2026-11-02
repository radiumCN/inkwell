package com.radium.inkwell.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.BookSourceListItem
import com.radium.inkwell.data.db.entity.EnabledSourceMeta
import kotlinx.coroutines.flow.Flow

@Dao
interface BookSourceDao {
    /**
     * 列表观察：刻意不选 [BookSourceEntity.json]。
     * 全量带 JSON 会撑爆 CursorWindow（约 2MB），真机表现为读到某一行闪退。
     */
    @Query(
        """
        SELECT id, name, enabled, sortOrder, updatedAt, groupName,
               checkStatus, checkMessage, respondTime, checkedAt, deleted
          FROM book_source
         WHERE deleted = 0
         ORDER BY sortOrder, name
        """,
    )
    fun observeAll(): Flow<List<BookSourceListItem>>

    /**
     * 启用源的元数据，顺序即书源管理里的手动顺序。规则正文用 [getById] 逐条取（避免一次 Cursor 塞满大 JSON），
     * 仓库先按 updatedAt 查解码缓存，命中就不必取。
     */
    @Query(
        """
        SELECT id, updatedAt, checkStatus, respondTime, sortOrder, searchMs, searchFails
          FROM book_source
         WHERE enabled = 1 AND deleted = 0
         ORDER BY sortOrder, name
        """,
    )
    suspend fun getEnabledMeta(): List<EnabledSourceMeta>

    @Query("SELECT * FROM book_source WHERE id = :id")
    suspend fun getById(id: String): BookSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: BookSourceEntity)

    /** 批量写在单个事务内完成，避免逐条写在真机上又慢又易被中断 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<BookSourceEntity>)

    @Query("SELECT id FROM book_source WHERE deleted = 0")
    suspend fun getAllIds(): List<String>

    /** 含墓碑，供 WebDAV LWW；同样只取 id，正文按条 [getById] */
    @Query("SELECT id FROM book_source")
    suspend fun getAllIdsIncludingDeleted(): List<String>

    /**
     * 启停、排序、分组都会跨设备同步，合并按 updatedAt 做 LWW —— 改它们必须同时盖时间戳，
     * 否则改动同步不出去，还会被别的设备上那份旧副本反向覆盖回来。
     */
    @Query("UPDATE book_source SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    /**
     * 软删除：打标记而不是删行，并**同时更新 updatedAt** —— 合并靠它做 LWW 裁决，
     * 不更新的话远端那份旧副本会比墓碑还"新"，删除又被覆盖回来。
     */
    @Query("UPDATE book_source SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDeleteById(id: String, now: Long)

    @Query("UPDATE book_source SET deleted = 1, updatedAt = :now WHERE id IN (:ids)")
    suspend fun softDeleteByIds(ids: List<String>, now: Long)

    @Query("UPDATE book_source SET enabled = :enabled, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setEnabledForIds(ids: List<String>, enabled: Boolean, now: Long)

    @Query(
        """
        UPDATE book_source
           SET checkStatus = :status, checkMessage = :message,
               respondTime = :respondTime, checkedAt = :checkedAt
         WHERE id = :id
        """
    )
    suspend fun saveCheck(id: String, status: Int, message: String, respondTime: Long, checkedAt: Long)

    /** 搜索测速。和 [saveCheck] 一样**不碰 updatedAt**：那是 WebDAV 合并的裁决依据，测速不是用户改了书源 */
    @Query("UPDATE book_source SET searchMs = :searchMs, searchFails = :searchFails WHERE id = :id")
    suspend fun saveSearchStats(id: String, searchMs: Long, searchFails: Int)

    @Query("UPDATE book_source SET sortOrder = :sortOrder, updatedAt = :now WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int, now: Long)

    @Query("UPDATE book_source SET groupName = :group, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setGroupForIds(ids: List<String>, group: String, now: Long)

    @Query("SELECT MIN(sortOrder) FROM book_source WHERE deleted = 0")
    suspend fun minSortOrder(): Int?

    @Query("SELECT MAX(sortOrder) FROM book_source WHERE deleted = 0")
    suspend fun maxSortOrder(): Int?

    /**
     * 把 [ids] 按给定顺序排到最前：递增分配（起点 = min - 个数），保持选中项之间的相对顺序 ——
     * 从前用 order-- 递减，会把多选置顶的顺序整体倒过来。
     *
     * 整批一个写事务。从前在仓库里逐条 UPDATE，每条各开各的事务：全选几千个置顶就是几千次提交、
     * 几千次表失效（书源列表跟着重查几千遍），中途被杀还会只挪了一半。读 min 也放进同一个事务，
     * 别让并发的另一次置顶插在读与写之间。
     */
    @Transaction
    suspend fun moveToTop(ids: List<String>, now: Long) {
        var order = (minSortOrder() ?: 0) - ids.size
        for (id in ids) setSortOrder(id, order++, now)
    }

    /** 同 [moveToTop]，排到最后 */
    @Transaction
    suspend fun moveToBottom(ids: List<String>, now: Long) {
        var order = (maxSortOrder() ?: 0) + 1
        for (id in ids) setSortOrder(id, order++, now)
    }

    /** 书源最近一次改动的时间（含墓碑）；空表为 0。同步「两边都没动就跳过」的本地一侧（见 WebDavRepository.syncLocked） */
    @Query("SELECT COALESCE(MAX(updatedAt), 0) FROM book_source")
    suspend fun lastChangeAt(): Long
}
