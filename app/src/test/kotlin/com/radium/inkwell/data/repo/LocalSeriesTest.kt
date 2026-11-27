package com.radium.inkwell.data.repo

import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.BookMetadata
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ChapterRef
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.model.ImageBlob
import com.radium.inkwell.core.model.TocEntry
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalSeriesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun oldAndNewManifestsSurviveRestoringOnlyBackedUpFiles() {
        val source = temporary.newFolder("source")
        val cache = temporary.newFolder("cache")
        val files = LocalSeriesFiles(source, cache)
        val volumes = listOf(LocalSeriesVolume("a", "一", 1, null), LocalSeriesVolume("b", "二", 1, null))
        val legacy = source.resolve("books/old.series")
        checkNotNull(legacy.parentFile).mkdirs()
        legacy.writeText(Json.encodeToString(volumes))
        assertEquals(volumes, files.read(legacy.path))
        files.migrateLegacy()
        val newManifest = files.newManifest().also { it.writeText(Json.encodeToString(volumes.reversed())) }
        assertFalse(legacy.exists())
        val restored = temporary.newFolder("restored")
        source.resolve("series").copyRecursively(restored.resolve("series"))
        val restoredFiles = LocalSeriesFiles(restored, temporary.newFolder("empty-cache"))
        assertEquals(volumes, restoredFiles.read(legacy.path))
        assertEquals(volumes.reversed(), restoredFiles.read(newManifest.path))
        restoredFiles.delete(legacy.path)
        assertFailsWith<IllegalStateException> { restoredFiles.read(legacy.path) }
        assertEquals(volumes.reversed(), restoredFiles.read(newManifest.path))
    }

    @Test fun cachedDirectoryAndImageRoutingUseAtMostOneOpenVolume() {
        val volumes = listOf(LocalSeriesVolume("a", "一", 1, null), LocalSeriesVolume("b", "二", 1, null))
        val catalogs = volumes.map { VolumeCatalog(listOf(it.title), listOf(TocEntry(it.title, 0, anchor = "part"))) }
        val series = BookEntity("s", BookType.LOCAL_SERIES, "合订本", addedAt = 0, updatedAt = 0)
        val opened = mutableListOf<Int>()
        var live = 0
        val handle = LocalSeriesHandle(series, volumes, catalogs) { volume ->
            assertEquals(0, live++)
            opened += volume
            object : BookHandle {
                override val metadata = BookMetadata(volumes[volume].title)
                override val chapters = listOf(ChapterRef(0, metadata.title))
                override val toc = catalogs[volume].toc
                override fun loadChapter(index: Int) = ChapterContent(listOf(ContentElement.Image("image")))
                override fun loadResource(resourceId: String) = ImageBlob(byteArrayOf(volume.toByte()))
                override fun close() { live-- }
            }
        }
        handle.use {
            assertEquals(listOf("一", "二"), it.chapters.map { ref -> ref.title })
            assertEquals(TocEntry("二", 1, "part", 1), it.toc.last())
            assertTrue(opened.isEmpty())
            assertEquals(ContentElement.Image("1:image"), it.loadChapter(1).elements.single())
            assertEquals(1.toByte(), it.loadResource("1:image")?.data?.single())
            assertEquals(0.toByte(), it.loadResource("0:image")?.data?.single())
            assertEquals(listOf(1, 0), opened)
        }
        assertEquals(0, live)
        assertFailsWith<IllegalStateException> { handle.loadChapter(0) }
    }

    @Test fun `progress follows the volume when reordered`() {
        val first = LocalSeriesVolume("a", "卷一", 12, null)
        val second = LocalSeriesVolume("b", "卷二", 8, null)
        val third = LocalSeriesVolume("c", "卷三", 3, null)
        val current = seriesPosition(listOf(first, second, third), 14)!!

        assertEquals("b" to 2, current)
        assertEquals(2, seriesIndex(listOf(second, third, first), current))
        assertNull(seriesIndex(listOf(first, third), current))
    }
}
