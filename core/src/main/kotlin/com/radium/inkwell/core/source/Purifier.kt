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
    /** Legado 模式：运行期某条正则抛错时退回按字面替换（先于 [lenient] 判断） */
    private val literalFallback: Boolean = false,
) {

    val isEmpty: Boolean get() = compiled.isEmpty()

    /** 逐段落应用；替换后整段变空则丢弃该段 */
    fun apply(elements: List<ContentElement>): List<ContentElement> {
        if (compiled.isEmpty()) return elements
        // 预算覆盖**整章**，而不是每段各发一份：否则一条病态规则会在每个段落上各烧满一次预算，
        // 总耗时 = 预算 × 段数，等于没有上限。
        return guarded { apply(elements, it) }
    }

    fun apply(text: String): String = guarded { apply(text, it) }

    /**
     * 整章一次交给 [RegexWatchdog]：真机上 [GuardedText] 拦不住 ICU 的回溯，只能靠看门狗
     * 让调用方别陪着卡死（阅读器是在互斥锁里净化的，卡住就是此后每一章都转圈）。
     * 整章一次而不是每段每条规则一次：一章几百段 × 几十条规则，逐次换线程的开销就不可忽略了。
     *
     * 本进程内超时过的规则还在列表里，就不再跑 —— 再跑一次只会再钉死一条停不下来的线程。
     */
    private fun <T> guarded(block: (Run) -> T): T {
        if (compiled.none { it.isRegex }) return block(Run(Budget()))
        if (compiled.any { it.isRegex && RegexWatchdog.isPoisoned(it.pattern) }) throw PurifyTimeoutException()
        val run = Run(Budget())
        return try {
            RegexWatchdog.run(current = { run.pattern }) { block(run) }
        } catch (_: RegexBudgetExceeded) {
            throw PurifyTimeoutException()
        }
    }

    /** 一次净化的现场：共享的时间预算，以及此刻正在跑哪条规则（超时时据此拉黑元凶） */
    private class Run(val budget: Budget) {
        @Volatile var pattern: String? = null
    }

    private fun apply(elements: List<ContentElement>, run: Run): List<ContentElement> {
        // 含 `\n` 或 `(?s)`（DOTALL）的规则是**跨段**的：要匹配的文本横跨多个段落，
        // 逐段各自 apply 永远匹配不到（这正是「规则页试算对、书里不生效」的一类成因）。
        // 有这类规则时退回「整章文本 join → apply → 按换行重新拆段」路径。
        if (compiled.any { it.multiline }) return applyCrossParagraph(elements, run)
        return elements.mapNotNull { el ->
            when (el) {
                is ContentElement.Paragraph -> clean(el.text, run)?.let { ContentElement.Paragraph(it) }
                is ContentElement.Heading -> clean(el.text, run)?.let { ContentElement.Heading(el.level, it) }
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
        run: Run,
    ): List<ContentElement> {
        val out = mutableListOf<ContentElement>()
        val buffer = mutableListOf<String>()
        fun flush() {
            if (buffer.isEmpty()) return
            apply(buffer.joinToString("\n"), run).split("\n")
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

    private fun apply(text: String, run: Run): String = compiled.fold(text) { acc, p ->
        run.pattern = p.pattern
        // apply 期兜底：替换串里的 `$`（Illegal group reference）、灾难性回溯的 StackOverflowError
        // 都会在这里炸。runCatching 捕获 Throwable，宽松模式保原文、严格模式抛 SourceException。
        runCatching { p.apply(acc, run.budget) }.getOrElse { e ->
            // 超时是**整章级别的降级信号**，不能被"跳过这条坏规则"的宽松逻辑吞掉：吞了的话
            // 后面每条规则都会立刻再超时一次，最后静默返回原文 —— 用户只会看到"我的净化规则
            // 怎么不生效了"，永远查不到原因。与本项目「catch 前先 rethrow 取消」同一个道理。
            if (e is RegexBudgetExceeded) throw PurifyTimeoutException()
            if (literalFallback) p.applyLiteral(acc)
            else if (lenient) acc
            else throw SourceException("净化规则执行失败: ${p.pattern}", e)
        }
    }

    private fun clean(text: String, run: Run): String? =
        apply(text, run).trim().takeIf { it.isNotEmpty() }

    private class Compiled(rule: PurifyRule) {
        private val regex: Regex? = if (rule.isRegex) Regex(rule.pattern) else null
        private val literal: String = rule.pattern
        private val replacement: String = rule.replacement
        val pattern: String = rule.pattern
        val isRegex: Boolean get() = regex != null
        /** 跨段规则：正则含换行（字面 `\n` 或真换行）或 DOTALL 标志，需在整章文本上匹配 */
        val multiline: Boolean = rule.isRegex &&
            (rule.pattern.contains("\\n") || rule.pattern.contains('\n') || rule.pattern.contains("(?s)"))
        // 字面替换不回溯，不必包 —— 只有正则才需要那层取字符的关卡
        fun apply(text: String, budget: Budget): String =
            regex?.replace(GuardedText(text, budget), replacement)
                ?: text.replace(literal, replacement)

        /** 按字面替换（替换串也按字面，`$` 不是组引用） */
        fun applyLiteral(text: String): String = text.replace(literal, replacement)
    }

    companion object {
        /**
         * 编译不过就是硬错。给替换规则编辑器做校验用：用户正在写的规则写错了，得当场让他看见。
         * 抓正文时的书源规则别用它，见 [legado]。
         */
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
         * 书源 `ruleContent.replaceRegex` 的语义，与 Legado 的 AnalyzeRule.replaceRegex 一致：
         * 正则编译不过（`##(广告`、`##*广告*`），或替换串里的 `$` 是非法组引用，就退回**按字面替换**。
         *
         * 从前这里用 [strict]：一条写坏的正则抛成 SourceException，整本书**每一章**都打不开，
         * 还会触发自动换源 —— 而同一个书源在阅读 APP 里只是那条广告没删掉。超时不在退回之列：
         * 病态正则照样撞预算、整章按未净化返回，看门狗与预算一点不放松。
         */
        fun legado(rules: List<PurifyRule>): Purifier = cached(Mode.LEGADO, rules) {
            Purifier(
                rules.map { rule ->
                    try {
                        Compiled(rule)
                    } catch (_: Exception) {
                        Compiled(rule.copy(isRegex = false))
                    }
                },
                lenient = true,
                literalFallback = true,
            )
        }

        /**
         * 用户自己写的规则：一条正则写错，跳过它就是，不该让整章正文加载失败 ——
         * 那在阅读页只会显示"正文规则未匹配到内容"，用户根本猜不到是自己的净化规则干的。
         */
        fun lenient(rules: List<PurifyRule>): Purifier = cached(Mode.LENIENT, rules) {
            Purifier(
                rules.mapNotNull { runCatching { Compiled(it) }.getOrNull() },
                lenient = true,
            )
        }

        private enum class Mode { LEGADO, LENIENT }

        /**
         * 按「模式 + 规则表」缓存编好的净化器。
         *
         * 抓正文时每章都要按书源的 replaceRegex 与通用规则各建一个，阅读页渲染时再按书内规则建一个 ——
         * 从前每次都把每条正则现编一遍。导入过几百条的 Legado 替换规则包，读一章、预取一章都要重编几百条。
         * 规则是 data class，按值比较；净化器本身不可变（每次 apply 的预算与现场都是现建的），跨线程共享安全。
         * 拉黑走的是 [RegexWatchdog] 的全局表，不随实例走，缓存不会让坏规则漏网。
         */
        private val cache = object : LinkedHashMap<Pair<Mode, List<PurifyRule>>, Purifier>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Mode, List<PurifyRule>>, Purifier>?) =
                size > CACHE_SIZE
        }
        private const val CACHE_SIZE = 32

        private fun cached(mode: Mode, rules: List<PurifyRule>, build: () -> Purifier): Purifier {
            if (rules.isEmpty()) return build()
            // 拷一份当键：调用方传进来的可能是之后还会被改的可变列表
            val key = mode to rules.toList()
            synchronized(cache) { cache[key] }?.let { return it }
            // 锁外编译：几百条正则编一遍不短，别让别的线程陪着等（重了也只是多编一次，结果一样）
            val built = build()
            return synchronized(cache) { cache.getOrPut(key) { built } }
        }

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
 * 预算耗尽。名字是中性的：这套关卡不只净化在用 —— 书源规则里的正则（`##替换##`、`:regex`）
 * 同样来自不可信的第三方书源，同样能写出灾难性回溯，见 [RuleEvaluator]。
 *
 * 各调用方自己决定怎么降级：净化翻译成 [PurifyTimeoutException]（正文按未净化返回），
 * 规则求值按「这条规则没匹配上」处理。
 */
internal class RegexBudgetExceeded : RuntimeException("正则执行超出时间预算")

/**
 * 正则执行的时间预算。
 *
 * **为什么非得这么绕**：Java 正则的灾难性回溯既不抛异常、也不理会线程中断标志 —— 它就是不返回。
 * 协程取消是协作式的，`withTimeout` 对它完全无效（换个 Dispatcher 也只是换条线程一起卡死）。
 * 正则引擎在回溯期间唯一会反复回调到我们手里的东西，是输入序列的取字符方法 ——
 * 于是把输入包一层（[GuardedText]），在取字符时检查预算，回溯就有了出口。
 *
 * 每次都读时钟太贵（回溯期间取字符会被调用上亿次），按调用次数分摊：走满 [CHECK_STRIDE] 次
 * 才真读一次 `nanoTime`。
 */
internal class Budget(budgetMs: Long = Purifier.BUDGET_MS) {
    private val deadline = System.nanoTime() + budgetMs * 1_000_000
    private var countdown = CHECK_STRIDE

    fun check() {
        if (--countdown > 0) return
        countdown = CHECK_STRIDE
        if (System.nanoTime() > deadline) throw RegexBudgetExceeded()
    }

    private companion object {
        const val CHECK_STRIDE = 4096
    }
}

/** 取每个字符时都过一道预算关卡的输入序列；除此之外完全透明地代理原文 */
internal class GuardedText(
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
