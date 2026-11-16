package com.radium.inkwell.core.source.js

import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 阅读来源脚本里 `new JavaImporter()` / `Packages.javax.crypto…` 的**替身**。
 *
 * 阅读给脚本开了整个 JDK，来源作者就直接拿 JDK 做加解密，比如七猫的接口源解正文：
 *
 *     var javaImport = new JavaImporter();
 *     javaImport.importPackage(Packages.java.lang, Packages.javax.crypto.spec, Packages.javax.crypto, Packages.java.util);
 *     with (javaImport) { … Base64.getDecoder().decode(…) … Cipher.getInstance("AES/CBC/PKCS5Padding") … }
 *
 * 我们的脚本沙箱只放行桥对象所在的包（[RhinoScriptRuntime.BRIDGE_ONLY]），`Packages`、`JavaImporter` 根本不存在，
 * 这类来源的正文一律是空的。**不放宽沙箱**：真把 JDK 类交给脚本，`getClass()`、反射、`Runtime` 的口子就又开了。
 * 改成在这个包里照 JDK 的样子写几样**纯计算**的替身 —— Base64、Arrays、String、Cipher 和三种密钥 / 参数规格、MessageDigest、Inflater、Thread.sleep，
 * 方法名、参数照 JDK，脚本不用改；真正的 JDK 对象都攥在这些类的私有字段里，不交给脚本。
 * 阅读跑在 Android 上，另有一批来源（连城读书、书耽、柚免费耽美……）导的是 `Packages.android.util`，
 * Base64 用的是 Android 那套 `decode(字节, 2)` / `encodeToString(字节, NO_WRAP)`，也照它做了（[AndroidBase64]）。
 * 替身之外的类（`java.io.File`、`java.lang.Runtime`、`java.lang.System`……）在 `Packages` 里压根没有，
 * 导进来也是 undefined，和从前一样跑不起来。
 * `org.jsoup`（Jsoup、Connection、TextNode、Parser）也挂在这棵树上，替身本身在 JsoupShim。
 *
 * 只在脚本里出现这几个名字时才装（[COMPAT_TRIGGERS]），装在这一次求值自己的作用域上（[PRELUDE]），不串到别的来源。
 */
class JdkCompat {

