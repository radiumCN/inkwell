package com.radium.inkwell.data.repo

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.util.toHex
import com.radium.inkwell.data.db.entity.ChapterEntity
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 网络书正文磁盘缓存。
 *
 * 以**章节 URL** 而非序号为 key：站点在前面插入章节（公告章、防盗章）后目录整体后移，
 * 若按序号存取，第 5 章会读出旧的第 5 章正文 —— 缓存必须跟着章节走，而不是跟着槽位走。
 *
 * 行格式：普通段落直接一行；`H<级别>:` 标题；`IMG:` 图片 URL（历史遗留，读时跳过）；`---` 分隔符。
 * 标题与分隔符必须原样存回：分页器对它们的处理和普通段落不同（标题另一套字号、
 * 分隔符占 1 个字符偏移），丢掉会让「首次分页」与「读缓存后分页」的页边界和
 * charOffset 对不上，恢复阅读位置就会漂。
 */
class ChapterContentCache(private val root: File) {

    private val digest = ThreadLocal.withInitial { MessageDigest.getInstance("MD5") }
    private val touched = ConcurrentHashMap.newKeySet<String>()

    /**
     * 书 id 拼进路径之前先过一道：id 不全是本机生成的 —— WebDAV 同步来的书行出自备份文件，
     * 谁能写备份谁就能填（明文 http 下还有中间人）。`../books` 这样的 id 原样拼进去，
     * [clear] 的 deleteRecursively 就删到缓存根之外（整个导入书目录）。
     *
     * 本机生成的 id 只有 UUID（本地书）和 MD5 hex（网络书），原样用，老缓存目录不用搬；
     * 其余一律换成 `_` + MD5 —— 照常能存能取，只是落不到 [root] 之外。
     */
    private fun bookDir(bookId: String): File =
        File(root, if (SAFE_ID.matches(bookId)) bookId else "_" + key(bookId))

    /**
     * 只拼路径，不建目录。从前这里走 `dir().apply { mkdirs() }`：has() / read() 每问一章都要 mkdirs 一次，
     * 追更逐章算 isCached 时还给没缓存过的书凭空建出空目录。目录只在 [write] 时建。
     */
    private fun file(bookId: String, chapterUrl: String): File =
        File(bookDir(bookId), key(chapterUrl) + ".txt")

    private fun key(chapterUrl: String): String {
        val md = checkNotNull(digest.get())
        md.reset()
        return md.digest(chapterUrl.toByteArray()).toHex()
    }

    fun has(bookId: String, chapterUrl: String): Boolean = file(bookId, chapterUrl).isFile

    /** 这本书有没有任何正文缓存。全书架清理先问这一句，没有就不必去读它几千行的目录 */
    fun hasAny(bookId: String): Boolean = bookDir(bookId).list()?.isNotEmpty() == true

    /**
     * 整本目录逐章问 [has] 的批量版：目录只列一次，之后每章算个 MD5 查集合。语义与 [has] 相同（文件在不在）。
     * 追更、加书架、换源都要给几千章各算一个 isCached —— 从前就是几千次文件 stat。
     */
    fun cachedChecker(bookId: String): (String) -> Boolean {
        val names = bookDir(bookId).list()?.toHashSet() ?: return { false }
        return { url -> (key(url) + ".txt") in names }
    }

    /**
     * 有缓存、且格式版本对得上（[read] 不会当它没缓存）。只读首行，不解析正文。
     *
     * 预取问「这章还要不要抓」用它。从前拿 [read] 来问：命中也是整章读进来、切行、建元素表再扔掉，
     * 每到章首章尾都要把后面几章这样过一遍。只看 [has] 又不够 —— 旧版本格式的文件在，
     * read 却当没缓存，预取就该照抓。
     */
    fun hasCurrent(bookId: String, chapterUrl: String): Boolean {
        val f = file(bookId, chapterUrl)
        if (!f.isFile) return false
        return runCatching { f.bufferedReader().use { it.readLine() } == FORMAT_VERSION }
            .getOrDefault(false)
    }

