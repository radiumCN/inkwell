package com.radium.inkwell.core.webdav

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * WebDAV 备份载荷：书架元数据 + 阅读进度 + 书源 + 净化规则 + 应用/阅读设置。
 * 不含书籍文件与正文缓存 —— 本地书的文件不上传，换设备要重新导入。
 *
 * 老版本的备份文件没有后加的字段，反序列化时按默认值补上（ignoreUnknownKeys + 默认值），
 * 所以新旧版本可以共用同一个 backup.json.gz，不需要迁移。
 */
@Serializable
data class BackupPayload(
    val version: Int = 2,
    val deviceId: String,
    val exportedAt: Long,
    val books: List<BackupBook> = emptyList(),
    val sources: List<BackupSource> = emptyList(),
    val replaceRules: List<BackupReplaceRule> = emptyList(),
    val rssSources: List<BackupRssSource> = emptyList(),
    /** 阅读排版设置；整块 LWW */
    val readerSettings: BackupSettings? = null,
    /** 应用设置（主题、更新渠道…）；整块 LWW */
    val appSettings: BackupSettings? = null,
    /** 阅读时长，按「设备 + 日期 + 书」一行。合并规则见 [BackupReadingStat] */
    val readingStats: List<BackupReadingStat> = emptyList(),
)

/**
 * 某台设备某一天在某本书上读了多久（毫秒）。
 *
 * 时长是**累加量**，不能像别的字段那样 LWW：两台设备同一天各读了半小时，LWW 只会留下一边的半小时。
 * 所以按设备拆行 —— 每台设备只往自己那几行上加，从不碰别人的行；合并时同一行取较大值
 * （同一台设备上它只增不减，较大的就是较新的），总时长 = 各设备的行加起来。
 * 这是计数器的标准做法（G-Counter）：怎么合、合几次、谁先谁后，结果都一样。
 *
 * [day] 是记录那台设备本地时区的日期（epochDay）。书删了行也留着：读过的时间不会因为删书就没读过。
 */
@Serializable
data class BackupReadingStat(
    val deviceId: String,
    val day: Long,
    val bookId: String,
    val millis: Long,
) {
    val key: Triple<String, Long, String> get() = Triple(deviceId, day, bookId)
}

/**
 * 一组设置的快照。
 *
 * 用 Map<String,String> 而不是强类型：BackupPayload 在 core，而阅读设置的类型定义在 reader 模块，
 * core 不该反过来依赖它。键与取值语义由 app 层负责，读到不认识的键直接忽略 —— 老版本
 * 也就能安全地读新版本写的备份。
 *
 * 整块 LWW（而非逐字段）：设置项之间常有关联（比如自定义配色的几个色值），
 * 逐字段合并会拼出一套谁都没设过的配色。阅读设置与应用设置各有自己的时间戳，互不牵连。
 */
@Serializable
data class BackupSettings(
    val updatedAt: Long = 0,
    val values: Map<String, String> = emptyMap(),
)

@Serializable
data class BackupRssSource(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val json: String,
    val sourceJson: String = "",
    val updatedAt: Long = 0,
    /** 软删除墓碑，语义见 [BackupBook.deleted] */
    val deleted: Boolean = false,
)

@Serializable
data class BackupReplaceRule(
    val id: String,
    val name: String,
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = true,
    val scope: String = "",
    /** 只对这一本书生效；空 = 通用规则 */
    val bookId: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val updatedAt: Long = 0,
    /** 软删除墓碑，语义见 [BackupBook.deleted] */
    val deleted: Boolean = false,
)

@Serializable
data class BackupBook(
    val id: String,
    val type: Int,
    val title: String,
    val author: String = "",
    val intro: String? = null,
    /**
     * 封面地址，**只带网络书的**。本地书的封面存的是本机绝对路径，换台设备就是条死路径，
     * 而封面文件本身并不上传（见文件头「不含书籍文件」）。
     *
     * 从前整个封面信息都不进备份，于是同步过去的书架整片是灰色占位块 —— 网络书的封面
     * 明明是个跨设备就能用的 URL，却在导出这一步被丢掉了，联网也救不回来：库里没地址可请求。
     *
     * 老备份没有这个键 → 默认 null，读起来就是「这条没带封面」，不影响。
     */
    val coverUrl: String? = null,
    val sourceId: String? = null,
    val bookUrl: String? = null,
    val tocUrl: String? = null,
    val totalChapters: Int = 0,
    val readChapterIndex: Int = 0,
    val readCharOffset: Int = 0,
    val readAt: Long = 0,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    /** 书架分组 */
    val groupName: String = "",
    /** 从书架隐藏（不是删除） */
    val hidden: Boolean = false,
    /**
     * 软删除墓碑。为真表示「这条被删了」，而不是「本地没有」。
     *
     * 没有它的话，删除在多设备间根本留不住：合并是「并集」，本地删掉的东西远端还在，
     * 下次同步就被当成「别的设备新加的」原样补回来。
     *
     * **合并逻辑不用为它开特例** —— 删除就是一次普通的 updatedAt 更新，
     * 现有的 LWW 自然能裁决：本地 deleted@T2 vs 远端 alive@T1，T2 大就保持删除。
     *
     * 老备份没有这个键 → 默认 false，读起来还是活的，不影响。
     */
    val deleted: Boolean = false,
)

