package com.radium.inkwell.ui.nav

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * Nav3 返回栈上的薄封装：等价于旧 Nav2 的 `launchSingleTop` / `popBackStack`，
 * 并把进书缩放原点与导航绑在一起（同生共死）。
 */
class InkwellNavigator(private val backStack: NavBackStack<NavKey>) {
    /** 进书放大动画原点；旋屏后由 NavHost 清回 Center。 */
    val openOrigin = mutableStateOf(TransformOrigin.Center)

    fun back() {
        backStack.removeLastOrNull()
    }

    /**
     * 前进。同类目的地在栈顶时替换（防双击叠栈）；书籍详情 / 设置详情会先清掉同组旧 detail，
     * 宽屏 list-detail 下左栏（list）得以保留。
     */
    fun go(key: NavKey) {
        when {
            key is BookDetailRoute -> {
                while (backStack.lastOrNull() is BookDetailRoute) backStack.removeLastOrNull()
                backStack.add(key)
            }
            key.isSettingsDetail() -> {
                while (backStack.lastOrNull()?.isSettingsDetail() == true) {
                    backStack.removeLastOrNull()
                }
                if (backStack.none { it is SettingsRoute }) {
                    backStack.add(SettingsRoute)
                }
                backStack.add(key)
            }
            else -> {
                val last = backStack.lastOrNull()
                if (last != null && last::class == key::class) {
                    if (last == key) return
                    backStack.removeLastOrNull()
                }
                backStack.add(key)
            }
        }
    }

    fun openBook(bookId: String, origin: TransformOrigin) {
        // 阅读页已在栈顶时整个忽略：入场动画那 200ms 里四周还露着书架，能点中第二本。
        // 放行的话会改写 origin 却不换书 —— 看着 A 进来、返回却缩向 B。
        if (backStack.lastOrNull() is ReaderRoute) return
        openOrigin.value = origin
        go(ReaderRoute(bookId))
    }
}
