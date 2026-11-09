package com.radium.inkwell.ui.bookshelf

import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.repo.TitleMatch

/**
 * 书架上的「同一本书有好几份」。
 *
 * 重复是这么来的，所以「已在书架」那道闸（逐字比书名作者）挡不住：
 * - 隐藏区上了锁时，搜索 / 预览刻意不认隐藏的书（见 [searchableShelf]），同一本又能加一份 —— 最常见的一种；
 * - 书源之间写法不同：「《三国演义》」「罗贯中 著」「作者：罗贯中」，逐字比认不出；
 * - 两台设备各自从不同书源加了同一本，WebDAV 按 id 合并，两份都留着。
 *
 * 一组里的书按「默认留哪一份」排好：[DuplicateGroup.books] 的第一本就是默认留下的。
 */
data class DuplicateGroup(
    /**
     * 这一组的身份：书的类别 + 归一化书名 + 归一化作者。**不含书的 id** —— 面板里拨「包含隐藏的书」，
     * 一组可能多进来一本隐藏的，key 不变，用户已经挑好的「留哪一份」、点过的「都保留」才接得上。
     */
    val key: String,
    /** 小标题用的书名 / 作者：取组里第一本（默认留下的那份）的写法 */
    val title: String,
    val author: String,
    val books: List<BookEntity>,
)

/**
 * 找出书架上的重复。[books] 是书架上的书（已排除墓碑、试读未上架，即 BookDao.observeAll 那一份），
 * 隐藏的算不算由调用方先筛好 —— 这里不管隐藏区，见 [shelfDuplicates]。
 *
 * 怎么算「同一本」：
 * - **书名**归一化后相等（去书名号与空白，[TitleMatch.normalize]）。不用换源那套「包含」：
 *   换源是在一本书的候选里认「精校版」，这里是在整个书架上两两比，「斗罗大陆」和「斗罗大陆 2」会被认成一本。
 * - **作者**去掉「作者：」「著」这类修饰后相等或互相包含，任一边没写作者也算（同 [TitleMatch.authorMatches]）。
 * - **只比同类**：网络书和网络书比，本地书和本地书比。同一本既有网络版、又导了一份本地文件，是有意留的备份
 *   （本地导入那边就只挡同名的本地书，见 BookRepository.shelfLocalBookIdByKey），不算重复。
 *
 * 组的顺序：最近读过的组在前（书架本来就是这个顺序，人刚读过的那本最容易想起来）。
 */
fun findDuplicates(books: List<BookEntity>): List<DuplicateGroup> {
    val buckets = LinkedHashMap<String, MutableList<BookEntity>>()
    for (b in books) {
        val title = TitleMatch.normalize(b.title).lowercase()
        if (title.isEmpty()) continue
        buckets.getOrPut(kindOf(b) + "\u0000" + title) { mutableListOf() }.add(b)
    }
    val groups = buildList {
        for ((bucketKey, list) in buckets) {
            if (list.size < 2) continue
            for (cluster in clusterByAuthor(list)) {
                if (cluster.size < 2) continue
                val sorted = cluster.sortedWith(KEEP_ORDER)
                val first = sorted.first()
                // 作者取组里写了的那个（第一本可能恰好没写）；key 用它的归一化写法
                val author = sorted.firstOrNull { normalizeAuthor(it.author).isNotEmpty() }?.author.orEmpty()
                add(
                    DuplicateGroup(
                        key = bucketKey + "\u0000" + normalizeAuthor(author),
                        title = first.title.trim(),
                        author = cleanAuthor(author),
                        books = sorted,
                    ),
                )
            }
        }
    }
    return groups.sortedByDescending { g -> g.books.maxOf { it.lastActiveAt() } }
}

/**
 * 按隐藏区的状态取要比的书。隐藏区收着时，隐藏的书**一本都不进来** —— 不只是不列出来：
 * 一本看得见的和一本藏着的重复，提示「有 1 组重复」就等于替旁人说「这儿还藏着一本」。
 * [includeHidden] 只在隐藏区展开时才可能为 true（「包含隐藏的书」开关）。
 */
