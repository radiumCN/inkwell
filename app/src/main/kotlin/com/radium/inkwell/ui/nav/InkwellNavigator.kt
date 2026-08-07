package com.radium.inkwell.ui.nav

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * Navigation 3 返回栈薄封装：栈顶同类替换（防双击叠栈）、设置树内同级替换/下钻叠栈，
 * 并把进书缩放原点与导航绑在一起（同生共死）。
 */
class InkwellNavigator(private val backStack: NavBackStack<NavKey>) {
    /** 进书放大动画原点；旋屏后由 InkwellNavDisplay 清回 Center。 */
    val openOrigin = mutableStateOf(TransformOrigin.Center)

    fun back() {
        backStack.removeLastOrNull()
    }

    /** 前进。规则全在 [navigateTo] 里 —— 那边能脱开 Compose 单测。 */
    fun go(key: NavKey) {
        backStack.navigateTo(key)
    }

    fun openBook(bookId: String, origin: TransformOrigin) {
        // 阅读页已在栈顶时整个忽略：入场动画那 200ms 里四周还露着书架，能点中第二本。
        // 放行的话会改写 origin 却不换书 —— 看着 A 进来、返回却缩向 B。
        if (backStack.lastOrNull() is ReaderRoute) return
        openOrigin.value = origin
        go(ReaderRoute(bookId))
    }
}

/**
 * 前进的全部规则。
 *
 * 写成 `MutableList<NavKey>` 扩展而不是 [InkwellNavigator] 的私有方法，是为了**能单测** ——
 * `NavBackStack` 本身就是个 `MutableList`，测试里传 `mutableListOf(...)` 即可，不必起 Compose。
 * 返回栈这套「弹几条、插哪条」的规则正是容易悄悄坏掉、坏了又只能靠手点才发现的那类逻辑。
 */
internal fun MutableList<NavKey>.navigateTo(key: NavKey) {
    val settingsDepth = key.settingsDetailDepth()
    when {
        key is BookDetailRoute -> {
            // 书架 detail pane 只有一格：换书就是换这一格的内容，不叠栈
            while (lastOrNull() is BookDetailRoute) removeLastOrNull()
            add(key)
        }

        settingsDepth != null -> {
            // 只弹**同级或更深**的：兄弟页互相替换（宽屏 detail 只一格），下钻则叠上去。
            //
            // 从前这里不分层级、一律把栈顶所有设置详情弹到底 —— 于是 关于 → 意见反馈
            // 会把「关于」一起弹掉，从意见反馈返回直接落回设置一级页；书源管理 → 书源详情、
            // RSS 文章列表 → 文章同样中间断一层。层级定义见 [settingsDetailDepth]。
            while (true) {
                val topDepth = lastOrNull()?.settingsDetailDepth() ?: break
                if (topDepth < settingsDepth) break
                removeLastOrNull()
            }
            // 宽屏 list-detail 要求同组里有个 list pane，否则右栏没有左栏可依附
            // （从「探索」直接进书源管理就是这种情况）
            if (none { it is SettingsRoute }) add(SettingsRoute)
            add(key)
        }

        else -> {
            val last = lastOrNull()
            if (last != null && last::class == key::class) {
                if (last == key) return
                removeLastOrNull()
            }
            add(key)
        }
    }
}