    fun read(bookId: String, chapterUrl: String): ChapterContent? {
        val f = file(bookId, chapterUrl)
        if (!f.isFile) return null
        val lines = f.readLines()
        // 版本对不上就当没缓存，逼它重抓一遍。
        // 这里存的是**解析结果**而不是原始 HTML —— 解析器修好了，磁盘上那份旧结果并不会
        // 跟着变好。没有这道校验的话，用户已经读过（也就是最想看到修复效果）的章节
        // 会永远停在旧解析上，升级完一看一模一样。见 [FORMAT_VERSION]。
        if (lines.firstOrNull() != FORMAT_VERSION) return null
        val elements = lines.drop(1).mapNotNull { line ->
            when {
                // 转义过的普通段落：它本来长得像标记行（H1:/IMG:/---），存时加了前缀，这里先剥掉。
                // 必须排在其它分支之前，否则又会被当成标记。
                line.startsWith(ESCAPE) -> ContentElement.Paragraph(line.substring(ESCAPE.length))
                line.isBlank() -> null
                line == DIVIDER -> ContentElement.Divider
                // 产品只排文本：解析器早已不再产出图片元素，这一行只会来自很老的缓存。
                // 读回成 Image 的话分页器会给它留一个 4:3 的灰色占位框（翻页模式不折叠），
                // 而图永远不会加载 —— 直接跳过。写侧的 IMG_PREFIX 分支保留只为 when 穷尽。
                line.startsWith(IMG_PREFIX) -> null
                // 标题只认 `H<数字>:`。从前是「H 开头且含冒号」，会把 "He said: ..." 这类
                // 英文段落误判成标题、冒号前整段丢失。
                HEAD_REGEX.matchAt(line, 0) != null -> ContentElement.Heading(
                    level = line.getOrNull(HEAD_PREFIX.length)?.digitToIntOrNull() ?: 1,
                    text = line.substringAfter(':'),
                )
                else -> ContentElement.Paragraph(line)
            }
        }
        // 命中即续命：自动清理按 lastModified 算「多久没碰过」。回看旧章时必须刷新，
        // 否则「刚翻回去看」也会被当成过期缓存下一进书就删掉。
        // 同一进程里同一文件只 touch 一次：预取连读会把闪存写砸在翻页动画上。
        if (touched.add(f.absolutePath)) {
            f.setLastModified(System.currentTimeMillis())
        }
        return ChapterContent(elements)
    }

    /** 段落文本恰好长得像标记行时要转义，否则读回会被误解析 */
    private fun escapeIfAmbiguous(text: String): String {
        val ambiguous = text == DIVIDER ||
            text.startsWith(IMG_PREFIX) ||
            text.startsWith(ESCAPE) ||
            HEAD_REGEX.matchAt(text, 0) != null
        return if (ambiguous) ESCAPE + text else text
    }

