package com.radium.inkwell.core.source

import com.radium.inkwell.core.util.toHex
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** 书源 JSON 统一编解码实例；存的是 Legado 原生格式，未知字段忽略 */
val BookSourceJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}

/**
 * 书源规则根对象 —— **Legado 原生形状**（字段名与开源阅读一致）。
 *
 * 规则串在运行期由 [LegadoRuleAnalyzer] 直接编译求值，不再经导入期转译。只覆盖小说源
 * （[BookSourceTypes.TEXT]）；音频/漫画/文件/视频源在导入与 WebDAV 同步时被过滤，见 [BookSourceTypes]。
 *
 * 顶层访问器（[id]/[name]/[baseUrl]/[group]/[search]/[explore]/[headers]/[rateLimit]）是给
 * 引擎与 app 层的兼容门面：它们从原生字段派生，让上层无需了解 Legado 字段名。
 */
@Serializable
data class BookSourceRule(
    val bookSourceUrl: String,
    val bookSourceName: String = "",
    val bookSourceGroup: String = "",
    /** 见 [BookSourceTypes]；本应用只支持文本小说（0） */
    val bookSourceType: Int = BookSourceTypes.TEXT,
    val bookSourceComment: String = "",
    val enabled: Boolean = true,
    val enabledExplore: Boolean = true,
    /**
     * 源级请求头：JSON 字符串，或返回 JSON 字符串的脚本（`@js:…` / `<js>…</js>`，阅读两种都认）。
     * JSON 解析一次就记住（[headers]）；脚本由引擎每次请求现跑（[headerScript]，见 BookSourceEngine.sourceHeaders）。
     * 有的合集直接写成 JSON 对象而不是字符串，照样认（[LenientJsonText]）
     */
    @Serializable(with = LenientJsonText::class) val header: String? = null,
    /** 并发限制："500"(毫秒间隔) 或 "1/1000"(次/毫秒) */
    val concurrentRate: String? = null,
    val searchUrl: String? = null,
    @Serializable(with = LenientJsonText::class) val exploreUrl: String? = null,
    /**
     * 阅读的登录网址或脚本。不少来源也把公用函数写在这里、规则开头 `eval(String(source.loginUrl))` 加载 ——
     * 脚本里的 `source.loginUrl` 读的就是它（见 [scriptFacts]）。这个字段之前导入的来源没存它：重新导入一次才有
     */
    val loginUrl: String? = null,
    /** 阅读的登录界面，可以是控件 JSON，也可以是生成控件列表的脚本 */
    @Serializable(with = LenientJsonText::class) val loginUi: String? = null,
    val loginCheckJs: String? = null,
    // 规则集读得宽：对象、JSON 字符串都认，别的写法（`[]`、乱填的值）当没有，同阅读的导入 —— 见 [LenientRuleSet]
    @Serializable(with = LenientSearchRuleSet::class) val ruleSearch: SearchRuleSet? = null,
    @Serializable(with = LenientSearchRuleSet::class) val ruleExplore: SearchRuleSet? = null,
    @Serializable(with = LenientBookInfoRuleSet::class) val ruleBookInfo: BookInfoRuleSet? = null,
    @Serializable(with = LenientTocRuleSet::class) val ruleToc: TocRuleSet? = null,
    @Serializable(with = LenientContentRuleSet::class) val ruleContent: ContentRuleSet? = null,
    /**
     * 公共脚本库（Legado 的 jsLib）：规则、模板、地址里的脚本都能调它定义的函数。
     * 阅读的远程库写法（`{"名字":"地址"}`）不下载，当没有库。
     * 这个字段之前导入的书源没存它：重新导入一次才有
     */
    val jsLib: String? = null,
) {
    /** 书源唯一标识 = 站点地址（含 Legado 的 `#后缀`，同站多源靠它区分） */
    val id: String get() = bookSourceUrl

    val name: String get() = bookSourceName.ifBlank { bookSourceUrl }

    /**
     * 相对链接解析基准。剥掉 Legado 的 `#后缀`（常含 emoji，用于区分同站重复书源），
     * 它不是真实地址；留着会污染相对链接、还会随 `Referer:{{baseUrl}}` 发出去被 OkHttp 拒收。
     */
    val baseUrl: String get() = bookSourceUrl.substringBefore('#').trimEnd('/')

    val group: String get() = bookSourceGroup

    val hasLogin: Boolean get() = !loginUrl.isNullOrBlank() ||
        loginUi?.filterNot(Char::isWhitespace)?.let { it.isNotEmpty() && it != "[]" } == true

    /**
     * 登录校验脚本也能写会话。按完整规则隔离会话，登录入口仍单独由 [hasLogin] 决定。
     *
     * 摘要算的是**重新编码**的规则：以后新认的字段（解码不再丢掉）会让同一个来源的摘要变掉，已经登录的会话就找不着了，
     * 升级完人被悄悄登出。所以新认的字段算摘要前清掉 —— 0.6.1 起认的 `checkKeyWord`（从前导入时丢了）就是这样。
     * 以后再给规则加字段，照这里一并清掉
     */
    val loginSessionId: String? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!hasLogin && loginCheckJs.isNullOrBlank()) null else MessageDigest.getInstance("SHA-256")
            .digest(withoutLaterFields().toJson().toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun withoutLaterFields(): BookSourceRule = copy(
        ruleSearch = ruleSearch?.copy(checkKeyWord = null),
        ruleExplore = ruleExplore?.copy(checkKeyWord = null),
    )

    /** Legado 无源级编码字段；编码来自 searchUrl 的 `,{charset}` 选项或响应头 */
    val charset: String? get() = null

    // headers / rateLimit / explore 是解析出来的，算一次就记住：每个请求都要读 headers 和 rateLimit
    // （JSON 解析 + 正则），搜一轮几百个源、目录几十页就是几百上千遍。规则对象不可变，懒算安全；
    // 带委托的属性不参与序列化，也不进 data class 的 equals / copy。
    val headers: Map<String, String> by lazy(LazyThreadSafetyMode.PUBLICATION) { parseHeaderJson(header) }

    /** [header] 写成脚本时的脚本本体（去掉 `@js:` / `<js>` 包装）；JSON 写法、没写为 null */
    val headerScript: String? by lazy(LazyThreadSafetyMode.PUBLICATION) { headerScriptOf(header) }

    /**
     * 来源自己没写 User-Agent 时发哪个：从阅读导入的发阅读的默认 UA（[SourceHttpClient.LEGADO_UA]，桌面 Chrome），
     * 「新建来源」向导做的发我们的手机 UA（[SourceHttpClient.DEFAULT_UA]）—— 各自的规则是照着哪一套页面写的，就发哪个。
     *
     * 向导做的来源现在把手机 UA 写进了 [header]（SourceDraftBuilder.build），走不到这里；
     * 这里认的是之前做好、请求头里没写 UA 的那些，靠向导留下的备注认（[NATIVE_DRAFT_COMMENTS]）。
     */
    val defaultUserAgent: String
        get() = if (isNativeDraft) SourceHttpClient.DEFAULT_UA else SourceHttpClient.LEGADO_UA

    /**
     * 「新建来源」向导做的（不是从阅读导入的），靠向导留下的备注认（[NATIVE_DRAFT_COMMENTS]）。
     * 向导写出的规则是照我们自己的约定拼好的：发手机 UA（[defaultUserAgent]）、POST 表单体已经编码好（BookSourceEngine.doFetch）
     */
    val isNativeDraft: Boolean
        get() = NATIVE_DRAFT_COMMENTS.any { bookSourceComment.startsWith(it) }

    val rateLimit: RateLimitRule? by lazy(LazyThreadSafetyMode.PUBLICATION) { parseConcurrentRate(concurrentRate) }

    /** 具备可用搜索能力时非空（有 searchUrl 且有列表规则），供上层 `search != null` 判断 */
    val search: SearchRuleSet? get() = ruleSearch?.takeIf { !searchUrl.isNullOrBlank() }

    /** 发现页目录，从 exploreUrl 解析（`名称::地址` 多行 / JSON 数组）；动态(JS)地址得空列表 */
    val explore: List<ExploreItem> by lazy(LazyThreadSafetyMode.PUBLICATION) { parseExplore(exploreUrl, enabledExplore) }

    /**
     * 脚本里 `source.…` 读得到的来源字段（阅读交给脚本的 `source` 是来源对象本身）。每条规则、每个列表项都新建一个
     * SourceBridge，规则集序列化成键值表这一步在这里算一次
     */
    internal val scriptFacts: com.radium.inkwell.core.source.js.SourceFacts by lazy(LazyThreadSafetyMode.PUBLICATION) {
        com.radium.inkwell.core.source.js.SourceFacts(
            name = bookSourceName,
            comment = bookSourceComment,
            group = bookSourceGroup,
            type = bookSourceType,
            loginUrl = loginUrl,
            loginUi = loginUi,
            hasLogin = hasLogin,
            header = header,
            jsLib = jsLib,
            searchUrl = searchUrl,
            exploreUrl = exploreUrl,
            ruleSets = buildMap {
                ruleSearch?.let { put("ruleSearch", ruleFields(SearchRuleSet.serializer(), it)) }
                ruleExplore?.let { put("ruleExplore", ruleFields(SearchRuleSet.serializer(), it)) }
                ruleBookInfo?.let { put("ruleBookInfo", ruleFields(BookInfoRuleSet.serializer(), it)) }
                ruleToc?.let { put("ruleToc", ruleFields(TocRuleSet.serializer(), it)) }
                ruleContent?.let { put("ruleContent", ruleFields(ContentRuleSet.serializer(), it)) }
            },
        )
    }

    fun toJson(): String = BookSourceJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String): BookSourceRule =
            BookSourceJson.decodeFromString(serializer(), text)
    }
}

