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
    fun `native array result is serialized as json not object Object`() {
        // 从前 Context.toString 会压成 "[object Object],[object Object]"，后续 JSONPath 全落空
        assertEquals(
            """[{"id":1},{"id":2}]""",
            runtime.eval("JSON.parse(result)", mapOf("result" to """[{"id":1},{"id":2}]""")),
        )
    }

    @Test
    fun `native object result is serialized as json`() {
        assertEquals("""{"a":1,"b":"x"}""", runtime.eval("var o={a:1,b:'x'}; o", emptyMap()))
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

    // ---- 沙箱逃逸 ----
    // 注入的桥是 NativeJavaObject，公开方法里带着继承自 Object 的 getClass()。
    // 没有 ClassShutter 时，从它反射一步就能拿到整个 JDK —— 恶意书源即可读应用私有目录
    // （Room 库、WebDAV 明文口令）并借 INTERNET 权限外发。这几条把那条路钉死。

    private val bridge = JavaBridge(http = null, cache = JsCache(), vars = mutableMapOf())

    private fun withBridge(script: String) = runtime.eval(script, mapOf("java" to bridge))

    @Test
    fun `getClass on an injected bridge is blocked`() {
        assertFailsWith<Exception> { withBridge("java.getClass()") }
    }

    @Test
    fun `Class forName escape through an injected bridge is blocked`() {
        assertFailsWith<Exception> {
            withBridge("""java.getClass().forName("java.lang.Runtime")""")
        }
    }

    @Test
    fun `bridge methods still work with the shutter installed`() {
        // 白名单必须放行桥自身，否则挡住越狱的同时也把书源全打死了
        assertEquals("YWJj", withBridge("""java.base64Encode("abc")"""))
        assertEquals("abc", withBridge("""java.base64Decode("YWJj")"""))
    }

    @Test
    fun `same script can be evaluated twice after compile cache`() {
        // 编译缓存不能把第二次变成「作用域脏了 / 绑错变量」
        val script = "baseUrl + result"
        assertEquals("https://a.com/1", runtime.eval(script, mapOf("baseUrl" to "https://a.com/", "result" to "1")))
        assertEquals("https://b.com/2", runtime.eval(script, mapOf("baseUrl" to "https://b.com/", "result" to "2")))
    }
}
