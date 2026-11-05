package com.radium.inkwell.ui.sourcemanage

import java.util.Locale

/**
 * 校验坏在哪一步：写在失败原因前面（「详情页被拒」「正文超时」）。
 * 从前只存异常原文，看不出是搜索就不行，还是搜得到、读不了 —— 后者才是最常见的坏法。
 */
enum class CheckStep(val label: String) {
    SEARCH("搜索"),
    DETAIL("详情页"),
    TOC("目录"),
    CONTENT("正文"),
}

/**
 * 校验结果的文案：存进库的、书源管理每行显示的，都从这里出。
 *
 * 从前存的是异常原文截 60 个字：超时是「Timed out waiting for 60000 ms」，被拒是「HTTP 403: https://…」
 * 截在半截网址上；可用的写「可用 · 69 章 · 4819ms」—— 章数是拿关键词随手搜到的第一本书的，跟书源好坏无关，
 * 毫秒还得自己换算。现在失败归成几类说人话、带上哪一步，可用的只写耗时（秒）。
 * 库里存着的旧文案，显示时也经这里重写（[okLine]、[display]），不用为了换个说法重跑一轮校验。
 */
internal object CheckMessages {

    /** 没有搜索规则的源只记这一句、一步都没校验。行上不写状态：标题后的「仅发现」「无搜索」已经说清了 */
    const val SEARCH_SKIPPED = "（不校验搜索）"

    fun skippedSearch(saved: String): Boolean = "不校验搜索" in saved

    /** 可用：只写耗时。[respondMs] ≤ 0（没测到的）就光写「可用」 */
    fun ok(respondMs: Long, shortContent: Boolean): String = buildList {
        add("可用")
        if (respondMs > 0) add(String.format(Locale.ROOT, "%.1f 秒", respondMs / 1000.0))
        if (shortContent) add(SHORT_CONTENT)
    }.joinToString(" · ")

    /** 存着的「可用…」→ 显示的。旧的「69 章 · 4819ms」按库里的响应耗时重写 */
    fun okLine(respondMs: Long, saved: String): String = ok(respondMs, SHORT_CONTENT in saved)

    /**
     * 新的一次失败：知道停在哪一步，手里有异常本身。类名也拿来认 —— 有的异常光看消息认不出
     * （SocketTimeoutException 的消息就一个「timeout」，SSLHandshakeException 常常只有一串证书路径）。
     */
    fun failure(step: CheckStep, error: Throwable): String {
        reasonOf("${error::class.java.name}: ${error.message.orEmpty()}")?.let { return it.text(step) }
        val msg = clean(error.message.orEmpty())
        return if (msg.isEmpty()) "${step.label}失败" else "${step.label}出错：$msg"
    }

    /**
     * 存着的失败原因 → 显示的。新文案原样返回（[reasonOf] 只认英文原文与几句引擎报错，认不到自己写出去的中文）；
     * 旧的异常原文认得出的归类 —— 旧文案不知道停在哪步，就不写步骤；认不出的去掉网址与书源名。
     */
    fun display(saved: String): String = reasonOf(saved)?.text(step = null) ?: clean(saved)

    private sealed interface Reason {
        fun text(step: CheckStep?): String
    }

    /** [seconds] = null：单个请求的网络超时（OkHttp 自己的），不是整源的预算 */
    private data class Timeout(val seconds: Long?) : Reason {
        override fun text(step: CheckStep?) =
            "${step?.label.orEmpty()}超时" + (seconds?.let { "（$it 秒）" } ?: "")
    }

    private data class Http(val code: Int) : Reason {
        override fun text(step: CheckStep?): String {
            val s = step?.label.orEmpty()
            return when (code) {
                404 -> "${s}地址失效（HTTP 404）"
                // 一次校验几百个源，最常见的 4xx 之一：过一会儿再校验多半就好了，别和「被拒」混在一起
                429 -> "${s}被限流（HTTP 429）"
                in 500..599 -> "${step?.let { it.label + "时" }.orEmpty()}站点出错（HTTP $code）"
                else -> "${s}被拒（HTTP $code）"
            }
        }
    }

