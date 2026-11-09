package com.radium.inkwell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「有新东西」的红点（HyperOS 的未读红点）：书架顶栏的设置齿轮、设置页「检查更新」那一行。
 * 只表示「有」，不写数字。读屏念不到一个点 —— 调用方把「有新版本」写进可点元素的名字或行上的值里。
 *
 * @param ring 点外面描的一圈底色。叠在线条图标的角上时给（齿轮的齿会碰到点，描一圈图标底下的颜色把两者隔开）；
 *   单独摆在行尾的不用
 */
@Composable
fun BadgeDot(modifier: Modifier = Modifier, ring: Color? = null) {
    val dot = MiuixTheme.colorScheme.error
    if (ring == null) {
        Box(modifier.size(Dimens.badgeDot).background(dot, CircleShape))
    } else {
        Box(
            modifier
                .size(Dimens.badgeDot + BADGE_RING * 2)
                .background(ring, CircleShape)
                .padding(BADGE_RING)
                .background(dot, CircleShape),
        )
    }
}

/** 描边的粗细：是一道线不是一块面，不落 4dp 栅格 */
private val BADGE_RING = 1.5.dp
