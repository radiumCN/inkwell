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
    @Query("SELECT * FROM book WHERE deleted = 0 AND inShelf = 1 ORDER BY readAt DESC, addedAt DESC")
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

    @Query("UPDATE book SET readChapterIndex = :chapterIndex, readCharOffset = :charOffset, readAt = :readAt WHERE id = :id")
    suspend fun updateProgress(id: String, chapterIndex: Int, charOffset: Int, readAt: Long)

    /** 含墓碑，**只给 WebDAV 备份用** —— 合并要靠 deleted 行表达「这本书被删过」。
     *  面向用户的查询一律用 [getAllVisible]，否则删掉的书会以「已在书架」的身份复活。 */
    @Query("SELECT * FROM book")
    suspend fun getAll(): List<BookEntity>

    /** 不含墓碑、不含试读未上架。与 [observeAll] 同一套过滤，供「这本书是不是已经在架上」判定用 */
    @Query("SELECT * FROM book WHERE deleted = 0 AND inShelf = 1")
    suspend fun getAllVisible(): List<BookEntity>

    @Query("UPDATE book SET groupName = :group WHERE id = :id")
    suspend fun setGroup(id: String, group: String)

    @Query("UPDATE book SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: String, hidden: Boolean)

    /** 打开这本书 = 你已经知道它更新了，红点清零 */
    @Query("UPDATE book SET newChapterCount = 0 WHERE id = :id")
    suspend fun clearNewChapters(id: String)
}
