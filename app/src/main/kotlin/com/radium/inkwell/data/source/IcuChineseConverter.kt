package com.radium.inkwell.data.source

import android.icu.text.Transliterator
import com.radium.inkwell.core.source.js.ChineseConverter

/**
 * 来源脚本的繁简转换（`java.t2s` / `java.s2t`），用 Android 自带的 ICU（API 29 起公开，minSdk 33）。
 * 不往包里另塞一份字表：ICU 的 Hant-Hans 本来就在系统里。
 *
 * 转换器头一次用时才建（要读规则表，几十毫秒），绝大多数来源一辈子用不上。ICU 不保证 Transliterator 线程安全，
 * 调用都很短（书名、标签、一个搜索词），一个锁足够。系统里没有这条转换（裁过 ICU 数据的 ROM）就原样返回，
 * 和没注入一样，不让脚本跟着报错。
 */
class IcuChineseConverter : ChineseConverter {

    private val toSimplified by lazy { runCatching { Transliterator.getInstance("Hant-Hans") }.getOrNull() }
    private val toTraditional by lazy { runCatching { Transliterator.getInstance("Hans-Hant") }.getOrNull() }

    override fun toSimplified(text: String): String = convert(toSimplified, text)

    override fun toTraditional(text: String): String = convert(toTraditional, text)

    private fun convert(t: Transliterator?, text: String): String {
        if (t == null || text.isEmpty()) return text
        return synchronized(t) { runCatching { t.transliterate(text) }.getOrDefault(text) }
    }
}