fun shelfDuplicates(books: List<BookEntity>, includeHidden: Boolean): List<DuplicateGroup> =
    findDuplicates(if (includeHidden) books else books.filter { !it.hidden })

/** 书架上要不要亮提示：有一组里出现了没被 ✕ 掉、也没被「都保留」过的书 */
fun hasFreshDuplicates(groups: List<DuplicateGroup>, dismissedIds: Set<String>): Boolean =
    groups.any { g -> g.books.any { it.id !in dismissedIds } }

/**
 * 默认留哪一份：读得最远的（章号、章内位置）；一样远看最近读的；都没读过留先加的那份。
 *
 * 不按「最近读」排第一：最常见的重复是「藏着一本读到第 40 回、上锁时又加了一本、点开看了一眼」——
 * 最近读的恰恰是刚加的那份，留它就把真正的进度删掉了。
 */
private val KEEP_ORDER: Comparator<BookEntity> = compareByDescending<BookEntity> { it.readChapterIndex }
    .thenByDescending { it.readCharOffset }
    .thenByDescending { it.lastActiveAt() }
    .thenBy { it.addedAt }

/** 网络书一类，本地书（txt / epub / mobi）一类 */
private fun kindOf(b: BookEntity): String = if (b.type == BookType.NET) "net" else "local"

/**
 * 同一个书名下按作者分堆：能对上的并成一组（并查集，传递地并 —— A 没写作者、B 和 C 写了同一个，三本一组）。
 * 一个书名下通常就两三本，两两比没有开销。
 */
private fun clusterByAuthor(list: List<BookEntity>): List<List<BookEntity>> {
    val parent = IntArray(list.size) { it }
    fun find(i: Int): Int {
        var x = i
        while (parent[x] != x) x = parent[x]
        return x
    }
    val authors = list.map { normalizeAuthor(it.author) }
    for (i in list.indices) for (j in i + 1 until list.size) {
        if (authorsMatch(authors[i], authors[j])) parent[find(i)] = find(j)
    }
    return list.indices.groupBy(::find).values.map { idx -> idx.map(list::get) }
}

private fun authorsMatch(a: String, b: String): Boolean =
    a.isEmpty() || b.isEmpty() || a == b || a.contains(b) || b.contains(a)

/** 作者的修饰：「作者：」「作者:」开头、「著」「编著」结尾、各种空白 */
private val AUTHOR_PREFIX = Regex("^(作者|作\\s*者)\\s*[:：]\\s*")
private val AUTHOR_SUFFIX = Regex("\\s*(编著|著)$")
private val WHITESPACE = Regex("\\s+")

/** 给人看的作者：去掉「作者：」「著」，留原来的空格 */
internal fun cleanAuthor(raw: String): String =
    raw.trim().replace(AUTHOR_PREFIX, "").replace(AUTHOR_SUFFIX, "").trim()

/** 拿来比的作者：在 [cleanAuthor] 之上再去掉所有空白、统一大小写 */
internal fun normalizeAuthor(raw: String): String =
    cleanAuthor(raw).replace(WHITESPACE, "").lowercase()

/**
 * 整理的结果：一组里留 [keepId]，删 [removeIds]。
 * 留下的那份接什么（被删那份的分组、隐藏）由仓库在写库时按最新的行定（见 BookRepository.mergeDuplicates）。
 */
data class DuplicateMerge(val keepId: String, val removeIds: List<String>)

/**
 * 面板上的选择 → 要做的事。[keep] 是用户在每组挑的那份（没挑过的组用默认，即第一本），[skipped] 是点了「都保留」的组。
 * 挑的那份不在组里了（列表刷新、那本被别处删掉）也退回默认，别把整组删光。
 */
fun planMerges(
    groups: List<DuplicateGroup>,
    keep: Map<String, String>,
    skipped: Set<String>,
): List<DuplicateMerge> = groups.mapNotNull { g ->
    if (g.key in skipped) return@mapNotNull null
    val keepId = keep[g.key]?.takeIf { id -> g.books.any { it.id == id } } ?: g.books.first().id
    val remove = g.books.map { it.id }.filter { it != keepId }
    if (remove.isEmpty()) null else DuplicateMerge(keepId, remove)
}
