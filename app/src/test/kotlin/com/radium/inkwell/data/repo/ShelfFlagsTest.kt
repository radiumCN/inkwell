package com.radium.inkwell.data.repo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 预览直读 vs 加入书架时，inShelf / deleted 怎么落。
 *
 * 算错一次就会回到「点开始阅读就进书架」，或者把删过的书自己复活上架。
 */
class ShelfFlagsTest {

    @Test
    fun `新书显式加架 —— 上架且不是墓碑`() {
        val flags = resolveShelfFlags(null, null, requestedInShelf = true)
        assertTrue(flags.inShelf)
        assertFalse(flags.deleted)
    }

    @Test
    fun `新书预览直读 —— 落库但不上架`() {
        val flags = resolveShelfFlags(null, null, requestedInShelf = false)
        assertFalse(flags.inShelf)
        assertFalse(flags.deleted)
    }

    @Test
    fun `已在架再直读 —— 不能降成试读`() {
        val flags = resolveShelfFlags(
            existingInShelf = true,
            existingDeleted = false,
            requestedInShelf = false,
        )
        assertTrue(flags.inShelf)
        assertFalse(flags.deleted)
    }

    @Test
    fun `试读行再点加入 —— 升成在架`() {
        val flags = resolveShelfFlags(
            existingInShelf = false,
            existingDeleted = false,
            requestedInShelf = true,
        )
        assertTrue(flags.inShelf)
        assertFalse(flags.deleted)
    }

    @Test
    fun `试读行再直读 —— 继续不上架`() {
        val flags = resolveShelfFlags(
            existingInShelf = false,
            existingDeleted = false,
            requestedInShelf = false,
        )
        assertFalse(flags.inShelf)
        assertFalse(flags.deleted)
    }

    @Test
    fun `墓碑再加架 —— 抹掉墓碑并上架`() {
        val flags = resolveShelfFlags(
            existingInShelf = true,
            existingDeleted = true,
            requestedInShelf = true,
        )
        assertTrue(flags.inShelf)
        assertFalse(flags.deleted)
    }

    @Test
    fun `墓碑再直读 —— 保持墓碑且不上架`() {
        val flags = resolveShelfFlags(
            existingInShelf = true,
            existingDeleted = true,
            requestedInShelf = false,
        )
        assertFalse(flags.inShelf)
        assertTrue(flags.deleted)
    }
}
