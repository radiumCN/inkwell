package com.radium.inkwell.core.source.js

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * `java.createSymmetricCrypto` / `HMacHex` / `des*`：阅读给脚本的是 hutool 的那套，来源照它的方法名和语义写。
 */
class SymmetricCryptoTest {

    private val runtime = RhinoScriptRuntime()
    private val javaBridge = JavaBridge(null, JsCache(), mutableMapOf())

    private fun eval(script: String, vararg bindings: Pair<String, Any?>) =
        runtime.eval(script, mapOf("java" to javaBridge, *bindings))

    private fun jdkEncrypt(transformation: String, key: ByteArray, iv: ByteArray?, plain: ByteArray): ByteArray {
        val algorithm = transformation.substringBefore('/')
        return Cipher.getInstance(transformation).apply {
            if (iv == null) init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, algorithm))
            else init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, algorithm), IvParameterSpec(iv))
        }.doFinal(plain)
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `晋江的 DES 密钥写了 22 个字符 —— 照 hutool 截前 8 字节`() {
        val content = b64(jdkEncrypt("DES/CBC/PKCS5Padding", "KK!%G3Jd".toByteArray(), "1ae2c94b".toByteArray(), "VIP 章节正文".toByteArray()))
        assertEquals(
            "VIP 章节正文",
            eval(
                """java.createSymmetricCrypto("DES/CBC/PKCS5Padding", String("KK!%G3JdCHJxpAF3%Vg9pN"), String("1ae2c94b")).decryptStr(content)""",
                "content" to content,
            ),
        )
        // 加密方向（晋江算签名）：照阅读出标准 Base64
        val sign = eval("""java.createSymmetricCrypto("DES/CBC/PKCS5Padding", String("KW8Dvm2N"), String("1ae2c94b")).encryptBase64("1700000000:tok:42")""")
        assertEquals(b64(jdkEncrypt("DES/CBC/PKCS5Padding", "KW8Dvm2N".toByteArray(), "1ae2c94b".toByteArray(), "1700000000:tok:42".toByteArray())), sign)
    }

    @Test
    fun `笔趣阁a 的 DESede、冷冷文学的 AES PKCS7Padding 都解得开`() {
        val part = b64(jdkEncrypt("DESede/CBC/PKCS5Padding", "OW84U8Eerdb99rtsTXWSILDO".toByteArray(), "SK8bncVu".toByteArray(), "第一章".toByteArray()))
        assertEquals("第一章", eval("java.createSymmetricCrypto('desede/CBC/PKCS5Padding', 'OW84U8Eerdb99rtsTXWSILDO', 'SK8bncVu').decryptStr(part)", "part" to part))

        val key = "0123456789abcdef"
        val iv = "fedcba9876543210"
        val a = b64(jdkEncrypt("AES/CBC/PKCS5Padding", key.toByteArray(), iv.toByteArray(), "正文".toByteArray()))
        assertEquals("正文", eval("""java.createSymmetricCrypto("AES/CBC/PKCS7Padding", e, d).decryptStr(a)""", "a" to a, "e" to key, "d" to iv))
        // 字节入参、字节出参（漫蛙解图片的写法）
        assertEquals(
            "正文",
            eval(
                "Packages.java.lang.String(java.createSymmetricCrypto('aes/cbc/pkcs7padding', k, d).decrypt(java.base64DecodeToByteArray(a)))",
                "a" to a, "k" to key, "d" to iv,
            ),
        )
    }

    @Test
    fun `字符串密文先认十六进制，ZeroPadding 补零去零`() {
        val key = "0123456789abcdef"
        val hex = jdkEncrypt("AES/ECB/PKCS5Padding", key.toByteArray(), null, "hex 密文".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("hex 密文", eval("java.createSymmetricCrypto('AES/ECB/PKCS5Padding', k).decryptStr(h)", "k" to key, "h" to hex))

        // ZeroPadding：加密补 0 到整块，和 JDK 的 NoPadding + 手补零一致；解密去掉末尾的 0
        val zero = eval("java.createSymmetricCrypto('AES/CBC/ZeroPadding', k, k).encryptHex('abc')", "k" to key)
        val expected = jdkEncrypt("AES/CBC/NoPadding", key.toByteArray(), key.toByteArray(), "abc".toByteArray().copyOf(16))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, zero)
        assertEquals("abc", eval("java.createSymmetricCrypto('AES/CBC/ZeroPadding', k, k).decryptStr(z)", "k" to key, "z" to zero))
    }

    @Test
    fun `阅友的 HMacHex 和 desEncodeToBase64String`() {
        val fox = "The quick brown fox jumps over the lazy dog"
        assertEquals("80070713463e7749b90c2dc24911e275", eval("java.HMacHex(d, 'HmacMD5', 'key')", "d" to fox))
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            eval("java.HMacHex(d, 'HMacSHA256', 'key')", "d" to fox),
        )
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec("sk".toByteArray(), "HmacSHA1")) }
        assertEquals(b64(mac.doFinal("data".toByteArray())), eval("java.HMacBase64('data', 'HMacSHA1', 'sk')"))

        // DES/ECB 给了空 IV
        val p = eval("""java.desEncodeToBase64String("u2&sign=1", "snY%169j", "DES/ECB/PKCS5Padding", "")""")
        assertEquals(b64(jdkEncrypt("DES/ECB/PKCS5Padding", "snY%169j".toByteArray(), null, "u2&sign=1".toByteArray())), p)
        assertEquals("u2&sign=1", eval("""java.desBase64DecodeToString(p, "snY%169j", "DES/ECB/PKCS5Padding", "")""", "p" to p))
    }

    @Test
    fun `aes 那几个照阅读走同一套 —— 密文串是 Base64 或十六进制`() {
        val key = "0123456789abcdef"
        val c = b64(jdkEncrypt("AES/CBC/PKCS5Padding", key.toByteArray(), key.toByteArray(), "旧接口".toByteArray()))
        assertEquals("旧接口", eval("java.aesDecodeToString(c, k, 'AES/CBC/PKCS5Padding', k)", "c" to c, "k" to key))
        assertEquals("旧接口", eval("java.aesBase64DecodeToString(c, k, 'AES/CBC/PKCS5Padding', k)", "c" to c, "k" to key))
        assertEquals(c, eval("java.aesEncodeToBase64String('旧接口', k, 'AES/CBC/PKCS5Padding', k)", "k" to key))
    }

    @Test
    fun `解密失败在脚本里接得住（深夜看书解不开就退回网页加载）`() {
        assertEquals(
            "fallback",
            eval("try { java.createSymmetricCrypto('AES/CBC/PKCS5Padding', '0123456789abcdef', '0123456789abcdef').decryptStr('AAAA') } catch (e) { 'fallback' }"),
        )
    }

    @Test
    fun `拿不到它攥着的 Cipher，getClass 照旧被挡`() {
        assertEquals("undefined", eval("typeof java.createSymmetricCrypto('AES/ECB/PKCS5Padding', '0123456789abcdef').key"))
        assertFails { eval("java.createSymmetricCrypto('AES/ECB/PKCS5Padding', '0123456789abcdef').getClass()") }
    }
}
