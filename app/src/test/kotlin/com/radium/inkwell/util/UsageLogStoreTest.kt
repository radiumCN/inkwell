package com.radium.inkwell.util

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsageLogStoreTest {
    @Test
    fun `轮转限制磁盘用量并按时间顺序导出完整 UTF8 事件`() {
        val dir = Files.createTempDirectory("usage-store-").toFile()
        try {
            val store = UsageLogStore(dir, fileBytes = 128, fileCount = 3)
            repeat(100) { store.append("""{"seq":$it,"label":"翻页完成"}""") }
            assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 128 * 3)
            val snapshot = store.snapshot(100)
            assertTrue(snapshot.toByteArray().size <= 100)
            val events = snapshot.lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
            assertTrue(events.isNotEmpty())
            val ids = events.map { it["seq"]!!.jsonPrimitive.content.toInt() }
            assertEquals(ids.sorted(), ids)
            assertEquals(99, ids.last())
            assertTrue(events.all { it["label"]!!.jsonPrimitive.content == "翻页完成" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `恢复写入前丢弃崩溃留下的半条记录`() {
        val dir = Files.createTempDirectory("usage-recover-").toFile()
        try {
            val store = UsageLogStore(dir)
            store.append("""{"seq":1}""")
            dir.resolve("usage-0.jsonl").appendText("""{"seq":2,"incomplete":""")
            assertEquals(listOf(1), store.snapshot().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject["seq"]!!.jsonPrimitive.content.toInt() })
            store.append("""{"seq":3}""")
            assertEquals(listOf(1, 3), store.snapshot().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject["seq"]!!.jsonPrimitive.content.toInt() })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `过期和清理不会带回旧会话`() {
        val dir = Files.createTempDirectory("usage-expire-").toFile()
        try {
            val now = System.currentTimeMillis()
            val store = UsageLogStore(dir, retentionMillis = 1_000, now = { now })
            store.append("""{"old":true}""")
            dir.resolve("usage-0.jsonl").setLastModified(now - 2_000)
            assertEquals("", store.snapshot())
            store.append("""{"new":true}""")
            assertFalse("old" in store.snapshot())
            store.clear()
            assertEquals("", store.snapshot())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `并发追加不会把事件交错成坏 JSON`() {
        val dir = Files.createTempDirectory("usage-concurrent-").toFile()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val store = UsageLogStore(dir)
            repeat(200) { id -> workers.submit { store.append("""{"seq":$id}""") } }
            workers.shutdown()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            val ids = store.snapshot().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject["seq"]!!.jsonPrimitive.content.toInt() }
            assertEquals(200, ids.toSet().size)
        } finally {
            workers.shutdownNow()
            dir.deleteRecursively()
        }
    }
}
