package com.radium.inkwell.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.TiltFeedback
import top.yukonga.miuix.kmp.utils.pressable

/**
 * HyperOS 的按压反馈（Miuix `Modifier.pressable`）：按下时这块面整体轻微缩小（[sinkOnPress]），
 * 或按触点倾斜、按住的那个角往下沉（[tiltOnPress]）。
 *
 * 接法照 Miuix 自己的 `Card(pressFeedbackType = …)`：反馈挂在**外层**（整块面一起动，角标、描边也跟着），
 * 点击挂在里面，两边**共用同一个** [interactionSource] —— 反馈只听这个源里的 Press / Release，
 * 点击、长按、读屏语义照旧由里面的 clickable / selectable 处理（pressable 不吃事件，也不加读屏动作）。
 * 里面那层的 indication 照旧给 `LocalIndication.current`，压暗和下沉一起出现。
 * delay = null：按下立刻沉，不等「是不是要滚动」那一小段，与 Miuix 卡片一致。
 *
 * 只给「一整块可点的面」用：书架上的书、继续阅读卡、色板。贴在卡片里的设置行、列表行按 HyperOS 是按下变灰、
 * 不下沉；Miuix 按钮自带按压效果，也别再叠一层。
 */
fun Modifier.sinkOnPress(interactionSource: MutableInteractionSource): Modifier =
    pressable(interactionSource = interactionSource, indication = SinkFeedback(), delay = null)

/** 大块的卡（继续阅读）用倾斜：整块缩小在大面上显得「瘪了一下」，倾斜才像按在一张实物卡片的某个角上 */
fun Modifier.tiltOnPress(interactionSource: MutableInteractionSource): Modifier =
    pressable(interactionSource = interactionSource, indication = TiltFeedback(), delay = null)
