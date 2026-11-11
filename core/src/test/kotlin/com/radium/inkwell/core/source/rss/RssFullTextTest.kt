package com.radium.inkwell.core.source.rss

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.source.SourceHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 只给摘要的 feed：认出来、去原网页整理全文。
 *
 * 起因是少数派（sspai.com/feed）：每篇只有一两句话加一个「查看全文」链接，点进文章页就只剩那一句，
 * 看全文只能跳外部浏览器。
 */
class RssFullTextTest {

    private fun p(text: String) = ContentElement.Paragraph(text)

    // ---- 是不是摘要 ----

    @Test
    fun `少数派那种一两句加「查看全文」是摘要，「查看全文」去掉`() {
        val els = listOf(p("复古游戏机风格的界面和「手柄」设计，希望可以唤起你的一些回忆。查看全文"))
        assertTrue(RssTeaser.isTeaser(els))
        assertEquals(listOf(p("复古游戏机风格的界面和「手柄」设计，希望可以唤起你的一些回忆。")), RssTeaser.stripReadMore(els))
    }

    @Test
    fun `较长的摘要以省略号或 Read more 收尾也算，省略号留着`() {
        val long = "上周社区速递".repeat(40)
        assertTrue(RssTeaser.isTeaser(listOf(p("$long ...查看全文"))))
        assertEquals(listOf(p("$long ...")), RssTeaser.stripReadMore(listOf(p("$long ...查看全文"))))
        assertTrue(RssTeaser.isTeaser(listOf(p("$long […]"))))
        assertTrue(RssTeaser.isTeaser(listOf(p(long), p("Read more »"))))
        // 整段只是「Read more」：去掉后这一段就没了
        assertEquals(listOf(p(long)), RssTeaser.stripReadMore(listOf(p(long), p("Read more »"))))
    }

    @Test
    fun `全文不是摘要，很长的文章以省略号结尾也不算`() {
        val para = "这是一段完整的正文，讲得清清楚楚。".repeat(10)
        assertFalse(RssTeaser.isTeaser(listOf(p(para), p(para))))
        assertFalse(RssTeaser.isTeaser(List(12) { p(para) } + p("未完待续……")))
        // 什么都没有：全文只能在原网页上
        assertTrue(RssTeaser.isTeaser(emptyList()))
    }

    // ---- 原网页 → 正文 ----

    /** 照少数派文章页的骨架：导航、作者卡、正文（分几节）、评论 */
    private val articlePage = """
        <html><head><title>扫雷游戏 - 少数派</title></head><body>
          <nav><a href="/">首页</a><a href="/matrix">Matrix</a><a href="/tag">标签</a></nav>
          <div class="author-card">关注 bakamio，少数派作者，写过很多文章，欢迎关注，一起交流。</div>
          <article>
            <h1>扫雷游戏</h1>
            <div class="article-body">
              <section>
                <p>我特别喜欢玩扫雷，甚至已经把它当成了一种日常「冥想」工具。打开玩一会儿，把注意力放到棋盘和数字上，人就能静下心来。</p>
                <figure><img src="https://cdn.sspai.com/1.png?w=1120" data-original="https://cdn.sspai.com/1.png"/><figcaption>同一套玩法</figcaption></figure>
                <noscript><img src="https://cdn.sspai.com/1.png"/></noscript>
                <p>在电脑上玩扫雷，无论是经典扫雷，还是复刻版本，都有许多不错的选择。但我一直没在手机上找到顺手的。</p>
              </section>
              <section>
                <h2>三种游戏机风格</h2>
                <p>目前游戏有三种主题，配色的灵感来自原版游戏机的机身、屏幕和按键，希望可以唤起你的一些回忆。</p>
                <p>在手机上玩扫雷，最直接的方式当然是触屏：轻触翻开、长按插旗。周围的地雷全部标记完毕后，还可以双击数字格，一次翻开周围的格子。</p>
              </section>
            </div>
          </article>
          <div class="comments"><p>写得好！</p><p>已下载，好玩。</p></div>
          <footer>© 少数派 版权所有，转载请注明出处，侵权必究。联系我们、加入我们、商务合作。</footer>
        </body></html>
    """.trimIndent()

