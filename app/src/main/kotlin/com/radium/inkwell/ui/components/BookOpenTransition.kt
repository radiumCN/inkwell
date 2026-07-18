package com.radium.inkwell.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * 「开书」容器变换（Material container transform）的管道。
 *
 * 效果：点书架上的某本书，**那本书的封面就地放大、morph 成整页阅读器**；返回时收回原来的封面格子。
 *
 * 为什么用 CompositionLocal 而不是给各屏加参数：共享元素要同时拿到 [SharedTransitionScope]（整个
 * NavHost 一份）和各自的 [AnimatedVisibilityScope]（每个路由一份）。若走参数，`BookshelfScreen`、
 * `BookCard`、`ReaderScreen` 的签名都要为一个纯视觉效果改一遍，还会一路往下传。用 Local 下发，
 * 调用点只多一个 Modifier，整套效果想撤就撤，不在业务签名上留疤。
 *
 * 两端都 **可能为 null**：只有书架和阅读页这两个路由提供了作用域（[LocalNavAnimatedScope]），
 * 别的入口（详情页、搜索预览进书）没有可放大的源封面，此时 [bookOpenContainer] 原样返回，
 * 转场自动退回 NavHost 里的「小幅上抬」兜底。
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** 当前路由的入退场作用域；只有参与开书变换的路由（书架 / 阅读页）提供 */
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** 同一本书的两端必须用同一个 key 才能配上对 */
private fun bookOpenKey(bookId: String) = "book-open-$bookId"

/**
 * 给「书封」和「阅读页根布局」两端打上同一个共享边界，两者之间就会做容器变换。
 *
 * 关键取舍 —— **必须用 [SharedTransitionScope.ResizeMode.ScaleToBounds] 而不是 RemeasureToBounds**：
 * 后者会在动画的每一帧用当时的尺寸重新测量子内容，对阅读页来说等于**每帧重跑一次分页**，
 * 进书本就是全 App 最吃紧的一段（见 ReaderViewModel.PREFETCH_LEAD_IN_MS），那样必卡。
 * ScaleToBounds 只按**最终尺寸**测一次、动画期间缩放绘制结果：分页照常在正确宽度上算一遍，
 * 动画只是把画好的页面从封面大小放大到全屏，纯 GPU 变换，不碰排版。
 *
 * 关了系统动画就原样返回，不进共享元素机器。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.bookOpenContainer(bookId: String): Modifier {
    // animationsEnabled() 会注册 ContentObserver，先无条件调用再判断，别让它落在提前 return 后面
    val enabled = animationsEnabled()
    val shared = LocalSharedTransitionScope.current
    val animated = LocalNavAnimatedScope.current
    if (!enabled || shared == null || animated == null) return this
    return with(shared) {
        this@bookOpenContainer.sharedBounds(
            rememberSharedContentState(key = bookOpenKey(bookId)),
            animatedVisibilityScope = animated,
            // 容器内部两端内容做短交叉淡入：封面淡出、正文淡入。这里的 alpha 只发生在
            // **正在放大的容器内部**（外面还是书架），不是整屏半透明，所以不会出现
            // 「正文里透出书封」那种跨主题穿帮 —— 封面化成书页本来就是这个效果要的。
            enter = fadeIn(Motion.readerEnterSpec()),
            exit = fadeOut(Motion.readerEnterSpec()),
            boundsTransform = { _, _ -> Motion.readerEnterSpec() },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
            ),
            // 变换中的容器要盖在书架之上，否则会被网格里其它书压住
            zIndexInOverlay = 1f,
        )
    }
}
