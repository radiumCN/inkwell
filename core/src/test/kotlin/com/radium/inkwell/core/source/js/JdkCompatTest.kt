package com.radium.inkwell.core.source.js

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * `JavaImporter` / `Packages` 的替身（JdkCompat）：阅读来源直接拿 JDK 做加解密的脚本能跑，沙箱照旧封着。
 */
class JdkCompatTest {

    private val runtime = RhinoScriptRuntime()

    /** 七猫接口源解正文的原样写法：密文 = Base64(IV 16 字节 + AES/CBC 密文) */
    private val qimaoDecode = """
        var javaImport = new JavaImporter();
        javaImport.importPackage(
            Packages.java.lang,
            Packages.javax.crypto.spec,
            Packages.javax.crypto,
            Packages.java.util
        );
        with(javaImport) {
            function decode(content) {
                var ivEncData = Base64.getDecoder().decode(String(content));
                var key = SecretKeySpec(String("242ccb8230d709e1").getBytes(), "AES");
                var iv = IvParameterSpec(Arrays.copyOfRange(ivEncData, 0, 16));
                var chipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                chipher.init(2, key, iv);
                return String(chipher.doFinal(Arrays.copyOfRange(ivEncData, 16, ivEncData.length)));
            }
        }
        decode(content)
    """.trimIndent()

