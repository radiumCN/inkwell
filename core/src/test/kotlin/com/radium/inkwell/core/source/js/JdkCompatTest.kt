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
}
