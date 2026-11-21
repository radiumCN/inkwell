package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.BookSourceTypes
import com.radium.inkwell.core.webdav.BackupBook
import com.radium.inkwell.core.webdav.BackupSync
import com.radium.inkwell.core.webdav.BackupMerger
import com.radium.inkwell.core.webdav.BackupPayload
import com.radium.inkwell.core.webdav.BackupReplaceRule
import com.radium.inkwell.core.webdav.BackupRssSource
import com.radium.inkwell.core.webdav.mergeBackupRssSource
import com.radium.inkwell.core.webdav.BackupSource
import com.radium.inkwell.core.webdav.WebDavClient
import com.radium.inkwell.core.webdav.WebDavQuota
import androidx.room3.withWriteTransaction
import com.radium.inkwell.data.db.InkwellDb
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import com.radium.inkwell.data.net.OfficialWebDav
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.prefs.ReaderPrefs
import com.radium.inkwell.data.prefs.WebDavConfig
import com.radium.inkwell.data.prefs.WebDavPrefs
import com.radium.inkwell.data.prefs.exportForBackup
import com.radium.inkwell.data.prefs.importFromBackup
import com.radium.inkwell.util.LocalNetworkAccess
import com.radium.inkwell.util.LocalNetworkPermissionMissing
import com.radium.inkwell.util.UsageLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.TimeSource

