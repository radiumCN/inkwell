package com.radium.inkwell.data.repo

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radium.inkwell.core.model.BookParserRegistry
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class LocalBookRestoreTest {
    @Test fun selectedBackupCanDifferFromShelfHashWhileManualRecoveryRequiresTheOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = GlobalContext.get().get<BookDao>()
        val books = GlobalContext.get().get<BookRepository>()
        val export = File(context.cacheDir, "export").also { it.mkdirs() }
        val selected = File.createTempFile("restore-version", ".txt", export)
        val id = UUID.randomUUID().toString()
        try {
            selected.writeText("原版本正文")
            val originalHash = selected.sha256()
            dao.upsert(BookEntity(
                id = id, title = selected.nameWithoutExtension, type = BookType.LOCAL_TXT,
                addedAt = 1L, updatedAt = 1L, localFileHash = originalHash,
            ))
            selected.writeText("选择的另一备份版本")
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", selected)
            val manual = runCatching { books.recoverLocalBook(id, uri) }.exceptionOrNull()
            assertEquals("文件内容与原书不同，请选择原文件", manual?.message)
            assertNull(dao.getById(id)?.localPath)
            assertEquals(originalHash, dao.getById(id)?.localFileHash)

            books.restoreLocalBook(id, "txt") { dest -> selected.copyTo(dest, overwrite = true) }
            val restored = checkNotNull(dao.getById(id))
            assertEquals(selected.sha256(), restored.localFileHash)
            assertEquals(selected.readText(), File(checkNotNull(restored.localPath)).readText())
        } finally {
            dao.getById(id)?.localPath?.let {
                val file = File(it)
                GlobalContext.get().get<BookParserRegistry>().evictCaches(file)
                file.delete()
            }
            GlobalContext.get().get<ChapterDao>().deleteByBook(id)
            dao.hardDelete(id)
            selected.delete()
        }
        Unit
    }
}
