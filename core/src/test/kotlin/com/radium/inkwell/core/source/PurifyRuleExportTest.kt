package com.radium.inkwell.core.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 净化规则导出（多选 →「导出」）：阅读认得、导回 Inkwell 内容不走样 */
class PurifyRuleExportTest {

    private val rule = ExportPurifyRule(
        id = "legado:1700000000001",
        name = "去「天才一秒记住」",
        pattern = "天才一秒记住.*?最快更新",
        replacement = "",
        isRegex = true,
        scope = "笔趣",
        enabled = true,
    )

    @Test
    fun `导出再导入，内容一样，阅读的 id 原样回来`() {
        val handmade = ExportPurifyRule(
            id = "0b5f2c5e-0000-4000-8000-000000000001",
            name = "屏蔽词还原",
            pattern = "ji",
            replacement = "鸡",
            isRegex = false,
            scope = "",
            enabled = false,
        )
        val back = PurifyRuleImport.parse(PurifyRuleExport.toJson(listOf(rule, handmade)))
        assertEquals(2, back.size)
        with(back[0]) {
            assertEquals("legado:1700000000001", id)
            assertEquals(rule.name, name)
            assertEquals(rule.pattern, pattern)
            assertEquals("笔趣", scope)
            assertTrue(isRegex)
            assertTrue(enabled)
            assertEquals(null, unsupported)
        }
        with(back[1]) {
            assertEquals("鸡", replacement)
            assertEquals(false, isRegex)
            // 关着的导出去也关着
            assertEquals(false, enabled)
            assertEquals(null, unsupported)
        }
    }

    @Test
    fun `阅读要的字段都写上：数字 id、作用于正文、按列表顺序排`() {
        val arr = Json.parseToJsonElement(PurifyRuleExport.toJson(listOf(rule, rule.copy(id = "seed:去掉网址")))).jsonArray
        val first = arr[0].jsonObject
        assertEquals(1700000000001L, first["id"]!!.jsonPrimitive.long)
        // 缺了 scopeContent，阅读当它不作用于正文
        assertTrue(first["scopeContent"]!!.jsonPrimitive.boolean)
        assertEquals(false, first["scopeTitle"]!!.jsonPrimitive.boolean)
        assertEquals(0L, first["order"]!!.jsonPrimitive.long)
        assertEquals(1L, arr[1].jsonObject["order"]!!.jsonPrimitive.long)
        // 不是阅读来的 id 也给一个数字：阅读把 id 当 Long 读，一个字符串会让整份导入失败
        assertTrue((arr[1].jsonObject["id"] as JsonPrimitive).isString.not())
    }

    @Test
    fun `非阅读 id 换成的数字是确定的、正的、在安全整数里`() {
        val a = PurifyRuleExport.legadoId("seed:去掉网址")
        assertEquals(a, PurifyRuleExport.legadoId("seed:去掉网址"))
        assertNotEquals(a, PurifyRuleExport.legadoId("seed:去掉「本章未完」"))
        listOf("seed:x", "legado:h0123456789abcdef", "legado:0", "legado:-5", "").forEach {
            val n = PurifyRuleExport.legadoId(it)
            assertTrue(n in 1..(1L shl 53), "$it → $n")
        }
    }

    @Test
    fun `空列表导出一个空数组`() {
        assertEquals(JsonArray(emptyList()), Json.parseToJsonElement(PurifyRuleExport.toJson(emptyList())))
        assertTrue(Json.parseToJsonElement(PurifyRuleExport.toJson(listOf(rule))).jsonArray[0] is JsonObject)
    }
}
