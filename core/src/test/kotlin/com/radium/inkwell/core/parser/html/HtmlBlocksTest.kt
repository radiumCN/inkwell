package com.radium.inkwell.core.parser.html

import com.radium.inkwell.core.model.ContentElement
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 订阅文章的代码块、列表、引用（keepBlocks）。从前这三样都压成普通段落：代码块的换行、缩进全没了，
 * 挤成一行（「她和她的猫」那篇被反馈过）。书（默认）不开，照旧当段落 —— 最后两条钉着。
 */
class HtmlBlocksTest {

    private fun article(html: String, base: String = "https://her-cat.com/posts/1/") =
        HtmlToElements(keepImages = true).convert(Jsoup.parse(html, base).body())

    private fun book(html: String) = HtmlToElements().convert(Jsoup.parse(html).body())

    // ---------- 代码块 ----------

    /** 原样取自 her-cat.com（Hugo 的 chroma 高亮：一行一个 span，换行字符在 span 里） */
    @Test
    fun `高亮过的代码块按原样换行，认出语言`() {
        val html = """<p>先装 1Password CLI：</p><div class="highlight"><pre tabindex="0" class="chroma"><code class="language-bash" data-lang="bash"><span class="line"><span class="cl"><span class="c1"># macOS</span>
</span></span><span class="line"><span class="cl">brew install 1password-cli
</span></span><span class="line"><span class="cl">  op item get <span class="o">{</span>id/name<span class="o">}</span>
</span></span></code></pre></div><p>装好后</p>"""
        assertEquals(
            listOf(
                ContentElement.Paragraph("先装 1Password CLI："),
                ContentElement.Code("# macOS\nbrew install 1password-cli\n  op item get {id/name}", "bash"),
                ContentElement.Paragraph("装好后"),
            ),
            article(html),
        )
    }

    @Test
    fun `带行号的代码表只取代码那一格`() {
        val html = """<div class="highlight"><div class="chroma"><table class="lntable"><tr>
            <td class="lntd"><pre class="chroma"><code><span class="lnt">1
</span><span class="lnt">2
</span></code></pre></td>
            <td class="lntd"><pre class="chroma"><code class="language-python" data-lang="python">print(1)
print(2)
</code></pre></td></tr></table></div></div>"""
        assertEquals(listOf(ContentElement.Code("print(1)\nprint(2)", "python")), article(html))
    }

    @Test
    fun `行内行号、br 换行、一行一个 div 都拼得回来`() {
        val inlineNumbers = """<pre><code><span class="ln">1</span>a = 1
<span class="ln">2</span>b = 2
</code></pre>"""
        assertEquals(listOf(ContentElement.Code("a = 1\nb = 2")), article(inlineNumbers))
        assertEquals(listOf(ContentElement.Code("ls\ncd /tmp")), article("<pre>ls<br>cd /tmp</pre>"))
        assertEquals(
            listOf(ContentElement.Code("x\n    y", "js")),
            article("""<pre class="brush: js;"><div>x</div><div>&nbsp;&nbsp;&nbsp;&nbsp;y</div></pre>"""),
        )
    }

    @Test
    fun `制表符换成空格，首尾空行去掉，plaintext 不算语言`() {
        assertEquals(
            listOf(ContentElement.Code("if x:\n    y")),
            article("<pre><code class=\"language-plaintext\">\n\nif x:\n\ty   \n\n</code></pre>"),
        )
    }

    @Test
    fun `外面包着的 div 上标的语言也认，lang- 只认 pre 和 code 自己的`() {
        assertEquals(
            listOf(ContentElement.Code("echo hi", "shell")),
            article("""<div class="language-shell highlighter-rouge"><div class="highlight"><pre class="highlight"><code>echo hi</code></pre></div></div>"""),
        )
        // 网页语言写在外层容器上（lang-zh），不是代码的语言
        assertEquals(
            listOf(ContentElement.Code("echo hi")),
            article("""<div class="post lang-zh"><pre><code>echo hi</code></pre></div>"""),
        )
    }

    @Test
    fun `段落里的行内 code 还是段落里的字`() {
        assertEquals(
            listOf(ContentElement.Paragraph("脚本里的 catch 命令类似 try-catch")),
            article("<p>脚本里的 <code>catch</code> 命令类似 try-catch</p>"),
        )
    }

    // ---------- 列表 ----------

    @Test
    fun `无序列表带圆点，有序列表按 start 编号`() {
        assertEquals(
            listOf(
                ContentElement.ListItem("配置 SSH Agent", "•"),
                ContentElement.ListItem("配置 2FA", "•"),
                ContentElement.ListItem("第三步", "3."),
                ContentElement.ListItem("第四步", "4."),
            ),
            article("<ul><li>配置 SSH Agent</li><li><p>配置 2FA</p></li></ul><ol start=\"3\"><li>第三步</li><li>第四步</li></ol>"),
        )
    }

    @Test
    fun `嵌套列表往里缩一层，项里的代码和图跟在后面`() {
        val els = article(
            "<ul><li>外层<ul><li>里层 <strong>加粗</strong></li></ul></li>" +
                "<li>装好后运行<pre><code>op signin</code></pre></li>" +
                "<li>截图<img src=\"/shot.png\" alt=\"设置\"></li></ul>",
        )
        assertEquals(
            listOf(
                ContentElement.ListItem("外层", "•", 0),
                ContentElement.ListItem("里层 加粗", "◦", 1),
                ContentElement.ListItem("装好后运行", "•", 0),
                ContentElement.Code("op signin"),
                ContentElement.ListItem("截图", "•", 0),
                ContentElement.Image("https://her-cat.com/shot.png", "设置"),
            ),
            els,
        )
    }

    @Test
    fun `列表项里的表情图换回文字`() {
        val emoji = "<img src=\"https://s.w.org/images/core/emoji/17.0.2/72x72/1f4a1.png\" alt=\"💡\" class=\"wp-smiley\"/>"
        assertEquals(listOf(ContentElement.ListItem("💡 小提示", "•")), article("<ul><li>$emoji 小提示</li></ul>"))
    }

    // ---------- 引用 ----------

    @Test
    fun `引用的几段合成一块，段间换行`() {
        assertEquals(
            listOf(
                ContentElement.Paragraph("前文"),
                ContentElement.Quote("需要在 1Password 设置里开启「使用 SSH Agent」。\n第二段"),
                ContentElement.Paragraph("后文"),
            ),
            article("<p>前文</p><blockquote><p>需要在 1Password 设置里开启「使用 SSH Agent」。</p><p>第二段</p></blockquote><p>后文</p>"),
        )
    }

    // ---------- 书不受影响 ----------

    @Test
    fun `书的正文里代码、列表、引用照旧是段落`() {
        assertEquals(
            listOf(
                ContentElement.Paragraph("a = 1 b = 2"),
                ContentElement.Paragraph("甲"),
                ContentElement.Paragraph("乙"),
                ContentElement.Paragraph("引文"),
            ),
            book("<pre>a = 1\nb = 2</pre><ul><li>甲</li><li>乙</li></ul><blockquote>引文</blockquote>"),
        )
    }
}
