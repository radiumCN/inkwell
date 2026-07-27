package com.radium.inkwell.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 书源规则里的正则同样要过预算关卡。
 *
 * 净化规则早就有这道关卡（见 [PurifierTest]），规则求值这条路从前没有 —— 而两边的正则
 * 是同一个来源：用户从网上导入的第三方书源。一条病态正则碰上长正文，就把 IO 线程永久钉死，
 * 且 Java 正则的回溯不理会线程中断，退出页面也停不下来。
 *
 * 病态用例沿用 PurifierTest 那条实测仍是指数级的（JDK 21 把教科书例子都优化掉了）：
 * `(.*,){11}P`，输入每加 4 个字符耗时约 ×8。
 */
class RuleRegexBudgetTest {

    private val evaluator = RuleEvaluator()

    private val evil = "(.*,){11}P"
    private val evilText = "a,".repeat(30)

    private fun ctx(text: String) = EvalContext(
        element = null,
        json = text,
        baseUrl = "https://a.com",
        vars = emptyMap(),
        js = JsContext(sourceKey = "t"),
    )

    private fun elapsedMs(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun `冒号 regex 抽取的病态正则在预算内中止`() {
        var out: List<String>? = null
        val ms = elapsedMs {
            out = evaluator.evalToStrings(RuleNode.RegexRule(evil), ctx(evilText))
        }
        // 没匹配上就是没匹配上，超时不该外泄成「书源坏了」
        assertEquals(emptyList(), out)
        assertTrue(ms < 2 * Purifier.BUDGET_MS, "应在预算内中止，实测 ${ms}ms")
    }

    @Test
    fun `管道替换的病态正则在预算内中止并保留原文`() {
        val node = RuleNode.Pipe(
            source = RuleNode.Literal(evilText),
            ops = listOf(PipeOp.RegexReplace(evil, "")),
        )
        var out: List<String>? = null
        val ms = elapsedMs { out = evaluator.evalToStrings(node, ctx(evilText)) }
        // 替换没跑成 = 这条替换没生效，原文照常往下走
        assertEquals(listOf(evilText), out)
        assertTrue(ms < 2 * Purifier.BUDGET_MS, "应在预算内中止，实测 ${ms}ms")
    }

    @Test
    fun `正常正则不受影响 —— 那层取字符的关卡必须是透明的`() {
        assertEquals(
            listOf("123"),
            evaluator.evalToStrings(RuleNode.RegexRule("""/book/(\d+)\.html"""), ctx("/book/123.html")),
        )
        assertEquals(
            listOf("干净正文"),
            evaluator.evalToStrings(
                RuleNode.Pipe(
                    source = RuleNode.Literal("广告干净正文"),
                    ops = listOf(PipeOp.RegexReplace("广告", "")),
                ),
                ctx(""),
            ),
        )
    }
}
