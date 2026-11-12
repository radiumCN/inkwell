package com.radium.inkwell.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 导出的文件写到 cacheDir/export/ 下：FileProvider 只把这个子目录交出去（file_paths.xml），
 * 别让分享面板拿到的 URI 能够到 cacheDir 里的其他东西（下载的安装包、图片缓存）。
 * 同名覆盖：每次导出就是最新的那份，不在缓存里越攒越多。
 */
suspend fun writeExportFile(context: Context, fileName: String, text: String): File =
    withContext(Dispatchers.IO) {
        val file = File(File(context.cacheDir, "export"), fileName)
        file.parentFile?.mkdirs()
        file.writeText(text)
        file
    }

/**
 * 页面上收导出好的文件，交给系统分享（存网盘、发给别人、直接「用阅读打开」都行）。
 * ViewModel 那边是一个 SharedFlow：导出一次分享一次，转屏回来不会再弹一遍。
 */
@Composable
fun CollectExportShares(files: Flow<File>, chooserTitle: String) {
    val context = LocalContext.current
    LaunchedEffect(files) {
        files.collect { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, chooserTitle))
        }
    }
}
