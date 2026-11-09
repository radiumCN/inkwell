package com.radium.inkwell.ui.bookshelf

import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 书架上「同一本书有好几份」怎么认、默认留哪份、面板上的选择怎么变成要删的书。
 *
 * 起因：隐藏区上锁时搜索刻意不认隐藏的书，同一本能再加一份；书源之间的书名作者写法又各不相同，
 * 「已在书架」逐字比挡不住 —— 书架上越攒越多一模一样的书。
 */
class ShelfDuplicatesTest {

    private fun book(
        id: String,
        title: String = "红楼梦",
        author: String = "曹雪芹",
        type: Int = BookType.NET,
        chapter: Int = 0,
        readAt: Long = 0,
        addedAt: Long = 0,
        hidden: Boolean = false,
    ) = BookEntity(
        id = id,
        type = type,
        title = title,
        author = author,
        sourceId = if (type == BookType.NET) "源$id" else null,
        bookUrl = if (type == BookType.NET) "/book/$id" else null,
        readChapterIndex = chapter,
        readAt = readAt,
        addedAt = addedAt,
        updatedAt = 0,
        hidden = hidden,
    )

    @Test
    fun `书名号、空白、作者修饰不一样 —— 照样认成一本`() {
        val groups = findDuplicates(
            listOf(
                book("a", title = "三国演义", author = "罗贯中"),
                book("b", title = "《三国演义》", author = "罗贯中 著"),
                book("c", title = "三国 演义", author = "作者：罗贯中"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(setOf("a", "b", "c"), groups.single().books.map { it.id }.toSet())
        // 小标题上的作者是去掉修饰的那个写法
        assertEquals("罗贯中", groups.single().author)
    }

    @Test
    fun `书名只是包含 —— 不算一本`() {
        // 换源那套「包含」在这里会把续集认成同一本
        val groups = findDuplicates(listOf(book("a", title = "斗罗大陆"), book("b", title = "斗罗大陆2")))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `同名不同作者 —— 不算一本；一边没写作者 —— 算`() {
        assertTrue(findDuplicates(listOf(book("a", author = "甲"), book("b", author = "乙"))).isEmpty())
        assertEquals(1, findDuplicates(listOf(book("a", author = "甲"), book("b", author = ""))).size)
    }

    @Test
    fun `同一本的网络版和本地文件 —— 是有意留的备份，不算`() {
        val groups = findDuplicates(
            listOf(book("net"), book("txt", type = BookType.LOCAL_TXT)),
        )
        assertTrue(groups.isEmpty())
        // 两份本地文件是重复
        val local = findDuplicates(
            listOf(book("t1", type = BookType.LOCAL_TXT), book("t2", type = BookType.LOCAL_EPUB)),
        )
        assertEquals(1, local.size)
    }

    @Test
    fun `默认留读得最远的那份，不是最近读的那份`() {
        // 藏着的那本读到第 40 回；上锁时又加了一本，今天点开看了一眼
        val groups = findDuplicates(
            listOf(
                book("new", chapter = 1, readAt = 200),
                book("old", chapter = 39, readAt = 100, hidden = true),
            ),
        )
        assertEquals("old", groups.single().books.first().id)
    }

    @Test
    fun `一样远看最近读的，都没读过留先加的`() {
        val sameChapter = findDuplicates(listOf(book("a", chapter = 3, readAt = 100), book("b", chapter = 3, readAt = 300)))
        assertEquals("b", sameChapter.single().books.first().id)
        val unread = findDuplicates(listOf(book("late", addedAt = 20), book("early", addedAt = 10)))
        assertEquals("early", unread.single().books.first().id)
    }

    @Test
    fun `隐藏区收着时隐藏的书一本都不进来`() {
        val shelf = listOf(book("visible"), book("hidden", hidden = true))
        // 收着：只有一本看得见，没有重复 —— 提示了就等于说「这儿还藏着一本」
        assertTrue(shelfDuplicates(shelf, includeHidden = false).isEmpty())
        assertEquals(1, shelfDuplicates(shelf, includeHidden = true).size)
    }

    @Test
    fun `组的 key 不随组里多进来一本隐藏的书而变`() {
        val visible = listOf(book("a"), book("b"))
        val withHidden = visible + book("h", hidden = true)
        assertEquals(
            shelfDuplicates(visible, includeHidden = false).single().key,
            shelfDuplicates(withHidden, includeHidden = true).single().key,
        )
    }

    @Test
    fun `✕ 掉的那批不再提示，出现新的一份才再提示`() {
        val groups = findDuplicates(listOf(book("a"), book("b")))
        assertTrue(hasFreshDuplicates(groups, emptySet()))
        assertFalse(hasFreshDuplicates(groups, setOf("a", "b")))
        val more = findDuplicates(listOf(book("a"), book("b"), book("c")))
        assertTrue(hasFreshDuplicates(more, setOf("a", "b")))
    }

    @Test
    fun `面板上的选择 —— 留挑的那份，「都保留」的组不动`() {
        val groups = findDuplicates(
            listOf(
                book("a", chapter = 5), book("b"),
                book("x", title = "西游记", author = "吴承恩", chapter = 5), book("y", title = "西游记", author = "吴承恩"),
            ),
        )
        val hongloumeng = groups.first { it.title == "红楼梦" }
        val xiyouji = groups.first { it.title == "西游记" }
        val merges = planMerges(groups, keep = mapOf(hongloumeng.key to "b"), skipped = setOf(xiyouji.key))
        assertEquals(listOf(DuplicateMerge(keepId = "b", removeIds = listOf("a"))), merges)
    }

    @Test
    fun `挑的那份已经不在组里 —— 退回默认，不把整组删光`() {
        val groups = findDuplicates(listOf(book("a", chapter = 5), book("b")))
        val merges = planMerges(groups, keep = mapOf(groups.single().key to "gone"), skipped = emptySet())
        assertEquals(listOf(DuplicateMerge(keepId = "a", removeIds = listOf("b"))), merges)
    }
}
