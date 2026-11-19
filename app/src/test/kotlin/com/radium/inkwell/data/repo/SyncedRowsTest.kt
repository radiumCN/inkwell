package com.radium.inkwell.data.repo

import com.radium.inkwell.core.webdav.BackupReplaceRule
import com.radium.inkwell.core.webdav.BackupRssSource
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import com.radium.inkwell.data.db.entity.RssSourceEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class SyncedRowsTest {
    private val rss = RssSourceEntity(
        id = "rss", name = "订阅", json = "{}", updatedAt = 200,
        groupName = "技术", icon = "icon.png", sortOrder = 4, converterVersion = 3,
    )
    private val remote = BackupRssSource(id = "rss", name = "远端订阅", json = "{}", updatedAt = 100)

    @Test fun `newer local deletion and disable survive stale merged snapshots`() {
        assertNull(applySyncedRss(rss.copy(deleted = true), remote))
        assertNull(applySyncedRss(rss.copy(enabled = false), remote.copy(updatedAt = 200)))
        val rule = ReplaceRuleEntity(id = "r", name = "净化", pattern = "广告", updatedAt = 200, deleted = true)
        assertNull(applySyncedRule(rule, BackupReplaceRule(id = "r", name = "旧规则", pattern = "广告", updatedAt = 100)))
    }

    @Test fun `legacy backup preserves existing grouping icon order and converter version`() {
        val actual = assertNotNull(applySyncedRss(rss, remote.copy(updatedAt = 300)))
        assertEquals("技术", actual.groupName)
        assertEquals("icon.png", actual.icon)
        assertEquals(4, actual.sortOrder)
        assertEquals(3, actual.converterVersion)
        assertEquals("远端订阅", actual.name)
    }

    @Test fun `explicit empty group is applied while omitted fields remain`() {
        val actual = assertNotNull(applySyncedRss(rss, remote.copy(updatedAt = 300, groupName = "")))
        assertEquals("", actual.groupName)
        assertEquals("icon.png", actual.icon)
    }

    @Test fun `new device receives all provided subscription metadata`() {
        val actual = assertNotNull(applySyncedRss(null, remote.copy(
            groupName = "技术", icon = "icon.png", sortOrder = 4, converterVersion = 3,
        )))
        assertEquals("技术", actual.groupName)
        assertEquals("icon.png", actual.icon)
        assertEquals(4, actual.sortOrder)
        assertEquals(3, actual.converterVersion)
    }

    @Test fun `equal revision fills legacy defaults without rewriting unchanged rows`() {
        val restored = rss.copy(groupName = "", icon = "", sortOrder = 0, converterVersion = 0)
        val complete = remote.copy(name = rss.name, updatedAt = rss.updatedAt,
            groupName = rss.groupName, icon = rss.icon, sortOrder = rss.sortOrder, converterVersion = rss.converterVersion)
        assertEquals(rss, applySyncedRss(restored, complete))
        assertNull(applySyncedRss(rss, complete))
        assertNull(applySyncedRss(restored.copy(deleted = true), complete))
    }
}
