package com.radium.inkwell.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PurifyRuleImportTest {

    @Test
    fun `阅读新版导出：一组规则，字段照搬`() {
        val rules = PurifyRuleImport.parse(
            """
            [
              {"id":1700000000001,"name":"去「天才一秒记住」","group":"广告","pattern":"天才一秒记住.*?最快更新",
               "replacement":"","scope":"","scopeTitle":false,"scopeContent":true,"excludeScope":"",
               "isEnabled":true,"isRegex":true,"timeoutMillisecond":3000,"order":1},
              {"id":1700000000002,"name":"屏蔽词还原","pattern":"ji","replacement":"鸡","isRegex":false,
               "isEnabled":false,"scope":"某某文学"}
            ]
            """.trimIndent(),
        )
        assertEquals(2, rules.size)
        with(rules[0]) {
            assertEquals("legado:1700000000001", id)
            assertEquals("去「天才一秒记住」", name)
            assertEquals("天才一秒记住.*?最快更新", pattern)
            assertEquals("", replacement)
            assertTrue(isRegex)
            assertTrue(enabled)
            assertNull(unsupported)
        }
        with(rules[1]) {
            assertEquals("鸡", replacement)
            assertFalse(isRegex)
            // 阅读里关着的，导进来也关着
            assertFalse(enabled)
            assertEquals("某某文学", scope)
        }
    }

    @Test
    fun `阅读新版缺省：正则、启用`() {
        val r = PurifyRuleImport.parse("""{"id":5,"pattern":"广告\\d+"}""").single()
        assertTrue(r.isRegex)
        assertTrue(r.enabled)
        // 没名字拿内容开头顶上，同手建规则
        assertEquals("广告\\d+", r.name)
    }

    @Test
    fun `阅读旧版字段也认，缺省是关`() {
        val r = PurifyRuleImport.parse(
            """[{"id":9,"regex":"本章未完.*","replaceSummary":"去本章未完","replacement":"","useTo":"笔趣","serialNumber":3}]""",
        ).single()
        assertEquals("本章未完.*", r.pattern)
        assertEquals("去本章未完", r.name)
        assertEquals("笔趣", r.scope)
        assertFalse(r.isRegex)
        assertFalse(r.enabled)
    }

    @Test
    fun `Inkwell 备份：带 replaceRules 的对象，id 原样，墓碑不复活`() {
        val rules = PurifyRuleImport.parse(
            """
            {"version":3,"replaceRules":[
              {"id":"seed:去掉网址","name":"去掉网址","pattern":"www\\.\\w+\\.com","enabled":true,"isRegex":true},
              {"id":"0b6c…","name":"删掉的","pattern":"x","deleted":true}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("seed:去掉网址"), rules.map { it.id })
    }

    @Test
    fun `同一个 id 出现两次取后一条`() {
        val r = PurifyRuleImport.parse("""[{"id":1,"pattern":"a"},{"id":1,"pattern":"b"}]""").single()
        assertEquals("b", r.pattern)
    }

    @Test
    fun `没有 id 的按内容算，导两次是同一个 id`() {
        val a = PurifyRuleImport.parse("""{"pattern":"广告","isRegex":false}""").single()
        val b = PurifyRuleImport.parse("""{"pattern":"广告","isRegex":false}""").single()
        val c = PurifyRuleImport.parse("""{"pattern":"广告","isRegex":false,"replacement":"x"}""").single()
        assertEquals(a.id, b.id)
        assertNotEquals(a.id, c.id)
        assertTrue(a.id.startsWith("legado:"))
    }

    @Test
    fun `导不了的照样列出来，带原因`() {
        val rules = PurifyRuleImport.parse(
            """
            [
              {"id":1,"name":"章节名去括号","pattern":"\\(.*\\)","scopeTitle":true,"scopeContent":false},
              {"id":2,"name":"脚本","pattern":"a","replacement":"@js:result.replace('a','b')"},
              {"id":3,"name":"坏正则","pattern":"(abc","isRegex":true},
              {"id":4,"name":"坏正则但按文本","pattern":"(abc","isRegex":false}
            ]
            """.trimIndent(),
        )
        assertEquals("只改章节名的规则，暂不支持", rules[0].unsupported)
        assertEquals("替换内容是脚本，暂不支持", rules[1].unsupported)
        assertEquals("正则写法有误", rules[2].unsupported)
        assertNull(rules[3].unsupported)
    }

    @Test
    fun `带 BOM 的文件照样读`() {
        assertEquals(1, PurifyRuleImport.parse("\uFEFF[{\"id\":1,\"pattern\":\"a\"}]").size)
    }

    @Test
    fun `不是净化规则就报错`() {
        assertFailsWith<PurifyImportException> { PurifyRuleImport.parse("") }
        assertFailsWith<PurifyImportException> { PurifyRuleImport.parse("<html>…</html>") }
        // 误选了来源文件：是 JSON，但一条规则都没有
        assertFailsWith<PurifyImportException> {
            PurifyRuleImport.parse("""[{"bookSourceUrl":"https://a.com","bookSourceName":"甲"}]""")
        }
    }

    @Test
    fun `阅读的分享链接拆出真正的地址`() {
        assertEquals(
            "https://example.com/rules.json",
            PurifyRuleImport.unwrapAppLink("legado://import/replaceRule?src=https%3A%2F%2Fexample.com%2Frules.json"),
        )
        assertEquals(
            "https://example.com/r.json",
            PurifyRuleImport.unwrapAppLink("yuedu://replace/importonline?src=https://example.com/r.json"),
        )
        assertEquals("https://example.com/r.json", PurifyRuleImport.unwrapAppLink("  https://example.com/r.json "))
    }
}

class PurifyScopeTest {

    @Test
    fun `空范围处处生效`() {
        assertTrue(PurifyScope.matches("", null, null, "雾港"))
        assertTrue(PurifyScope.matches(" , ", "https://a.com", "甲", ""))
    }

    @Test
    fun `来源的地址或名字包含其中一段就生效`() {
        assertTrue(PurifyScope.matches("笔趣，乙", "https://www.biquge.com", "笔趣阁", "雾港"))
        assertTrue(PurifyScope.matches("biquge.com", "https://www.biquge.com", "笔趣阁", "雾港"))
        assertFalse(PurifyScope.matches("顶点", "https://www.biquge.com", "笔趣阁", "雾港"))
    }

    @Test
    fun `写书名的规则只管那本书，书名号、空白不计`() {
        assertTrue(PurifyScope.matches("《雾港》;星河渡", null, null, "雾港"))
        assertTrue(PurifyScope.matches("星河渡", "https://a.com", "甲", "星河 渡"))
        assertFalse(PurifyScope.matches("雾港", null, null, "雾港旧事"))
    }

    @Test
    fun `本地书没有来源，写来源的规则不生效`() {
        assertFalse(PurifyScope.matches("笔趣", null, null, "雾港"))
    }
}
