package com.radium.inkwell.util

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsageLogTest {
    @Test
    fun `导出会先排空后台队列且不包含输入和凭据`() = runBlocking {
        val directory = Files.createTempDirectory("usage-pipeline-").toFile()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        try {
            UsageLog.initialize(directory, "test-version")
            UsageLog.screenVisible("reader", 2)
            val book = UsageLog.reference("private-original-book-id")
            repeat(80) { index ->
                UsageLog.event("test.page", "book" to book, "page" to index,
                    "endpoint" to "https://example.org/?token=private-token",
                    "password" to "private-password", "query" to "private-query",
                    "api_key" to "private-api-key",
                    "error" to "Authorization: Bearer private-auth")
            }
            val snapshot = UsageLog.snapshot()
            val events = snapshot.lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
            val pages = events.filter { it["event"]!!.jsonPrimitive.content == "test.page" }
            assertEquals(80, pages.size)
            assertEquals("79", pages.last()["fields"]!!.jsonObject["page"]!!.jsonPrimitive.content)
            assertTrue(pages.all { it["screen"]!!.jsonPrimitive.content == "reader" })
            listOf("private-original-book-id", "private-token", "private-password", "private-query", "private-auth", "private-api-key", "example.org").forEach {
                assertFalse(it in snapshot, "leaked $it")
            }
            assertEquals("log.snapshot", events.last()["event"]!!.jsonPrimitive.content)
            assertEquals("0", events.last()["fields"]!!.jsonObject["write_failures"]!!.jsonPrimitive.content)
            UsageLog.clear()
            assertFalse("test.page" in UsageLog.snapshot())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }
}
