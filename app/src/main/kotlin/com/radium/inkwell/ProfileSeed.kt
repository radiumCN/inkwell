package com.radium.inkwell

import android.content.Context
import com.radium.inkwell.data.repo.BookRepository
import java.io.File
import org.koin.core.context.GlobalContext

/**
 * Baseline Profile 生成器走进阅读器的夹具。
 *
 * 生成器跑在另一个进程，空书架走不进 [Paginator]/[ReaderScreen]。
 * 启动时带 [EXTRA] 就把 assets 里那本固定 txt 导入书架，测试再点书名进去。
 * 用户正常启动不会带这个 extra。
 */
internal object ProfileSeed {
    const val EXTRA = "com.radium.inkwell.seed_profile_book"
    const val TITLE = "Benchmark"

    suspend fun importIfRequested(context: Context, requested: Boolean) {
        if (!requested) return
        val repo = GlobalContext.get().get<BookRepository>()
        if (repo.shelfLocalBookIdByKey(TITLE, "") != null) return
        val tmp = File(context.cacheDir, "benchmark-seed.txt")
        context.assets.open("benchmark/seed.txt").use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        try {
            repo.importLocalFile(tmp, "$TITLE.txt").getOrThrow()
        } finally {
            tmp.delete()
        }
    }
}
