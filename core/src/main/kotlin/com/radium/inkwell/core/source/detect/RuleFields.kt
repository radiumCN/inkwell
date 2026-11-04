package com.radium.inkwell.core.source.detect

import com.radium.inkwell.core.source.BookInfoRuleSet
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.ContentRuleSet
import com.radium.inkwell.core.source.SearchRuleSet
import com.radium.inkwell.core.source.TocRuleSet

/**
 * 向导「高级」页能逐项改的规则字段：在 [BookSourceRule] 上怎么读写，以及它在引擎调试记录里叫什么。
 *
 * [stage] / [traceName] 对的是 [com.radium.inkwell.core.source.RuleTrace] 的 stage / fieldName ——
 * 引擎内部的字段名和 Legado 字段名不一样（详情的书名叫 `title`，目录的章节地址叫 `url`），
 * 试抓一遍之后靠这张表把调试记录对回每一栏。[traceName] 为空的字段引擎不单独记（替换规则、搜索地址），
 * 它们的效果看别的栏（正文预览、搜索结果）。
 */
enum class RuleField(val stage: String, val traceName: String) {
    BookName(STAGE_DETAIL, "title"),
    BookAuthor(STAGE_DETAIL, "author"),
    BookCover(STAGE_DETAIL, "coverUrl"),
    BookIntro(STAGE_DETAIL, "intro"),
    TocUrl(STAGE_DETAIL, "tocUrl"),

    ChapterList(STAGE_TOC, "list"),
    ChapterName(STAGE_TOC, "title"),
    ChapterUrl(STAGE_TOC, "url"),
    NextTocUrl(STAGE_TOC, "nextPage"),

    Content(STAGE_CONTENT, "content"),
    NextContentUrl(STAGE_CONTENT, "nextPage"),
    ReplaceRegex(STAGE_CONTENT, ""),

    SearchUrl(STAGE_SEARCH, ""),
    SearchList(STAGE_SEARCH, "list"),
    SearchName(STAGE_SEARCH, "title"),
    SearchAuthor(STAGE_SEARCH, "author"),
    SearchBookUrl(STAGE_SEARCH, "bookUrl"),
    SearchCover(STAGE_SEARCH, "coverUrl"),
    SearchLatest(STAGE_SEARCH, "latestChapter"),
    ;

    fun get(rule: BookSourceRule): String? = when (this) {
        BookName -> rule.ruleBookInfo?.name
        BookAuthor -> rule.ruleBookInfo?.author
        BookCover -> rule.ruleBookInfo?.coverUrl
        BookIntro -> rule.ruleBookInfo?.intro
        TocUrl -> rule.ruleBookInfo?.tocUrl
        ChapterList -> rule.ruleToc?.chapterList
        ChapterName -> rule.ruleToc?.chapterName
        ChapterUrl -> rule.ruleToc?.chapterUrl
        NextTocUrl -> rule.ruleToc?.nextTocUrl
        Content -> rule.ruleContent?.content
        NextContentUrl -> rule.ruleContent?.nextContentUrl
        ReplaceRegex -> rule.ruleContent?.replaceRegex
        SearchUrl -> rule.searchUrl
        SearchList -> rule.ruleSearch?.bookList
        SearchName -> rule.ruleSearch?.name
        SearchAuthor -> rule.ruleSearch?.author
        SearchBookUrl -> rule.ruleSearch?.bookUrl
        SearchCover -> rule.ruleSearch?.coverUrl
        SearchLatest -> rule.ruleSearch?.lastChapter
    }

    /** 写回一个字段；空白当作删掉这一项（存 null，序列化时不出现） */
    fun set(rule: BookSourceRule, raw: String?): BookSourceRule {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() }
        val info = rule.ruleBookInfo ?: BookInfoRuleSet()
        val toc = rule.ruleToc ?: TocRuleSet()
        val content = rule.ruleContent ?: ContentRuleSet()
        val search = rule.ruleSearch ?: SearchRuleSet()
        return when (this) {
            BookName -> rule.copy(ruleBookInfo = info.copy(name = v))
            BookAuthor -> rule.copy(ruleBookInfo = info.copy(author = v))
            BookCover -> rule.copy(ruleBookInfo = info.copy(coverUrl = v))
            BookIntro -> rule.copy(ruleBookInfo = info.copy(intro = v))
            TocUrl -> rule.copy(ruleBookInfo = info.copy(tocUrl = v))
            ChapterList -> rule.copy(ruleToc = toc.copy(chapterList = v))
            ChapterName -> rule.copy(ruleToc = toc.copy(chapterName = v))
            ChapterUrl -> rule.copy(ruleToc = toc.copy(chapterUrl = v))
            NextTocUrl -> rule.copy(ruleToc = toc.copy(nextTocUrl = v))
            Content -> rule.copy(ruleContent = content.copy(content = v))
            NextContentUrl -> rule.copy(ruleContent = content.copy(nextContentUrl = v))
            ReplaceRegex -> rule.copy(ruleContent = content.copy(replaceRegex = v))
            SearchUrl -> rule.copy(searchUrl = v)
            SearchList -> rule.copy(ruleSearch = search.copy(bookList = v))
            SearchName -> rule.copy(ruleSearch = search.copy(name = v))
            SearchAuthor -> rule.copy(ruleSearch = search.copy(author = v))
            SearchBookUrl -> rule.copy(ruleSearch = search.copy(bookUrl = v))
            SearchCover -> rule.copy(ruleSearch = search.copy(coverUrl = v))
            SearchLatest -> rule.copy(ruleSearch = search.copy(lastChapter = v))
        }
    }
}

const val STAGE_DETAIL = "detail"
const val STAGE_TOC = "toc"
const val STAGE_CONTENT = "content"
const val STAGE_SEARCH = "search"
