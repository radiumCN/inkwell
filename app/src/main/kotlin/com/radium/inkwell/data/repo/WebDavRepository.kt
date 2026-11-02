package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.BookSourceTypes
import com.radium.inkwell.core.webdav.BackupBook
import com.radium.inkwell.core.webdav.BackupCodec
import com.radium.inkwell.core.webdav.BackupMerger
import com.radium.inkwell.core.webdav.BackupPayload
import com.radium.inkwell.core.webdav.BackupReplaceRule
import com.radium.inkwell.core.webdav.BackupRssSource
import com.radium.inkwell.core.webdav.BackupSource
import com.radium.inkwell.core.webdav.WebDavClient
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
import com.radium.inkwell.data.prefs.WebDavPrefs
import com.radium.inkwell.data.prefs.exportForBackup
import com.radium.inkwell.data.prefs.importFromBackup
import com.radium.inkwell.util.LocalNetworkAccess
import com.radium.inkwell.util.LocalNetworkPermissionMissing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException

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
    suspend fun sync(auto: Boolean = false): Result<String> = try {
        // 备份里装着每个书源的完整 JSON，几百个源就是几 MB：gzip、整份编解码、逐条解析书源类型
        // 都是重活，而设置页的「立即同步」是从 viewModelScope（主线程）调进来的
        withContext(Dispatchers.IO) {
            syncMutex.withLock { Result.success(syncLocked(auto)) }
        }
    } catch (e: CancellationException) {
        // 退到后台的自动同步被进程回收、或用户离开设置页 —— 这是取消，不是「同步失败」
        throw e
    } catch (e: Exception) {
        // 记下来给设置页看。记录本身出错（存储写不进去）不能盖掉真正的失败原因
        runCatching { prefs.markSyncFailed(e.message ?: e.javaClass.simpleName, System.currentTimeMillis()) }
        Result.failure(e)
    }

    private suspend fun syncLocked(auto: Boolean): String {
        val config = prefs.config.first()
        check(config.isConfigured) { "尚未配置 WebDAV" }
        val url = if (OfficialWebDav.isLegacyDav(config.url)) OfficialWebDav.DAV else config.url
        // 局域网 NAS 缺 Android 17 的局域网权限时，连接不报错、只会挂到超时 ——
        // 先拦下来，给一句看得懂的话（设置页据此弹授权）。后台自动同步也不必白等十几秒
        if (localNetwork.isBlocked(url)) throw LocalNetworkPermissionMissing()
        val client = WebDavClient(url, config.username, config.password, http)
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
                    return "已是最新"
                }
            }
        }

        // 先建目录再读：首次同步时 inkwell/ 还不存在，直接 GET 备份文件，
        // 坚果云对「父目录不存在」回的是 409 而不是 404 —— 同步就一次都成不了。
        // MKCOL 是幂等的（已存在返回 405，客户端当成功）。
        client.mkcol(DIR)

        // 远端版本在**下载之前**取：这一轮拿到的内容不会比它旧。从前是整轮同步走完再取 ——
        // 合并几千个书源的那几秒里，另一台设备要是传了一版，它的版本会被当成「这一轮已经同步过」记下来，
        // 之后的自动同步一直跳过，那一版（比如对面的阅读进度）永远拉不下来。
        // 下载前后之间若有人上传：这里记的是更旧的版本，下次一比对不上就照常完整同步，不丢东西。
        val remoteBefore = remoteVersion(client)

        // 远端文件损坏（上传中断留半截、被别的客户端写坏）时 decode 会抛异常。若让它把整个 sync
        // 拖垮，PUT 永远执行不到 → 损坏文件永远修不好、同步永久瘫痪。这里把损坏视作「远端没有可用备份」，
        // 照常用本地覆盖上去，下一次同步就自愈了。
        // 只认 Exception（gzip 截断、JSON 坏掉）：内存不够解不开**不是**文件坏了 —— 从前 runCatching 连 OOM
        // 一起吞，当成「远端没有」，随后就拿本地把别的设备的整份数据盖掉。那种情况让这次同步失败、远端原样留着。
        val remote = client.get(BACKUP)?.let { bytes ->
            try {
                BackupCodec.decode(bytes)
            } catch (e: OutOfMemoryError) {
                throw IOException("远端备份太大，本机内存不足以解析，本次同步已跳过", e)
            } catch (_: Exception) {
                null
            }
        }
        // 本地快照在下载**之后**再拍：备份动辄几 MB，冷启动同步下载的那几秒里用户删书、隐藏、
        // 改分组，按下载前的快照合并会把这些修改原样写回去（删掉的书复活）。
        // 拍完到写回之间仍有一小段合并耗时，那段由 applyBooks / applySources 按时间戳兜住。
        // 拍快照**之前**记下本地的「最新修改时间」，下次跳过的条件是它原样没变。
        // 不拿墙钟时刻当基线：时间戳盖在快照之前、却在快照之后才提交的写入（导入大合集时先取 now、
        // 好几秒后才落库，正赶上切到后台），既不在这次上传里、又不晚于基线 —— 会被永远当成已同步。
        // 按「相等」比，那一笔一落库最新时间就变了，下次照常同步。
        val localStamp = localChangeAt()
        val local = buildLocalPayload()

        val toUpload: BackupPayload
        var applied = 0
        if (remote == null) {
            toUpload = local
        } else {
            val merged = BackupMerger.merge(local, remote)
            // 书目按真正写了库的条数算：合并说「变了」的，按事务里的最新行可能一条都不采纳
            applied = applyToLocal(merged) +
                merged.changedSources.size +
                merged.changedReplaceRules.size +
                merged.changedRssSources.size +
                listOfNotNull(merged.readerSettings, merged.appSettings).size
            toUpload = local.copy(
                books = merged.books,
                sources = merged.sources,
                replaceRules = merged.replaceRules,
                rssSources = merged.rssSources,
                readerSettings = merged.mergedReaderSettings,
                appSettings = merged.mergedAppSettings,
                readingStats = merged.readingStats,
            )
        }

        // 直接 PUT 覆盖。半截文件的风险由上面的「decode 失败即当远端没有、直接覆盖自愈」兜底，
        // 不再用「PUT 临时名 + MOVE」——坚果云等服务器对 MOVE 回 409，反而把同步整个搞挂。
        // 合并完跟远端一模一样（这次只有远端的更新要拉下来，或两边本来就一致）就不必再传一遍
        val uploaded = remote == null || !sameContent(toUpload, remote)
        if (uploaded) {
            client.put(BACKUP, BackupCodec.encode(toUpload), contentType = "application/gzip")
        }
        prefs.markSynced(System.currentTimeMillis())
        // 传了就紧接着取新版本（与 PUT 之间只隔一个请求）；没传，远端就还是下载前的那一版
        val remoteAfter = if (uploaded) remoteVersion(client) else remoteBefore
        prefs.saveSyncMark(localStamp, remoteAfter?.let { account + it })
        return if (applied > 0) "同步完成，合并了 $applied 项远端更新" else "同步完成"
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

    /**
     * 两份备份内容是否一致，不看导出时间与设备号。按 id 比，不看列表顺序：
     * 远端那份是别的设备合并出来的，顺序跟这边从库里读出来的不一定一样。
     */
    private fun sameContent(a: BackupPayload, b: BackupPayload): Boolean =
        a.version == b.version &&
            a.books.associateBy { it.id } == b.books.associateBy { it.id } &&
            a.sources.associateBy { it.id } == b.sources.associateBy { it.id } &&
            a.replaceRules.associateBy { it.id } == b.replaceRules.associateBy { it.id } &&
            a.rssSources.associateBy { it.id } == b.rssSources.associateBy { it.id } &&
            a.readerSettings == b.readerSettings &&
            a.appSettings == b.appSettings &&
            a.readingStats.associateBy { it.key } == b.readingStats.associateBy { it.key }

    private suspend fun buildLocalPayload(): BackupPayload = BackupPayload(
        deviceId = prefs.deviceId(),
        exportedAt = System.currentTimeMillis(),
        // 试读未上架不进备份：从没成为用户书架上的书，同步出去只会在别的设备
        // 冒出一本看不见的书，或在用户点「不加入」之后变成凭空墓碑。
        // 墓碑仍要带走（inShelf 可能是 false —— 删过又试读过），合并靠它表达「删过」。
        books = bookDao.getAll().filter { it.inShelf || it.deleted }.map { b ->
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
        rssSources = rssDao.getAll().map { r ->
            BackupRssSource(
                id = r.id, name = r.name, enabled = r.enabled, json = r.json,
                sourceJson = r.sourceJson, updatedAt = r.updatedAt, deleted = r.deleted,
            )
        },
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
        // 别的设备删掉的书：本地删书（BookRepository.deleteBook）会顺手清掉导入的书文件、本地封面、
        // 正文缓存和目录行，同步过来的删除从前只打了墓碑 —— 这些东西就永远躺在磁盘上，没有任何入口能清。
        // 先记下要清的（事务里会改掉 deleted），目录行随事务删，文件 IO 放在事务之后
        val remotelyDeleted = merged.changedBooks.mapNotNull { b ->
            val existing = bookDao.getById(b.id) ?: return@mapNotNull null
            existing.takeIf { applySyncedBook(it, b).deletedRemotely }
        }

        // 几十条逐行写 + 三张表：中途被杀不留半套。远端设置的导入是 DataStore，在事务外。
        val appliedBooks = db.withWriteTransaction {
            val n = applyBooks(merged)
            applySources(merged)
            replaceRules.upsertAll(
                merged.changedReplaceRules.map { r ->
                    ReplaceRuleEntity(
                        id = r.id, name = r.name, pattern = r.pattern, replacement = r.replacement,
                        isRegex = r.isRegex, scope = r.scope, bookId = r.bookId, enabled = r.enabled,
                        sortOrder = r.sortOrder, updatedAt = r.updatedAt, deleted = r.deleted,
                    )
                }
            )
            rssDao.upsertAll(
                merged.changedRssSources.map { r ->
                    com.radium.inkwell.data.db.entity.RssSourceEntity(
                        id = r.id, name = r.name, enabled = r.enabled, json = r.json,
                        sourceJson = r.sourceJson, updatedAt = r.updatedAt, deleted = r.deleted,
                    )
                }
            )
            n
        }
        if (remotelyDeleted.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                // 和本地删书同一条路：路径限定在应用自己的目录里，同步来的行改不了删哪
                remotelyDeleted.forEach { bookRepo.deleteLocalFiles(it) }
            }
        }
        merged.readerSettings?.let { readerPrefs.importFromBackup(it) }
        // 别的设备的阅读时长、本机清数据前记下的时长。不计入「合并了几项更新」：那是给人看书架变化的
        readingStats.applySynced(merged.changedReadingStats)
        merged.appSettings?.let { appPrefs.importFromBackup(it) }
        return appliedBooks
    }

    /** @return 真正写了库的书目数。合并结果里「变了」的条目，到了事务里按最新行可能一条都不采纳 */
    private suspend fun applyBooks(merged: BackupMerger.MergeResult): Int {
        var applied = 0
        merged.changedBooks.forEach { b ->
            val existing = bookDao.getById(b.id)
            if (existing != null) {
                // 拿事务里的最新行裁决：本地在拍快照之后又改过（时间戳已经比合并结果新），那一块就保留本地的
                val synced = applySyncedBook(existing, b)
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
                        inShelf = !b.deleted,
                    )
                )
                applied++
            }
        }
        return applied
    }

    private suspend fun applySources(merged: BackupMerger.MergeResult) {
        merged.changedSources.forEach { s ->
            // 与 importJson 一致：远端若带了漫画/听书/视频源，别写进只跑小说引擎的本地库
            if (!isTextNovelSourceJson(s.json)) return@forEach
            // 校验结果（checkStatus/checkMessage/respondTime/checkedAt）是本地专属、不跨设备同步的列 ——
            // 整行 REPLACE 会把它们清空。读旧行、只覆盖同步字段（含分组），本地校验结果原样保留。
            val existing = sourceDao.getById(s.id)
            // 同 applyBooks：本地在拍快照之后又改过（启停/分组/删除），保留本地的
            if (existing != null && s.updatedAt <= existing.updatedAt) return@forEach
            sourceDao.upsert(
                (existing ?: BookSourceEntity(id = s.id, name = s.name, json = s.json, updatedAt = s.updatedAt))
                    .copy(
                        name = s.name, enabled = s.enabled, sortOrder = s.sortOrder,
                        json = s.json, updatedAt = s.updatedAt, groupName = s.groupName,
                        deleted = s.deleted,
                    )
            )
        }
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
    val previewOnly = !existing.inShelf && !existing.deleted && !b.deleted
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
            inShelf = !b.deleted,
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
