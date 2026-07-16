package com.radium.inkwell.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.radium.inkwell.data.db.entity.BookSourceHitEntity

@Dao
interface BookSourceHitDao {

    @Query("SELECT * FROM book_source_hit WHERE bookId = :bookId")
    suspend fun getByBook(bookId: String): List<BookSourceHitEntity>

    /** 同一 (bookId, sourceId) 直接覆盖：只关心最近一次搜索的结论 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(hit: BookSourceHitEntity)

    /** 书被删掉时一起清，别在库里养孤儿行 */
    @Query("DELETE FROM book_source_hit WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)

    /** 书源被删/换 id 时同理 */
    @Query("DELETE FROM book_source_hit WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)

    /** 批量删源时用；逐条走几百个源会很慢 */
    @Query("DELETE FROM book_source_hit WHERE sourceId IN (:sourceIds)")
    suspend fun deleteBySources(sourceIds: List<String>)
}
