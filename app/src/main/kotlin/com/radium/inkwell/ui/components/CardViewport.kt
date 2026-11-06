package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.squircle.squircleClip

/**
 * 面板里定高的滚动列表（阅读页的目录、换源候选、发现页的书源面板）：把滚动的视口裁成卡片的圆上角。
 *
 * [groupedCardItem] 只给第一行圆上角。列表在面板里定高、上面压着一行字（概况行、搜索框）时，一滚，视口顶上切过的
 * 是中间的行，卡顶就成了一刀直线 —— 整张卡变成一块直边直角的板（目录面板上被指出「和应用的风格不搭」）。
 * 这里把视口本身裁成卡的上角（同一个 [AppShapes.radiusLarge]、同一条 squircle）：看上去是卡钉在原地、章节在卡里面滚；
 * 没滚的时候首行自己的圆角和视口的角重合，看不出是两层。
 *
 * **只裁上角**：这几处的列表都一直铺到手势条下面（末尾垫 [sheetBottomInset]），底边就是屏幕边，滚到底才露出卡的圆下角。
 *
 * 视口自己离面板两边 [cardMargin]，里面的 [CardLayer] 把外边距换成 0：行、分组小标题、卡下那行字照旧按 [cardMargin] 排，
 * 落在屏幕上的位置不变，只是从视口的边算起。叠在列表上的东西（快速滚动条、「定位当前章」胶囊）放在视口**外面**、
 * 和它同在一个 Box 里 —— 滚动条贴着面板边，放进来会被裁掉。
 *
 * 和上面那行字之间的缝由调用方留在视口**外面**，别放进列表的 `contentPadding.top`：放进去的那一截会跟着内容滚走，
 * 一滚，行就顶到字底下（目录的概况行从前就是这样）。
 *
 * 页面上的列表用不着它：那些列表滚到毛玻璃顶栏底下，切口在顶栏后面、被糊掉了。
 *
 * 代价是一个离屏层（squircle 裁切是着色器遮罩），整个视口一个，和卡里首尾两行各自那一层是同一种开销。
 */
@Composable
fun CardViewport(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val radius = AppShapes.radiusLarge
    val color = settingsCardColor()
    // 记住它：LocalCardLayer 是 static 的，每次换一个新实例，里面整棵列表都要跟着重组
    val inner = remember(color) { CardLayer(color = color, margin = 0.dp) }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = cardMargin())
            .squircleClip(topStart = radius, topEnd = radius, bottomEnd = 0.dp, bottomStart = 0.dp),
    ) {
        CompositionLocalProvider(LocalCardLayer provides inner, content = content)
    }
}