class WebDavRepository(
    private val db: InkwellDb,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val sourceDao: BookSourceDao,
    private val rssDao: com.radium.inkwell.data.db.dao.RssSourceDao,
    private val cache: ChapterContentCache,
    private val prefs: WebDavPrefs,
    private val readerPrefs: ReaderPrefs,
    private val appPrefs: AppPrefs,
    private val replaceRules: ReplaceRuleRepository,
    private val localNetwork: LocalNetworkAccess,
    private val bookRepo: BookRepository,
    /**
     * 共用应用的 HTTP 客户端（WebDavClient 在它上面派生自己的超时）。从前每次同步都 new 一个
     * OkHttpClient：连接池、线程池、TLS 会话全是新的，退到后台一次就重新握一次手。
     */
    private val http: okhttp3.OkHttpClient,
    private val readingStats: ReadingStatsRepository,
) {
    private companion object {
        const val DIR = "inkwell"
        const val BACKUP = "inkwell/backup.json.gz"
        const val UP_TO_DATE = "已是最新"
        private val backupJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    /**
     * 同一时刻只跑一次同步。入口有三个（冷启动、退到后台、设置页手点），彼此不知情：
     * 启动即进设置页点「立即同步」就是两次 merge 加两次 PUT 并行 —— 后 PUT 的那份
     * 基于更旧的本地快照，会把前一份刚合并进来的远端更新又盖回去。
     */
    private val syncMutex = Mutex()

    suspend fun testConnection(url: String, username: String, password: String): Result<Unit> =
        WebDavClient(url, username, password, http).check()

    /**
     * 双向同步：GET 远端 → 字段级 LWW 合并 → 写回本地 → PUT 合并结果。
     *
     * @param auto 自动同步（冷启动、退到后台）。两边都没动过就整次跳过，见 [syncLocked]；
     *   设置页手点的「立即同步」照常完整走一遍 —— 那是用户明说要同步。
     */
    suspend fun sync(auto: Boolean = false): Result<String> = sync(auto, config = null)

    private suspend fun sync(auto: Boolean, config: WebDavConfig?): Result<String> {
        val started = TimeSource.Monotonic.markNow()
        var outcome = "cancelled"
        var errorCategory: String? = null
        try {
            // 编解码、解析书源和双向合并都在 IO 执行，手动同步由主线程发起。
            val message = withContext(Dispatchers.IO) {
                syncMutex.withLock { syncLocked(auto, config ?: prefs.config.first()) }
            }
            outcome = if (message == UP_TO_DATE) "up_to_date" else "ok"
            return Result.success(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            outcome = "failed"
            errorCategory = syncErrorCategory(e)
            // 记录失败本身出错时，仍把原来的失败交还给设置页。
            runCatching { prefs.markSyncFailed(describeSyncError(e), System.currentTimeMillis()) }
            return Result.failure(e)
        } finally {
            val trigger = when {
                auto -> "auto"
                config != null -> "before_book_backup"
                else -> "manual"
            }
            UsageLog.event(
                "webdav.sync.result", "trigger" to trigger, "outcome" to outcome,
                "duration_ms" to started.elapsedNow().inWholeMilliseconds, "error_category" to errorCategory,
            )
        }
    }

    private suspend fun syncLocked(auto: Boolean, config: WebDavConfig): String {
        val url = if (OfficialWebDav.isLegacyDav(config.url)) OfficialWebDav.DAV else config.url
        val client = clientFor(config, url)
        // 版本标记连同是哪个地址、哪个账号一起记：换了账号，旧标记不能被当成「远端没变」
        val account = url + "\u0000" + config.username + "\u0000"

        // 两边都没动过就整次跳过。自动同步的常态是「切出 App 时什么都没改」：从前每次都整份下载、
        // 合并、再整份上传 —— 几千个书源的备份就是每切一次后台几 MB 的上下行。
        // 本地：「最新修改时间」与上次拍快照时记下的**相等**（见 [localChangeAt]）；
        // 远端：版本标记（ETag / Last-Modified）与上次同步后记下的一致。任何一样拿不准都当动过，照常完整同步。
        if (auto) {
            val mark = prefs.syncMark()
            if (mark.remote != null && localChangeAt() == mark.localStamp) {
                val remoteNow = remoteVersion(client)?.let { account + it }
                if (remoteNow == mark.remote) {
                    prefs.markSynced(System.currentTimeMillis())
                    return UP_TO_DATE
                }
            }
        }

        // 先建目录再读：首次同步时 inkwell/ 还不存在，直接 GET 备份文件，
        // 坚果云对「父目录不存在」回的是 409 而不是 404 —— 同步就一次都成不了。
        // MKCOL 是幂等的（已存在返回 405，客户端当成功）。
        client.mkcol(DIR)

        var localStamp = 0L
        var applied = 0
        val result = BackupSync(client).run(
            BACKUP,
            local = {
                // 每次冲突重试都重新拍快照；先记修改标记，期间落库的新修改留给下次同步。
                localStamp = localChangeAt()
                buildLocalPayload()
            },
            apply = { merged -> applied += applyToLocal(merged) },
        )
        prefs.markSynced(System.currentTimeMillis())
        prefs.saveSyncMark(localStamp, result.remoteVersion?.let { account + it })
        return if (applied > 0) "同步完成，合并了 $applied 项远端更新" else "同步完成"
    }

    internal suspend fun bookFilesClient(syncShelf: Boolean = false): WebDavClient {
        val config = prefs.config.first()
        val url = if (OfficialWebDav.isLegacyDav(config.url)) OfficialWebDav.DAV else config.url
        if (syncShelf) sync(auto = false, config).getOrThrow()
        return clientFor(config, url)
    }

    suspend fun officialQuota(): Result<WebDavQuota?> = try {
        val config = prefs.config.first()
        check(config.isOfficialConnected) { "请先连接官方同步" }
        Result.success(clientFor(config, OfficialWebDav.resolveDavUrl(config.url)).quota())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun clientFor(config: WebDavConfig, url: String): WebDavClient {
        check(config.isConfigured) { "尚未配置 WebDAV" }
        if (localNetwork.isBlocked(url)) throw LocalNetworkPermissionMissing()
        return WebDavClient(url, config.username, config.password, http)
    }

    /**
     * 本地会进备份的东西最近一次改动的时间：书架（含进度）、书源、净化规则、订阅源、两份设置。
     *
     * 当「版本」用，按相等比（见 [syncLocked]）：任何一笔新写入，时间戳只要比现有的都新，这个值就变。
     * 盲区只剩系统时间被往回调之后的写入（新时间戳比已有的旧）—— 那种情况等时间走过来，或下一次手动同步。
     * 合并时写进本地的远端改动也会让它变：拉过远端更新的下一次自动同步会再完整走一遍，之后就稳定了。
     */
    private suspend fun localChangeAt(): Long = maxOf(
        maxOf(bookDao.lastChangeAt(), sourceDao.lastChangeAt()),
        maxOf(replaceRules.lastChangeAt(), rssDao.lastChangeAt()),
        maxOf(appPrefs.updatedAt(), readerPrefs.updatedAt()),
        readingStats.lastChangeAt(),
    )

    /** 远端备份的版本标记；读不到（服务器不支持 HEAD、网络抖一下）就是 null，调用方当「动过」处理 */
    private suspend fun remoteVersion(client: WebDavClient): String? =
        try {
            client.version(BACKUP)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun buildLocalPayload(): BackupPayload = BackupPayload(
        deviceId = prefs.deviceId(),
        exportedAt = System.currentTimeMillis(),
        // 试读未上架不进备份：从没成为用户书架上的书，同步出去只会在别的设备
        // 冒出一本看不见的书，或在用户点「不加入」之后变成凭空墓碑。
        // 墓碑仍要带走（inShelf 可能是 false —— 删过又试读过），合并靠它表达「删过」。
        books = bookDao.getAll().filter { it.inShelf || it.deleted || it.removedFromShelf }.map { b ->
            BackupBook(
                id = b.id, type = b.type, title = b.title, author = b.author,
                intro = b.intro,
                // coverPath 这个列名对两类书是两种东西：网络书存的是远程 URL（跨设备可用），
                // 本地书存的是本机绝对路径（换设备就是死路径）。只把前者带走。
                coverUrl = b.coverPath?.takeIf { b.type == BookType.NET },
                sourceId = b.sourceId, bookUrl = b.bookUrl, tocUrl = b.tocUrl,
                totalChapters = b.totalChapters,
                readChapterIndex = b.readChapterIndex, readCharOffset = b.readCharOffset,
                readAt = b.readAt, addedAt = b.addedAt, updatedAt = b.updatedAt,
                groupName = b.groupName,
                hidden = b.hidden,
                deleted = b.deleted,
                removedFromShelf = b.removedFromShelf,
            )
        },
        // 逐条取完整行：SELECT * 全表会把大 JSON 塞进 CursorWindow（~2MB）直接闪退。
        // 单行超大读不出来就跳过这一条（远端那份照旧留在合并结果里）；取消照常往外抛
        sources = sourceDao.getAllIdsIncludingDeleted().mapNotNull { id ->
            try {
                sourceDao.getById(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }.map { s ->
            BackupSource(
                id = s.id, name = s.name, enabled = s.enabled, json = s.json,
                updatedAt = s.updatedAt,
                sortOrder = s.sortOrder,
                groupName = s.groupName,
                deleted = s.deleted,
            )
        },
        replaceRules = replaceRules.getAll().map { r ->
            BackupReplaceRule(
                id = r.id, name = r.name, pattern = r.pattern, replacement = r.replacement,
                isRegex = r.isRegex, scope = r.scope, bookId = r.bookId, enabled = r.enabled,
                sortOrder = r.sortOrder, updatedAt = r.updatedAt, deleted = r.deleted,
            )
        },
        rssSources = rssDao.getAll().map { it.toBackupRssSource() },
        readerSettings = readerPrefs.exportForBackup(),
        appSettings = appPrefs.exportForBackup(),
        readingStats = readingStats.exportForBackup(),
    )

    private suspend fun applyToLocal(merged: BackupMerger.MergeResult): Int {
        // 另一台设备换过源的书：sourceId / bookUrl 跟本地对不上。目录表不进备份，本地留着的
        // 还是**旧源**的章节 URL，拿去配新源的规则抓正文必失败 → 触发自动换源 → 又换到第三个源、
        // updatedAt 刷新 → 下次同步反过来盖掉对面 —— 两台设备来回乒乒。本地换源
        // （NetBookRepository.changeSource）是「清目录 + 清缓存 + 改书行」一起做的，远端来的
        // 换源也得走同一条路：目录清空后，阅读页「目录为空则先拉一次」自然接管。
        // 缓存是文件 IO，放事务外先做（按这一刻的行判断，尽力而为）；目录删除跟书行更新同一事务、按事务里的最新行判断。
        val resourced = merged.changedBooks.filter { b ->
            val existing = bookDao.getById(b.id) ?: return@filter false
            // 书源不会被采纳（本地在快照之后又读过/换过源，见 applySyncedBook）就谈不上换源
            applySyncedBook(existing, b).sourceChanged
        }.map { it.id }
        if (resourced.isNotEmpty()) {
            withContext(Dispatchers.IO) { resourced.forEach { cache.clear(it) } }
        }
        // 事务内记录要清理的最新路径，包含同步期间刚恢复的本地文件。
        val remotelyDeleted = mutableListOf<BookEntity>()

        // 几十条逐行写 + 三张表：中途被杀不留半套。远端设置的导入是 DataStore，在事务外。
        val appliedRows = db.withWriteTransaction {
            var n = applyBooks(merged, remotelyDeleted)
            n += applySources(merged)
            merged.changedReplaceRules.forEach { r ->
                // 快照后用户刚禁用、编辑或删除的规则不能被旧合并结果盖掉。
                applySyncedRule(replaceRules.getById(r.id), r)?.let {
                    replaceRules.upsertAll(listOf(it))
                    n++
                }
            }
            merged.changedRssSources.forEach { r ->
                applySyncedRss(rssDao.getById(r.id), r)?.let {
                    rssDao.upsertAll(listOf(it))
                    n++
                }
            }
            n
        }
        if (remotelyDeleted.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                // 和本地删书同一条路：路径限定在应用自己的目录里，同步来的行改不了删哪
                remotelyDeleted.forEach { bookRepo.deleteLocalFiles(it) }
            }
        }
        var appliedSettings = 0
        merged.readerSettings?.let { if (readerPrefs.importFromBackup(it)) appliedSettings++ }
        // 别的设备的阅读时长、本机清数据前记下的时长。不计入「合并了几项更新」：那是给人看书架变化的
        readingStats.applySynced(merged.changedReadingStats)
        merged.appSettings?.let { if (appPrefs.importFromBackup(it)) appliedSettings++ }
        return appliedRows + appliedSettings
    }

    /** @return 真正写了库的书目数。合并结果里「变了」的条目，到了事务里按最新行可能一条都不采纳 */
    private suspend fun applyBooks(
        merged: BackupMerger.MergeResult,
        remotelyDeleted: MutableList<BookEntity>,
    ): Int {
        var applied = 0
        merged.changedBooks.forEach { b ->
            val existing = bookDao.getById(b.id)
            if (existing != null) {
                // 拿事务里的最新行裁决：本地在拍快照之后又改过（时间戳已经比合并结果新），那一块就保留本地的
                val synced = applySyncedBook(existing, b)
                if (synced.deletedRemotely) remotelyDeleted += existing
                if (synced.sourceChanged || synced.deletedRemotely) chapterDao.deleteByBook(b.id)
                // 一个字段都没变就别写：整表 Flow 白重发一轮，还会把这条算进「合并了 N 项」
                if (synced.entity != existing) {
                    bookDao.update(synced.entity)
                    applied++
                }
            } else {
                // 远端新增：本地占位（本地书缺文件时详情页提示重新导入；网络书可直接读）
                bookDao.upsert(
                    BookEntity(
                        id = b.id, type = b.type, title = b.title, author = b.author,
                        intro = b.intro, localPath = null, coverPath = remoteCover(b),
                        sourceId = b.sourceId, bookUrl = b.bookUrl, tocUrl = b.tocUrl,
                        totalChapters = b.totalChapters,
                        readChapterIndex = b.readChapterIndex, readCharOffset = b.readCharOffset,
                        readAt = b.readAt,
                        addedAt = if (b.addedAt > 0) b.addedAt else System.currentTimeMillis(),
                        updatedAt = b.updatedAt,
                        groupName = b.groupName, hidden = b.hidden,
                        // 墓碑也要落地：别把别的设备删掉的书当成新书插回来
                        deleted = b.deleted,
                        inShelf = !b.deleted && !b.removedFromShelf,
                        removedFromShelf = b.removedFromShelf,
                    )
                )
                applied++
            }
        }
        return applied
    }

    private suspend fun applySources(merged: BackupMerger.MergeResult): Int {
        var applied = 0
        merged.changedSources.forEach { s ->
            // 与 importJson 一致：远端若带了漫画/听书/视频源，别写进只跑小说引擎的本地库
            if (!isTextNovelSourceJson(s.json)) return@forEach
            // 本地测速和校验只适用于当前这份规则。规则相同的启停/分组改动保留它们。
            val existing = sourceDao.getById(s.id)
            // 同 applyBooks：本地在拍快照之后又改过（启停/分组/删除），保留本地的
            if (existing != null && s.updatedAt <= existing.updatedAt) return@forEach
            sourceDao.upsert(
                (existing?.takeIf { it.json == s.json }
                    ?: BookSourceEntity(id = s.id, name = s.name, json = s.json, updatedAt = s.updatedAt))
                    .copy(
                        name = s.name, enabled = s.enabled, sortOrder = s.sortOrder,
                        json = s.json, updatedAt = s.updatedAt, groupName = s.groupName,
                        deleted = s.deleted,
                    )
            )
            applied++
        }
        return applied
    }

    /** 备份里的书源 JSON 是否为小说源；解析失败按「不是小说」丢掉，避免脏数据进库 */
    private fun isTextNovelSourceJson(json: String): Boolean = runCatching {
        val root = backupJson.parseToJsonElement(json)
        val obj = root as? JsonObject ?: root.jsonObject
        BookSourceTypes.isTextNovel(BookSourceTypes.parse(obj))
    }.getOrDefault(false)
}

/**
 * 远端封面只对网络书采纳 —— 与导出侧 `buildLocalPayload` 的约定一致：本地书的封面是本机路径，
 * 导出时永远给 null。本地书却带着一个「封面」，只可能是被改过的备份；而本地书的 coverPath
 * 会被当成本机文件读、删，不能让备份往里填路径。
 */
private fun remoteCover(b: BackupBook): String? = b.coverUrl?.takeIf { b.type == BookType.NET }

/** 同步来的一条书目落到本机已有书行上的结果 */
internal data class SyncedBook(
    val entity: BookEntity,
    /** 采纳了另一个源：本机目录与正文缓存都还是旧源的，要清掉，阅读页会按新源重新拉目录 */
    val sourceChanged: Boolean,
    /** 远端删掉了：本机目录跟着清，书文件/封面/缓存由调用方在事务外清 */
    val deletedRemotely: Boolean,
)

/**
 * 合并结果里的一条书目怎么落到本机**最新**的那一行上（调用方在写事务里重读后传进来）。
 *
 * 规则与 BackupMerger.mergeBook 一致，只是对面换成了事务里的最新行：合并只在远端时间戳**严格更大**时
 * 才取远端那一份（相等算本地），本地在拍快照之后又改过（时间戳已经比合并结果新），那一块就保留本地的 ——
 * 否则用户刚做的修改被合并结果里的旧值盖回去，还会随下一次上传扩散到所有设备。
 *
 * 抽成纯函数是为了能脱离 Room 单测。
 */
internal fun applySyncedBook(existing: BookEntity, b: BackupBook): SyncedBook {
    // 本机这一行只是试读（预览直读落的库，不进备份），远端却是一本真正在架上的书：别的设备把它加上了书架，
    // 这台只是之后点开看了一眼。试读行的 updatedAt 是点开那一刻盖的，按 LWW 它永远比远端新 ——
    // 这本书在这台设备上就一直看不见，每次同步还都报「合并了 1 项远端更新」。
    // 试读不算用户对「在不在架」表过态，在架状态与元数据直接听远端的。
    val previewOnly = !existing.inShelf && !existing.deleted && !existing.removedFromShelf && !b.deleted
    val takeMeta = previewOnly || b.updatedAt > existing.updatedAt
    val sameSource = existing.sourceId == b.sourceId && existing.bookUrl == b.bookUrl
    // 书源身份跟进度绑在一起裁：章号只在记下它的那个源的目录里有意义，不能拿 A 源的章号配 B 源的目录。
    // 两边源不同时 readAt 新的一边整体胜出，打平随元数据（理由见 BackupMerger.mergeBook）
    val takePosition = if (sameSource) {
        b.readAt > existing.readAt
    } else {
        b.readAt > existing.readAt || (b.readAt == existing.readAt && takeMeta)
    }
    val sourceChanged = existing.type == BookType.NET && !sameSource && takePosition
    val deletedRemotely = b.deleted && !existing.deleted && b.updatedAt > existing.updatedAt
    // 同一个源时 tocUrl 照旧随元数据；不同源时整套地址都跟着进度那一边
    val identityFromRemote = if (sameSource) takeMeta else takePosition

    val meta = if (takeMeta) {
        existing.copy(
            title = b.title, author = b.author, intro = b.intro,
            updatedAt = b.updatedAt,
            groupName = b.groupName, hidden = b.hidden,
            deleted = b.deleted,
            // 备份里的都是真正上过架的书（或墓碑）。本地若还是试读行，
            // 被远端一份活书盖过来时必须上架，否则书架上永远看不见。
            inShelf = !b.deleted && !b.removedFromShelf,
            removedFromShelf = b.removedFromShelf,
        )
    } else {
        existing
    }
    val entity = meta.copy(
        sourceId = if (identityFromRemote) b.sourceId else existing.sourceId,
        bookUrl = if (identityFromRemote) b.bookUrl else existing.bookUrl,
        tocUrl = if (identityFromRemote) b.tocUrl else existing.tocUrl,
        // 只补空，不覆盖：本地书这一列是有效的本机文件路径，
        // 而远端对本地书永远给 null —— 拿 null 盖上去等于把封面弄丢。
        coverPath = existing.coverPath ?: remoteCover(b),
        totalChapters = when {
            sameSource -> maxOf(existing.totalChapters, b.totalChapters)
            // 换了源就按新源的章节数来，旧源的更大值对新目录没有意义
            takePosition -> b.totalChapters
            // 反过来也一样：留在本机这个源上，就别拿另一个源的章数来比大小
            else -> existing.totalChapters
        },
        readChapterIndex = if (takePosition) b.readChapterIndex else existing.readChapterIndex,
        readCharOffset = if (takePosition) b.readCharOffset else existing.readCharOffset,
        readAt = if (takePosition) b.readAt else existing.readAt,
    )
    return SyncedBook(entity, sourceChanged, deletedRemotely)
}

/** 写事务内重读后的规则裁决；null 表示最新本地行胜出、不必写库。 */
internal fun applySyncedRule(existing: ReplaceRuleEntity?, r: BackupReplaceRule): ReplaceRuleEntity? {
    if (existing != null && existing.updatedAt >= r.updatedAt) return null
    return ReplaceRuleEntity(
        id = r.id, name = r.name, pattern = r.pattern, replacement = r.replacement,
        isRegex = r.isRegex, scope = r.scope, bookId = r.bookId, enabled = r.enabled,
        sortOrder = r.sortOrder, updatedAt = r.updatedAt, deleted = r.deleted,
    )
}

/** 旧备份未携带的附属字段保留本地，显式空值则按远端更新。 */
internal fun applySyncedRss(
    existing: com.radium.inkwell.data.db.entity.RssSourceEntity?, r: BackupRssSource,
): com.radium.inkwell.data.db.entity.RssSourceEntity? {
    if (existing != null && existing.updatedAt > r.updatedAt) return null
    val incoming = if (existing == null) r else mergeBackupRssSource(existing.toBackupRssSource(), r)
    val base = existing ?: com.radium.inkwell.data.db.entity.RssSourceEntity(id = r.id, name = r.name, json = r.json)
    return base.copy(
        name = incoming.name, enabled = incoming.enabled, json = incoming.json, sourceJson = incoming.sourceJson,
        updatedAt = incoming.updatedAt, deleted = incoming.deleted,
        groupName = incoming.groupName ?: base.groupName, sortOrder = incoming.sortOrder ?: base.sortOrder,
        icon = incoming.icon ?: base.icon, converterVersion = incoming.converterVersion ?: base.converterVersion,
    ).takeUnless { it == existing }
}

private fun com.radium.inkwell.data.db.entity.RssSourceEntity.toBackupRssSource() = BackupRssSource(
    id = id, name = name, enabled = enabled, json = json, sourceJson = sourceJson,
    updatedAt = updatedAt, deleted = deleted, groupName = groupName, sortOrder = sortOrder,
    icon = icon, converterVersion = converterVersion,
)