@Serializable
data class BackupSource(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val json: String,
    val updatedAt: Long = 0,
    val sortOrder: Int = 0,
    /** 书源分组；老备份没有此键 → 默认空，不影响读取 */
    val groupName: String = "",
    /** 软删除墓碑，语义见 [BackupBook.deleted] */
    val deleted: Boolean = false,
)

object BackupCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(payload: BackupPayload): ByteArray {
        val raw = json.encodeToString(BackupPayload.serializer(), payload).toByteArray()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): BackupPayload {
        val raw = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        return json.decodeFromString(BackupPayload.serializer(), String(raw, Charsets.UTF_8))
    }
}

/**
 * 字段级 Last-Write-Wins 合并：
 * 同一本书进度取 readAt 较大者、元数据取 updatedAt 较大者；书源按 updatedAt。
 * 例外是书的**书源身份**：它跟进度绑成一体按 readAt 裁，章号不能脱离记下它的那个源（见 [mergeBook]）。
 *
 * **删除靠软删除墓碑表达**（`deleted = true` + 新的 updatedAt），而不是「从列表里消失」。
 * 从前是纯并集、不认墓碑，于是删除在多设备间根本留不住 —— 本地删掉的书源远端还在，
 * 下次同步就被当成「别的设备新加的」补回来，用户删一次它回来一次。
 *
 * 注意合并这里**没有为删除写任何特例**：删除就是一次普通的 updatedAt 更新，
 * 现有的 LWW 自然裁决。真正要小心的是「本地无 + 远端有」这一支 ——
 * 它仍然表示「远端新增」，因为本地压根没见过这条；已删除的东西在本地是**有行的**
 * （带 deleted 标记），不会走到那一支。
 */
object BackupMerger {

    data class MergeResult(
        val books: List<BackupBook>,
        val sources: List<BackupSource>,
        val replaceRules: List<BackupReplaceRule>,
        val rssSources: List<BackupRssSource>,
        /** 合并后与本地不同的条目（需要写回本地库） */
        val changedBooks: List<BackupBook>,
        val changedSources: List<BackupSource>,
        val changedReplaceRules: List<BackupReplaceRule>,
        val changedRssSources: List<BackupRssSource>,
        /** 远端更新（需要写回本地）；null = 本地的更新或一样新 */
        val readerSettings: BackupSettings?,
        val appSettings: BackupSettings?,
        /** 合并后的全部阅读时长行，与其中比本地大（需要写回本地）的那些 */
        val readingStats: List<BackupReadingStat> = emptyList(),
        val changedReadingStats: List<BackupReadingStat> = emptyList(),
        /** 合并后要上传的设置（取较新的那份） */
        val mergedReaderSettings: BackupSettings?,
        val mergedAppSettings: BackupSettings?,
    )

    fun merge(local: BackupPayload, remote: BackupPayload): MergeResult {
        val localBooks = local.books.associateBy { it.id }
        val remoteBooks = remote.books.associateBy { it.id }
        val mergedBooks = (localBooks.keys + remoteBooks.keys).map { id ->
            val l = localBooks[id]
            val r = remoteBooks[id]
            when {
                l == null -> r!!
                r == null -> l
                else -> mergeBook(l, r)
            }
        }
        val changedBooks = mergedBooks.filter { it != localBooks[it.id] }

        val localSources = local.sources.associateBy { it.id }
        val remoteSources = remote.sources.associateBy { it.id }
        val mergedSources = (localSources.keys + remoteSources.keys).map { id ->
            val l = localSources[id]
            val r = remoteSources[id]
            when {
                l == null -> r!!
                r == null -> l
                else -> if (r.updatedAt > l.updatedAt) r else l
            }
        }
        val changedSources = mergedSources.filter { it != localSources[it.id] }

        val localRules = local.replaceRules.associateBy { it.id }
        val remoteRules = remote.replaceRules.associateBy { it.id }
        val mergedRules = (localRules.keys + remoteRules.keys).map { id ->
            val l = localRules[id]
            val r = remoteRules[id]
            when {
                l == null -> r!!
                r == null -> l
                else -> if (r.updatedAt > l.updatedAt) r else l
            }
        }
        val changedRules = mergedRules.filter { it != localRules[it.id] }

        val localRss = local.rssSources.associateBy { it.id }
        val remoteRss = remote.rssSources.associateBy { it.id }
        val mergedRss = (localRss.keys + remoteRss.keys).map { id ->
            val l = localRss[id]
            val r = remoteRss[id]
            when {
                l == null -> r!!
                r == null -> l
                else -> if (r.updatedAt > l.updatedAt) r else l
            }
        }
        val changedRss = mergedRss.filter { it != localRss[it.id] }

        val localStats = local.readingStats.associateBy { it.key }
        val mergedStats = mergeReadingStats(local.readingStats, remote.readingStats)
        val changedStats = mergedStats.filter { it != localStats[it.key] }

        val readerWinner = newer(local.readerSettings, remote.readerSettings)
        val appWinner = newer(local.appSettings, remote.appSettings)

        return MergeResult(
            books = mergedBooks,
            sources = mergedSources,
            replaceRules = mergedRules,
            rssSources = mergedRss,
            changedBooks = changedBooks,
            changedSources = changedSources,
            changedReplaceRules = changedRules,
            changedRssSources = changedRss,
            // 只有远端确实更新时才回写本地；否则别拿一份等价的设置去覆盖用户当前的
            readerSettings = remote.readerSettings
                ?.takeIf { it === readerWinner && it != local.readerSettings },
            appSettings = remote.appSettings
                ?.takeIf { it === appWinner && it != local.appSettings },
            mergedReaderSettings = readerWinner,
            mergedAppSettings = appWinner,
            readingStats = mergedStats,
            changedReadingStats = changedStats,
        )
    }

