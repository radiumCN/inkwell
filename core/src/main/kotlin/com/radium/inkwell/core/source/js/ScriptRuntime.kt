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
     * @param jsLib 源里的公共脚本库（阅读的 jsLib）：里面定义的函数、变量对这段脚本可见
     * @throws Exception 脚本错误由调用方决定如何降级
     */
    fun eval(script: String, bindings: Map<String, Any?>, jsLib: String? = null): String?
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
            // 调用方已经取消（离开页面、单源搜索超时）就别跑完。脚本是纯 CPU 循环、没有挂起点，
            // withTimeout 管不住它：从前一段死循环要烧满指令预算才停，期间一直占着一个搜索名额。
            // 抛的是取消而不是普通异常：Rhino 对非 JS 异常只走 finally、不进脚本的 catch，书源吞不掉它；
            // RuleEvaluator.runJs 也会把它放出去（见那里）
            if (EngineJsHttp.currentJob()?.isActive == false) {
                throw kotlinx.coroutines.CancellationException("脚本所在的引擎调用已取消")
            }
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

    /**
     * 每线程一份 initSafeStandardObjects 原型。eval 只 newObject 挂绑定，
     * 避免每次重建 Object/Array/JSON。绑定打在子作用域上，两次 eval 不会串变量。
     *
     * 原型**必须 sealed**：子作用域只隔得住顶层变量，`String.prototype.replace = …` 这种写法
     * 顺着原型链改的是共享的那份，同一线程上后面跑的**别的书源**脚本全跟着中招 ——
     * 而且哪个源改的、什么时候改的，事后无从查起。封死后这类赋值抛 TypeError，
     * 只影响写它的那一个源。代价：极少数在脚本里给内建原型打补丁的书源会失效，
     * Rhino 自带的 ES2015+ 内建方法已经覆盖了它们打补丁的常见目的。
     */
    private val prototypeByThread = ThreadLocal<org.mozilla.javascript.ScriptableObject>()

    /**
     * 源的公共脚本库（阅读的 jsLib）：每线程、每份库一个作用域，库代码只在里面跑一次，规则脚本的作用域以它为原型
     * —— 同 Legado 的 SharedJsScope。库里的函数照 Legado 的写法用 `this.java` / `this.source` 拿当次的绑定
     * （不带限定地调用库函数时，Rhino 把 `this` 设成脚本自己的作用域，绑定就在那上面）。
     *
     * 每线程一份而不是全局一份：作用域对象不是线程安全的，库里的顶层变量会被脚本改写。
     * 库本身跑不起来（语法错、阅读的远程库写法 `{"名字":"地址"}`）记成 null：当没有库，别让这个源的每条规则都陪着失败，
     * 也别每次重跑一遍。
     */
    private val libScopesByThread = ThreadLocal<LinkedHashMap<String, Scriptable?>>()

    private fun libScope(cx: Context, proto: org.mozilla.javascript.ScriptableObject, lib: String): Scriptable? {
        val cache = libScopesByThread.get()
            ?: object : LinkedHashMap<String, Scriptable?>(8, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Scriptable?>?): Boolean =
                    size > LIB_SCOPES_PER_THREAD
            }.also { libScopesByThread.set(it) }
        if (cache.containsKey(lib)) return cache[lib]
        val scope: Scriptable? = try {
            cx.newObject(proto).also {
                it.prototype = proto
                it.parentScope = null
                // 库自己的顶层代码就用 JavaImporter / Packages 的，加载前先装上（替身无状态，按库缓存不串源）
                if (JdkCompat.wanted(lib, null)) installJdkCompat(cx, it)
                cx.evaluateString(it, lib, "jsLib.js", 1, null)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消不是「库坏了」：不记，下次照常加载
            throw e
        } catch (_: Exception) {
            null
        }
        cache[lib] = scope
        return scope
    }

    override fun eval(script: String, bindings: Map<String, Any?>, jsLib: String?): String? {
        // 嵌套求值（脚本里 java.getString("@js:…") 又回到这里）进的是同一个 Context：只在最外层清零
        // 指令计数。每进一层都清零的话，`while(true){ java.getString("@js:1") }` 永远撞不到上限
        val outermost = Context.getCurrentContext() == null
        val cx = factory.enterContext()
        try {
            if (outermost) cx.putThreadLocal(KEY_COUNT, 0L)
            val proto = prototypeByThread.get() ?: cx.initSafeStandardObjects(null, true).also {
                prototypeByThread.set(it)
            }
            // 绑定挂在 holder 上。有 jsLib 时作用域链是：脚本作用域 → 库作用域 → holder → 标准对象。
            // holder 垫在库**后面**：库函数里不管怎么调（`this.java`、裸写 `java`、库函数之间不带 this 互相调用），
            // 往上找都找得到这一次的绑定。只靠「脚本作用域在库前面」的话，库函数之间互相调用时 this 是库作用域，
            // `this.java` 是 undefined（Legado 就是这样，源作者只好处处写 `this.M(…)`）
            val holder: Scriptable = cx.newObject(proto).also {
                it.prototype = proto
                it.parentScope = null
            }
            bindings.forEach { (name, value) ->
                holder.put(name, holder, Context.javaToJS(value, holder))
            }
            // 阅读来源直接拿 JDK 做加解密（JavaImporter / Packages.javax.crypto…）：装上替身，见 JdkCompat。
            // 装在这一次的 holder 上，不进每线程共用的原型 —— 顶层 importPackage 导进来的名字不能串到别的来源
            if (JdkCompat.wanted(script, jsLib)) installJdkCompat(cx, holder)
            val lib = jsLib?.takeIf { it.isNotBlank() }?.let { libScope(cx, proto, it) }
            val scope: Scriptable = if (lib == null) holder else cx.newObject(proto).also {
                it.prototype = lib
                it.parentScope = null
            }
            // 库作用域是每线程共用的：这一次的 holder 接上去，用完接回原来的那个 ——
            // 脚本里 java.getString("@js:…") 会在同一线程上重入这里，内层换掉的得还给外层
            val outerHolder = lib?.prototype
            lib?.prototype = holder
            try {
                // 锁里只查表，编译放在锁外：一段几十 KB 的脚本（内嵌 CryptoJS 的源不少）编译期间，
                // 从前别的书源的 JS 求值 —— 连缓存命中的 —— 全排在这把锁上。重了也只是多编一次
                val compiled = synchronized(compiledScripts) { compiledScripts[script] }
                    ?: cx.compileString(script, "rule.js", 1, null).let { fresh ->
                        synchronized(compiledScripts) { compiledScripts.getOrPut(script) { fresh } }
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
                if (lib != null) lib.prototype = outerHolder
            }
        } finally {
            Context.exit()
        }
    }

    @Volatile
    private var compiledPrelude: org.mozilla.javascript.Script? = null

    /** 在 [target] 上装 JDK 替身（[JdkCompat.PRELUDE]）；替身脚本只编译一次 */
    private fun installJdkCompat(cx: Context, target: Scriptable) {
        target.put("__inkwellJdk", target, Context.javaToJS(JdkCompat(), target))
        val prelude = compiledPrelude
            ?: cx.compileString(JdkCompat.PRELUDE, "jdk-compat.js", 1, null).also { compiledPrelude = it }
        prelude.exec(cx, target)
    }

    /** 用 Rhino 内置的 JSON.stringify 序列化原生数组/对象；失败返回 null 交调用方兜底。 */
    private fun stringifyJson(cx: Context, scope: Scriptable, value: Any): String? =
        runCatching { NativeJSON.stringify(cx, scope, value, null, "") }
            .getOrNull()
            ?.let { if (it is String) it else Context.toString(it) }

    private companion object {
        val KEY_COUNT = Any()

        /** 每线程留几份库的作用域：同时在用的通常就是正在抓的那一两个源 */
        const val LIB_SCOPES_PER_THREAD = 8

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
            name.startsWith(BRIDGE_PACKAGE) ||
                name.startsWith("[L$BRIDGE_PACKAGE") ||
                name in VALUE_TYPES
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
            // getStringList / headers(name) 返回 String[]；strToBytes 返回 byte[]
            "[Ljava.lang.String;",
            "[B",
        )
    }
}
