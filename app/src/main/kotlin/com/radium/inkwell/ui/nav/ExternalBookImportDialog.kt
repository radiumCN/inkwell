package com.radium.inkwell.ui.nav

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.ui.components.AppAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun ExternalBookImportDialog(
    request: Intent,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val uri = checkNotNull(request.data)
    val books = koinInject<BookRepository>()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    LaunchedEffect(request) {
        try {
            name = books.externalBookDisplayName(uri, request.type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = if (e is IllegalArgumentException) e.message else "无法读取该文件，请从文件管理器重新打开。"
        }
    }

    val failedToRead = name == null && error != null
    AppAlertDialog(
        title = if (failedToRead) "无法打开书籍" else "导入到书架",
        text = error ?: name ?: "正在读取文件…",
        onDismissRequest = onDismiss,
        confirmText = if (failedToRead) "知道了" else "导入并阅读",
        dismissText = "取消".takeUnless { failedToRead },
        confirmEnabled = name != null || failedToRead,
        confirmLoading = importing,
        onConfirm = {
            val displayName = name
            if (displayName == null) {
                onDismiss()
            } else if (!importing) {
                importing = true
                error = null
                scope.launch {
                    try {
                        books.importLocalBook(uri, selectedDisplayName = displayName)
                            .onSuccess { onOpen(it.bookId) }
                            .onFailure { error = "导入失败：${it.message ?: "无法读取所选文件"}" }
                    } finally {
                        importing = false
                    }
                }
            }
        },
    )
}
