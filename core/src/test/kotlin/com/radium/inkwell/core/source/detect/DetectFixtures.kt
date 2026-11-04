package com.radium.inkwell.core.source.detect

/**
 * 识别测试用的样本页。按国内小说站几种常见模板手写的**结构**（标签层级、类名习惯、页面上的干扰项），
 * 内容全是占位文字，不对应任何真实站点。
 */
internal object DetectFixtures {

    fun paragraph(i: Int) =
        "这是第${i}段正文，少年推开山门，远处传来悠长的钟声。他停下脚步，抬头望去，云海翻涌，像是有什么东西正在醒来。"

    private fun ordinal(n: Int) = "第${n}章 山门之外（$n）"

    /** 页头导航：每个站都有，链接数不少、地址各不相同 */
    private val NAV = """
        <div class="header"><div class="nav"><ul>
          <li><a href="/">首页</a></li>
          <li><a href="/xuanhuan/">玄幻小说</a></li>
          <li><a href="/xiuzhen/">修真小说</a></li>
          <li><a href="/dushi/">都市小说</a></li>
          <li><a href="/lishi/">历史小说</a></li>
          <li><a href="/top/">排行榜</a></li>
          <li><a href="/full/">完本小说</a></li>
        </ul></div></div>
    """.trimIndent()

    /** 页脚推荐：别的书，地址同构，但不带本书书号 */
    private fun recommend(n: Int) = (1..n).joinToString(
        "",
        prefix = """<div class="footer-rec"><h3>猜你喜欢</h3><ul>""",
        postfix = "</ul></div>",
    ) { """<li><a href="/book/${5000 + it}/">另一本书之$it</a></li>""" }

    /**
     * 笔趣阁式 PC 目录页：OG meta 齐全；`#list > dl` 里先 `<dt>` 最新章节（倒着列最后 [latest] 章），
     * 再 `<dt>` 正文（正序全部 [chapters] 章）。
     */
    fun biqugeToc(chapters: Int = 30, latest: Int = 6) = buildString {
        append(
            """
            <html><head><title>示例之书最新章节_示例之书全文阅读</title>
            <meta property="og:novel:book_name" content="示例之书">
            <meta property="og:novel:author" content="某作者">
            <meta property="og:image" content="/cover/1234.jpg">
            <meta property="og:description" content="一个少年走出山门的故事。">
            </head><body>
            """.trimIndent(),
        )
        append(NAV)
        append("""<div class="box_con"><div id="maininfo"><h1>示例之书</h1><p>作&nbsp;&nbsp;者：某作者</p></div></div>""")
        append("""<div class="box_con"><div id="list"><dl>""")
        append("<dt>《示例之书》最新章节</dt>")
        for (n in chapters downTo chapters - latest + 1) append("""<dd><a href="/book/1234/${100000 + n}.html">${ordinal(n)}</a></dd>""")
        append("<dt>《示例之书》正文</dt>")
        for (n in 1..chapters) append("""<dd><a href="/book/1234/${100000 + n}.html">${ordinal(n)}</a></dd>""")
        append("</dl></div></div>")
        append(recommend(8))
        append("</body></html>")
    }

    /**
     * 分卷目录：每卷一个 `div.volume`，卷名 + `ul.cf > li > a`。没有 OG，书名在唯一的 h1，
     * 作者包在链接里。页头导航也是 `div > ul > li > a`，从 body 往下看和章节列表同构。
     */
    fun volumeToc(volumes: Int = 3, perVolume: Int = 10) = buildString {
        append("<html><head><title>分卷之书</title></head><body>")
        append(NAV)
        append("""<div class="book-info"><h1>分卷之书</h1><p class="info">作者：<a href="/author/77">写书人</a> 分类：玄幻</p></div>""")
        append("""<div class="catalog">""")
        var n = 0
        for (v in 1..volumes) {
            append("""<div class="volume"><h2>第${v}卷 起风</h2><div class="volume-wrap"><ul class="cf">""")
            repeat(perVolume) {
                n++
                append("""<li><a href="/read/77/$n.html">${ordinal(n)}</a></li>""")
            }
            append("</ul></div></div>")
        }
        append("</div>")
        append(recommend(6))
        append("</body></html>")
    }