/** 搜索/发现的列表与字段规则（Legado ruleSearch/ruleExplore） */
@Serializable
data class SearchRuleSet(
    val bookList: String? = null,
    val name: String? = null,
    val author: String? = null,
    val kind: String? = null,
    val wordCount: String? = null,
    val lastChapter: String? = null,
    val intro: String? = null,
    val coverUrl: String? = null,
    val bookUrl: String? = null,
    /** 可选扩展：规则明确提取的作品更新时间。 */
    val updateTime: String? = null,
    /**
     * 校验来源时搜的词（阅读的 `ruleSearch.checkKeyWord`）。来源作者挑一个这个站一定搜得到的词：
     * 有的站只收某一类书（「催眠」「玩偶姐姐」），有的接口按分类名搜（我是盐神的「回答」），拿通用的「我的」去搜
     * 必然「搜索无结果」，被判失效。从前导入时这个字段直接丢了
     */
    val checkKeyWord: String? = null,
)

/** 详情页规则（Legado ruleBookInfo）；[init] 在其余字段之前求值（常见 `@put`） */
@Serializable
data class BookInfoRuleSet(
    val init: String? = null,
    val name: String? = null,
    val author: String? = null,
    val kind: String? = null,
    val wordCount: String? = null,
    val lastChapter: String? = null,
    val intro: String? = null,
    val coverUrl: String? = null,
    val tocUrl: String? = null,
    /**
     * Legado 的「允许修改书名作者」：非空（写什么都行）时，详情页的书名、作者盖过搜索列表里的；
     * 空着就只拿来补搜索列表的空缺。给搜索列表把书名截短、作者带前缀的站用，见 [bookNameAndAuthor]
     */
    val canReName: String? = null,
)

