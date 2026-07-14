package com.radium.inkwell.core.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 设置与净化规则的同步：整块 LWW（设置）、逐条 LWW（规则） */
class BackupSettingsMergeTest {

    private fun payload(
        reader: BackupSettings? = null,
        app: BackupSettings? = null,
        rules: List<BackupReplaceRule> = emptyList(),
    ) = BackupPayload(
        deviceId = "test", exportedAt = 0,
        readerSettings = reader, appSettings = app, replaceRules = rules,
    )

    @Test
    fun `远端设置更新则回写本地`() {
        val local = payload(reader = BackupSettings(100, mapOf("font_size_sp" to "18.0")))
        val remote = payload(reader = BackupSettings(200, mapOf("font_size_sp" to "22.0")))

        val m = BackupMerger.merge(local, remote)
        assertEquals("22.0", m.readerSettings?.values?.get("font_size_sp"))
        assertEquals("22.0", m.mergedReaderSettings?.values?.get("font_size_sp"))
    }

    /** 本地更新时，不该拿远端的旧设置覆盖用户手里正在用的 */
    @Test
    fun `本地设置更新则不回写，且上传本地的`() {
        val local = payload(reader = BackupSettings(300, mapOf("font_size_sp" to "22.0")))
        val remote = payload(reader = BackupSettings(100, mapOf("font_size_sp" to "18.0")))

        val m = BackupMerger.merge(local, remote)
        assertNull(m.readerSettings, "本地更新，不该回写")
        assertEquals("22.0", m.mergedReaderSettings?.values?.get("font_size_sp"))
    }

    /** 阅读设置与应用设置各有各的时间戳：改了字号不该把对面的主题也拽回来 */
    @Test
    fun `阅读设置与应用设置互不牵连`() {
        val local = payload(
            reader = BackupSettings(300, mapOf("font_size_sp" to "22.0")),
            app = BackupSettings(100, mapOf("theme_mode" to "LIGHT")),
        )
        val remote = payload(
            reader = BackupSettings(100, mapOf("font_size_sp" to "18.0")),
            app = BackupSettings(300, mapOf("theme_mode" to "DARK")),
        )

        val m = BackupMerger.merge(local, remote)
        assertNull(m.readerSettings, "阅读设置本地更新")
        assertEquals("DARK", m.appSettings?.values?.get("theme_mode"), "应用设置远端更新")
    }

    /** 一边有、一边没有（老版本备份里没这个字段）时，取有的那份，别丢 */
    @Test
    fun `远端是老版本备份（无设置）时保留本地设置`() {
        val local = payload(reader = BackupSettings(100, mapOf("font_size_sp" to "22.0")))
        val remote = payload(reader = null)

        val m = BackupMerger.merge(local, remote)
        assertNull(m.readerSettings)
        assertEquals("22.0", m.mergedReaderSettings?.values?.get("font_size_sp"))
    }

    @Test
    fun `净化规则逐条按时间戳合并`() {
        val local = payload(
            rules = listOf(
                BackupReplaceRule(id = "a", name = "旧", pattern = "x", updatedAt = 100),
                BackupReplaceRule(id = "b", name = "只在本地", pattern = "y", updatedAt = 100),
            )
        )
        val remote = payload(
            rules = listOf(
                BackupReplaceRule(id = "a", name = "新", pattern = "x2", updatedAt = 200),
                BackupReplaceRule(id = "c", name = "只在远端", pattern = "z", updatedAt = 100),
            )
        )

        val m = BackupMerger.merge(local, remote)
        assertEquals(setOf("a", "b", "c"), m.replaceRules.map { it.id }.toSet())
        assertEquals("新", m.replaceRules.first { it.id == "a" }.name)
        // 需要写回本地的只有：被远端更新的 a、本地没有的 c
        assertEquals(setOf("a", "c"), m.changedReplaceRules.map { it.id }.toSet())
    }

    /**
     * 预置的净化规则在两台设备上必须是同一个 id，否则一同步就翻倍。
     * 仓库那边用的是 "seed:<名称>" 这种确定性 id —— 这里守住合并端的前提。
     */
    @Test
    fun `同 id 的预置规则不会翻倍`() {
        val seeded = BackupReplaceRule(id = "seed:去掉网址", name = "去掉网址", pattern = "x", updatedAt = 0)
        val m = BackupMerger.merge(payload(rules = listOf(seeded)), payload(rules = listOf(seeded)))
        assertEquals(1, m.replaceRules.size)
        assertTrue(m.changedReplaceRules.isEmpty())
    }
}