    @Test
    fun `整理出正文：几节都收进来，图留着，导航、作者卡、评论、页面上重复的标题不要`() {
        val els = ArticleExtractor.extract(Jsoup.parse(articlePage, "https://sspai.com/post/1"), "扫雷游戏")
        val texts = els.filterIsInstance<ContentElement.Paragraph>().map { it.text }
        assertTrue(texts.first().startsWith("我特别喜欢玩扫雷"), texts.toString())
        assertTrue(texts.last().startsWith("在手机上玩扫雷"), texts.toString())
        assertEquals(listOf("三种游戏机风格"), els.filterIsInstance<ContentElement.Heading>().map { it.text })
        // noscript 里那份备份不收：一张图只出现一次
        assertEquals(
            listOf("https://cdn.sspai.com/1.png?w=1120"),
            els.filterIsInstance<ContentElement.Image>().map { it.resourceId },
        )
        val all = texts.joinToString()
        for (junk in listOf("首页", "关注 bakamio", "写得好", "版权所有")) assertFalse(junk in all, "混进了「$junk」")
    }

    // ---- 整条：feed 列表 → 摘要 → 原网页 ----

    @Test
    fun `只给摘要的文章先给摘要，再从原网页整理出全文；全文 feed 照旧`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/feed" -> MockResponse().setBody(
                    """
                    <?xml version="1.0"?>
                    <rss version="2.0"><channel><title>少数派</title>
                      <item><title>扫雷游戏</title><link>${server.url("/post/1")}</link>
                        <description>复古游戏机风格的界面。&lt;a href="${server.url("/post/1")}"&gt;查看全文&lt;/a&gt;</description></item>
                      <item><title>脚本页</title><link>${server.url("/post/2")}</link>
                        <description>一句话。查看全文</description></item>
                      <item><title>全文</title><link>${server.url("/post/3")}</link>
                        <description>&lt;p&gt;${"完整的一段正文，讲得清清楚楚。".repeat(20)}&lt;/p&gt;&lt;img src="/pic.png"/&gt;</description></item>
                    </channel></rss>
                    """.trimIndent(),
                )
                "/post/1" -> MockResponse().setBody(articlePage)
                // 要跑脚本才出内容的页面：静态 HTML 里只有一个空壳
                "/post/2" -> MockResponse().setBody("<html><body><div id=\"app\"></div><script>render()</script></body></html>")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val http = SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true)
        val engine = RssEngine(http)
        val rule = RssSourceRule(id = server.url("/feed").toString(), name = "少数派")
        val (teaser, scripted, full) = engine.articles(rule, rule.id).items

        val c1 = engine.content(rule, teaser)
        assertTrue(c1.teaser)
        assertEquals(listOf(p("复古游戏机风格的界面。")), c1.elements)
        val text = assertNotNull(engine.fullText(rule, teaser, c1.elements))
        assertTrue(text.any { it is ContentElement.Heading && it.text == "三种游戏机风格" })
        assertEquals(1, text.count { it is ContentElement.Image })

        val c2 = engine.content(rule, scripted)
        assertTrue(c2.teaser)
        assertNull(engine.fullText(rule, scripted, c2.elements), "整理不出来就是 null，页面上留着摘要")

        val c3 = engine.content(rule, full)
        assertFalse(c3.teaser)
        // 全文 feed 里的图也显示了（从前一张都没有）
        assertEquals(listOf(server.url("/pic.png").toString()), c3.elements.filterIsInstance<ContentElement.Image>().map { it.resourceId })
        // feed 一次、两篇摘要各抓一次原网页；全文那篇不再抓
        assertEquals(3, server.requestCount, "全文 feed 不该再去抓原网页")
        server.shutdown()
    }
}
