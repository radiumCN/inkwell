package com.radium.inkwell.core.source

import com.radium.inkwell.core.model.ContentElement

/**
 * 净化规则的执行。
 *
 * 抽出来是因为净化发生在**两个**时机：书源自带的与通用的规则在抓取时应用（结果进缓存），
 * 用户挂在某本书上的规则在渲染时应用（对已缓存的章节也立刻生效）。两条路径必须是
 * 同一套语义 —— 各写一份迟早会跑偏，届时"为什么规则页试算是对的、书里却没生效"这种问题
 * 根本无从查起。
 */
class Purifier private constructor(private val compiled: List<Compiled>) {

    val isEmpty: Boolean get() = compiled.isEmpty()

    /** 逐段落应用；替换后整段变空则丢弃该段 */
    fun apply(elements: List<ContentElement>): List<ContentElement> {
        if (compiled.isEmpty()) return elements
        return elements.mapNotNull { el ->
            when (el) {
                is ContentElement.Paragraph -> clean(el.text)?.let { ContentElement.Paragraph(it) }
                is ContentElement.Heading -> clean(el.text)?.let { ContentElement.Heading(el.level, it) }
                else -> el
            }
        }
    }

    fun apply(text: String): String = compiled.fold(text) { acc, p -> p.apply(acc) }

    private fun clean(text: String): String? =
        apply(text).trim().takeIf { it.isNotEmpty() }

    private class Compiled(rule: PurifyRule) {
        private val regex: Regex? = if (rule.isRegex) Regex(rule.pattern) else null
        private val literal: String = rule.pattern
        private val replacement: String = rule.replacement
        fun apply(text: String): String =
            regex?.replace(text, replacement) ?: text.replace(literal, replacement)
    }

    companion object {
        /** 书源自带的规则：编译不过就是硬错 —— 那是书源坏了，得让用户看见 */
        fun strict(rules: List<PurifyRule>): Purifier = Purifier(
            rules.map {
                try {
                    Compiled(it)
                } catch (e: Exception) {
                    throw SourceException("净化规则正则错误: ${it.pattern}", e)
                }
            }
        )

        /**
         * 用户自己写的规则：一条正则写错，跳过它就是，不该让整章正文加载失败 ——
         * 那在阅读页只会显示"正文规则未匹配到内容"，用户根本猜不到是自己的净化规则干的。
         */
        fun lenient(rules: List<PurifyRule>): Purifier = Purifier(
            rules.mapNotNull { runCatching { Compiled(it) }.getOrNull() }
        )
    }
}
