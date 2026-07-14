package com.radium.inkwell.di

import androidx.room.Room
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
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.data.repo.ReplaceRuleRepository
import com.radium.inkwell.data.repo.WebDavRepository
import com.radium.inkwell.ui.bookshelf.BookshelfViewModel
import com.radium.inkwell.ui.reader.ReaderViewModel
import com.radium.inkwell.ui.search.SearchViewModel
import com.radium.inkwell.ui.sourceedit.SourceEditViewModel
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
            )
            .build()
    }
    single { get<InkwellDb>().bookDao() }
    single { get<InkwellDb>().chapterDao() }
    single { get<InkwellDb>().bookSourceDao() }
    single { get<InkwellDb>().replaceRuleDao() }

    // EPUB/MOBI 的 sniff 靠魔数，txt 兜底放最后
    single { BookParserRegistry(listOf(EpubParser(), MobiParser(), TxtParser())) }

    single { ReaderPrefs(androidContext()) }
    single { WebDavPrefs(androidContext()) }
    single { com.radium.inkwell.data.prefs.AppPrefs(androidContext()) }
    single { KeyEventBus() }
    single { ChapterContentCache(java.io.File(androidContext().filesDir, "cache")) }

    single { SourceHttpClient() }
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
            globalPurify = { source -> replaceRules.purifyFor(source) },
        )
    }
    single { com.radium.inkwell.update.UpdateChecker() }

    single { BookRepository(androidContext(), get(), get(), get()) }
    single { BookSourceRepository(get()) }
    single { ReplaceRuleRepository(get()) }
    single { NetBookRepository(get(), get(), get(), get()) }
    single { WebDavRepository(get(), get(), get(), get(), get(), get()) }

    viewModel { BookshelfViewModel(get()) }
    viewModel { (bookId: String) ->
        ReaderViewModel(bookId, get(), get(), get(), get(), get(), get(), get(), get(), get())
    }
    viewModel { SearchViewModel(get(), get(), get()) }
    viewModel { (results: List<com.radium.inkwell.core.source.SearchResult>) ->
        com.radium.inkwell.ui.preview.BookPreviewViewModel(results, get(), get())
    }
    viewModel { com.radium.inkwell.ui.explore.ExploreViewModel(get(), get(), get()) }
    viewModel { SourceManageViewModel(androidContext(), get(), get(), get()) }
    viewModel { (sourceId: String?) -> SourceEditViewModel(sourceId, get(), get(), get()) }
    viewModel { WebDavViewModel(get(), get()) }
    viewModel { com.radium.inkwell.ui.replace.ReplaceRuleViewModel(get()) }
}