    /** `java.lang.String(x)` / `String(bytes, charset)`：字节按 UTF-8（或给定编码）解，别的转成字符串 */
    fun string(value: Any?): String = when (value) {
        null -> "null"
        is ByteArray -> String(value, Charsets.UTF_8)
        // JS 数字到这里是 Double：整数别写成「5.0」
        is Double -> if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    fun string(bytes: ByteArray, charset: String): String = String(bytes, charset(charset))

    /** `new String(字节, 起点, 长度, 编码)`：七猫接口源从 epub 的 zip 里一段一段切出文件名、正文 */
    fun string(bytes: ByteArray, offset: Int, length: Int, charset: String): String =
        String(bytes, offset, length, charset(charset))

    /** `java.security.MessageDigest`：接口源拿它算签名（七猫的 MD5） */
    fun messageDigest(algorithm: String): MessageDigestShim = MessageDigestShim(MessageDigest.getInstance(algorithm))

    /** `java.util.zip.Inflater`：七猫接口源的正文是一份 epub，脚本自己读 zip 目录、逐个文件解压 */
    fun inflater(nowrap: Boolean): InflaterShim = InflaterShim(Inflater(nowrap))

    /** `java.net.URLEncoder` / `URLDecoder`：来源拿它按 GBK 转关键词、解开地址里的参数 */
    fun urlEncode(text: String, charset: String): String = java.net.URLEncoder.encode(text, charset(charset))

    fun urlDecode(text: String, charset: String): String = java.net.URLDecoder.decode(text, charset(charset))

    /**
     * `java.lang.Thread.sleep(毫秒)`：来源在两次请求之间随机停一会儿，免得被站点限流（八叉书库的目录）。
     * 照停，但一次最多 [MAX_SLEEP_MS]；每 100 毫秒看一眼调用方还在不在 —— 脚本睡着时指令计数不走，
     * 离开页面、换源取消了也得能停下（同 RhinoScriptRuntime 的取消检查）
     */
    fun sleep(ms: Long) {
        var left = ms.coerceIn(0, MAX_SLEEP_MS)
        while (left > 0) {
            if (EngineJsHttp.currentJob()?.isActive == false) {
                throw kotlinx.coroutines.CancellationException("脚本所在的引擎调用已取消")
            }
            val step = minOf(left, SLEEP_STEP_MS)
            Thread.sleep(step)
            left -= step
        }
    }

    fun base64Decoder(): Base64DecoderShim = Base64DecoderShim(Base64.getDecoder())

    fun base64MimeDecoder(): Base64DecoderShim = Base64DecoderShim(Base64.getMimeDecoder())

    fun base64UrlDecoder(): Base64DecoderShim = Base64DecoderShim(Base64.getUrlDecoder())

    fun base64Encoder(): Base64EncoderShim = Base64EncoderShim(Base64.getEncoder())

    fun base64UrlEncoder(): Base64EncoderShim = Base64EncoderShim(Base64.getUrlEncoder())

    // android.util.Base64：阅读跑在 Android 上，不少来源导的是 Packages.android.util，调法是「串或字节 + 标志位」

    fun androidBase64Decode(text: String, flags: Int): ByteArray = AndroidBase64.decode(text, flags)

    fun androidBase64Decode(bytes: ByteArray, flags: Int): ByteArray =
        AndroidBase64.decode(String(bytes, Charsets.ISO_8859_1), flags)

    fun androidBase64Encode(bytes: ByteArray, flags: Int): ByteArray =
        AndroidBase64.encodeToString(bytes, flags).toByteArray(Charsets.US_ASCII)

    fun androidBase64EncodeToString(bytes: ByteArray, flags: Int): String = AndroidBase64.encodeToString(bytes, flags)

    fun copyOfRange(bytes: ByteArray, from: Int, to: Int): ByteArray = bytes.copyOfRange(from, to)

    fun copyOf(bytes: ByteArray, length: Int): ByteArray = bytes.copyOf(length)

    fun cipher(transformation: String): CipherShim = CipherShim(Cipher.getInstance(transformation))

    fun secretKey(key: ByteArray, algorithm: String): SecretKeyShim = SecretKeyShim(key.copyOf(), algorithm)

    fun iv(iv: ByteArray): IvParameterShim = IvParameterShim(iv.copyOf())

    fun iv(iv: ByteArray, offset: Int, length: Int): IvParameterShim = IvParameterShim(iv.copyOfRange(offset, offset + length))

    fun gcm(tagBits: Int, iv: ByteArray): GcmParameterShim = GcmParameterShim(tagBits, iv.copyOf())

    // org.jsoup：解析与造节点，见 JsoupShim。`Jsoup.connect` 要这一次求值的网络出口，替身里转给 `java.jsoupConnect`

    fun jsoupParse(html: String, baseUri: String, xml: Boolean): JsElement = JsoupShim.parse(html, baseUri, xml)

    fun jsoupParseBodyFragment(html: String, baseUri: String): JsElement = JsoupShim.parseBodyFragment(html, baseUri)

    fun jsoupTextNode(text: String): JsElement = JsoupShim.textNode(text)

    fun jsoupElement(tag: String): JsElement = JsoupShim.element(tag)

    companion object {
        /**
         * 脚本（或它的 jsLib）里出现这些字才装替身：不用 JDK 的脚本一点开销都没有。
         * `org.jsoup`：阅读的作用域里 `org` 是顶层包，脚本不写 Packages 直接 `org.jsoup.Jsoup.parse(…)`
         */
        val COMPAT_TRIGGERS = listOf("Packages", "JavaImporter", "importPackage", "importClass", "org.jsoup")

        /** [sleep] 一次最多停多久：来源写的都是一秒以内的随机数，再长就是写错了 */
        const val MAX_SLEEP_MS = 3_000L

        private const val SLEEP_STEP_MS = 100L

        fun wanted(script: String, jsLib: String?): Boolean =
            COMPAT_TRIGGERS.any { script.contains(it) || jsLib?.contains(it) == true }

        /**
         * 装在这一次求值的 holder 上：`Packages` 树、`JavaImporter`、顶层的 `importPackage` / `importClass`
         * （导进 holder，脚本后面的代码看得见）。类是 JS 函数，带不带 `new` 都返回替身对象（同 Rhino 里调 Java 类）。
         * `__inkwellJdk` 由调用方先放到 holder 上。
         */
        const val PRELUDE = """
(function (top) {
  var jdk = top.__inkwellJdk;
  function cls(name, make, statics) {
    var f = function () { return make.apply(null, arguments); };
    f.__inkwellClass = name;
    if (statics) for (var k in statics) f[k] = statics[k];
    return f;
  }
  var Str = cls('String', function (a, b, c, d) {
    if (arguments.length > 3) return jdk.string(a, b | 0, c | 0, String(d));
    if (arguments.length > 2) return jdk.string(a, b | 0, c | 0, 'UTF-8');
    return arguments.length > 1 ? jdk.string(a, b) : jdk.string(a);
  });
  // java.util.Base64 与 android.util.Base64 是同一个对象：两边的静态方法名不重叠，
  // 脚本把两个包都导进同一个 JavaImporter 时，谁后导进来都不会把另一套方法盖掉
  function slice(b, off, len) { return jdk.copyOfRange(b, off, off + len); }
  var B64 = cls('Base64', null, {
    getDecoder: function () { return jdk.base64Decoder(); },
    getMimeDecoder: function () { return jdk.base64MimeDecoder(); },
    getUrlDecoder: function () { return jdk.base64UrlDecoder(); },
    getEncoder: function () { return jdk.base64Encoder(); },
    getUrlEncoder: function () { return jdk.base64UrlEncoder(); },
    DEFAULT: 0, NO_PADDING: 1, NO_WRAP: 2, CRLF: 4, URL_SAFE: 8, NO_CLOSE: 16,
    decode: function (input, a, b, c) {
      return arguments.length > 3 ? jdk.androidBase64Decode(slice(input, a, b), c | 0) : jdk.androidBase64Decode(input, a | 0);
    },
    encode: function (bytes, a, b, c) {
      return arguments.length > 3 ? jdk.androidBase64Encode(slice(bytes, a, b), c | 0) : jdk.androidBase64Encode(bytes, a | 0);
    },
    encodeToString: function (bytes, a, b, c) {
      return arguments.length > 3
        ? jdk.androidBase64EncodeToString(slice(bytes, a, b), c | 0)
        : jdk.androidBase64EncodeToString(bytes, a | 0);
    }
  });
  var Arr = cls('Arrays', null, {
    copyOfRange: function (a, from, to) { return jdk.copyOfRange(a, from, to); },
    copyOf: function (a, n) { return jdk.copyOf(a, n); }
  });
  var Ciph = cls('Cipher', null, {
    getInstance: function (t) { return jdk.cipher(String(t)); },
    ENCRYPT_MODE: 1, DECRYPT_MODE: 2, WRAP_MODE: 3, UNWRAP_MODE: 4
  });
  var Key = cls('SecretKeySpec', function (k, alg) { return jdk.secretKey(k, String(alg)); });
  var Iv = cls('IvParameterSpec', function (b, off, len) {
    return arguments.length > 1 ? jdk.iv(b, off, len) : jdk.iv(b);
  });
  var Gcm = cls('GCMParameterSpec', function (bits, b) { return jdk.gcm(bits, b); });
  var Md = cls('MessageDigest', null, { getInstance: function (alg) { return jdk.messageDigest(String(alg)); } });
  var Infl = cls('Inflater', function (nowrap) { return jdk.inflater(!!nowrap); });
  var Thr = cls('Thread', null, { sleep: function (ms) { jdk.sleep(Number(ms) || 0); } });
  var UrlEnc = cls('URLEncoder', null, { encode: function (s, enc) { return jdk.urlEncode(str(s), enc == null ? 'UTF-8' : String(enc)); } });
  var UrlDec = cls('URLDecoder', null, { decode: function (s, enc) { return jdk.urlDecode(str(s), enc == null ? 'UTF-8' : String(enc)); } });
  // org.jsoup：Parser 只是个记号，parse 认出 xmlParser 就按 XML 解析
  function str(v) { return v == null ? '' : String(v); }
  function isParser(p) { return !!(p && p.__inkwellParser); }
  var Prs = cls('Parser', null, {
    xmlParser: function () { return { __inkwellParser: 'xml' }; },
    htmlParser: function () { return { __inkwellParser: 'html' }; }
  });
  var Jsp = cls('Jsoup', null, {
    parse: function (html, base, parser) {
      if (isParser(base)) { parser = base; base = ''; }
      return jdk.jsoupParse(str(html), str(base), !!(parser && parser.__inkwellParser === 'xml'));
    },
    parseBodyFragment: function (html, base) { return jdk.jsoupParseBodyFragment(str(html), str(base)); },
    connect: function (url) { return top.java.jsoupConnect(String(url)); }
  });
  var Conn = cls('Connection', null, {
    Method: { GET: 'GET', POST: 'POST', PUT: 'PUT', DELETE: 'DELETE', PATCH: 'PATCH', HEAD: 'HEAD', OPTIONS: 'OPTIONS', TRACE: 'TRACE' }
  });
  var TxtNode = cls('TextNode', function (t) { return jdk.jsoupTextNode(str(t)); });
  var Elem = cls('Element', function (tag) { return jdk.jsoupElement(String(tag)); });
  var pkgs = {
    java: { lang: { String: Str, Thread: Thr }, util: { Base64: B64, Arrays: Arr, zip: { Inflater: Infl } }, security: { MessageDigest: Md },
      net: { URLEncoder: UrlEnc, URLDecoder: UrlDec } },
    javax: { crypto: { Cipher: Ciph, spec: { SecretKeySpec: Key, IvParameterSpec: Iv, GCMParameterSpec: Gcm } } },
    android: { util: { Base64: B64 } },
    org: { jsoup: { Jsoup: Jsp, Connection: Conn, nodes: { TextNode: TxtNode, Element: Elem }, parser: { Parser: Prs } } }
  };
  function importInto(target, what) {
    if (!what) return;
    if (what.__inkwellClass) { target[what.__inkwellClass] = what; return; }
    for (var k in what) if (what[k] && what[k].__inkwellClass) target[k] = what[k];
  }
  function JavaImporter() {
    var self = this instanceof JavaImporter ? this : Object.create(JavaImporter.prototype);
    for (var i = 0; i < arguments.length; i++) importInto(self, arguments[i]);
    return self;
  }
  JavaImporter.prototype.importPackage = function () {
    for (var i = 0; i < arguments.length; i++) importInto(this, arguments[i]);
  };
  JavaImporter.prototype.importClass = JavaImporter.prototype.importPackage;
  top.Packages = pkgs;
  top.org = pkgs.org;
  top.JavaImporter = JavaImporter;
  top.importPackage = function () { for (var i = 0; i < arguments.length; i++) importInto(top, arguments[i]); };
  top.importClass = top.importPackage;
})(this);
"""
    }
}

class Base64DecoderShim internal constructor(private val decoder: Base64.Decoder) {
    fun decode(text: String): ByteArray = decoder.decode(text)

