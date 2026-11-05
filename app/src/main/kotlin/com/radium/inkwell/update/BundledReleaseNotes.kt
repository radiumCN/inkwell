package com.radium.inkwell.update

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * 随安装包带上的**这一版**发行说明：关于页点「版本」看的就是它。
 *
 * 发版流水线（release.yml）在打包之前，把 tag 注解正文（= Release 正文 = 更新弹窗里的说明）写进
 * `assets/release/notes.txt`、tag 日期写进 `date.txt`。所以离线也能看、点开就有，和 Release 页一字不差；
 * 不走网络，GitHub 打不开也不受影响。那个目录不入库（.gitignore），本地编的包没有它 —— 返回 null。
 */
data class BundledReleaseNotes(
    val notes: String,
    /** tag 日期，`yyyy-MM-dd`；取不到为 null */
    val date: String?,
)

suspend fun loadBundledReleaseNotes(assets: AssetManager): BundledReleaseNotes? = withContext(Dispatchers.IO) {
    val notes = assets.readText(NOTES)?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
    BundledReleaseNotes(notes, assets.readText(DATE)?.trim()?.takeIf { it.isNotEmpty() })
}

private fun AssetManager.readText(path: String): String? =
    try {
        open(path).bufferedReader().use { it.readText() }
    } catch (_: FileNotFoundException) {
        null
    }

private const val NOTES = "release/notes.txt"
private const val DATE = "release/date.txt"