/** 目录规则（Legado ruleToc） */
@Serializable
data class TocRuleSet(
    val chapterList: String? = null,
    val chapterName: String? = null,
    val chapterUrl: String? = null,
    val isVolume: String? = null,
    val updateTime: String? = null,
    val nextTocUrl: String? = null,
)

/** 正文规则（Legado ruleContent） */
@Serializable
data class ContentRuleSet(
    val content: String? = null,
    val nextContentUrl: String? = null,
    val webJs: String? = null,
    val sourceRegex: String? = null,
    /** `##正则##替换` 净化，可能多行；与用户全局净化合并 */
    val replaceRegex: String? = null,
    val imageStyle: String? = null,
)

/**
 * 规则集（ruleSearch / ruleExplore / ruleBookInfo / ruleToc / ruleContent）的宽松读法，同阅读导入时的 GSON：
 * 是对象照常读；是字符串、里面是一个 JSON 对象的，解开再读（有的合集导出时把规则集存成了字符串）；
 * 其余（`[]`、数字、解不开的字符串）当这个规则集没写。
 *
 * 从前一律按对象读，一个来源把没用到的发现规则写成 `"ruleExplore": []`，整条解析失败、导入时被跳过 ——
 * 搜索、目录、正文明明都好好的（UU看书、肉文小说这几个就是这么没的）。写出去照旧是对象。
 */
