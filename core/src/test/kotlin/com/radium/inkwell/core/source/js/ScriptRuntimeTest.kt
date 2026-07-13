package com.radium.inkwell.core.source.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ScriptRuntimeTest {

    private val runtime = RhinoScriptRuntime()

    @Test
    fun `evaluates expression with bindings`() {
        assertEquals(
            "https://a.com/book/1",
            runtime.eval("baseUrl + result", mapOf("baseUrl" to "https://a.com", "result" to "/book/1")),
        )
    }

    @Test
    fun `supports regex match and string methods`() {
        val script = """result.match(/\('(.*?)', '', ''\)/)[1]"""
        assertEquals(
            "/b/123.html",
            runtime.eval(script, mapOf("result" to "open('/b/123.html', '', '')")),
        )
    }

    @Test
    fun `supports json parse and array join`() {
        val script = "JSON.parse(result).map(function(x){return x.id}).join(',')"
        assertEquals(
            "1,2",
            runtime.eval(script, mapOf("result" to """[{"id":1},{"id":2}]""")),
        )
    }

    @Test
    fun `undefined result is null`() {
        assertNull(runtime.eval("var x = 1;", emptyMap()))
    }

    @Test
    fun `infinite loop is aborted by instruction limit`() {
        val fast = RhinoScriptRuntime(maxInstructions = 500_000)
        assertFailsWith<IllegalStateException> {
            fast.eval("while(true){}", emptyMap())
        }
    }

    @Test
    fun `java bridge is not exposed`() {
        // initSafeStandardObjects 不注入 Packages/java 对象
        assertFailsWith<Exception> {
            runtime.eval("java.lang.System.exit(0)", emptyMap())
        }
    }
}