    /** 域名解析不了、连接被拒、连不通：还没走到哪一步，不写步骤 */
    private data object Unreachable : Reason {
        override fun text(step: CheckStep?) = "连不上站点"
    }

    private data object Dropped : Reason {
        override fun text(step: CheckStep?) = "${step?.let { it.label + "时" }.orEmpty()}连接中断"
    }

    private data object BadCert : Reason {
        override fun text(step: CheckStep?) = "站点证书有问题"
    }

    private data object RuleError : Reason {
        override fun text(step: CheckStep?) = "书源规则出错" + (step?.let { "（${it.label}）" } ?: "")
    }

    /**
     * 先去掉网址再认：网址路径里什么字都可能有（/timeout/、/ssl/）。
     * 顺序有讲究：「failed to connect to … after 10000ms」是连接超时，算连不上而不是超时；
     * 「SSLException: … Connection reset by peer」是连接被掐了，不是证书，所以连接中断排在证书前面。
     */
    private fun reasonOf(text: String): Reason? {
        val t = URL.replace(text, "")
        TIMEOUT_BUDGET.find(t)?.let { return Timeout(it.groupValues[1].toLong() / 1000) }
        HTTP.find(t)?.let { return Http(it.groupValues[1].toInt()) }
        return when {
            UNREACHABLE.containsMatchIn(t) -> Unreachable
            DROPPED.containsMatchIn(t) -> Dropped
            BAD_CERT.containsMatchIn(t) -> BadCert
            TIMEOUT_ANY.containsMatchIn(t) -> Timeout(null)
            RULE_ERROR.containsMatchIn(t) -> RuleError
            else -> null
        }
    }

    /** 认不出的原文：去掉网址（连同前面的冒号）、书源名（书源「某某」）和换行，截短 */
    private fun clean(msg: String): String = msg
        .replace(URL_TAIL, "")
        .replace(URL, "")
        .replace(SOURCE_NAME, "")
        .replace(WHITESPACE, " ")
        .trim()
        .trimEnd(':', '：')
        .trim()
        .take(MAX_REASON_CHARS)

    private const val SHORT_CONTENT = "正文偏短"
    private const val MAX_REASON_CHARS = 60

    private val URL = Regex("""https?://\S*""")
    private val URL_TAIL = Regex("""\s*[:：]\s*https?://\S*""")
    private val SOURCE_NAME = Regex("""书源「[^」]*」""")
    private val WHITESPACE = Regex("""\s+""")

    /** withTimeout 到点：整源的时间预算用完了 */
    private val TIMEOUT_BUDGET = Regex("""Timed out waiting for (\d+) ms""")

    /**
     * SourceHttpClient 的「HTTP 403: 网址」。只认带冒号的原文写法 —— 自己写出去的「（HTTP 403）」不能再被认一遍，
     * 否则 [display] 会把新文案里的步骤吃掉。
     */
    private val HTTP = Regex("""\bHTTP ([45]\d\d):""")
    private val UNREACHABLE = Regex(
        """(?i)unable to resolve host|UnknownHostException|failed to connect|connection refused|""" +
            """ConnectException|no route to host|network is unreachable""",
    )
    /** 区分大小写：主机名里的小写 ssl（www.sslxs.com）不算 */
    private val BAD_CERT = Regex(
        """SSLHandshakeException|SSLPeerUnverifiedException|Trust anchor|CertPathValidator|""" +
            """Certificate\w*Exception|Chain validation failed|Hostname \S+ not verified""",
    )
    private val TIMEOUT_ANY = Regex("""(?i)SocketTimeoutException|\btimeout\b|timed out""")
    private val DROPPED = Regex("""(?i)connection reset|unexpected end of stream|broken pipe|stream was reset""")

    /** Rhino 的 JS 报错与引擎自己的几句规则报错 */
    private val RULE_ERROR = Regex(
        """ReferenceError|TypeError|SyntaxError|RangeError|InternalError|EcmaError|EvaluatorException|""" +
            """JavaScriptException|WrappedException|org\.mozilla\.javascript|UnsupportedRuleException|""" +
            """脚本执行|地址脚本|正则执行超出|非法 URL""",
    )
}