    fun write(bookId: String, chapterUrl: String, content: ChapterContent) {
        val body = content.elements.joinToString("\n") { el ->
            when (el) {
                is ContentElement.Paragraph -> escapeIfAmbiguous(el.text.replace('\n', ' '))
                is ContentElement.Heading -> "$HEAD_PREFIX${el.level}:${el.text.replace('\n', ' ')}"
                is ContentElement.Image -> IMG_PREFIX + el.resourceId
                ContentElement.Divider -> DIVIDER
            }
        }
        // 文件名不变（仍是 MD5(url)），所以重抓时是**覆盖**同名文件 —— 旧版本的内容
        // 自然被替换掉，不会在磁盘上留一份读不到又删不掉的垃圾
        val text = FORMAT_VERSION + "\n" + body
        // 先写临时文件再原子改名：进程被杀留下的半截文件不会被 has()/read() 当成有效缓存，
        // 两个协程（前台加载 + 后台预取）同抓同章时也是「整文件替换」，不会交错出半截内容。
        val target = file(bookId, chapterUrl)
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile("tmp", ".part", target.parentFile)
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) target.writeText(text)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** 全部正文缓存占用的字节数（设置页要显示，用户得知道清的是多少东西） */
    fun sizeBytes(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 清空所有书的正文缓存。目录与阅读进度不受影响，只是下次读要重新联网抓 */
    fun clearAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun clear(bookId: String) {
        bookDir(bookId).deleteRecursively()
    }

    /** 删掉早先按序号命名的缓存（`0.txt`）：目录一变它们就名不副实，且再也不会被读到 */
    fun purgeLegacy(bookId: String) {
        bookDir(bookId).listFiles()
            ?.filter { it.nameWithoutExtension.toIntOrNull() != null }
            ?.forEach { it.delete() }
    }

    /**
     * 清掉「进度之前」且超过 [maxAgeMs] 未碰过的正文缓存。
     *
     * 当前章与其后（含预取）一律保留 —— 用户可能离线往后翻，或刚预取完还没用。
     * [chapterUrls] 按下标对齐目录；返回被删章节的下标（孤儿文件无下标，不进列表）。
     *
     * 目录换过后对不上任何现 URL 的 `.txt` 算孤儿：同样超龄才删，避免误伤刚写入、
     * 但 TOC 尚未刷新到的竞态窗口。
     */
    fun pruneBehindProgress(
        bookId: String,
        readChapterIndex: Int,
        chapterUrls: List<String?>,
        maxAgeMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Int> {
        if (maxAgeMs <= 0L) return emptyList()
        val cutoff = nowMs - maxAgeMs
        val bookDir = bookDir(bookId)
        if (!bookDir.isDirectory) return emptyList()

        val liveKeys = chapterUrls.mapNotNullTo(HashSet()) { url ->
            url?.takeIf { it.isNotBlank() }?.let { key(it) }
        }
        val pruned = ArrayList<Int>()

        // 进度为 0（还在第一章）时没有「进度之前」的章，跳过；孤儿仍要清。
        if (readChapterIndex > 0) {
            for (index in 0 until readChapterIndex.coerceAtMost(chapterUrls.size)) {
                val url = chapterUrls.getOrNull(index)?.takeIf { it.isNotBlank() } ?: continue
                val f = file(bookId, url)
                if (!f.isFile) continue
                if (f.lastModified() > cutoff) continue
                if (f.delete()) pruned.add(index)
            }
        }

        bookDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == "txt" }
            ?.filter { it.nameWithoutExtension !in liveKeys }
            // 旧按序号命名的残留也算孤儿；purgeLegacy 仍可另清，这里按年龄兜底
            ?.filter { it.lastModified() <= cutoff }
            ?.forEach { it.delete() }

        return pruned
    }

    /**
     * 删掉库里已经没有的书留下的整本缓存目录，返回删了几本。
     *
     * 删书、丢弃试读、同步来的远端删除都会顺手清缓存，可总有漏网的：老版本删书不清、清到一半进程被杀、
     * 书行被别的路径删掉……这些目录没有任何入口能再读到，只会一直占着空间。
     * 留 [graceMs] 的余地：刚开始加的书可能还没落库，别跟它抢。
     */
    fun pruneOrphanBooks(liveBookIds: Collection<String>, graceMs: Long, nowMs: Long = System.currentTimeMillis()): Int {
        val live = liveBookIds.mapTo(HashSet()) { bookDir(it).name }
        val cutoff = nowMs - graceMs
        var removed = 0
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory || dir.name in live) return@forEach
            // 目录自己的 mtime 只随增删文件变；里面最近碰过的那个文件才说明它还有没有人用
            val newest = dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: dir.lastModified()
            if (newest <= cutoff && dir.deleteRecursively()) removed++
        }
        return removed
    }

    private companion object {
        /**
         * 缓存格式 / 解析器版本。**任何会改变正文解析结果的改动都必须 +1。**
         *
         * 范围是**整条解析链**，不只是某一个类：书源规则求值（LegadoSelector 的 text/textNodes/
         * html 等提取器）、HtmlToElements 的切段、以及本文件的行格式 —— 任何一环改了输出，都得 +1。
         *
         * 缓存里躺的是解析好的段落，不是原始 HTML。不 +1 的话，用户**已经读过**的章节
         * （恰恰是最想看到修复效果的那些）会永远停在旧解析结果上，改了解析器也白改。
         *
         * v2：修好「靠原始换行分段的源整章挤成一坨」时才发现这件事 —— 当时没有版本号，
         * 用户升级后一看一模一样，因为坏结果早就缓存在磁盘上了。
         * v3：@textNodes 从前用 text()+trim() 取文本，换行被压成空格、段首全角缩进被剥掉，
         * 段落结构在规则求值阶段就没了。改动的是 LegadoSelector 而不是 HtmlToElements ——
         * 正是「范围是整条链」这句话要拦住的情况。
         * v4：HtmlToElements 不再把 img / image 收成正文元素（分页空框、滚动半页空白）。
         *
         * 用 [ESCAPE] 打头，保证撞不上任何正文：真以 ESCAPE 开头的段落会被
         * escapeIfAmbiguous 再加一层前缀。老缓存没有这一行，读时自然对不上、当没缓存。
         */
        const val FORMAT_VERSION = "\u0001V4"

        const val IMG_PREFIX = "IMG:"
        const val HEAD_PREFIX = "H"
        const val DIVIDER = "---"
        /** 转义前缀：正常正文里不会出现的控制字符，老缓存里也没有，兼容旧文件 */
        const val ESCAPE = "\u0001"
        /** 标题行：`H` + 一或多位数字 + `:` */
        val HEAD_REGEX = Regex("H\\d+:")
        /** 能直接当目录名的书 id：本机生成的 UUID / MD5 hex 都落在这里面，不含 `.` 与路径分隔符 */
        val SAFE_ID = Regex("[A-Za-z0-9-]+")
    }
}

/** 目录行 → 按章号对齐的地址表，喂给 [ChapterContentCache.pruneBehindProgress]。目录偶有空洞时不能用 list 位置冒充 chapter.index */
fun chapterUrlsByIndex(chapters: List<ChapterEntity>): List<String?> {
    val maxIndex = chapters.maxOfOrNull { it.index } ?: return emptyList()
    val urls = MutableList<String?>(maxIndex + 1) { null }
    for (c in chapters) {
        if (c.index in urls.indices) urls[c.index] = c.url
    }
    return urls
}
