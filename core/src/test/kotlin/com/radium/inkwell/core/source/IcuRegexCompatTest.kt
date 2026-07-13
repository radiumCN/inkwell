package com.radium.inkwell.core.source

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Android 的 java.util.regex 由 ICU 实现，比桌面 JVM 严格：正则里的裸 `{` / `}` 会直接编译失败
 * （U_REGEX_BAD_INTERVAL / U_REGEX_RULE_SYNTAX），而 JVM 把它们当字面量放行。
 *
 * 这类差异只在真机暴露，桌面单测与 CI 全绿，代价却是整批书源导入失败（见 `\{\{([^}]*)}}`
 * 让 339 个书源报「解析失败: Syntax error in regexp pattern」）。这里静态兜底：源码里的正则字面量
 * 中，花括号必须转义，或构成合法量词（`{3}` / `{0,30}` / `{2,}`）。
 */
class IcuRegexCompatTest {

    @Test
    fun `正则字面量不含 ICU 不接受的裸花括号`() {
        val offenders = mutableListOf<String>()
        sourceFiles().forEach { file ->
            val text = file.readText()
            regexLiterals(text).forEach { (pattern, offset) ->
                bareBrace(pattern)?.let { why ->
                    val line = text.take(offset).count { it == '\n' } + 1
                    offenders += "${file.path}:$line  $why\n    pattern: $pattern"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "以下正则含裸花括号，Android(ICU) 上会抛 PatternSyntaxException；请转义为 \\{ \\}：\n" +
                    offenders.joinToString("\n")
            )
        }
    }

    /** 返回不合法的原因，合法则 null */
    private fun bareBrace(pattern: String): String? {
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i++ // 跳过转义字符
                inClass && c == ']' -> inClass = false
                inClass -> Unit // 字符类内部的 { } 是字面量，ICU 接受
                c == '[' -> inClass = true
                c == '{' -> {
                    val end = quantifierEnd(pattern, i)
                        ?: return "位置 $i 的 `{` 既未转义也不是量词"
                    i = end // 合法量词，连同收尾的 } 一起跳过
                }
                c == '}' -> return "位置 $i 的 `}` 未转义"
            }
            i++
        }
        return null
    }

    /** pattern[start] == '{'；是 {n} / {n,} / {n,m} 时返回收尾 `}` 的下标，否则 null */
    private fun quantifierEnd(pattern: String, start: Int): Int? {
        val close = pattern.indexOf('}', start + 1).takeIf { it > 0 } ?: return null
        val body = pattern.substring(start + 1, close)
        return if (body.matches(Regex("\\d+(,\\d*)?"))) close else null
    }

    private fun sourceFiles(): List<File> =
        File("..").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "build" !in it.path.split(File.separator) }
            .toList()
            .also { check(it.size > 20) { "没找到源码，工作目录不对：${File("..").absolutePath}" } }

    /** 抽出 Regex("…") / Pattern.compile("…") / Regex("""…""") 里的正则，返回 (实际 pattern, 源码偏移) */
    private fun regexLiterals(text: String): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        RAW_LITERAL.findAll(text).forEach { out += it.groupValues[1] to it.range.first }
        ESCAPED_LITERAL.findAll(text).forEach { out += unescape(it.groupValues[1]) to it.range.first }
        return out
    }

    /** Kotlin 转义字符串字面量 → 实际字符（\\d → \d，\" → "） */
    private fun unescape(literal: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < literal.length) {
            val c = literal[i]
            if (c == '\\' && i + 1 < literal.length) {
                when (val n = literal[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\', '"', '\'', '$' -> sb.append(n)
                    else -> sb.append('\\').append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private companion object {
        val ESCAPED_LITERAL =
            Regex("(?:Regex|Pattern\\.compile)\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val RAW_LITERAL =
            Regex("(?:Regex|Pattern\\.compile)\\(\\s*\"\"\"([\\s\\S]*?)\"\"\"")
    }
}
