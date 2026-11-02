package com.radium.inkwell.data.db.entity

import androidx.room3.Entity

/**
 * 阅读时长：某台设备某一天在某本书上读了多久。只加不减，同一行反复累加。
 *
 * 按设备拆行是为了多设备同步时能正确相加（见 core 的 BackupReadingStat）：本机只写 [deviceId] 是自己的行，
 * 别的设备的行只由同步写进来。[day] 是本机时区的 epochDay；[updatedAt] 只给「本地有没有新改动」用，不进备份。
 */
@Entity(tableName = "reading_stat", primaryKeys = ["deviceId", "day", "bookId"])
data class ReadingStatEntity(
    val deviceId: String,
    val day: Long,
    val bookId: String,
    val millis: Long,
    val updatedAt: Long,
)
