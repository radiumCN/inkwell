package com.radium.inkwell.core.source

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.model.ChapterContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 净化器的**时间上限**。
 *
 * 这里测的不是"净化对不对"，而是"净化会不会永远不返回"。区别很要紧：Java 正则的灾难性回溯
 * 不抛异常、不响应线程中断，`runCatching` 接不住它，`withTimeout` 也打断不了它 ——
 * 它就是不返回。而净化是在 `ReaderViewModel` 的 engineMutex 里跑的，一旦空转，
 * 锁死的是此后**所有**章节的加载，不只当前这一章。
 *
 * 所以下面的用例必须用真正会指数级回溯的规则来跑：如果保护失效，测试的表现是**挂住**
 * （而不是断言失败）—— 这正是它要防的那个症状。
 *
 * **选这条规则是实测出来的，别随手换成教科书上那些。** JDK 21 已经把经典例子
 * （`(a+)+$`、`(x+x+)+y`、`(a*)*b`、`^(\w+\s?)*$`）全优化掉了，实测一律 0~2ms 返回 ——
 * 拿它们当用例，测试会绿得毫无意义（保护根本没被触发过）。下面这条实测仍是指数级：
 * 输入每加 4 个字符耗时约 ×8（n=22→282ms，n=26→2278ms）。
 */
class PurifierTest {

    @Test
    fun multilineRulesRemapAnchorsAcrossRemovedAndInsertedParagraphs() {
        fun p(text: String) = ContentElement.Paragraph(text)
        val image = ContentElement.Image("pic")
        val input = ChapterContent(
            listOf(p("甲"), p("广告上"), p("广告下"), p("同名"), image, p("同名")),
            mapOf("first" to 0, "ad" to 1, "second" to 3, "image" to 4, "last" to 5, "end" to 6),
        )
        val rules = listOf(
            PurifyRule("广告上\\n广告下\\n", ""),
            PurifyRule("(甲)", "\$1\n乙\n丙"),
        )
        val output = Purifier.strict(rules).apply(input)
        assertEquals(listOf(p("甲"), p("乙"), p("丙"), p("同名"), image, p("同名")), output.elements)
        assertEquals(mapOf("first" to 0, "ad" to 3, "second" to 3, "image" to 4, "last" to 5, "end" to 6), output.anchors)
    }

    @Test
    fun failedMultilineReplacementPreservesPositionsForSubsequentRules() {
        val input = ChapterContent(
            listOf(ContentElement.Paragraph("a"), ContentElement.Paragraph("b"), ContentElement.Paragraph("c")),
            mapOf("c" to 2),
        )
        val rules = listOf(PurifyRule("a\\nb", "\$8"), PurifyRule("a\n", "", isRegex = false))
        assertEquals(1, Purifier.lenient(rules).apply(input).anchors["c"])
        assertEquals(1, Purifier.legado(rules).apply(input).anchors["c"])
    }

    /** 计数重复套 `.*`：JDK 21 上仍会指数级回溯（见类注释的实测数据） */
    private fun pathological() = listOf(PurifyRule(pattern = "(.*,){11}P", replacement = ""))

    /** 无保护时这个规模要跑十几秒；有保护则 1 秒内中止 */
    private val evilText = "a,".repeat(30)

    @Test
    fun `病态正则在预算内中止，而不是永远空转`() {
        val started = System.nanoTime()
        assertFailsWith<PurifyTimeoutException> {
            Purifier.lenient(pathological()).apply(evilText)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // 预算 1 秒，给一倍余量兜住 CI 上的抖动；真正的失败模式是跑不到这行
        assertTrue(elapsedMs < 2 * Purifier.BUDGET_MS, "应在预算内中止，实测 ${elapsedMs}ms")
    }

    @Test
    fun `超时不被宽松模式吞掉 —— 否则整章静默变成未净化`() {
        // lenient 的本意是"某条规则写错就跳过它"，但超时是整章级别的降级信号：
        // 若被当成"跳过这条"，后面每条规则都会立刻再超时一次，最后静默返回原文。
        val rules = pathological() + PurifyRule(pattern = "广告", replacement = "")
        assertFailsWith<PurifyTimeoutException> {
            Purifier.lenient(rules).apply(evilText)
        }
    }

    @Test
    fun `预算覆盖整章，不是每段各发一份`() {
        // 每段一份预算的话，总耗时 = 预算 × 段数，等于没有上限。
        val elements = List(20) { ContentElement.Paragraph(evilText) }
        val started = System.nanoTime()
        assertFailsWith<PurifyTimeoutException> {
            Purifier.lenient(pathological()).apply(elements)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 2 * Purifier.BUDGET_MS, "20 段也只该烧一份预算，实测 ${elapsedMs}ms")
    }

    @Test
    fun `正常规则不受影响 —— 那层取字符的关卡必须是透明的`() {
        val rules = listOf(
            PurifyRule(pattern = "本站首发.*?完", replacement = ""),
            PurifyRule(pattern = "【广告】", replacement = "", isRegex = false),
        )
        val elements = listOf(
            ContentElement.Paragraph("第一段本站首发请勿转载完正文开始"),
            ContentElement.Paragraph("【广告】第二段"),
            ContentElement.Heading(1, "标题【广告】"),
        )
        val out = Purifier.lenient(rules).apply(elements)
        assertEquals(
            listOf("第一段正文开始", "第二段", "标题"),
            out.map {
                when (it) {
                    is ContentElement.Paragraph -> it.text
                    is ContentElement.Heading -> it.text
                    else -> ""
                }
            },
        )
    }

    @Test
    fun `替换后整段变空则丢弃该段`() {
        val rules = listOf(PurifyRule(pattern = "整段都是广告", replacement = "", isRegex = false))
        val out = Purifier.lenient(rules).apply(
            listOf(
                ContentElement.Paragraph("整段都是广告"),
                ContentElement.Paragraph("正文"),
            )
        )
        assertEquals(1, out.size)
        assertEquals("正文", (out[0] as ContentElement.Paragraph).text)
    }

    // ---- 书源 replaceRegex（Legado 语义）----

    @Test
    fun `legado 模式：编译不过的正则退回字面替换`() {
        val p = Purifier.legado(
            listOf(
                PurifyRule(pattern = "(广告", replacement = ""),
                PurifyRule(pattern = "*推荐*", replacement = ""),
                PurifyRule(pattern = "本站.*?完", replacement = ""),
            ),
        )
        assertEquals("正文接着正文", p.apply("正文(广告*推荐*本站首发完接着正文"))
    }

    @Test
    fun `legado 模式：替换串里的非法组引用退回字面替换`() {
        val p = Purifier.legado(listOf(PurifyRule(pattern = "钟响", replacement = "\$x")))
        assertEquals("一声\$x。", p.apply("一声钟响。"))
    }

    @Test
    fun `legado 模式不放松超时 —— 病态正则照样在预算内中止`() {
        assertFailsWith<PurifyTimeoutException> {
            Purifier.legado(pathological()).apply(evilText)
        }
    }

    @Test
    fun `strict 照旧：坏正则就是硬错（规则编辑器的校验靠它）`() {
        assertFailsWith<SourceException> { Purifier.strict(listOf(PurifyRule(pattern = "(广告"))) }
    }
}
