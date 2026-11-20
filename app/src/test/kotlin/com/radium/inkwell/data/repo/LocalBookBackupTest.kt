package com.radium.inkwell.data.repo

import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalBookBackupTest {
    @Test
    fun `only ordinary shelf books are eligible for file backup and restore`() {
        val book = BookEntity(
            id = "a69b6b92-86bc-4327-8f1f-3ad3fd73e108",
            type = BookType.LOCAL_EPUB,
            title = "本地书",
            addedAt = 0L,
            updatedAt = 0L,
        )

        assertTrue(book.canBackUpFile())
        assertFalse(book.copy(hidden = true).canBackUpFile())
        assertFalse(book.copy(inShelf = false).canBackUpFile())
        assertFalse(book.copy(deleted = true).canBackUpFile())
        assertFalse(book.copy(type = BookType.NET).canBackUpFile())
    }
}
