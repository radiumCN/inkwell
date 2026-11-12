package com.radium.inkwell.core.source.js

import com.radium.inkwell.core.util.toHex
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `java.createSymmetricCrypto(transformation, key, iv)` 的返回值。
 *
 * 阅读交给脚本的是 hutool 的 SymmetricCrypto，来源照它的方法名写：`.decryptStr(密文)`、`.encryptBase64(明文)`、
 * `.decrypt(字节)`……这里照名字、照语义各做一个；真的 Cipher 不交出去（同 [CipherShim]），每次加解密现取一个。
 * 阅读自己的 `aes*` / `des*` 也是经它实现的，[JavaBridge] 那几个照样走这里。
 *
 * 照 hutool 的几处语义，不照着来，同一段脚本在阅读里解得开、这里解不开：
 * - **密钥按算法截**：DES 取前 8 字节、DESede 取前 24 字节（DESKeySpec 的做法）—— 晋江的 DES 密钥写的是 22 个字符，
 *   原样交给 JDK 是「密钥长度不对」；
 * - **字符串密文先认十六进制**（整串都是 0-9a-f），否则按 Base64 解；
 * - `ZeroPadding`：换成 NoPadding，加密前补 0 到整块、解密后去掉末尾的 0；
 * - `PKCS7Padding` 是 Android 的叫法，JDK 只认 PKCS5Padding（对 8 / 16 字节的块两者是一回事）。
 *
 * ECB 不带 IV：给了也不用（JDK 收到会报错，hutool 那边来源本来也不这么写）。
 */
class SymmetricCrypto internal constructor(transformation: String, key: ByteArray, iv: ByteArray?) {

    private val algorithm: String = transformation.substringBefore('/').trim().let {
        if (it.equals("DESede", ignoreCase = true)) "DESede" else it.uppercase()
    }

    private val zeroPadding = transformation.contains("ZeroPadding", ignoreCase = true)

    private val jdkTransformation = transformation.trim()
        .replace(PKCS7, "PKCS5Padding")
        .replace(ZERO_PADDING, "NoPadding")

    private val ecb = transformation.split('/').getOrNull(1)?.trim().let { it == null || it.equals("ECB", ignoreCase = true) }

    private val key: ByteArray = when {
        algorithm == "DES" && key.size > 8 -> key.copyOf(8)
        algorithm == "DESede" && key.size > 24 -> key.copyOf(24)
        else -> key.copyOf()
    }

    private var iv: ByteArray? = iv?.takeIf { it.isNotEmpty() }?.copyOf()

    fun setIv(iv: String): SymmetricCrypto = setIv(iv.toByteArray(Charsets.UTF_8))

    fun setIv(iv: ByteArray): SymmetricCrypto {
        this.iv = iv.copyOf()
        return this
    }

    fun encrypt(data: String): ByteArray = encrypt(data.toByteArray(Charsets.UTF_8))

    fun encrypt(data: ByteArray): ByteArray {
        val cipher = cipher(Cipher.ENCRYPT_MODE)
        return cipher.doFinal(if (zeroPadding) zeroPad(data, cipher.blockSize) else data)
    }

    fun encryptHex(data: String): String = encrypt(data).toHex()

    fun encryptHex(data: ByteArray): String = encrypt(data).toHex()

    fun encryptBase64(data: String): String = Base64.getEncoder().encodeToString(encrypt(data))

    fun encryptBase64(data: ByteArray): String = Base64.getEncoder().encodeToString(encrypt(data))

    /** 字符串密文：十六进制或 Base64（同 hutool 的 SecureUtil.decode） */
    fun decrypt(data: String): ByteArray = decrypt(decodeCipherText(data))

    fun decrypt(data: ByteArray): ByteArray {
        val plain = cipher(Cipher.DECRYPT_MODE).doFinal(data)
        if (!zeroPadding) return plain
        var end = plain.size
        while (end > 0 && plain[end - 1] == 0.toByte()) end--
        return plain.copyOf(end)
    }

    fun decryptStr(data: String): String = String(decrypt(data), Charsets.UTF_8)

    fun decryptStr(data: ByteArray): String = String(decrypt(data), Charsets.UTF_8)

    fun decryptStr(data: String, charset: String): String = String(decrypt(data), charset(charset))

    private fun cipher(mode: Int): Cipher {
        val cipher = Cipher.getInstance(jdkTransformation)
        val spec = SecretKeySpec(key, algorithm)
        val iv = iv
        if (iv == null || ecb) cipher.init(mode, spec) else cipher.init(mode, spec, IvParameterSpec(iv))
        return cipher
    }

    private fun zeroPad(data: ByteArray, blockSize: Int): ByteArray {
        if (blockSize <= 0 || data.size % blockSize == 0) return data
        return data.copyOf(data.size + blockSize - data.size % blockSize)
    }

    private fun decodeCipherText(text: String): ByteArray {
        val t = text.trim()
        if (t.isNotEmpty() && t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            val even = if (t.length % 2 == 0) t else "0$t"
            return ByteArray(even.length / 2) { even.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        return decodeBase64Lenient(t)
    }

    private companion object {
        val PKCS7 = Regex("PKCS7Padding", RegexOption.IGNORE_CASE)
        val ZERO_PADDING = Regex("ZeroPadding", RegexOption.IGNORE_CASE)
    }
}
