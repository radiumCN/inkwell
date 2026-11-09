package com.radium.inkwell.update

import com.radium.inkwell.data.prefs.KnownUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 启动时查更新之后，红点亮不亮、「检查更新」那一行写不写「可更新」。
 */
class UpdateBadgeTest {

    private val target = "GITHUB/STABLE"

    private fun badge(
        latest: String? = "0.5.5",
        checkedFor: String = target,
        seen: String? = null,
        auto: Boolean = true,
        current: String = "0.5.4",
    ) = updateBadge(KnownUpdate(latest = latest, checkedFor = checkedFor, seen = seen), auto, target, current)

    @Test
    fun `查到新版、还没看过 —— 亮点`() {
        val b = badge()
        assertEquals("0.5.5", b.latest)
        assertTrue(b.dot)
    }

    @Test
    fun `看过这一版的弹窗 —— 点收起，「可更新」还写着`() {
        val b = badge(seen = "0.5.5")
        assertEquals("0.5.5", b.latest)
        assertFalse(b.dot)
    }

    @Test
    fun `看过的是旧的一版，又出了更新的 —— 重新亮`() {
        assertTrue(badge(latest = "0.5.6", seen = "0.5.5").dot)
    }

    @Test
    fun `上次查的是别的渠道 —— 不作数`() {
        val b = badge(latest = "0.6.0-beta.1", checkedFor = "GITHUB/BETA")
        assertNull(b.latest)
        assertFalse(b.dot)
    }

    @Test
    fun `已经装上了记着的那一版 —— 不作数`() {
        assertNull(badge(latest = "0.5.5", current = "0.5.5").latest)
        assertNull(badge(latest = "0.5.5-beta.2", current = "0.5.5").latest)
    }

    @Test
    fun `关了启动时检查 —— 不亮点，「可更新」照写`() {
        val b = badge(auto = false)
        assertEquals("0.5.5", b.latest)
        assertFalse(b.dot)
    }

    @Test
    fun `没查到过 —— 什么都没有`() {
        assertEquals(UpdateBadge(), badge(latest = null))
    }
}
