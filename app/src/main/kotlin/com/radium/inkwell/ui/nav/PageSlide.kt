package com.radium.inkwell.ui.nav

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.radium.inkwell.ui.components.Motion
import com.radium.inkwell.ui.components.animationsEnabled

/**
 * 整页横滑（共享轴 X）：新页整屏从右滑入、旧页左让 1/4，返回镜像；侧滑返回同几何但线性。
 *
 * **为什么不用 `slideInHorizontally` / `slideOutHorizontally`**：转场被打断、页面掉头时
 * （快速进了又退、退了又进、前进没收尾就侧滑），Compose 不用我们给的 spec ——
 * 内置 slide 对掉头那段（PostExit→Visible）写死用 `DefaultOffsetAnimationSpec`，
 * `Transition` 本身也会把被打断的非 spring 动画换成默认 spring。实测：掉头的页起步慢、
 * 最后十几像素一像素一像素地爬 150ms 以上，侧滑时被盖页还不跟手 —— 就是「快速进退页面会卡一下」。
 *
 * 所以位移在这里自己做，挂在 AnimatedContent 的子 transition 上（退场页照样等它跑完才移出组合，
 * 侧滑时 NavDisplay seek 的也是同一个 transition，照样跟手），并且：
 * - 前进 / 返回用**不回弹的 spring**（见 [Motion.PAGE_ENTER_STIFFNESS]）—— spring 是 Transition
 *   被打断时唯一会被保留的 spec，掉头时带着当前速度平滑转向，和正常进退是同一条曲线；
 * - 侧滑用线性 tween：seek 期间 spec 照用，不受上面那条规则影响；
 * - 每页记住自己是从哪边进来、往哪边退的，掉头时从实际位置出发，不会因为这次转场换了方向而跳到另一侧。
 *
 * NavDisplay 那边的 ContentTransform 一律给 [PageSlideTransform]（两头都是 None），
 * 只负责让 AnimatedContent 切场景，位移全在这里。
 */
internal enum class PageSlideMode {
    /** 前进：新页从右整屏滑入，旧页左让 1/4 */
    Push,

    /** 返回（箭头 / 返回键）：当前页右滑出，底层页从左 1/4 回位 */
    Pop,

    /** 侧滑返回：几何同 [Pop]，线性，按手势进度 seek */
    Predictive,

    /** 阅读器开合：走它自己的缩放转场，这里不动 */
    None,
}

/**
 * 当前这次转场是哪一种。由 NavDisplay 的各个 transitionSpec 在取转场时写入 —— 它们最清楚
 * 这次是前进、返回、侧滑还是开合阅读器；页面在自己的分段换目标时读一次（见 [rememberPageSlideDecorator]）。
 *
 * 普通字段而不是 State：只在转场开始的那一帧被读，不需要触发重组；写成 State 反而是在组合期间写快照。
 */
internal class PageSlideState {
    var mode: PageSlideMode = PageSlideMode.Push
}

/** 交给 NavDisplay 的转场：不带任何内置效果，位移由 [rememberPageSlideDecorator] 负责 */
internal val PageSlideTransform: ContentTransform = EnterTransition.None togetherWith ExitTransition.None

/** 一页的去留方式：从哪种转场进来（定 PreEnter 的位置）、最近一次往哪种转场退（定 PostExit 的位置） */
private class PageSlideMemory(var enterMode: PageSlideMode, var exitMode: PageSlideMode)

/**
 * 每个页面包一层，按转场方式做横向位移。位移量按窗口宽度算而不是页面自身宽度：
 * 宽屏双栏时同一场景里的几个 pane 一起整体滑，和以前对整个场景做 slide 观感一致。
 */
@Composable
internal fun rememberPageSlideDecorator(state: PageSlideState): NavEntryDecorator<NavKey> {
    val animate = animationsEnabled()
    return remember(state, animate) {
        NavEntryDecorator { entry ->
            val transition = LocalNavAnimatedContentScope.current.transition
            val width = LocalWindowInfo.current.containerSize.width.toFloat().coerceAtLeast(1f)
            val memory = remember { PageSlideMemory(state.mode, state.mode) }
            // 分段一换目标就重新取一次：决定这一段用哪条曲线。往 PostExit 去时顺便记下退场方向，
            // 之后掉头回来、或被 seek 回放时，PostExit 仍是它真正离开的那一侧
            val target = transition.targetState
            val mode = remember(target) {
                state.mode.also { if (target == EnterExitState.PostExit) memory.exitMode = it }
            }
            // 只在 graphicsLayer 里读值：每帧变化只走绘制，不让整页跟着每帧重组
            val offset = transition.animateFloat(
                transitionSpec = { pageSlideSpec(mode, targetState, animate, width) },
                label = "PageSlide",
            ) {
                when (it) {
                    EnterExitState.PreEnter -> preEnterOffset(memory.enterMode)
                    EnterExitState.Visible -> 0f
                    EnterExitState.PostExit -> postExitOffset(memory.exitMode)
                }
            }
            Box(
                Modifier.graphicsLayer { translationX = offset.value * width },
                propagateMinConstraints = true,
            ) {
                entry.Content()
            }
        }
    }
}

/** 进场起点，单位是窗口宽度 */
private fun preEnterOffset(mode: PageSlideMode): Float = when (mode) {
    PageSlideMode.Push -> 1f
    PageSlideMode.Pop, PageSlideMode.Predictive -> -0.25f
    PageSlideMode.None -> 0f
}

/** 退场终点，单位是窗口宽度 */
private fun postExitOffset(mode: PageSlideMode): Float = when (mode) {
    PageSlideMode.Push -> -0.25f
    PageSlideMode.Pop, PageSlideMode.Predictive -> 1f
    PageSlideMode.None -> 0f
}

/**
 * 去向 [target] 的那段用什么 spec。只看去向不看来处 —— 掉头那段（PostExit→Visible）
 * 和正常进场同一条。系统「移除动画」时归零（这里不读 motionScheme，主题那层管不到）。
 *
 * spring 的 visibilityThreshold 按半个像素折算：值的单位是窗口宽度，Float 默认阈值 0.01
 * 相当于 12px，停得太早，最后会一下跳到位。
 */
private fun pageSlideSpec(
    mode: PageSlideMode,
    target: EnterExitState,
    animate: Boolean,
    width: Float,
): FiniteAnimationSpec<Float> = when {
    !animate || mode == PageSlideMode.None -> tween(0)
    mode == PageSlideMode.Predictive -> tween(Motion.PREDICTIVE_BACK_MS, easing = LinearEasing)
    else -> spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = if (target == EnterExitState.Visible) {
            Motion.PAGE_ENTER_STIFFNESS
        } else {
            Motion.PAGE_EXIT_STIFFNESS
        },
        visibilityThreshold = 0.5f / width,
    )
}
