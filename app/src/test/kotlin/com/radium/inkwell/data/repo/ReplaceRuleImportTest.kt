package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.ImportedPurifyRule
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 导入面板上每条规则的状态。标错的代价：「已有」标成「新增」，导进来就是两条一样的规则各替换一遍；
 * 「更新」标成「已有」，订阅里改好的规则永远进不来。
 */
class ReplaceRuleImportTest {

    private fun imported(
        id: String,
        pattern: String = "广告.*",
        name: String = "去广告",
        unsupported: String? = null,
    ) = ImportedPurifyRule(
        id = id, name = name, pattern = pattern, replacement = "", isRegex = true, scope = "",
        enabled = true, unsupported = unsupported,
    )

    private fun existing(
        id: String,
        pattern: String = "广告.*",
        name: String = "去广告",
        bookId: String = "",
        deleted: Boolean = false,
    ) = ReplaceRuleEntity(id = id, name = name, pattern = pattern, bookId = bookId, deleted = deleted)

    private fun status(rule: ImportedPurifyRule, vararg rows: ReplaceRuleEntity) =
        ReplaceRuleRepository.classifyImport(listOf(rule), rows.toList()).single().status

    @Test
    fun `库里没有的是新增`() {
        assertEquals(RuleImportStatus.NEW, status(imported("legado:1")))
    }

    @Test
    fun `同一个 id、内容一样是已有，不一样是更新`() {
        assertEquals(RuleImportStatus.SAME, status(imported("legado:1"), existing("legado:1")))
        assertEquals(RuleImportStatus.UPDATE, status(imported("legado:1", pattern = "广告\\d+"), existing("legado:1")))
        // 改了个名字也算更新
        assertEquals(RuleImportStatus.UPDATE, status(imported("legado:1", name = "新名字"), existing("legado:1")))
    }

    @Test
    fun `手建过一模一样的（id 不同、名字不同）也是已有`() {
        assertEquals(RuleImportStatus.SAME, status(imported("legado:1", name = "阅读里的名字"), existing("uuid-x", name = "我的")))
    }

    @Test
    fun `只对某本书生效的同内容规则不算已有`() {
        // 那条只管一本书，导进来的是管所有书的，不是同一回事
        assertEquals(RuleImportStatus.NEW, status(imported("legado:1"), existing("uuid-x", bookId = "book-1")))
    }

    @Test
    fun `删掉的不算有，重新导是新增`() {
        assertEquals(RuleImportStatus.NEW, status(imported("legado:1"), existing("legado:1", deleted = true)))
    }

    @Test
    fun `导不了的就是不支持，勾不上`() {
        val item = ReplaceRuleRepository.classifyImport(
            listOf(imported("legado:1", unsupported = "只改章节名的规则，暂不支持")),
            emptyList(),
        ).single()
        assertEquals(RuleImportStatus.UNSUPPORTED, item.status)
        assertFalse(item.selectable)
    }

    @Test
    fun `新增和更新勾得上，已有勾不上`() {
        val items = ReplaceRuleRepository.classifyImport(
            listOf(imported("a"), imported("b", pattern = "x"), imported("c", pattern = "y")),
            listOf(existing("b", pattern = "old"), existing("c", pattern = "y")),
        )
        assertEquals(listOf(true, true, false), items.map { it.selectable })
    }
}

class FolderBooksTest {

    @Test
    fun `只认书的格式`() {
        assertEquals("TXT", FolderBooks.formatOf("雾港.txt"))
        assertEquals("EPUB", FolderBooks.formatOf("Fog Harbor.EPUB"))
        assertEquals("AZW3", FolderBooks.formatOf("听潮.azw3"))
        assertEquals("MOBI", FolderBooks.formatOf("a.mobi"))
        assertEquals(null, FolderBooks.formatOf("封面.jpg"))
        assertEquals(null, FolderBooks.formatOf("合集.zip"))
        assertEquals(null, FolderBooks.formatOf("README"))
        // 系统、同步盘留下的点文件不是书
        assertEquals(null, FolderBooks.formatOf("._雾港.txt"))
    }

    @Test
    fun `文件名和书名比：扩展名、书名号、空白、大小写不计`() {
        assertEquals(FolderBooks.titleKey("雾港"), FolderBooks.titleKey("《雾港》.txt"))
        assertEquals(FolderBooks.titleKey("Fog Harbor"), FolderBooks.titleKey("fog_harbor.epub"))
        assertTrue(FolderBooks.titleKey("雾港") != FolderBooks.titleKey("雾港旧事.txt"))
        // 只去掉认得的扩展名：书名里本来就带点的别切
        assertEquals("v20终章", FolderBooks.titleKey("v2.0 终章"))
    }
}
