package com.radium.inkwell.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleParserTest {

    @Test
    fun `default engine is css with text extractor`() {
        assertEquals(
            RuleNode.Css("div.item h3", CssExtractor.Text),
            RuleParser.parse("div.item h3"),
        )
    }

    @Test
    fun `css extractors table`() {
        val cases = mapOf(
            "css:a@href" to RuleNode.Css("a", CssExtractor.Href),
            "css:img@src" to RuleNode.Css("img", CssExtractor.Src),
            "css:div@html" to RuleNode.Css("div", CssExtractor.Html),
            "css:div@outerHtml" to RuleNode.Css("div", CssExtractor.OuterHtml),
            "css:p@ownText" to RuleNode.Css("p", CssExtractor.OwnText),
            "css:h3 a@text" to RuleNode.Css("h3 a", CssExtractor.Text),
            "css:meta[name=author]@attr(content)" to
                RuleNode.Css("meta[name=author]", CssExtractor.Attr("content")),
            // 空查询 = 当前上下文元素
            "css:@text" to RuleNode.Css("", CssExtractor.Text),
            "css:@href" to RuleNode.Css("", CssExtractor.Href),
        )
        for ((rule, expected) in cases) {
            assertEquals(expected, RuleParser.parse(rule), "规则: $rule")
        }
    }

    @Test
    fun `json and text engines`() {
        assertEquals(RuleNode.JsonPath("$.data[*].name"), RuleParser.parse("json:$.data[*].name"))
        assertEquals(RuleNode.Literal("hello {{page}}"), RuleParser.parse("text:hello {{page}}"))
        assertEquals(RuleNode.RegexRule("第(\\d+)章"), RuleParser.parse("regex:第(\\d+)章"))
    }

    @Test
    fun `pipes parsed in order`() {
        val n = RuleParser.parse("css:span@text | regex:作者[：:] X | trim | first | index:2 | join:, | prepend:A | append:B | match:(\\d+) | stripTags | last") as RuleNode.Pipe
        assertEquals(
            listOf(
                PipeOp.RegexReplace("作者[：:]", "X"),
                PipeOp.Trim,
                PipeOp.First,
                PipeOp.Index(2),
                PipeOp.Join(","),
                PipeOp.Prepend("A"),
                PipeOp.Append("B"),
                PipeOp.Match("(\\d+)"),
                PipeOp.StripTags,
                PipeOp.Last,
            ),
            n.ops,
        )
        assertEquals(RuleNode.Css("span", CssExtractor.Text), n.source)
    }

    @Test
    fun `pipe regex without space has empty replacement`() {
        val n = RuleParser.parse("css:a@text | regex:广告") as RuleNode.Pipe
        assertEquals(PipeOp.RegexReplace("广告", ""), n.ops[0])
    }

    @Test
    fun `fallback and concat structure`() {
        // && 优先分割，|| 在段内分割
        val n = RuleParser.parse("css:a@text||css:b@text&&css:c@text")
        assertEquals(
            RuleNode.Concat(
                listOf(
                    RuleNode.Fallback(
                        listOf(
                            RuleNode.Css("a", CssExtractor.Text),
                            RuleNode.Css("b", CssExtractor.Text),
                        )
                    ),
                    RuleNode.Css("c", CssExtractor.Text),
                )
            ),
            n,
        )
    }

    @Test
    fun `escaped pipes inside regex do not split`() {
        // \|\| 是正则转义，不能被当作回退分隔符
        val n = RuleParser.parse("regex:(a\\|\\|b)")
        assertEquals(RuleNode.RegexRule("(a\\|\\|b)"), n)
        // 管道 regex 中的 \| 也不切分
        val p = RuleParser.parse("css:a@text | regex:x\\|y z") as RuleNode.Pipe
        assertEquals(PipeOp.RegexReplace("x\\|y", "z"), p.ops[0])
    }

    @Test
    fun `js rules parse as Js nodes`() {
        assertEquals(RuleNode.Js("result.title"), RuleParser.parse("js:result.title"))
        assertEquals(RuleNode.Js("foo()"), RuleParser.parse("@js:foo()"))
        // base64 形式（转换器产物，绕开 DSL 的 | 与 && 切分）
        val b64 = java.util.Base64.getEncoder()
            .encodeToString("a || b".toByteArray(Charsets.UTF_8))
        assertEquals(RuleNode.Js("a || b"), RuleParser.parse("js:b64:$b64"))
        // 作为管道
        val piped = RuleParser.parse("css:a@href | js:b64:$b64") as RuleNode.Pipe
        assertEquals(listOf(PipeOp.Js("a || b")), piped.ops)
    }

    @Test
    fun `unknown pipe op reports position`() {
        val e = assertFailsWith<RuleSyntaxException> { RuleParser.parse("css:a@text | frobnicate") }
        assertEquals(13, e.position)
        assertTrue(e.message!!.contains("frobnicate"))
    }

    @Test
    fun `invalid css selector reports position`() {
        val e = assertFailsWith<RuleSyntaxException> { RuleParser.parse("css:div[@") }
        assertEquals(4, e.position)
    }

    @Test
    fun `invalid regex reports position`() {
        assertFailsWith<RuleSyntaxException> { RuleParser.parse("regex:([)") }
        val e = assertFailsWith<RuleSyntaxException> { RuleParser.parse("css:a@text | match:([)") }
        assertEquals(19, e.position)
    }

    @Test
    fun `index requires integer`() {
        assertFailsWith<RuleSyntaxException> { RuleParser.parse("css:a@text | index:x") }
    }

    @Test
    fun `empty rule segment fails`() {
        assertFailsWith<RuleSyntaxException> { RuleParser.parse("") }
        assertFailsWith<RuleSyntaxException> { RuleParser.parse("css:a@text || ") }
    }
}
