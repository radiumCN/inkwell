package com.radium.inkwell.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 落盘密文的封装。
 *
 * 目前只有 WebDAV 口令在用。它从前是**明文**存在 DataStore Preferences 里的 ——
 * protobuf 文件里就是一串裸字符串，root 过的机器、任何能读到应用私有目录的途径都能直接看到。
 *
 * 抽成接口是为了可测：AndroidKeyStore 在 JVM 单测里不存在（本项目没有 Robolectric），
 * 而真正容易写错的是**信封格式与明文迁移**那部分逻辑，那部分必须能测。
 */
interface SecretCipher {
    /** 返回带 [ENVELOPE_PREFIX] 前缀的密文信封 */
    fun encrypt(plain: String): String

    /** 解不开返回 null（密钥被系统清掉、数据损坏）—— 绝不能抛，抛了用户连设置页都进不去 */
    fun decrypt(envelope: String): String?

    companion object {
        /**
         * 信封前缀，用来把「已加密」和「升级前留下的明文」区分开。
         *
         * 带版本号：将来换算法时，靠它认出旧信封并原地升级，而不是把用户的口令解成乱码。
         */
        const val ENVELOPE_PREFIX = "enc:v1:"
    }
}

/**
 * 读出可用的明文，顺带告知这条记录是否**还是**明文（需要迁移重写）。
 *
 * 老用户的库里存的是裸口令，不能一律当密文去解 —— 那会把好好的口令解成 null，
 * 表现为「升级后 WebDAV 突然要重新登录」。没有前缀就按明文原样返回。
 */
fun SecretCipher.read(stored: String): SecretRead = when {
    stored.isEmpty() -> SecretRead(value = "", needsMigration = false)
    stored.startsWith(SecretCipher.ENVELOPE_PREFIX) ->
        SecretRead(value = decrypt(stored).orEmpty(), needsMigration = false)
    // 升级前留下的明文：能用，但该趁下次写库换成密文
    else -> SecretRead(value = stored, needsMigration = true)
}

data class SecretRead(val value: String, val needsMigration: Boolean)

/**
 * AndroidKeyStore 实现：AES-256/GCM，密钥生在 Keystore 里、**导不出来**（有 TEE/StrongBox 的
 * 机器上它根本不进普通内存）。所以就算 DataStore 文件被整个拷走，离开这台设备也解不开。
 *
 * GCM 的 IV 每次加密由系统随机生成，随密文一起存进信封 —— 复用 IV 会直接毁掉 GCM 的安全性。
 */
class KeystoreSecretCipher(
    private val alias: String = DEFAULT_ALIAS,
) : SecretCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return SecretCipher.ENVELOPE_PREFIX +
            cipher.iv.b64() + ":" + ct.b64()
    }

    override fun decrypt(envelope: String): String? = runCatching {
        val body = envelope.removePrefix(SecretCipher.ENVELOPE_PREFIX)
        val (ivPart, ctPart) = body.split(':', limit = 2).let { it[0] to it[1] }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, ivPart.unB64()))
        String(cipher.doFinal(ctPart.unB64()), Charsets.UTF_8)
    }.getOrNull()

    /** 取密钥；没有就现生一把。生成是幂等的 —— 已存在时直接复用，否则会把旧密文全变成解不开 */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 刻意**不**要求用户认证：同步是后台静默跑的（冷启动、退到后台），
                // 要求解锁会让自动同步在锁屏状态下直接失败
                .build()
        )
        return gen.generateKey()
    }

    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.unB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val DEFAULT_ALIAS = "inkwell_secret_v1"
    }
}