    /** 同一行（设备 + 日期 + 书）取较大值；只有一边有的原样保留。理由见 [BackupReadingStat] */
    fun mergeReadingStats(
        local: List<BackupReadingStat>,
        remote: List<BackupReadingStat>,
    ): List<BackupReadingStat> {
        val merged = LinkedHashMap<Triple<String, Long, String>, BackupReadingStat>(local.size + remote.size)
        (local + remote).forEach { row ->
            val existing = merged[row.key]
            if (existing == null || row.millis > existing.millis) merged[row.key] = row
        }
        return merged.values.toList()
    }

    /** 整块 LWW；时间戳相同时本地优先（免得每次同步都来回覆盖） */
    private fun newer(local: BackupSettings?, remote: BackupSettings?): BackupSettings? = when {
        local == null -> remote
        remote == null -> local
        remote.updatedAt > local.updatedAt -> remote
        else -> local
    }

    /**
     * 一本书两边都有时怎么合。元数据（书名/分组/隐藏/删除）按 updatedAt，进度按 readAt。
     *
     * **书源身份（sourceId/bookUrl/tocUrl/章数）跟着进度走，不跟着元数据走**：章号只在记下它的那个源的
     * 目录里有意义。从前书源随元数据按 updatedAt 裁、进度按 readAt 裁，两边换过源时就能拼出
     * 「新源 + 旧源的章号」—— A 换源（新源前面多 3 章公告，进度对齐成 103）后同步，B 拿到了新源，
     * 进度却因 readAt 打平留在自己的 100：落后 3 章，下次同步还把这个错位推回给 A。
     *
     * 两边源不同时，就把「书源 + 进度」当一个整体：readAt 新的一边整体胜出（换源本身会盖 readAt，
     * 见 NetBookRepository.changeSource），打平时随元数据的裁决。选这个而不是「一律取元数据那边的进度」：
     * 后者在另一台设备换源之后、同步之前还在旧源上往下读的情况下，会把这段新进度整个丢掉；
     * 而整体取 readAt 新的一边，最坏也只是换源被撤回、书停在旧源上 —— 章号和源始终对得上，
     * 旧源真读不了时自动换源会按章节标题再对齐一次。
     */
    private fun mergeBook(l: BackupBook, r: BackupBook): BackupBook {
        val meta = if (r.updatedAt > l.updatedAt) r else l
        // 封面不走 LWW，走「谁有算谁的」：它不是用户改得动的状态，null 只意味着
        // 「这台机器还没拿到」。按 LWW 裁的话，接收端只要读过一次书（updatedAt 一更新），
        // 本地那条空封面就会一直赢下去，封面永远补不回来。
        val coverUrl = meta.coverUrl ?: l.coverUrl ?: r.coverUrl
        if (sameSource(l, r)) {
            val progress = if (r.readAt > l.readAt) r else l
            return meta.copy(
                readChapterIndex = progress.readChapterIndex,
                readCharOffset = progress.readCharOffset,
                readAt = progress.readAt,
                coverUrl = coverUrl,
            )
        }
        val position = when {
            r.readAt > l.readAt -> r
            l.readAt > r.readAt -> l
            // 打平多半是老版本换源（那时不盖 readAt）：此时只有 updatedAt 记着「换过源」这件事
            else -> meta
        }
        return meta.copy(
            sourceId = position.sourceId,
            bookUrl = position.bookUrl,
            tocUrl = position.tocUrl,
            // 章数是那个源的目录的章数；拿另一个源的去比大小没有意义
            totalChapters = position.totalChapters,
            readChapterIndex = position.readChapterIndex,
            readCharOffset = position.readCharOffset,
            readAt = position.readAt,
            coverUrl = coverUrl,
        )
    }

    /** 两份记录是不是同一个书源上的同一本书（本地书两边都是 null，也算同一个） */
    fun sameSource(a: BackupBook, b: BackupBook): Boolean =
        a.sourceId == b.sourceId && a.bookUrl == b.bookUrl
}
