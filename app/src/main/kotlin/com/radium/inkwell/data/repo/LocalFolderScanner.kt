package com.radium.inkwell.data.repo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 文件夹里找到的一本书 */
data class FolderBook(
    /** 文档 uri（经所选文件夹授权的那棵树），导入时照常 openInputStream */
    val uri: Uri,
    /** 文件名，带扩展名 */
    val name: String,
    /** 在所选文件夹里的子路径（「完本 / 金庸」）；空 = 就在所选文件夹里 */
    val folder: String,
    val size: Long,
    /** 「TXT」「EPUB」「MOBI」「AZW3」，见 [FolderBooks.formatOf] */
    val format: String,
)

/**
 * @param truncated 扫描触及书籍数量或目录深度上限
 */
data class FolderScan(
    val folderName: String,
    val books: List<FolderBook>,
    val truncated: Boolean,
)

/**
 * 「选择文件夹」导入：把系统文件夹选择器（SAF 文档树）给的文件夹连同子文件夹走一遍，找出认得的书。
 *
 * 走 [DocumentsContract] 直接查子项，不另引 DocumentFile：后者每取一个属性就查一次 ContentResolver，
 * 几百个文件的下载目录要查上千次；这里一个文件夹一次查询，名字、类型、大小一起拿回来。
 *
 * 手动导入使用会话内临时授权，书籍复制到私有目录。
 * 用户开启自动扫描时，由 LocalFolderLibrary 额外申请持久读授权，后续仍使用同一扫描器。
 */
class LocalFolderScanner(private val context: Context) {

    /**
     * @param onProgress 每找到一本回调一次（找到的总数），面板上「正在查找… 已找到 N 本」；在 IO 线程上回调
     */
    suspend fun scan(treeUri: Uri, maxBooks: Int = MAX_BOOKS, maxDepth: Int = MAX_DEPTH, onProgress: (Int) -> Unit = {}): FolderScan = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val folderName = displayName(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))
            ?: rootId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "所选文件夹" }
        val books = ArrayList<FolderBook>()
        suspend fun visit(docId: String, path: List<String>): List<Pair<String, String>>? {
            currentCoroutineContext().ensureActive()
            val subDirs = ArrayList<Pair<String, String>>()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val cursor = context.contentResolver.query(children, COLUMNS, null, null, null)
                ?: error("无法读取文件夹，请检查目录是否仍然可用")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    when {
                        // 点开头的是缓存、缩略图这类系统目录，里面不会有书
                        mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                            if (!name.startsWith('.')) subDirs += id to name
                        else -> {
                            val format = FolderBooks.formatOf(name) ?: continue
                            if (books.size >= maxBooks) return null
                            books += FolderBook(
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                name = name,
                                folder = path.joinToString(" / "),
                                size = if (c.isNull(3)) 0L else c.getLong(3),
                                format = format,
                            )
                            onProgress(books.size)
                        }
                    }
                }
            }
            return subDirs
        }

        val truncated = walkFolderTree(rootId, maxDepth, ::visit)
        FolderScan(folderName, FolderBooks.sorted(books), truncated)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    companion object {
        /** 往下最多走几层子文件夹：选错了选到一个很深的目录，别一直走下去 */
        const val MAX_DEPTH = 6
        /** 最多列几本：一屏勾选面板列上千本已经没法看了 */
        const val MAX_BOOKS = 1000

        private val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}

/** visit 返回 null 表示书籍上限已耗尽，整轮停止；深度上限只截断当前子树。 */
internal suspend fun walkFolderTree(
    rootId: String,
    maxDepth: Int,
    visit: suspend (String, List<String>) -> List<Pair<String, String>>?,
): Boolean {
    var depthLimited = false
    suspend fun walk(id: String, path: List<String>): Boolean {
        val subDirs = visit(id, path) ?: return true
        if (path.size >= maxDepth) {
            depthLimited = depthLimited || subDirs.isNotEmpty()
            return false
        }
        return subDirs.sortedWith(compareBy(FolderBooks.NAME_ORDER) { it.second })
            .any { (childId, name) -> walk(childId, path + name) }
    }
    return walk(rootId, emptyList()) || depthLimited
}

/** 文件夹导入里不碰 Android 的那部分（认格式、排序和分组），单测见 FolderBooksTest */
object FolderBooks {

    /** 完整相对路径避免不同父目录下的同名子文件夹混组；根目录中的书归入根目录名。 */
    fun groupName(rootName: String, relativeFolder: String): String =
        listOf(rootName.trim(), relativeFolder.trim()).filter { it.isNotEmpty() }.joinToString(" / ")

    /** 书文件的格式名；不是书（图片、压缩包、没有扩展名的）返回 null */
    fun formatOf(fileName: String): String? {
        if (fileName.startsWith('.')) return null
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext.takeIf { localBookTypeForExtension(it) != null }?.uppercase()
    }

    /** 文件名按人读的顺序比（不分大小写）；数字不做自然排序，同系统文件管理器的默认 */
    val NAME_ORDER: Comparator<String> = String.CASE_INSENSITIVE_ORDER

    /** 先按子文件夹（所选文件夹自己的在最前），再按文件名 */
    fun sorted(books: List<FolderBook>): List<FolderBook> =
        books.sortedWith(
            compareBy<FolderBook> { it.folder.isNotEmpty() }
                .thenBy(NAME_ORDER) { it.folder }
                .thenBy(NAME_ORDER) { it.name },
        )
}
