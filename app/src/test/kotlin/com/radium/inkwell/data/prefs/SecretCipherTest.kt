package com.radium.inkwell.data.prefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 信封格式与**明文迁移**的判定。
 *
 * 真正的 AndroidKeyStore 在 JVM 单测里不存在（本项目没引 Robolectric），所以这里用一个
 * 假实现顶掉密码学部分 —— 反正容易出错的从来不是 AES 本身，而是「这串东西到底是密文
 * 还是升级前留下的明文」这个判断。判错的代价很具体：把老用户的口令当密文去解，
 * 解出 null，表现为升级后 WebDAV 莫名其妙要求重新登录。
 */
class SecretCipherTest {

    /** 反转字符串冒充加密：可逆、且与明文明显不同，足够验证信封的拆装 */
    private val fake = object : SecretCipher {
        override fun encrypt(plain: String) =
            SecretCipher.ENVELOPE_PREFIX + plain.reversed()

        override fun decrypt(envelope: String) =
            envelope.removePrefix(SecretCipher.ENVELOPE_PREFIX).reversed()
    }

    @Test
    fun `密文信封能原样解回来`() {
        val secret = "hunter2"
        val read = fake.read(fake.encrypt(secret))
        assertEquals(secret, read.value)
        assertFalse(read.needsMigration)
    }

    @Test
    fun `升级前的明文按原样返回并标记待迁移`() {
        val read = fake.read("hunter2")
        assertEquals("hunter2", read.value, "老用户的口令必须还能用，不能解成空")
        assertTrue(read.needsMigration)
    }

    @Test
    fun `空值不算待迁移`() {
        // 没配过 WebDAV 的用户不该在每次启动时被写一次库
        val read = fake.read("")
        assertEquals("", read.value)
        assertFalse(read.needsMigration)
    }

    @Test
    fun `解不开时给空串而不是抛异常`() {
        val broken = object : SecretCipher {
            override fun encrypt(plain: String) = SecretCipher.ENVELOPE_PREFIX + plain
            override fun decrypt(envelope: String): String? = null // 密钥被系统清掉了
        }
        val read = broken.read(SecretCipher.ENVELOPE_PREFIX + "whatever")
        // 抛出去的话用户连 WebDAV 设置页都进不去，改不了配置也就自救不了
        assertEquals("", read.value)
        assertFalse(read.needsMigration, "解不开 ≠ 明文，别把密文当明文再加密一层")
    }

    @Test
    fun `恰好以前缀开头的明文口令 —— 边界`() {
        // 极端但不是不可能：用户口令字面量就是 "enc:v1:..."。此时会被当密文去解，
        // 解不开则退成空 —— 用户重新输一次即可。这里钉住这个行为是已知取舍，不是意外。
        val read = fake.read(SecretCipher.ENVELOPE_PREFIX + "abc")
        assertEquals("cba", read.value)
    }
}
