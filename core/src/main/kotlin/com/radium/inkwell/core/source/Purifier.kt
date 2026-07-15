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
class Purifier private constructor(
    private val compiled: List<Compiled>,
    /** 宽松模式：运行期某条规则抛错（非法组引用/灾难回溯）时吞掉、保留原文；严格模式转 SourceException */
    private val lenient: Boolean,
) {

    val isEmpty: Boolean get() = compiled.isEmpty()

    /** 逐段落应用；替换后整段变空则丢弃该段 */
    fun apply(elements: List<ContentElement>): List<ContentElement> {
        if (compiled.isEmpty()) return elements
        // 含 `\n` 或 `(?s)`（DOTALL）的规则是**跨段**的：要匹配的文本横跨多个段落，
        // 逐段各自 apply 永远匹配不到（这正是「规则页试算对、书里不生效」的一类成因）。
        // 有这类规则时退回「整章文本 join → apply → 按换行重新拆段」路径。
        if (compiled.any { it.multiline }) return applyCrossParagraph(elements)
        return elements.mapNotNull { el ->
            when (el) {
                is ContentElement.Paragraph -> clean(el.text)?.let { ContentElement.Paragraph(it) }
                is ContentElement.Heading -> clean(el.text)?.let { ContentElement.Heading(el.level, it) }
                else -> el
            }
        }
    }

    /**
     * 把连续的文本段合并成整块跑净化（跨段规则才匹配得到），再按换行重新拆回段落。
     * 图片、标题等非普通段落原位保留，作为文本块的边界。
     */
    private fun applyCrossParagraph(elements: List<ContentElement>): List<ContentElement> {
        val out = mutableListOf<ContentElement>()
        val buffer = mutableListOf<String>()
        fun flush() {
            if (buffer.isEmpty()) return
            apply(buffer.joinToString("\n")).split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { out += ContentElement.Paragraph(it) }
            buffer.clear()
        }
        for (el in elements) {
            if (el is ContentElement.Paragraph) buffer += el.text
            else { flush(); out += el }
        }
        flush()
        return out
    }

    fun apply(text: String): String = compiled.fold(text) { acc, p ->
        // apply 期兜底：替换串里的 `$`（Illegal group reference）、灾难性回溯的 StackOverflowError
        // 都会在这里炸。runCatching 捕获 Throwable，宽松模式保原文、严格模式抛 SourceException。
        runCatching { p.apply(acc) }.getOrElse { e ->
            if (lenient) acc
            else throw SourceException("净化规则执行失败: ${p.pattern}", e)
        }
    }

    private fun clean(text: String): String? =
        apply(text).trim().takeIf { it.isNotEmpty() }

    private class Compiled(rule: PurifyRule) {
        private val regex: Regex? = if (rule.isRegex) Regex(rule.pattern) else null
        private val literal: String = rule.pattern
        private val replacement: String = rule.replacement
        val pattern: String = rule.pattern
        /** 跨段规则：正则含换行（字面 `\n` 或真换行）或 DOTALL 标志，需在整章文本上匹配 */
        val multiline: Boolean = rule.isRegex &&
            (rule.pattern.contains("\\n") || rule.pattern.contains('\n') || rule.pattern.contains("(?s)"))
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
            },
            lenient = false,
        )

        /**
         * 用户自己写的规则：一条正则写错，跳过它就是，不该让整章正文加载失败 ——
         * 那在阅读页只会显示"正文规则未匹配到内容"，用户根本猜不到是自己的净化规则干的。
         */
        fun lenient(rules: List<PurifyRule>): Purifier = Purifier(
            rules.mapNotNull { runCatching { Compiled(it) }.getOrNull() },
            lenient = true,
        )
    }
}