abstract class LenientRuleSet<T : Any>(private val inner: KSerializer<T>) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = inner.descriptor.nullable

    override fun deserialize(decoder: Decoder): T? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeNullableSerializableValue(inner.nullable)
        val obj = when (val el = json.decodeJsonElement()) {
            is JsonObject -> el
            is JsonPrimitive -> el.contentOrNull
                ?.takeIf { el.isString && it.trimStart().startsWith("{") }
                ?.let { runCatching { json.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            else -> null
        } ?: return null
        return runCatching { json.json.decodeFromJsonElement(inner, obj) }.getOrNull()
    }

    override fun serialize(encoder: Encoder, value: T?) {
        encoder.encodeNullableSerializableValue(inner, value)
    }
}

/**
 * 本该是字符串、合集里却常写成 JSON 对象 / 数组的字段（`header`、`exploreUrl`、`loginUi`）：字符串照读，
 * 对象、数组按 JSON 原文收下（阅读那边这几个字段装的本来就是 JSON 文本），null 和别的写法当没写。
 * 从前按字符串硬读，一个来源把 header 写成 `{"User-Agent": …}` 对象，整条解析失败、导入时被跳过（思兔小说）
 */
object LenientJsonText : KSerializer<String?> {
    override val descriptor: SerialDescriptor = String.serializer().descriptor.nullable

    override fun deserialize(decoder: Decoder): String? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeNullableSerializableValue(String.serializer().nullable)
        return when (val el = json.decodeJsonElement()) {
            is JsonPrimitive -> el.contentOrNull?.takeIf { el.isString }
            is JsonObject, is kotlinx.serialization.json.JsonArray -> el.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        encoder.encodeNullableSerializableValue(String.serializer().nullable, value)
    }
}

/** 规则集 → 阅读字段名的键值表（没写的规则不在里面），给脚本的 `source.ruleSearch.author` */
private fun <T> ruleFields(serializer: KSerializer<T>, value: T): Map<String, String> =
    BookSourceJson.encodeToJsonElement(serializer, value).jsonObject.mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
    }.toMap()

object LenientSearchRuleSet : LenientRuleSet<SearchRuleSet>(SearchRuleSet.serializer())
object LenientBookInfoRuleSet : LenientRuleSet<BookInfoRuleSet>(BookInfoRuleSet.serializer())
object LenientTocRuleSet : LenientRuleSet<TocRuleSet>(TocRuleSet.serializer())
object LenientContentRuleSet : LenientRuleSet<ContentRuleSet>(ContentRuleSet.serializer())

/** 令牌桶限速：每 intervalMs 补一个令牌，桶容量 burst */
@Serializable
data class RateLimitRule(
    val intervalMs: Long = 0,
    val burst: Int = 1,
)

/** 净化规则：对段落文本做替换，替换后全空的段落丢弃 */
@Serializable
data class PurifyRule(
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = true,
)

/** 发现页一项：显示名 + 地址（可含 {{page}}） */
data class ExploreItem(val name: String, val url: String)

// ---- 原生字段解析（门面访问器用） ----

private val headerJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 「新建来源」向导写进 bookSourceComment 的开头（SourceDraftBuilder.build），见 [BookSourceRule.defaultUserAgent] */
internal const val NATIVE_DRAFT_COMMENT = "由「新建来源」根据 "

/** 认向导做的来源用：后一个是功能改名前（0.5.x）的写法，那时做的来源备注里还是它 */
internal val NATIVE_DRAFT_COMMENTS = listOf(NATIVE_DRAFT_COMMENT, "由「新建书源」根据 ")

/** 请求头里写的 User-Agent（键不分大小写）；没写为 null */
internal fun Map<String, String>.userAgent(): String? =
    entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value

internal fun parseHeaderJson(raw: String?): Map<String, String> {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty() || t.startsWith("@js", ignoreCase = true) || t.contains("<js>", ignoreCase = true)) {
        return emptyMap()
    }
    return runCatching { headerObject(t) }
        .recoverCatching { headerObject(singleQuotedToJson(t)) }
        .getOrDefault(emptyMap())
}

private fun headerObject(json: String): Map<String, String> =
    headerJson.parseToJsonElement(json).jsonObject.entries.associate { (k, v) ->
        k to ((v as? JsonPrimitive)?.content ?: v.toString())
    }

/**
 * 单引号写的 JSON（`{'User-Agent': '…'}`）→ 双引号的。阅读先严格解析、不行再交给 GSON 的宽松模式，宽松模式认单引号；
 * kotlinx 的 isLenient 不认，从前整条请求头解析失败、一个头都没发 —— 语料里十来个来源这么写（起点中文、69书吧、
 * 猫眼看书的 client-device……），独步小说要手机 UA 才给搜索结果，拿到的是默认 UA。
 * 只改引号：单引号串里的 `"` 转义、`\'` 还原成 `'`；双引号串原样抄过去（里面的单引号不动）
 */
