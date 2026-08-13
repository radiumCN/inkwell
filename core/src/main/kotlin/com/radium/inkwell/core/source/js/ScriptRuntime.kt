package com.radium.inkwell.core.source.js

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable

/** JS 规则执行器抽象；不注入时含 js: 规则的求值会明确报不支持 */
interface ScriptRuntime {
    /**
     * 执行脚本并返回最后一个表达式的字符串值。
     * @param bindings 注入的变量（result/baseUrl/key/page 等）
     * @throws Exception 脚本错误由调用方决定如何降级
     */
    fun eval(script: String, bindings: Map<String, Any?>): String?
}

/**
 * Rhino 实现（与 Legado 同引擎，JS 语义一致）。
 * 解释模式运行（Android 无法使用 Rhino 字节码编译），指令数限制防死循环。
 */
class RhinoScriptRuntime(
    private val maxInstructions: Long = 20_000_000L,
) : ScriptRuntime {

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1 // Android 兼容：纯解释执行
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = 100_000
            cx.setClassShutter(BRIDGE_ONLY)
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val counted = (cx.getThreadLocal(KEY_COUNT) as? Long ?: 0L) + instructionCount
            if (counted > maxInstructions) {
                throw IllegalStateException("脚本执行超出指令数限制")
            }
            cx.putThreadLocal(KEY_COUNT, counted)
        }
    }

    /**
     * 同一段 JS 规则会被每个列表项、每章正文反复 eval。opt=-1 不能出字节码，
     * 但 compileString 仍能把源码变成 AST，免掉每次的解析。64 条 LRU 够覆盖
     * 一个书源里常见的 search/detail/toc/content 脚本，又不会把超长动态脚本攒满。
     */
    private val compiledScripts = object : LinkedHashMap<String, org.mozilla.javascript.Script>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, org.mozilla.javascript.Script>?): Boolean =
            size > 64
    }

    override fun eval(script: String, bindings: Map<String, Any?>): String? {
        val cx = factory.enterContext()
        try {
            cx.putThreadLocal(KEY_COUNT, 0L)
            val scope: Scriptable = cx.initSafeStandardObjects()
            bindings.forEach { (name, value) ->
                scope.put(name, scope, Context.javaToJS(value, scope))
            }
            val compiled = synchronized(compiledScripts) {
                compiledScripts[script] ?: cx.compileString(script, "rule.js", 1, null).also {
                    compiledScripts[script] = it
                }
            }
            val result = compiled.exec(cx, scope)
            return when {
                result == null || result === Context.getUndefinedValue() -> null
                // JS 返回原生数组/对象时，Context.toString 会压成 "[object Object]" 或逗号串，
                // 后续 JSONPath 全落空（JSON API 书源 `<js>JSON.parse(result).list</js>` 就死在这）。
                // 用 NativeJSON.stringify 序列化成合法 JSON，求值器再据 `[`/`{` 前缀接上 JSONPath。
                result is NativeArray || result is NativeObject ->
                    stringifyJson(cx, scope, result) ?: Context.toString(result)
                else -> Context.toString(result)
            }
        } finally {
            Context.exit()
        }
    }

    /** 用 Rhino 内置的 JSON.stringify 序列化原生数组/对象；失败返回 null 交调用方兜底。 */
    private fun stringifyJson(cx: Context, scope: Scriptable, value: Any): String? =
        runCatching { NativeJSON.stringify(cx, scope, value, null, "") }
            .getOrNull()
            ?.let { if (it is String) it else Context.toString(it) }

    private companion object {
        val KEY_COUNT = Any()

        /**
         * 只有桥对象所在的包对脚本可见，其余 Java 类一律拒绝。
         *
         * `initSafeStandardObjects()` 只是不注册顶层的 `Packages`/`java`/`getClass`，它**不拦**
         * 「从已在作用域里的对象反射出去」—— 而我们恰恰往作用域里塞了六个 Kotlin 桥对象，
         * Rhino 把它们包成 NativeJavaObject，公开方法全都可见，其中包括继承自 Object 的
         * `getClass()`。于是恶意书源只要一行就能越狱：
         *
         *     java.getClass().forName("java.lang.Runtime")...
         *
         * 拿到 Class 之后整个反射面就打开了：应用私有目录（Room 库、WebDAV 明文口令）能读，
         * 又有 INTERNET 权限能外发。书源是用户从网上导入的第三方内容，这就是不可信输入。
         *
         * 装上 ClassShutter 后，Rhino 在 JavaMembers.lookupClass 反射任何类之前都会问一次；
         * `java.lang.Class` 不在白名单里，`getClass()` 的返回值就包不出来，链条断在第一步。
         *
         * 白名单 = 桥所在的包 + 跨桥传递的值类型。值类型这一项不能省：Rhino 默认
         * `javaPrimitiveWrap = true`，桥方法返回的 String 也会先包成 NativeJavaObject
         * （随后挂上 JS String 原型，脚本才能 `.match()`），这一步同样要过 shutter ——
         * 不放行的话 `java.base64Encode(...)` 这类最普通的调用都会被自己挡死。
         *
         * 关键在于 `java.lang.Class` **不在**白名单里：`getClass()` 的返回值包不出来，
         * 反射链条断在第一步，放行 String/数字并不会把它接回去。
         */
        val BRIDGE_ONLY = ClassShutter { name ->
            name.startsWith(BRIDGE_PACKAGE) || name in VALUE_TYPES
        }

        const val BRIDGE_PACKAGE = "com.radium.inkwell.core.source.js."

        /** 桥的入参/返回值里会出现的 JDK 值类型；都是终态数据，反射不出更多东西 */
        val VALUE_TYPES = setOf(
            "java.lang.String",
            "java.lang.Boolean",
            "java.lang.Character",
            "java.lang.Number",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
        )
    }
}
