package com.radium.inkwell.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 落在浮层空白处的触摸到此为止，不漏给盖在下面的内容。
 *
 * Compose 的命中测试从最上层往下找「接触摸的东西」：浮层本身（只是一层布局 + 玻璃）不接，触摸就落到它下面
 * 被盖住的那一行上。M3 的 `Surface` 自带这一层（一个空的 pointerInput），Miuix 的没有 —— 换过来之后：
 * 书源管理顶栏里「正在校验 497 / 886」那行字上一点，点到的是被玻璃糊住的那一行书源的开关；
 * 阅读菜单顶栏的书名上一点会关掉菜单，进度条那行字上一滑会翻页。
 *
 * 空的 pointerInput 不消费任何事件：浮层里的按钮、chip、滑块、输入框照常收到；包着它的父级（可点的卡片）也照常收到。
 * 只是命中测试停在这里，不再往下找兄弟节点。**凡是叠在可点内容上面的面**（顶栏、底部悬浮胶囊、提示条）都挂上它。
 */
fun Modifier.blockTouchesBelow(): Modifier = this.pointerInput(Unit) {}