    fun decode(bytes: ByteArray): ByteArray = decoder.decode(bytes)
}

class Base64EncoderShim internal constructor(private val encoder: Base64.Encoder) {
    fun encode(bytes: ByteArray): ByteArray = encoder.encode(bytes)

    fun encodeToString(bytes: ByteArray): String = encoder.encodeToString(bytes)
}

/** `SecretKeySpec`：只留 JDK 那两个公开的取值方法 */
class SecretKeyShim internal constructor(private val key: ByteArray, private val algorithm: String) {
    fun getEncoded(): ByteArray = key.copyOf()

    fun getAlgorithm(): String = algorithm
}

/** `Cipher.init` 的第三个参数：IV 或 GCM 参数 */
sealed interface CipherParameterShim

class IvParameterShim internal constructor(private val iv: ByteArray) : CipherParameterShim {
    fun getIV(): ByteArray = iv.copyOf()
}

class GcmParameterShim internal constructor(private val tagBits: Int, private val iv: ByteArray) : CipherParameterShim {
    fun getTLen(): Int = tagBits

    fun getIV(): ByteArray = iv.copyOf()
}

/** `MessageDigest`：只转交算摘要的几个方法 */
class MessageDigestShim internal constructor(private val digest: MessageDigest) {
    fun update(bytes: ByteArray) {
        digest.update(bytes)
    }

