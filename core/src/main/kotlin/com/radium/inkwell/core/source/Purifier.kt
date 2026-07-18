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
        // 预算覆盖**整章**，而不是每段各发一份：否则一条病态规则会在每个段落上各烧满一次预算，
        // 总耗时 = 预算 × 段数，等于没有上限。
        return apply(elements, Budget())
    }

    private fun apply(elements: List<ContentElement>, budget: Budget): List<ContentElement> {
        // 含 `\n` 或 `(?s)`（DOTALL）的规则是**跨段**的：要匹配的文本横跨多个段落，
        // 逐段各自 apply 永远匹配不到（这正是「规则页试算对、书里不生效」的一类成因）。
        // 有这类规则时退回「整章文本 join → apply → 按换行重新拆段」路径。
        if (compiled.any { it.multiline }) return applyCrossParagraph(elements, budget)
        return elements.mapNotNull { el ->
            when (el) {
                is ContentElement.Paragraph -> clean(el.text, budget)?.let { ContentElement.Paragraph(it) }
                is ContentElement.Heading -> clean(el.text, budget)?.let { ContentElement.Heading(el.level, it) }
                else -> el
            }
        }
    }

    /**
     * 把连续的文本段合并成整块跑净化（跨段规则才匹配得到），再按换行重新拆回段落。
     * 图片、标题等非普通段落原位保留，作为文本块的边界。
     */
    private fun applyCrossParagraph(
        elements: List<ContentElement>,
        budget: Budget,
    ): List<ContentElement> {
        val out = mutableListOf<ContentElement>()
        val buffer = mutableListOf<String>()
        fun flush() {
            if (buffer.isEmpty()) return
            apply(buffer.joinToString("\n"), budget).split("\n")
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

    fun apply(text: String): String = apply(text, Budget())

    private fun apply(text: String, budget: Budget): String = compiled.fold(text) { acc, p ->
        // apply 期兜底：替换串里的 `$`（Illegal group reference）、灾难性回溯的 StackOverflowError
        // 都会在这里炸。runCatching 捕获 Throwable，宽松模式保原文、严格模式抛 SourceException。
        runCatching { p.apply(acc, budget) }.getOrElse { e ->
            // 超时是**整章级别的降级信号**，不能被"跳过这条坏规则"的宽松逻辑吞掉：吞了的话
            // 后面每条规则都会立刻再超时一次，最后静默返回原文 —— 用户只会看到"我的净化规则
            // 怎么不生效了"，永远查不到原因。与本项目「catch 前先 rethrow 取消」同一个道理。
            if (e is PurifyTimeoutException) throw e
            if (lenient) acc
            else throw SourceException("净化规则执行失败: ${p.pattern}", e)
        }
    }

    private fun clean(text: String, budget: Budget): String? =
        apply(text, budget).trim().takeIf { it.isNotEmpty() }

    private class Compiled(rule: PurifyRule) {
        private val regex: Regex? = if (rule.isRegex) Regex(rule.pattern) else null
        private val literal: String = rule.pattern
        private val replacement: String = rule.replacement
        val pattern: String = rule.pattern
        /** 跨段规则：正则含换行（字面 `\n` 或真换行）或 DOTALL 标志，需在整章文本上匹配 */
        val multiline: Boolean = rule.isRegex &&
            (rule.pattern.contains("\\n") || rule.pattern.contains('\n') || rule.pattern.contains("(?s)"))
        // 字面替换不回溯，不必包 —— 只有正则才需要那层取字符的关卡
        fun apply(text: String, budget: Budget): String =
            regex?.replace(GuardedText(text, budget), replacement)
                ?: text.replace(literal, replacement)
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

        /**
         * 单次净化（一整章）的时间预算。
         *
         * 正常净化是毫秒级的，1 秒是百倍富余；上限压这么低是因为它**只可能**在病态规则上撞到，
         * 而那种情况下等多久都等不出结果，早停早降级。
         */
        const val BUDGET_MS = 1_000L
    }
}

/** 净化超时：某条规则陷入灾难性回溯，已放弃净化（正文按未净化返回） */
class PurifyTimeoutException : RuntimeException("净化规则执行超时")

/**
 * 净化的时间预算。
 *
 * **为什么非得这么绕**：Java 正则的灾难性回溯既不抛异常、也不理会线程中断标志 —— 它就是不返回。
 * 协程取消是协作式的，`withTimeout` 对它完全无效（换个 Dispatcher 也只是换条线程一起卡死）。
 * 正则引擎在回溯期间唯一会反复回调到我们手里的东西，是输入序列的取字符方法 ——
 * 于是把输入包一层（[GuardedText]），在取字符时检查预算，回溯就有了出口。
 *
 * 每次都读时钟太贵（回溯期间取字符会被调用上亿次），按调用次数分摊：走满 [CHECK_STRIDE] 次
 * 才真读一次 `nanoTime`。
 */
private class Budget {
    private val deadline = System.nanoTime() + Purifier.BUDGET_MS * 1_000_000
    private var countdown = CHECK_STRIDE

    fun check() {
        if (--countdown > 0) return
        countdown = CHECK_STRIDE
        if (System.nanoTime() > deadline) throw PurifyTimeoutException()
    }

    private companion object {
        const val CHECK_STRIDE = 4096
    }
}

/** 取每个字符时都过一道预算关卡的输入序列；除此之外完全透明地代理原文 */
private class GuardedText(
    private val delegate: CharSequence,
    private val budget: Budget,
) : CharSequence {
    override val length: Int get() = delegate.length

    override fun get(index: Int): Char {
        budget.check()
        return delegate[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        GuardedText(delegate.subSequence(startIndex, endIndex), budget)

    override fun toString(): String = delegate.toString()
}
