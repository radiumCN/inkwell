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
        Room.databaseBuilder(androidContext(), InkwellDb::class.java, "inkwell.db").build()
    }
    single { get<InkwellDb>().bookDao() }
    single { get<InkwellDb>().chapterDao() }
    single { get<InkwellDb>().bookSourceDao() }

    // EPUB/MOBI 的 sniff 靠魔数，txt 兜底放最后
    single { BookParserRegistry(listOf(EpubParser(), MobiParser(), TxtParser())) }

    single { ReaderPrefs(androidContext()) }
    single { WebDavPrefs(androidContext()) }
    single { KeyEventBus() }
    single { ChapterContentCache(androidContext()) }

    single { SourceHttpClient() }
    single { BookSourceEngine(get()) }

    single { BookRepository(androidContext(), get(), get(), get()) }
    single { BookSourceRepository(get()) }
    single { NetBookRepository(get(), get(), get(), get()) }
    single { WebDavRepository(get(), get(), get()) }

    viewModel { BookshelfViewModel(get()) }
    viewModel { (bookId: String) ->
        ReaderViewModel(bookId, get(), get(), get(), get(), get(), get(), get())
    }
    viewModel { SearchViewModel(get(), get(), get()) }
    viewModel { SourceManageViewModel(androidContext(), get()) }
    viewModel { (sourceId: String?) -> SourceEditViewModel(sourceId, get(), get()) }
    viewModel { WebDavViewModel(get(), get()) }
}