    private fun qimaoEncrypt(plain: String): String {
        val iv = ByteArray(16) { (it * 7 + 3).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("242ccb8230d709e1".toByteArray(), "AES"), IvParameterSpec(iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    @Test
    fun `七猫的正文解密脚本原样能跑`() {
        val plain = "<p>第一章 陨落的天才</p><p>“斗之力，三段！”</p>"
        assertEquals(plain, runtime.eval(qimaoDecode, mapOf("content" to qimaoEncrypt(plain))))
    }

    @Test
    fun `顶层 importPackage、不带 new 的 JavaImporter、加密方向也认`() {
        val script = """
            importPackage(Packages.java.util);
            importClass(Packages.javax.crypto.Cipher);
            var imp = JavaImporter(Packages.javax.crypto.spec, Packages.java.lang);
            var key = imp.SecretKeySpec(imp.String("242ccb8230d709e1").getBytes(), "AES");
            var c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, key, imp.IvParameterSpec(Arrays.copyOf(imp.String("0123456789abcdef").getBytes(), 16)));
            Base64.getEncoder().encodeToString(c.doFinal(imp.String("你好").getBytes("UTF-8")))
        """.trimIndent()
        val out = runtime.eval(script, emptyMap())!!
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec("242ccb8230d709e1".toByteArray(), "AES"),
            IvParameterSpec("0123456789abcdef".toByteArray()),
        )
        assertEquals("你好", String(cipher.doFinal(Base64.getDecoder().decode(out))))
    }

    @Test
    fun `替身之外的 JDK 类一个都没有 —— Runtime、System、文件照样够不着`() {
        assertEquals("undefined", runtime.eval("typeof Packages.java.lang.Runtime", emptyMap()))
        assertEquals("undefined", runtime.eval("typeof Packages.java.io", emptyMap()))
        assertEquals(
            "undefined",
            runtime.eval("var i = new JavaImporter(Packages.java.lang); with (i) { typeof System }", emptyMap()),
        )
        // 替身对象上拿不到它攥着的那个真 Cipher
        assertEquals(
            "undefined",
            runtime.eval("typeof Packages.javax.crypto.Cipher.getInstance('AES/ECB/PKCS5Padding').cipher", emptyMap()),
        )
    }

    @Test
    fun `getClass 越狱链照旧断在第一步`() {
        assertFails {
            runtime.eval("Packages.java.lang.String('x').getClass().forName('java.lang.Runtime')", emptyMap())
        }
        assertFails {
            runtime.eval("Packages.javax.crypto.Cipher.getInstance('AES/ECB/PKCS5Padding').getClass()", emptyMap())
        }
    }

    @Test
    fun `替身只装在这一次求值上，不串到下一段脚本`() {
        assertEquals("function", runtime.eval("importPackage(Packages.java.util); typeof Base64", emptyMap()))
        // 同一线程上的下一段脚本：没导过 Base64 就没有
        assertEquals("undefined", runtime.eval("typeof Packages; typeof Base64", emptyMap()))
        // 不提这几个名字的脚本根本不装
        assertEquals("undefined", runtime.eval("typeof this['Pack' + 'ages']", emptyMap()))
    }

    // ---- android.util.Base64（连城读书、书耽、柚免费耽美这一批接口源）----

    private val javaBridge = JavaBridge(null, JsCache(), mutableMapOf())

    private val androidImport = """
        var javaImport = new JavaImporter();
        javaImport.importPackage(
            Packages.java.lang,
            Packages.javax.crypto.spec,
            Packages.javax.crypto,
            Packages.android.util
        );
    """.trimIndent()

    private val shublKey = Base64.getDecoder().decode("nvlrM3RT6n0iYj4I/zbGqisUGGMpy3UT84cNphYONC8=")

    private fun shublCipher(mode: Int) = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(mode, SecretKeySpec(shublKey, "AES"), IvParameterSpec(ByteArray(16)))
    }

    @Test
    fun `书耽的加解密脚本原样能跑 —— Base64 用的是 Android 那套`() {
        val decode = androidImport + """

            with(javaImport){
              function decode(word){
                  let key=SecretKeySpec(java.base64DecodeToByteArray("nvlrM3RT6n0iYj4I/zbGqisUGGMpy3UT84cNphYONC8="),"AES");
                  let iv=IvParameterSpec(java.base64DecodeToByteArray("AAAAAAAAAAAAAAAAAAAAAA=="));
                  let chipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
                  let bytes=Base64.decode(String(word).getBytes(),2);
                  chipher.init(2,key,iv);
                  return String(chipher.doFinal(bytes));
                }
            }
            decode(result)
        """.trimIndent()
        val plain = """{"data":{"book_list":[{"book_name":"天官赐福"}]}}"""
        val cipherText = Base64.getEncoder().encodeToString(shublCipher(Cipher.ENCRYPT_MODE).doFinal(plain.toByteArray()))
        assertEquals(plain, runtime.eval(decode, mapOf("result" to cipherText, "java" to javaBridge)))

        val encode = androidImport + """

            with(javaImport){
              function encode(word){
                  let key=SecretKeySpec(java.base64DecodeToByteArray("nvlrM3RT6n0iYj4I/zbGqisUGGMpy3UT84cNphYONC8="),"AES");
                  let iv=IvParameterSpec(java.base64DecodeToByteArray("AAAAAAAAAAAAAAAAAAAAAA=="));
                  let chipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
                  chipher.init(1,key,iv);
                  return java.encodeURI(Base64.encodeToString(chipher.doFinal(String(word).getBytes()),Base64.NO_WRAP));
                }
            }
            "secret_content="+encode(JSON.stringify({"book_id":100256}))
        """.trimIndent()
        val body = runtime.eval(encode, mapOf("java" to javaBridge))!!
        val sent = java.net.URLDecoder.decode(body.removePrefix("secret_content="), "UTF-8")
        assertEquals("""{"book_id":100256}""", String(shublCipher(Cipher.DECRYPT_MODE).doFinal(Base64.getDecoder().decode(sent))))
    }

    @Test
    fun `连城读书的 DES 正文解密原样能跑`() {
        val script = androidImport + """

            with(javaImport){
                function decrypt(str){
                    var key=SecretKeySpec(String("1701019k").getBytes(),"DES");
                    var iv=IvParameterSpec(java.base64DecodeToByteArray("AQIDBAUGBwg="));
                    var bytes=Base64.decode(String(str).getBytes(),2);
                    var chipher=Cipher.getInstance("DES/CBC/PKCS5Padding");
                    chipher.init(2,key,iv);
                    return String(chipher.doFinal(bytes));
                }
            }
            String(decrypt(JSON.parse(result).DATA.CTXT)).replace(/{{title}}/,'')
        """.trimIndent()
        val des = Cipher.getInstance("DES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec("1701019k".toByteArray(), "DES"), IvParameterSpec(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        }
        val ctxt = Base64.getEncoder().encodeToString(des.doFinal("{{title}}　　第一章的正文".toByteArray()))
        assertEquals(
            "　　第一章的正文",
            runtime.eval(script, mapOf("result" to """{"DATA":{"CTXT":"$ctxt"}}""", "java" to javaBridge)),
        )
    }

    @Test
    fun `android Base64 的换行、标志位、宽松解码都照它`() {
        fun eval(expr: String) = runtime.eval("importPackage(Packages.android.util); $expr", emptyMap())
        val bytes = "var b = Packages.java.lang.String('${"x".repeat(58)}').getBytes();"
        // DEFAULT：76 个字符一行，末尾也带换行
        assertEquals("${"eHh4".repeat(19)}\neA==\n", eval("$bytes Base64.encodeToString(b, Base64.DEFAULT)"))
        assertEquals("${"eHh4".repeat(19)}\r\neA==\r\n", eval("$bytes Base64.encodeToString(b, Base64.CRLF)"))
        assertEquals("${"eHh4".repeat(19)}eA", eval("$bytes Base64.encodeToString(b, Base64.NO_WRAP | Base64.NO_PADDING)"))
        // URL_SAFE 换字母表；偏移 + 长度的四参数写法
        assertEquals("-_8=", eval("Base64.encodeToString(Base64.decode('+/8=', 0), Base64.URL_SAFE | Base64.NO_WRAP)"))
        assertEquals("eHg=", eval("$bytes Base64.encodeToString(b, 1, 2, Base64.NO_WRAP)"))
        // 解码跳过换行、空格，缺填充也认
        assertEquals("你好", eval("Packages.java.lang.String(Base64.decode('5L2g\\n5aW9 ', 0))"))
        assertEquals("你好", eval("Packages.java.lang.String(Base64.decode('5L2g5aW9', Base64.NO_PADDING))"))
        // 两个包导进同一个 JavaImporter：两套 Base64 的方法都在
        assertEquals(
            "function function",
            runtime.eval(
                "var i = new JavaImporter(Packages.android.util, Packages.java.util); " +
                    "with (i) { typeof Base64.getDecoder + ' ' + typeof Base64.encodeToString }",
                emptyMap(),
            ),
        )
    }
}
