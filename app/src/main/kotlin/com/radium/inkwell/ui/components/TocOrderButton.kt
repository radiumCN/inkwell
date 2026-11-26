package com.radium.inkwell.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 倒序时图标用强调色，读屏念出当前顺序。 */
@Composable
internal fun TocOrderButton(reversed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    AppIconButton(
        onClick = onToggle,
        modifier = modifier.semantics { stateDescription = if (reversed) "倒序" else "正序" },
        contentColor = if (reversed) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
    ) {
        Icon(MiuixIcons.Sort, contentDescription = "切换目录顺序")
    }
}
