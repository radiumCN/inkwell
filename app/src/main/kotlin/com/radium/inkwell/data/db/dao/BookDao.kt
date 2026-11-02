package com.radium.inkwell.data.db.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import com.radium.inkwell.data.db.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    /** 「最近阅读」= 进度挪动过或打开过，取较晚者（见 [BookEntity.openedAt]） */
    @Query("SELECT * FROM book WHERE deleted = 0 AND inShelf = 1 ORDER BY MAX(readAt, openedAt) DESC, addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun getById(id: String): BookEntity?

    @Query("SELECT * FROM book WHERE id = :id")
    fun observeById(id: String): Flow<BookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    /**
     * 软删除：打标记而不是删行，并**同时更新 updatedAt** —— 合并靠它做 LWW 裁决，
     * 不更新的话远端那份旧副本会比墓碑还"新"，删除又被覆盖回来。
     */
    @Query("UPDATE book SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** 真删，只给「导入失败回滚」用：那行从没成功存在过，留墓碑会同步出一条凭空的删除 */
    @Query("DELETE FROM book WHERE id = :id")
    suspend fun hardDelete(id: String)

    /** 有几行指向这个本机文件。导入回滚前用它确认「事务其实已经提交了」，见 BookRepository.rollbackImport */
    @Query("SELECT COUNT(*) FROM book WHERE localPath = :path")
    suspend fun countByLocalPath(path: String): Int

    /**
     * 写进度；**位置没变就不动 readAt**。同步按 readAt 裁决进度，打开书时首屏、退出时 flush 都会来写一次 ——
     * 一律盖新时间戳的话，还没同步的旧设备开一下书就拿旧位置赢过别的设备。
     * SQLite 的 SET 里各表达式读的都是更新前的行，CASE 比较的是旧位置。
     */
    @Query(
        """
        UPDATE book SET
            readAt = CASE WHEN readChapterIndex != :chapterIndex OR readCharOffset != :charOffset
                THEN :readAt ELSE readAt END,
            readChapterIndex = :chapterIndex,
            readCharOffset = :charOffset
        WHERE id = :id
        """,
    )
    suspend fun updateProgress(id: String, chapterIndex: Int, charOffset: Int, readAt: Long)

    /** 含墓碑，**只给 WebDAV 备份用** —— 合并要靠 deleted 行表达「这本书被删过」。
     *  面向用户的查询一律用 [getAllVisible]，否则删掉的书会以「已在书架」的身份复活。 */
    @Query("SELECT * FROM book")
    suspend fun getAll(): List<BookEntity>

    /** 不含墓碑、不含试读未上架。与 [observeAll] 同一套过滤，供「这本书是不是已经在架上」判定用 */
    @Query("SELECT * FROM book WHERE deleted = 0 AND inShelf = 1")
    suspend fun getAllVisible(): List<BookEntity>

    /**
     * 分组、隐藏都是要跨设备同步的元数据，合并按 updatedAt 做 LWW —— 必须同时盖时间戳。
     * 不盖的话改动永远同步不出去，别的设备一追更（刷新了 updatedAt）还会把它改回来：
     * 隐藏的书自己冒回书架、分组掉回原样。
     */
    @Query("UPDATE book SET groupName = :group, updatedAt = :now WHERE id = :id")
    suspend fun setGroup(id: String, group: String, now: Long)

    @Query("UPDATE book SET hidden = :hidden, updatedAt = :now WHERE id = :id")
    suspend fun setHidden(id: String, hidden: Boolean, now: Long)

    /** 打开这本书 = 你已经知道它更新了，红点清零；顺带记下打开时间给书架排序 */
    @Query("UPDATE book SET newChapterCount = 0, openedAt = :now WHERE id = :id")
    suspend fun markOpened(id: String, now: Long)

    /** 书架最近一次改动（元数据或进度）的时间；空表为 0。同步「两边都没动就跳过」的本地一侧（见 WebDavRepository.syncLocked） */
    @Query("SELECT MAX(COALESCE(MAX(updatedAt), 0), COALESCE(MAX(readAt), 0)) FROM book")
    suspend fun lastChangeAt(): Long
}
