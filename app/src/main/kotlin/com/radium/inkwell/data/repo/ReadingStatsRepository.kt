package com.radium.inkwell.data.repo

import androidx.room3.withWriteTransaction
import com.radium.inkwell.core.webdav.BackupReadingStat
import com.radium.inkwell.data.db.InkwellDb
import com.radium.inkwell.data.db.dao.ReadingStatDao
import com.radium.inkwell.data.db.entity.ReadingStatEntity
import com.radium.inkwell.data.prefs.WebDavPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * 阅读时长的读写。记录由阅读页按「真的在读」的时长喂进来（见 ReadingClock），这里只管落库与同步。
 *
 * 行按设备拆（设备 id 与 WebDAV 备份用的是同一个），本机只往自己的行上加。理由见 core 的 BackupReadingStat。
 */
class ReadingStatsRepository(
    private val db: InkwellDb,
    private val dao: ReadingStatDao,
    private val prefs: WebDavPrefs,
) {
    /** 把 [millis] 记到 [at] 那一天的这本书上。读改写放一个事务里：两次记录挨得很近时别丢一次 */
    suspend fun record(
        bookId: String,
        millis: Long,
        at: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (millis <= 0) return
        withContext(Dispatchers.IO) {
            val device = prefs.deviceId()
            val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toEpochDay()
            db.withWriteTransaction {
                val current = dao.get(device, day, bookId)
                dao.upsert(ReadingStatEntity(device, day, bookId, (current?.millis ?: 0) + millis, at))
            }
        }
    }

    fun observeAll(): Flow<List<ReadingStatEntity>> = dao.observeAll()

    suspend fun lastChangeAt(): Long = dao.lastChangeAt()

    suspend fun exportForBackup(): List<BackupReadingStat> = dao.getAll().map {
        BackupReadingStat(deviceId = it.deviceId, day = it.day, bookId = it.bookId, millis = it.millis)
    }

    /**
     * 同步合并出来、比本地大的行写回。事务里按最新行再比一次：合并那几秒里本机可能又记了一笔，
     * 拿合并前的快照整行覆盖会把这笔吞掉 —— 与书行「写库前重读最新行」同一个道理。
     */
    suspend fun applySynced(rows: List<BackupReadingStat>) {
        if (rows.isEmpty()) return
        val now = System.currentTimeMillis()
        db.withWriteTransaction {
            rows.forEach { r ->
                val current = dao.get(r.deviceId, r.day, r.bookId)
                if (current == null || r.millis > current.millis) {
                    dao.upsert(ReadingStatEntity(r.deviceId, r.day, r.bookId, r.millis, now))
                }
            }
        }
    }
}

/**
 * 阅读页的计时器：只算「人在读」的时间。
 *
 * 阅读页在前台不等于在读 —— 屏幕常亮是默认开的，人起身走开，App 能在一页上停一下午。
 * 所以按「活动」计：每次翻页（含自动翻页、滚动越过一页）算一次活动，两次活动之间的间隔计入时长，
 * 但单段最多算 [idleCapMs]：一页字再多也读不了几分钟，超出的部分多半是人不在了。
 * 进后台 / 退出阅读页时以那一刻收尾，同样受这个上限约束。
 *
 * 纯计算、不碰时钟以外的东西，便于单测；落库由调用方拿 [drain] 的结果去做。
 */
class ReadingClock(
    private val now: () -> Long = System::currentTimeMillis,
    private val idleCapMs: Long = IDLE_CAP_MS,
) {
    private var lastActiveAt = STOPPED
    private var pending = 0L

    /** 回到前台 / 打开书：开始计，但这一刻之前的不算 */
    fun start() {
        lastActiveAt = now()
    }

    /** 翻了一页。没在计（还没 start、或已 stop）时当作重新开始 */
    fun touch() {
        val t = now()
        if (lastActiveAt != STOPPED) pending += (t - lastActiveAt).coerceIn(0, idleCapMs)
        lastActiveAt = t
    }

    /** 进后台 / 离开：把最后一段收进来，然后停表 */
    fun stop() {
        if (lastActiveAt != STOPPED) touch()
        lastActiveAt = STOPPED
    }

    /** 攒着还没落库的时长；取走即清零 */
    fun drain(): Long = pending.also { pending = 0 }

    val pendingMillis: Long get() = pending

    companion object {
        private const val STOPPED = -1L
        /** 单段上限。一页正文常态 30 秒到一分钟，给到三分钟已经很宽 */
        const val IDLE_CAP_MS = 3 * 60_000L
        /** 攒够这么多就落一次库，别全压到退出时 —— 进程在后台被杀，攒着的就没了 */
        const val FLUSH_EVERY_MS = 60_000L
    }
}