private fun singleQuotedToJson(s: String): String {
    val out = StringBuilder(s.length + 8)
    var quote: Char? = null
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            quote == null && (c == '\'' || c == '"') -> { quote = c; out.append('"') }
            quote == null -> out.append(c)
            c == '\\' && i + 1 < s.length -> {
                val next = s[i + 1]
                // 单引号串里的 \' 在 JSON 里不是合法转义，还原成字面的 '
                if (quote == '\'' && next == '\'') out.append('\'') else out.append(c).append(next)
                i++
            }
            c == quote -> { quote = null; out.append('"') }
            c == '"' -> out.append("\\\"") // 只会出现在单引号串里
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

/**
 * 源级 header 的脚本写法（同阅读 BaseSource.getHeaderMap）：`@js:` 开头取后面的全部，`<js>` 开头取到最后一个 `</js>`。
 * 31 个来源这么写（2893 个里）：签名带时间戳、`"User-Agent": java.getWebViewUA()`、`"Referer": baseUrl`。
 * 从前一律当没有源级请求头，请求头里该带的 UA、签名一个都没发出去
 */
internal fun headerScriptOf(raw: String?): String? {
    val t = raw?.trim().orEmpty()
    val script = when {
        t.startsWith("@js:", ignoreCase = true) -> t.substring(4)
        t.startsWith("<js>", ignoreCase = true) -> {
            val end = t.lastIndexOf("</js>", ignoreCase = true)
            if (end >= 4) t.substring(4, end) else t.substring(4)
        }
        else -> return null
    }
    return script.takeIf { it.isNotBlank() }
}

/** concurrentRate: "500"(毫秒间隔) 或 "1/1000"(次数/毫秒) */
internal fun parseConcurrentRate(raw: String?): RateLimitRule? {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty()) return null
    t.toLongOrNull()?.let { return if (it > 0) RateLimitRule(intervalMs = it) else null }
    val m = RATE_FRACTION.find(t) ?: return null
    val (count, ms) = m.destructured
    val interval = ms.toLong() / count.toLong().coerceAtLeast(1)
    return if (interval > 0) RateLimitRule(intervalMs = interval) else null
}

private val RATE_FRACTION = Regex("^(\\d+)/(\\d+)$")

/**
 * 解析 exploreUrl 为发现页列表。支持 Legado 两种写法：
 * - `名称::地址`，项之间用换行**或 `&&`** 分隔（Legado 按 `(&&|\n)+` 拆）
 * - JSON 数组 `[{"title":"…","url":"…"}, …]`
 * 动态地址（`<js>`/`@js:`/整串是表达式）无法静态列举，返回空。
 *
 * 从前只按换行拆：写成一行的 `玄幻::/a/{{page}}&&武侠::/b/{{page}}` 只剩一个分类，
 * 地址还是 `/a/{{page}}&&武侠::/b/{{page}}` 这么一长串拼起来的废地址。
 */
internal fun parseExplore(raw: String?, enabled: Boolean): List<ExploreItem> {
    if (!enabled) return emptyList()
    val t = raw?.trim().orEmpty()
    if (t.isEmpty() || t.startsWith("@js", ignoreCase = true) || t.contains("<js>", ignoreCase = true)) {
        return emptyList()
    }
    if (t.startsWith("[")) {
        val arr = runCatching { headerJson.parseToJsonElement(t) }.getOrNull()
        val list = (arr as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["title"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val url = (o["url"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (name.isEmpty() || url.isEmpty()) null else ExploreItem(name, url)
        }
    }
    return t.split(LEGADO_ITEM_SEPARATOR).mapNotNull { line ->
        val s = line.trim().trimEnd('&')
        if (s.isEmpty()) return@mapNotNull null
        val sep = s.indexOf("::")
        if (sep <= 0) return@mapNotNull null
        ExploreItem(s.substring(0, sep).trim(), s.substring(sep + 2).trim())
    }
}

/**
 * Legado 分类列表（发现页 exploreUrl、订阅源 sortUrl）的项分隔符：换行或 `&&`，连续的算一个。
 * 换行含单独的 `\r`：从前按 `lines()` 拆的 exploreUrl 认它，别在这里丢了。
 */
internal val LEGADO_ITEM_SEPARATOR = Regex("(&&|\\r\\n?|\\n)+")
