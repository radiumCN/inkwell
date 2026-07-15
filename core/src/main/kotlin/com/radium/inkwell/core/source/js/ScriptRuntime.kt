package com.radium.inkwell.core.source.js

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

    override fun eval(script: String, bindings: Map<String, Any?>): String? {
        val cx = factory.enterContext()
        try {
            cx.putThreadLocal(KEY_COUNT, 0L)
            val scope: Scriptable = cx.initSafeStandardObjects()
            bindings.forEach { (name, value) ->
                scope.put(name, scope, Context.javaToJS(value, scope))
            }
            val result = cx.evaluateString(scope, script, "rule.js", 1, null)
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
    }
}