    fun update(bytes: ByteArray, offset: Int, length: Int) {
        digest.update(bytes, offset, length)
    }

    fun digest(): ByteArray = digest.digest()

    fun digest(bytes: ByteArray): ByteArray = digest.digest(bytes)

    fun reset() {
        digest.reset()
    }

    fun getAlgorithm(): String = digest.algorithm
}

/** `Inflater`：解压到脚本给的字节数组里（脚本先 `Arrays.copyOf` 出一块，解完读它），方法同 JDK */
class InflaterShim internal constructor(private val inflater: Inflater) {
    fun setInput(bytes: ByteArray) {
        inflater.setInput(bytes)
    }

    fun setInput(bytes: ByteArray, offset: Int, length: Int) {
        inflater.setInput(bytes, offset, length)
    }

    fun inflate(out: ByteArray): Int = inflater.inflate(out)

    fun inflate(out: ByteArray, offset: Int, length: Int): Int = inflater.inflate(out, offset, length)

    fun finished(): Boolean = inflater.finished()

    fun needsInput(): Boolean = inflater.needsInput()

    fun getRemaining(): Int = inflater.remaining

    fun getTotalIn(): Int = inflater.totalIn

    fun getTotalOut(): Int = inflater.totalOut

    fun reset() {
        inflater.reset()
    }

