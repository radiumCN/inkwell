package com.radium.inkwell.core.source.js

import org.mozilla.javascript.Script
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * 编译好的脚本怎么留、怎么淘汰（[RhinoScriptRuntime.CompiledScripts]）。
 * 一次搜索要轮几百个源，按条数留太少会回回重编；只按条数放宽，几段内嵌 CryptoJS 的长脚本又会把内存撑大 —— 所以两样都封顶。
 */
class CompiledScriptsTest {

    private fun script() = Script { _, _, _ -> null }

    private fun src(tag: Char, length: Int) = tag.toString().repeat(length)

    @Test
    fun `总字数超了，最久没用的先走`() {
        val cache = RhinoScriptRuntime.CompiledScripts(maxEntries = 100, maxChars = 1_000)
        val a = src('a', 400)
        val b = src('b', 400)
        val c = src('c', 400)
        cache.put(a, script())
        cache.put(b, script())
        cache.get(a) // 用过一次：b 成了最久没用的
        cache.put(c, script())
        assertNotNull(cache.get(a))
        assertNull(cache.get(b))
        assertNotNull(cache.get(c))
    }

    @Test
    fun `条数超了同样淘汰`() {
        val cache = RhinoScriptRuntime.CompiledScripts(maxEntries = 2, maxChars = 1_000_000)
        cache.put("x", script())
        cache.put("y", script())
        cache.put("z", script())
        assertNull(cache.get("x"))
        assertNotNull(cache.get("y"))
        assertNotNull(cache.get("z"))
    }

    @Test
    fun `单段就比上限还大：照样交回，只是不留，也不把别的挤掉`() {
        val cache = RhinoScriptRuntime.CompiledScripts(maxEntries = 10, maxChars = 100)
        cache.put("small", script())
        val huge = script()
        assertSame(huge, cache.put(src('h', 101), huge))
        assertNull(cache.get(src('h', 101)))
        assertNotNull(cache.get("small"))
    }

    @Test
    fun `别的线程抢先编好了同一段：交回先到的那份`() {
        val cache = RhinoScriptRuntime.CompiledScripts(maxEntries = 10, maxChars = 1_000)
        val first = script()
        cache.put("s", first)
        assertSame(first, cache.put("s", script()))
    }
}
