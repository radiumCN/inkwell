package com.radium.inkwell.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
/**
 * 封面转开的角度。必须**过 90°**：到 90° 时封面正好侧对视线、薄成一条线，再多转一点才彻底让开，
 * 不会在书页上留一道边。
 */
private const val COVER_OPEN_DEGREES = 105f

/**
 * 透视强度（会再乘 density）。Compose 默认 8 透视过强，绕左边缘转时封面右侧会夸张地拉伸变形，
 * 看着像一张软塑料片；拉远到 14 收敛成"一本书的硬封面在转开"。
 */
private const val COVER_CAMERA_DISTANCE = 14f

/**
 * @param isCover true = 书架上那张封面（会绕书脊转开），false = 阅读页（被翻开后露出的书页）
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.bookOpenContainer(bookId: String, isCover: Boolean = false): Modifier {
    // animationsEnabled() 会注册 ContentObserver，先无条件调用再判断，别让它落在提前 return 后面
    val enabled = animationsEnabled()
    val shared = LocalSharedTransitionScope.current
    val animated = LocalNavAnimatedScope.current
    if (!enabled || shared == null || animated == null) return this

    // 转开进度 0→1。书架离场时 Visible→PostExit（封面转开）；返回时 PreEnter→Visible，
    // 同一个值反过来跑，封面自己就转回去合上了 —— 不必为"关书"再写一套。
    val opened by animated.transition.animateFloat(
        transitionSpec = { tween(Motion.BOOK_OPEN_MS, easing = Motion.ReaderEnterEasing) },
        label = "bookOpen",
    ) { state -> if (state == EnterExitState.Visible) 0f else 1f }

    return with(shared) {
        this@bookOpenContainer
            .sharedBounds(
                rememberSharedContentState(key = bookOpenKey(bookId)),
                animatedVisibilityScope = animated,
                // 两端的淡入淡出节奏**故意不同**：
                //   书页要尽快变成实心（头 1/3 就不透明），否则整段飞行半透明、书架一直透上来，
                //     看着像一团虚影盖在书架上（0.1.6-beta.4 就栽在这，把淡入摊到了全程）。
                //   封面则要**全程看得见**，因为它得让你看见它在转 —— 它的消失靠转过 90° 自然侧隐，
                //     而不是靠淡出。若沿用书页那套快淡出，封面会在还没转起来时就没了，等于没有"翻开"。
                enter = fadeIn(tween(if (isCover) Motion.BOOK_OPEN_MS else Motion.BOOK_OPEN_MS / 3)),
                exit = fadeOut(tween(if (isCover) Motion.BOOK_OPEN_MS else Motion.BOOK_OPEN_MS / 3)),
                boundsTransform = { _, _ -> Motion.bookOpenSpec() },
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                ),
                // 封面压在书页之上 —— 物理上它就是"盖着"书页的那一面，转开才露出底下的页。
                // 两者都要盖在书架之上，否则会被网格里其它书压住。
                zIndexInOverlay = if (isCover) 2f else 1f,
            )
            .then(
                if (!isCover) Modifier else Modifier.graphicsLayer {
                    // 转轴钉在**左边缘** = 书脊。默认转轴在中心，那是"卡片翻面"不是"开书"。
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    rotationY = -COVER_OPEN_DEGREES * opened
                    cameraDistance = COVER_CAMERA_DISTANCE * density
                }
            )
    }
}