    fun end() {
        inflater.end()
    }
}

/** `javax.crypto.Cipher`：真的那个攥在私有字段里，只转交加解密这几个方法 */
class CipherShim internal constructor(private val cipher: Cipher) {
    fun init(mode: Int, key: SecretKeyShim) {
        cipher.init(mode, jdkKey(key))
    }

    fun init(mode: Int, key: SecretKeyShim, params: CipherParameterShim) {
        val spec = when (params) {
            is IvParameterShim -> IvParameterSpec(params.getIV())
            is GcmParameterShim -> GCMParameterSpec(params.getTLen(), params.getIV())
        }
        cipher.init(mode, jdkKey(key), spec)
    }

    fun update(bytes: ByteArray): ByteArray = cipher.update(bytes) ?: ByteArray(0)

    fun doFinal(): ByteArray = cipher.doFinal()

    fun doFinal(bytes: ByteArray): ByteArray = cipher.doFinal(bytes)

    fun getIV(): ByteArray? = cipher.iv

    fun getBlockSize(): Int = cipher.blockSize

    private fun jdkKey(key: SecretKeyShim) = SecretKeySpec(key.getEncoded(), key.getAlgorithm())
}

/**
 * `android.util.Base64` 的编解码规则，标志位同它（NO_PADDING 1、NO_WRAP 2、CRLF 4、URL_SAFE 8；NO_CLOSE 只管流，这里无关）。
 *
 * 和 `java.util.Base64` 不一样的两处，来源的签名、解密都靠它对上：
 * - 编码不带 NO_WRAP 时**每 76 个字符换一次行，末尾也带一个换行**（DEFAULT 就是这样）。拿它的结果再算签名的，
 *   少一个换行就对不上；
 * - 解码很宽松：字母表以外的字符（换行、空格、另一套字母表的 `-_` / `+/`）一律跳过，结尾的 `=` 可有可无。
 */
internal object AndroidBase64 {
    private const val NO_PADDING = 1
    private const val NO_WRAP = 2
    private const val CRLF = 4
    private const val URL_SAFE = 8
    private const val LINE_CHARS = 76

    fun encodeToString(bytes: ByteArray, flags: Int): String {
        var encoder = if (flags and URL_SAFE != 0) Base64.getUrlEncoder() else Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        val text = encoder.encodeToString(bytes)
        if (flags and NO_WRAP != 0 || text.isEmpty()) return text
        val newline = if (flags and CRLF != 0) "\r\n" else "\n"
        return text.chunked(LINE_CHARS).joinToString("") { it + newline }
    }

    fun decode(text: String, flags: Int): ByteArray {
        val urlSafe = flags and URL_SAFE != 0
        val clean = StringBuilder(text.length)
        for (c in text) {
            if (c == '=') break // 填充即结尾
            if (inAlphabet(c, urlSafe)) clean.append(c)
        }
        val decoder = if (urlSafe) Base64.getUrlDecoder() else Base64.getDecoder()
        return decoder.decode(clean.toString())
    }

    private fun inAlphabet(c: Char, urlSafe: Boolean): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            (if (urlSafe) c == '-' || c == '_' else c == '+' || c == '/')
}
