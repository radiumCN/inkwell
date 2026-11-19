package com.radium.inkwell.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import com.radium.inkwell.data.db.entity.SourceReadHealthEntity

@Dao
interface SourceReadHealthDao {
    @Query("SELECT * FROM source_read_health WHERE observedAt >= :since")
    suspend fun recent(since: Long): List<SourceReadHealthEntity>

    @Query("DELETE FROM source_read_health WHERE observedAt < :since")
    suspend fun prune(since: Long)

    @Query("""
        INSERT INTO source_read_health(sourceId, stage, ruleHash, score, samples, observedAt)
        VALUES (:sourceId, :stage, :ruleHash, :score, 1, :now)
        ON CONFLICT(sourceId, stage, ruleHash) DO UPDATE SET
            score = CASE WHEN observedAt >= :since
                        THEN (score * 7 + :score * 3) / 10 ELSE :score END,
            samples = CASE WHEN observedAt >= :since
                          THEN min(8, samples + 1) ELSE 1 END,
            observedAt = :now
        WHERE observedAt <= :now
    """)
    suspend fun record(sourceId: String, stage: String, ruleHash: Int, score: Int, now: Long, since: Long)
}
