package com.radium.inkwell.data.db.entity

import androidx.room3.Entity

@Entity(tableName = "source_read_health", primaryKeys = ["sourceId", "stage", "ruleHash"])
data class SourceReadHealthEntity(
    val sourceId: String,
    val stage: String,
    val ruleHash: Int,
    val score: Int,
    val samples: Int,
    val observedAt: Long,
)
