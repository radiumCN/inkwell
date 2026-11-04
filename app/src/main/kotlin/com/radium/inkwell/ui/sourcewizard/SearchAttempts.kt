package com.radium.inkwell.ui.sourcewizard

import com.radium.inkwell.core.source.detect.SearchTemplates
import com.radium.inkwell.core.source.detect.SiteAnalyzer

/**
 * 「就是这一页」时要试的搜索地址（模板 + 搜索词），按顺序试，第一个认得出结果列表的为准：
 * 1. 表单生成的：字段、方法、编码都在，POST 站只能靠它；
 * 2. 在当前地址里找表单里填的那个词；
 * 3. 在当前地址里找建议用户搜的那个词（没有表单可抓的站）。
 *
 * 地址那两条不能只在「没有表单」时才试：有的站用脚本拦下表单提交、自己拼地址跳过去（`/search/剑/`），
 * 表单照样被记下，可它的 action 只是当前页，按它生成的地址根本搜不到。从前就此卡死 ——
 * 「重新搜一次」记到的还是同一个表单，而地址里明明带着关键词。
 */
internal fun searchAttempts(form: CapturedForm?, webUrl: String): List<Pair<String, String>> {
    val keyword = form?.let { SearchTemplates.formKeyword(it.fields) }
    return listOfNotNull(
        form?.let { f ->
            keyword?.let { kw -> SearchTemplates.fromForm(f.action, f.method, f.charset, f.fields)?.let { it to kw } }
        },
        keyword?.let { kw -> SearchTemplates.fromUrl(webUrl, kw)?.let { it to kw } },
        SearchTemplates.fromUrl(webUrl, SiteAnalyzer.SUGGESTED_KEYWORD)?.let { it to SiteAnalyzer.SUGGESTED_KEYWORD },
    ).distinct()
}
