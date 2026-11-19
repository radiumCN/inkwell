package com.radium.inkwell.data.repo

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderLibraryStateTest {
    @Test fun `depth limit still visits shallow siblings but book limit stops the walk`() = runBlocking {
        val tree = mapOf(
            "root" to listOf("shallow" to "B", "deep" to "A"),
            "deep" to listOf("grandchild" to "Too deep"),
        )
        val visited = mutableListOf<String>()
        assertTrue(walkFolderTree("root", maxDepth = 1) { node, _ ->
            visited += node
            tree[node].orEmpty()
        })
        assertEquals(listOf("root", "deep", "shallow"), visited)
        visited.clear()
        assertTrue(walkFolderTree("root", maxDepth = 1) { node, _ ->
            visited += node
            if (node == "deep") null else tree[node].orEmpty()
        })
        assertEquals(listOf("root", "deep"), visited)
    }

    @Test fun `restart preserves exclusions so removed or unchecked books are not reimported`() {
        val watched = WatchedFolder("content://provider/tree/books", "书库", true,
            seen = setOf("previously-imported", "unchecked-file"), status = "新增 1 本")
        val restored = FolderLibraryState.decode(FolderLibraryState.encode(listOf(watched))).single()
        assertEquals(watched, restored)
        val candidates = listOf("previously-imported", "unchecked-file", "new-file")
        assertEquals(listOf("new-file"), candidates.filterNot { it in restored.seen })
    }

    @Test fun `pausing and independent folder settings survive restart`() {
        val configs = listOf(WatchedFolder("first", "同名目录", true, enabled = false),
            WatchedFolder("second", "同名目录", false))
        val restored = FolderLibraryState.decode(FolderLibraryState.encode(configs))
        assertFalse(restored[0].enabled)
        assertTrue(restored[1].enabled)
        assertFalse(restored[1].groupByFolder)
        assertEquals("书库 / 文学 / 小说", FolderBooks.groupName("书库", "文学 / 小说"))
        assertEquals("书库", FolderBooks.groupName("书库", ""))
    }
}
