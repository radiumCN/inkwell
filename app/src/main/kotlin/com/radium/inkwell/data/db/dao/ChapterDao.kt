package com.radium.inkwell.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.radium.inkwell.data.db.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapter WHERE bookId = :bookId ORDER BY `index`")
    suspend fun getByBook(bookId: String): List<ChapterEntity>

    /**
     * 目录的实时流。一次性的 [getByBook] 只能给出"进书那一刻"的快照，而 isCached 是
     * 边读边预取、一直在变的 —— 拿快照渲染的话，预取跑起来了目录也不会亮，
     * 用户只会以为预加载坏了。
     */
    @Query("SELECT * FROM chapter WHERE bookId = :bookId ORDER BY `index`")
    fun observeByBook(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapter WHERE bookId = :bookId AND `index` = :index")
    suspend fun get(bookId: String, index: Int): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("UPDATE chapter SET isCached = :cached WHERE bookId = :bookId AND `index` = :index")
    suspend fun markCached(bookId: String, index: Int, cached: Boolean)

    @Query("UPDATE chapter SET isCached = 0 WHERE bookId = :bookId AND `index` IN (:indices)")
    suspend fun markUncachedIn(bookId: String, indices: List<Int>)

    /**
     * 一批章节标成未缓存。进书清旧缓存走这里：从前逐章 [markCached]，清掉几百章就是几百次写事务，
     * 每次都让目录流把整本目录重查一遍，正好和首章加载抢 IO。分批是为了别撞 SQLite 的绑定参数上限。
     */
    @Transaction
    suspend fun markUncached(bookId: String, indices: List<Int>) {
        for (chunk in indices.chunked(500)) markUncachedIn(bookId, chunk)
    }

    /** 按章节 URL 标记：正文缓存本身就是按 URL 存的，同 URL 的行共用同一份缓存 */
    @Query("UPDATE chapter SET isCached = :cached WHERE bookId = :bookId AND url = :url")
    suspend fun markCachedByUrl(bookId: String, url: String, cached: Boolean)

    @Query("DELETE FROM chapter WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}
