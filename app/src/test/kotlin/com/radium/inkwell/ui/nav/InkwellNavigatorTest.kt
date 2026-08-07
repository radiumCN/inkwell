package com.radium.inkwell.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlin.test.assertEquals
import org.junit.Test

/**
 * 返回栈规则。
 *
 * 起因是个真 bug：从三级页（意见反馈）按返回直接回到设置一级页 —— `go` 把「下钻」当成了
 * 宽屏 detail pane 的「兄弟替换」，一律把栈顶的设置详情弹到底，中间那层「关于」被抹掉。
 * 这种错只能靠手点发现，所以钉在这里。
 */
class InkwellNavigatorTest {

    private fun stack(vararg keys: NavKey) = mutableListOf(*keys)

    // ---------- 下钻：中间层必须留在栈里 ----------

    @Test
    fun `关于下钻到意见反馈时保留关于`() {
        val s = stack(BookshelfRoute, SettingsRoute, AboutSettingsRoute)
        s.navigateTo(FeedbackRoute)
        assertEquals(
            listOf(BookshelfRoute, SettingsRoute, AboutSettingsRoute, FeedbackRoute),
            s,
            "返回时应回到「关于」，而不是跨回设置一级页",
        )
    }

    @Test
    fun `外观下钻到主题时保留外观`() {
        val s = stack(BookshelfRoute, SettingsRoute, AppearanceSettingsRoute)
        s.navigateTo(ThemeSettingsRoute)
        assertEquals(
            listOf(BookshelfRoute, SettingsRoute, AppearanceSettingsRoute, ThemeSettingsRoute),
            s,
        )
    }

    /** 四级：阅读 → 书源管理 → 书源详情，一层都不能少 */
    @Test
    fun `书源详情之上保留书源管理与阅读设置`() {
        val s = stack(BookshelfRoute, SettingsRoute, ReadingSettingsRoute)
        s.navigateTo(SourceManageRoute)
        s.navigateTo(SourceDetailRoute("src-1"))
        assertEquals(
            listOf(
                BookshelfRoute,
                SettingsRoute,
                ReadingSettingsRoute,
                SourceManageRoute,
                SourceDetailRoute("src-1"),
            ),
            s,
        )
    }

    // ---------- 兄弟替换：宽屏 detail pane 只有一格 ----------

    @Test
    fun `同级的两个二级页互相替换`() {
        val s = stack(BookshelfRoute, SettingsRoute, AppearanceSettingsRoute)
        s.navigateTo(ReadingSettingsRoute)
        assertEquals(listOf(BookshelfRoute, SettingsRoute, ReadingSettingsRoute), s)
    }

    /** 从三级页跳去另一个二级页（宽屏下左栏一直可点）：更深的那几层要一起收掉 */
    @Test
    fun `从三级页跳到二级页会收掉整条下钻路径`() {
        val s = stack(BookshelfRoute, SettingsRoute, AboutSettingsRoute, FeedbackRoute)
        s.navigateTo(ReadingSettingsRoute)
        assertEquals(listOf(BookshelfRoute, SettingsRoute, ReadingSettingsRoute), s)
    }

    @Test
    fun `同级的两个三级页互相替换`() {
        val s = stack(BookshelfRoute, SettingsRoute, AboutSettingsRoute, FeedbackRoute)
        s.navigateTo(DisclaimerRoute)
        assertEquals(
            listOf(BookshelfRoute, SettingsRoute, AboutSettingsRoute, DisclaimerRoute),
            s,
        )
    }

    @Test
    fun `重复点同一页不叠栈`() {
        val s = stack(BookshelfRoute, SettingsRoute, AboutSettingsRoute)
        s.navigateTo(FeedbackRoute)
        s.navigateTo(FeedbackRoute)
        assertEquals(
            listOf(BookshelfRoute, SettingsRoute, AboutSettingsRoute, FeedbackRoute),
            s,
        )
    }

    /** 换一条书源详情是换内容，不是下钻 */
    @Test
    fun `换另一个书源详情时替换而不叠栈`() {
        val s = stack(BookshelfRoute, SettingsRoute, SourceManageRoute, SourceDetailRoute("a"))
        s.navigateTo(SourceDetailRoute("b"))
        assertEquals(
            listOf(BookshelfRoute, SettingsRoute, SourceManageRoute, SourceDetailRoute("b")),
            s,
        )
    }

    // ---------- 设置详情需要同组的 list pane ----------

    @Test
    fun `从探索进书源管理会补上设置列表`() {
        val s = stack(BookshelfRoute, ExploreRoute)
        s.navigateTo(SourceManageRoute)
        assertEquals(listOf(BookshelfRoute, ExploreRoute, SettingsRoute, SourceManageRoute), s)
    }

    @Test
    fun `栈里已有设置列表时不会再补一条`() {
        val s = stack(BookshelfRoute, SettingsRoute, AboutSettingsRoute)
        s.navigateTo(FeedbackRoute)
        assertEquals(1, s.count { it is SettingsRoute })
    }

    // ---------- 书架 detail ----------

    @Test
    fun `换另一本书的详情时替换而不叠栈`() {
        val s = stack(BookshelfRoute, BookDetailRoute("a"))
        s.navigateTo(BookDetailRoute("b"))
        assertEquals(listOf(BookshelfRoute, BookDetailRoute("b")), s)
    }

    // ---------- 其余页面：栈顶同类替换，防双击叠栈 ----------

    @Test
    fun `同类页在栈顶时替换`() {
        val s = stack(BookshelfRoute, SearchRoute("旧词"))
        s.navigateTo(SearchRoute("新词"))
        assertEquals(listOf(BookshelfRoute, SearchRoute("新词")), s)
    }

    @Test
    fun `完全相同的目的地直接忽略`() {
        val s = stack(BookshelfRoute, ExploreRoute)
        s.navigateTo(ExploreRoute)
        assertEquals(listOf(BookshelfRoute, ExploreRoute), s)
    }
}
