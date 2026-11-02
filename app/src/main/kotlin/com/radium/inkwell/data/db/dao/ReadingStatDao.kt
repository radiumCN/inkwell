package com.radium.inkwell.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.radium.inkwell.data.db.entity.ReadingStatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingStatDao {

    @Query("SELECT * FROM reading_stat WHERE deviceId = :deviceId AND day = :day AND bookId = :bookId")
    suspend fun get(deviceId: String, day: Long, bookId: String): ReadingStatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ReadingStatEntity)

    @Query("SELECT * FROM reading_stat")
    suspend fun getAll(): List<ReadingStatEntity>

    /**
     * 统计页直接订整张表、在内存里聚合：一台设备一天读几本书就几行，读上几年也就几千行，
     * 为热力图、本周、本年、读得最多各写一条 GROUP BY 反而难测、难改。
     */
    @Query("SELECT * FROM reading_stat")
    fun observeAll(): Flow<List<ReadingStatEntity>>

    /** 本地最近一次记时长的时间，并进同步的「本地有没有动过」判断 */
    @Query("SELECT COALESCE(MAX(updatedAt), 0) FROM reading_stat")
    suspend fun lastChangeAt(): Long
}
