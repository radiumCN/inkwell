package com.radium.inkwell.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.launch

/**
 * 按下去整块微微「沉下去」的反馈 —— HyperOS 里大块可点元素（书封、卡片、图标）的标志性手感。
 *
 * 用法是当 `indication` 传给 `clickable` / `combinedClickable`，替掉默认的水波纹：
 * ```
 * Modifier.combinedClickable(
 *     interactionSource = interactionSource,
 *     indication = rememberSinkIndication(),
 *     ...
 * )
 * ```
 *
 * **只能用在我们自己持有 clickable 的地方。** M3 组件（`Button`、`ListItem` 的交互重载、
 * `FilterChip`…）把 `ripple()` 写死在内部，既不收 `indication` 参数也不读 `LocalIndication`，
 * 没有公开口子能把这套塞进去 —— 想让它们也沉下去，只能整套重写组件，那就是另一个设计系统了。
 * 所以这里的定位是「大块媒体元素专用」，不是全局替换水波纹：把 M3 的水波纹关掉而又塞不进下沉，
 * 结果是那些组件按下去**毫无反馈**，比不一致更糟。
 *
 * 参数取自 Apache-2.0 的 [Miuix](https://github.com/compose-miuix-ui/miuix)
 * （`miuix-ui` 的 `PressFeedback.kt` 里的 `SinkFeedback`），照数值重写而非引依赖，
 * 原因见 [com.radium.inkwell.ui.theme.SquircleShape] 的说明。
 *
 * 走 [LayoutModifierNode] 在放置阶段改 `scaleX/scaleY`，不是 `graphicsLayer` + `State` ——
 * 后者每帧都要重组一次调用点，而一次按压能连着跑几十帧。
 */
@Stable
data class SinkIndication(
    private val sinkAmount: Float = SINK_AMOUNT,
    private val animationSpec: AnimationSpec<Float>,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        SinkNode(interactionSource, sinkAmount, animationSpec)

    private class SinkNode(
        private val interactionSource: InteractionSource,
        private val sinkAmount: Float,
        private val animationSpec: AnimationSpec<Float>,
    ) : Modifier.Node(),
        LayoutModifierNode {

        private val scale = Animatable(1f)

        override fun onAttach() {
            coroutineScope.launch {
                interactionSource.interactions.collect { interaction ->
                    val pressed = when (interaction) {
                        is PressInteraction.Press -> true
                        is PressInteraction.Release, is PressInteraction.Cancel -> false
                        // 悬停 / 聚焦不参与：下沉是「手指压住」的隐喻，鼠标划过去不该塌一下
                        else -> return@collect
                    }
                    launch { scale.animateTo(if (pressed) sinkAmount else 1f, animationSpec) }
                }
            }
        }

        override fun MeasureScope.measure(
            measurable: Measurable,
            constraints: Constraints,
        ): MeasureResult {
            val placeable = measurable.measure(constraints)
            return layout(placeable.width, placeable.height) {
                placeable.placeWithLayer(0, 0) {
                    scaleX = scale.value
                    scaleY = scale.value
                }
            }
        }
    }

    companion object {
        /**
         * 按下时缩到 94%。
         *
         * 别再往下调：书封那么大一块，缩到 0.9 以下时相邻两本之间会看出明显的缝隙变化，
         * 从「这一本被按住了」变成「整个网格抖了一下」。
         */
        const val SINK_AMOUNT = 0.94f

        /**
         * 阻尼比 0.8 —— **欠阻尼**，收尾会有一次极轻的回弹。
         *
         * 这一下回弹就是「有实体」的来源：完全不回弹（ζ=1）的落定像贴图切换，
         * 而真实的按键抬起来时总会过冲一点点。配合 600 的劲度，回弹幅度小到说不出来，
         * 但拿掉之后能感觉到发木。
         */
        const val SINK_DAMPING = 0.8f
        const val SINK_STIFFNESS = 600f
    }
}

/**
 * 按当前系统动画开关取的下沉反馈。
 *
 * 系统开「移除动画」时用 [snap]：仍然沉下去（反馈不能丢，那是可用性），只是不插值。
 */
@Composable
fun rememberSinkIndication(): SinkIndication {
    val animate = animationsEnabled()
    return remember(animate) {
        SinkIndication(
            animationSpec = if (animate) {
                spring(
                    dampingRatio = SinkIndication.SINK_DAMPING,
                    stiffness = SinkIndication.SINK_STIFFNESS,
                )
            } else {
                snap()
            },
        )
    }
}
