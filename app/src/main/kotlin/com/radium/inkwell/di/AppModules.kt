package com.radium.inkwell.di

import androidx.room3.Room
import com.radium.inkwell.core.model.BookParserRegistry
import com.radium.inkwell.core.parser.epub.EpubParser
import com.radium.inkwell.core.parser.mobi.MobiParser
import com.radium.inkwell.core.parser.txt.TxtParser
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.data.db.InkwellDb
import com.radium.inkwell.data.prefs.ReaderPrefs
import com.radium.inkwell.data.prefs.WebDavPrefs
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.ChapterContentCache
import com.radium.inkwell.data.repo.AutoSourceSwitcher
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.data.repo.ReplaceRuleRepository
import com.radium.inkwell.data.repo.WebDavRepository
import com.radium.inkwell.ui.bookshelf.BookshelfViewModel
import com.radium.inkwell.ui.reader.ReaderViewModel
import com.radium.inkwell.ui.search.SearchViewModel
import com.radium.inkwell.ui.sourcedetail.SourceDetailViewModel
import com.radium.inkwell.ui.sourcemanage.SourceManageViewModel
import com.radium.inkwell.ui.webdav.WebDavViewModel
import com.radium.inkwell.util.KeyEventBus
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single {
        Room.databaseBuilder(androidContext(), InkwellDb::class.java, "inkwell.db")
            .addMigrations(
                InkwellDb.MIGRATION_1_2,
                InkwellDb.MIGRATION_2_3,
                InkwellDb.MIGRATION_3_4,
                InkwellDb.MIGRATION_4_5,
                InkwellDb.MIGRATION_5_6,
                InkwellDb.MIGRATION_6_7,
                InkwellDb.MIGRATION_7_8,
                InkwellDb.MIGRATION_8_9,
                InkwellDb.MIGRATION_9_10,
                InkwellDb.MIGRATION_10_11,
                InkwellDb.MIGRATION_11_12,
                InkwellDb.MIGRATION_12_13,
                InkwellDb.MIGRATION_13_14,
                InkwellDb.MIGRATION_14_15,
                InkwellDb.MIGRATION_15_16,
                InkwellDb.MIGRATION_16_17,
                InkwellDb.MIGRATION_17_18,
                InkwellDb.MIGRATION_18_19,
                InkwellDb.MIGRATION_19_20,
            )
            // 用户侧载旧版 APK（库版本比代码新）时，Room 默认抛异常 → 每次启动崩溃死循环。
            // 仅**降级**时销毁重建（升级仍走上面的迁移，不丢数据）。
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
    }
    single { get<InkwellDb>().bookDao() }
    single { get<InkwellDb>().chapterDao() }
    single { get<InkwellDb>().bookSourceDao() }
    single { get<InkwellDb>().bookSourceHitDao() }
    single { get<InkwellDb>().replaceRuleDao() }
    single { get<InkwellDb>().rssSourceDao() }
    single { get<InkwellDb>().readingStatDao() }
    single { com.radium.inkwell.data.repo.ReadingStatsRepository(get(), get(), get()) }

    // EPUB/MOBI 的 sniff 靠魔数，txt 兜底放最后
    // txt 的解码结果与拆章表缓存在系统可清理的缓存目录：没了只是下次打开慢一次（见 TxtParser / TxtCache）
    single {
        BookParserRegistry(
            listOf(EpubParser(), MobiParser(), TxtParser(cacheDir = java.io.File(androidContext().cacheDir, "txt"))),
        )
    }

    single { com.radium.inkwell.data.repo.ReaderFontStore(androidContext()) }
    // 字体库按需再取：ReaderPrefs 在 Application.onCreate 就被预热，别让它把扫字体目录也拽到启动路径上
    single { ReaderPrefs(androidContext()) { id -> get<com.radium.inkwell.data.repo.ReaderFontStore>().isAvailable(id) } }
    single { WebDavPrefs(androidContext()) }
    single { com.radium.inkwell.data.prefs.AppPrefs(androidContext()) }
    single { com.radium.inkwell.util.RunLogRecorder(androidContext()) }
    single { KeyEventBus() }
    single { ChapterContentCache(java.io.File(androidContext().filesDir, "cache")) }

    // 抓取日志接到 logcat：成功 debug、失败/重试 warn。开发者模式的运行日志录的就是 logcat
    single {
        SourceHttpClient(
            logger = { warn, msg, e ->
                if (warn) android.util.Log.w("SourceHttp", msg, e) else android.util.Log.d("SourceHttp", msg)
            },
        )
    }
    // Rhino 解释执行 JS 规则（与 Legado 同引擎）
    single<com.radium.inkwell.core.source.js.ScriptRuntime> {
        com.radium.inkwell.core.source.js.RhinoScriptRuntime()
    }
    // JS 渲染回退：静态 HTML 解析不出内容时用 WebView 执行页面 JS 再试（详情/目录/正文三级）
    single<com.radium.inkwell.core.source.PageRenderer> {
        com.radium.inkwell.data.source.WebViewPageRenderer(androidContext())
    }
    single {
        val replaceRules = get<ReplaceRuleRepository>()
        BookSourceEngine(
            http = get(),
            scriptRuntime = get(),
            renderer = get(),
            globalPurify = { source, bookTitle -> replaceRules.purifyFor(source, bookTitle) },
        )
    }
    single { com.radium.inkwell.core.source.rss.RssEngine(http = get(), scriptRuntime = get()) }
    // 新建书源的页面识别：与引擎共用抓取客户端（同一套 UA、Cookie、私网防护）、WebView 渲染回退、
    // 脚本运行时与全局净化 —— 「高级」页的试抓要和加书后的正式引擎一个样
    single {
        val replaceRules = get<ReplaceRuleRepository>()
        com.radium.inkwell.core.source.detect.SiteAnalyzer(
            http = get(),
            renderer = get(),
            scriptRuntime = get(),
            globalPurify = { source, bookTitle -> replaceRules.purifyFor(source, bookTitle) },
        )
    }
    single { com.radium.inkwell.data.repo.RssRepository(get(), get()) }
    // 更新用的通用 HTTP 客户端（检查 + 下载安装包 + 反馈上报）
    single {
        okhttp3.OkHttpClient.Builder()
            // OkHttp 默认这三项各 10 秒，显式写出来是为了把「没配」和「配成 10 秒」区分开。
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(20))
            .writeTimeout(java.time.Duration.ofSeconds(15))
            // **刻意不设 callTimeout**：这个客户端要下载几十 MB 的安装包，
            // 慢网下整次调用超过任何合理总闸都是正常的。逐段的 readTimeout 已经能识别真卡死。
            .build()
    }
    single { com.radium.inkwell.update.UpdateChecker(get()) }
    single { com.radium.inkwell.update.ServerUpdateChecker(get()) }
    single { com.radium.inkwell.data.repo.FeedbackRepository(get()) }
    single {
        com.radium.inkwell.update.UpdateInstaller(
            get(),
            com.radium.inkwell.update.InstalledAppAuthenticity(androidContext()),
        )
    }
    single { com.radium.inkwell.update.UpdateManager(get(), get(), get()) }
    // 启动时查更新、书架齿轮与设置页的红点读它（见 UpdateNotifier）
    single { com.radium.inkwell.update.UpdateNotifier(androidContext(), get(), get()) }

    single { BookRepository(androidContext(), get(), get(), get(), get(), get(), get(), get()) }
    single { BookSourceRepository(get(), get()) }
    single { ReplaceRuleRepository(get()) }
    single { com.radium.inkwell.data.repo.LocalFolderScanner(androidContext()) }
    single { NetBookRepository(get(), get(), get(), get(), get()) }
    single { AutoSourceSwitcher(get(), get()) }
    single { com.radium.inkwell.util.LocalNetworkAccess(androidContext()) }
    single { WebDavRepository(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { com.radium.inkwell.data.net.OfficialWebDavClient(get()) }
    single { com.radium.inkwell.data.repo.OfficialWebDavRepository(get(), get()) }

    viewModel { BookshelfViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { (bookId: String) ->
        ReaderViewModel(bookId, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }
    viewModel { SearchViewModel(get(), get(), get(), get()) }
    viewModel { (results: List<com.radium.inkwell.core.source.SearchResult>) ->
        com.radium.inkwell.ui.preview.BookPreviewViewModel(results, get(), get(), get())
    }
    viewModel { com.radium.inkwell.ui.explore.ExploreViewModel(get(), get(), get(), get()) }
    viewModel { (bookId: String) -> com.radium.inkwell.ui.detail.BookDetailViewModel(bookId, get(), get(), get(), get(), get()) }
    // 校验挂在应用级单例上，离开书源管理页也照跑（见 SourceChecker）
    single { com.radium.inkwell.ui.sourcemanage.SourceChecker(get(), get(), get()) }
    // 整本缓存同理：离开详情页照常跑
    single { com.radium.inkwell.data.repo.OfflineDownloader(get(), get(), get(), get(), get()) }
    viewModel { SourceManageViewModel(androidContext(), get(), get(), get()) }
    viewModel { (sourceId: String) -> SourceDetailViewModel(sourceId, get(), get(), get()) }
    viewModel { com.radium.inkwell.ui.sourcewizard.SourceWizardViewModel(get(), get(), get(), get()) }
    viewModel { WebDavViewModel(get(), get(), get(), get()) }
    viewModel { com.radium.inkwell.ui.feedback.FeedbackViewModel(androidContext(), get(), get()) }
    viewModel { com.radium.inkwell.ui.settings.DeveloperViewModel(get(), get()) }
    viewModel { com.radium.inkwell.ui.stats.ReadingStatsViewModel(get(), get()) }
    viewModel { com.radium.inkwell.ui.replace.ReplaceRuleViewModel(androidContext(), get(), get()) }
    viewModel { com.radium.inkwell.ui.rss.RssSourceViewModel(androidContext(), get()) }
    viewModel { (sourceId: String) -> com.radium.inkwell.ui.rss.RssArticlesViewModel(sourceId, get()) }
    viewModel { (sourceId: String) -> com.radium.inkwell.ui.rss.RssWebViewModel(sourceId, get()) }
    viewModel { (args: com.radium.inkwell.ui.rss.RssArticleArgs) ->
        com.radium.inkwell.ui.rss.RssArticleViewModel(args, get())
    }
}
