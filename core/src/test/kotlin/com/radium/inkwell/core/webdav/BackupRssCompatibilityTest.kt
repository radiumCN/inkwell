package com.radium.inkwell.core.webdav

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupRssCompatibilityTest {
    private val current = BackupRssSource(
        id = "r", name = "订阅", json = "{}", updatedAt = 100,
        groupName = "技术", sortOrder = 4, icon = "https://example.com/icon.png", converterVersion = 3,
    )
    private fun payload(source: BackupRssSource) = BackupPayload(deviceId = "d", exportedAt = 1, rssSources = listOf(source))

    @Test fun `new backup roundtrip retains subscription metadata`() {
        assertEquals(current, BackupCodec.decode(BackupCodec.encode(payload(current))).rssSources.single())
    }

    @Test fun `actual old serialized backup leaves group unspecified`() {
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write("""{"deviceId":"old","exportedAt":1,"rssSources":[{"id":"r","name":"订阅","json":"{}"}]}""".toByteArray()) }
        }.toByteArray()
        val old = BackupCodec.decode(bytes).rssSources.single()
        assertNull(old.groupName)
        assertEquals("技术", BackupMerger.merge(payload(current), payload(old.copy(updatedAt = 200))).rssSources.single().groupName)
    }

    @Test fun `old device newer enable change preserves known metadata in either merge order`() {
        val old = BackupRssSource(id = "r", name = "新名字", json = "{}", enabled = false, updatedAt = 200)
        for ((a, b) in listOf(current to old, old to current)) {
            val merged = BackupMerger.merge(payload(a), payload(b)).rssSources.single()
            assertEquals(false, merged.enabled)
            assertEquals("技术", merged.groupName)
            assertEquals(4, merged.sortOrder)
            assertEquals(current.icon, merged.icon)
        }
    }

    @Test fun `explicit clearing survives merge`() {
        val remote = current.copy(updatedAt = 200, groupName = "", icon = "", sortOrder = 0)
        assertEquals(remote, BackupMerger.merge(payload(current), payload(remote)).rssSources.single())
    }

    @Test fun `defaults materialized by a legacy restore are filled on either side of an equal revision`() {
        val restored = current.copy(groupName = "", sortOrder = 0, icon = "", converterVersion = 0)
        for ((a, b) in listOf(restored to current, current to restored)) {
            assertEquals(current, BackupMerger.merge(payload(a), payload(b)).rssSources.single())
        }
        val independentlyDisabled = restored.copy(enabled = false)
        assertEquals(independentlyDisabled, BackupMerger.merge(payload(independentlyDisabled), payload(current)).rssSources.single())
    }
}
