package com.radium.inkwell.core.source.js

import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.WrapFactory
import org.mozilla.javascript.lc.type.TypeInfo

/**
 * 来源脚本用的 WrapFactory：桥上「像数组的列表」（[JsArrayLike]，Jsoup 替身的 [JsElements]）包成能当数组用的对象，
 * 其余照 Rhino 默认。
 *
 * 阅读那边脚本拿到的是 Java 的 List：`els[0]`、`els.length`、`for (i in els)` 是日常写法，
 * 不少还直接 `els.forEach(…)`、`Array.from(els)`。Rhino 默认把我们的类包成普通的 Java 对象，这些一个都不认。
 * 这里在 Java 方法之外认下标和 length，原型挂 Array.prototype：`map` / `forEach` / `filter` / `join` / `for…of`
 * 都是 Array.prototype 上的通用实现，只靠下标和 length 就能跑。Java 方法（`size()`、`get(i)`、`text()`）照样先认。
 *
 * 沙箱不受影响：包的还是桥所在包里的类，ClassShutter 照常把关（NativeJavaObject 构造时查）。
 */
internal class BridgeWrapFactory : WrapFactory() {
    override fun wrapAsJavaObject(cx: Context?, scope: Scriptable?, javaObject: Any?, staticType: TypeInfo?): Scriptable =
        if (javaObject is JsArrayLike) ArrayLikeObject(scope, javaObject, staticType)
        else super.wrapAsJavaObject(cx, scope, javaObject, staticType)
}

private class ArrayLikeObject(
    scope: Scriptable?,
    private val list: JsArrayLike,
    staticType: TypeInfo?,
) : NativeJavaObject(scope, list, staticType) {

    private var arrayProto: Scriptable? = null

    override fun getClassName(): String = "JavaList"

    override fun has(index: Int, start: Scriptable?): Boolean = index in 0 until list.size()

    override fun get(index: Int, start: Scriptable?): Any? =
        if (index in 0 until list.size()) Context.javaToJS(list.get(index), parentScope) else Scriptable.NOT_FOUND

    override fun has(name: String?, start: Scriptable?): Boolean = name == "length" || super.has(name, start)

    override fun get(name: String?, start: Scriptable?): Any? = if (name == "length") list.size() else super.get(name, start)

    override fun getIds(): Array<Any> = Array(list.size()) { it }

    override fun getPrototype(): Scriptable? =
        arrayProto ?: ScriptableObject.getArrayPrototype(parentScope).also { arrayProto = it }

    override fun setPrototype(prototype: Scriptable?) {
        arrayProto = prototype
    }
}