    /** 倒序目录：新章在上 */
    fun reversedToc(chapters: Int = 20) = buildString {
        append("<html><body>")
        append(NAV)
        append("""<ul id="chapters">""")
        for (n in chapters downTo 1) append("""<li><a href="/b/9/$n.html">${ordinal(n)}</a></li>""")
        append("</ul></body></html>")
    }

    /** 手机站详情页：只列最新几章（倒序），完整目录在「查看完整目录」 */
    fun mobileDetail(base: String, latest: Int = 5, total: Int = 27) = buildString {
        append(
            """
            <html><head>
            <meta property="og:novel:book_name" content="手机之书">
            <meta property="og:novel:author" content="掌上客">
            </head><body>
            """.trimIndent(),
        )
        append("""<div class="synopsisArea"><h1>手机之书</h1></div>""")
        append("""<p><a href="$base/book/88/all_1.html">查看完整目录</a></p>""")
        append("""<ul class="chapter">""")
        for (n in total downTo total - latest + 1) append("""<li><a href="/book/88/$n.html">${ordinal(n)}</a></li>""")
        append("</ul></body></html>")
    }

    /** 手机站分页目录：每页 [perPage] 章，「下一页」翻页；末页的下一页是 javascript: */
    fun mobileTocPage(page: Int, perPage: Int = 20, total: Int = 27) = buildString {
        val first = (page - 1) * perPage + 1
        val last = minOf(total, page * perPage)
        val pages = (total + perPage - 1) / perPage
        append("<html><body>")
        append("""<div class="intro">手机之书 目录</div><ul class="chapter">""")
        for (n in first..last) append("""<li><a href="/book/88/$n.html">${ordinal(n)}</a></li>""")
        append("</ul>")
        val prev = if (page > 1) "/book/88/all_${page - 1}.html" else "javascript:void(0)"
        val next = if (page < pages) "/book/88/all_${page + 1}.html" else "javascript:void(0)"
        append("""<div class="listpage"><a href="$prev">上一页</a><a href="$next">下一页</a></div>""")
        append("</body></html>")
    }

    /** 老站正文：整章一个 `div#content`，段落靠 `<br>` 分；块里夹一个广告，下面一行章节导航 */
    fun brContent(paragraphs: Int = 8, nextPage: String? = null) = buildString {
        append("<html><head><title>第一章</title></head><body>")
        append(NAV)
        append("""<div class="content_read"><div class="box_con"><div class="bookname"><h1>第1章 山门之外</h1>""")
        append("""<div class="bottem1"><a href="/book/1234/">章节目录</a><a href="/book/1234/100002.html">下一章</a></div></div>""")
        append("""<div id="content">""")
        for (i in 1..paragraphs) append("&nbsp;&nbsp;&nbsp;&nbsp;${paragraph(i)}<br><br>")
        append("""<div class="ad">请收藏本站，最新章节抢先看</div>""")
        append("</div>")
        append("""<div class="bottem2"><a href="/book/1234/">章节目录</a>""")
        if (nextPage != null) append("""<a href="$nextPage">下一页</a>""")
        append("""<a href="/book/1234/100002.html">下一章</a></div>""")
        append("</div></div></body></html>")
    }

    /** 新式正文：`div.read-content > p`，外层还包着标题和导航 */
    fun pContent(paragraphs: Int = 8) = buildString {
        append("<html><body>")
        append(NAV)
        append("""<div class="main-text-wrap"><h3 class="j_chapterName">第1章 山门之外</h3>""")
        append("""<div class="read-content j_readContent">""")
        for (i in 1..paragraphs) append("<p>${paragraph(i)}</p>")
        append("</div>")
        append("""<div class="chapter-control"><a href="/prev">上一章</a><a href="/toc">目录</a><a href="/next">下一章</a></div>""")
        append("</div></body></html>")
    }

    /** 一段一个 div 的正文 */
    fun divContent(paragraphs: Int = 8) = buildString {
        append("<html><body>")
        append(NAV)
        append("""<div class="wrap"><div id="chaptercontent">""")
        for (i in 1..paragraphs) append("<div>${paragraph(i)}</div>")
        append("</div></div></body></html>")
    }
}
