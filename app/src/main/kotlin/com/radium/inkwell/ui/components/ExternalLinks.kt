package com.radium.inkwell.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 用外部应用（通常是浏览器）打开链接；返回有没有真的拉起来。拉不起来时由调用方告诉用户，
 * 文案统一用 [NO_BROWSER_MESSAGE]。
 *
 * 不能裸调 `startActivity(ACTION_VIEW)`：设备上没有能处理链接的应用（没装浏览器、被停用、
 * 被家长控制藏起来）时它抛 [ActivityNotFoundException]，整个 App 当场崩 —— 而点的只是
 * 「开源地址」「查看进展」这种无关紧要的链接。个别 ROM 上目标 Activity 不让外部调起，
 * 抛的是 [SecurityException]，同样接住。
 *
 * **用户点链接别直接调它**，走 [rememberExternalLinkOpener]（先弹窗问一句、把链接亮出来）。只有已经在确认弹窗里的那一步
 * 才直接调（更新弹窗的「查看 Release」「下载 APK」：那个弹窗本身就是确认，按钮上写着去哪）。
 */
fun Context.openExternalLink(url: String): Boolean {
    if (url.isBlank()) return false
    return try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** [openExternalLink] 拉不起应用时给用户的那句话 */
const val NO_BROWSER_MESSAGE = "没有可以打开链接的应用，请先安装或启用浏览器"

/** [rememberExternalLinkOpener] 给的打开器：[open] 先弹确认，用户点了「打开」才跳出去 */
@Stable
class ExternalLinkOpener internal constructor(private val request: (String) -> Unit) {
    fun open(url: String) {
        if (url.isNotBlank()) request(url)
    }
}

/**
 * 点链接跳出 App 前先问一句：「打开外部链接」，正文把**完整的链接**亮出来，确认了才交给浏览器。
 *
 * 为什么要问：点一行「官网」「开源地址」就直接被甩进浏览器，人还没看清要去哪儿就离开了 App；
 * 订阅文章、反馈进展这类链接的地址来自外面（订阅源、服务端），先让人看一眼域名，也免得被带去一个不认识的站点。
 *
 * 弹窗由这里自己挂（调用方不用管显示状态），转屏后还在。拉不起浏览器时回调 [onNoBrowser]，
 * 调用方照旧发 [NO_BROWSER_MESSAGE]。
 */
@Composable
fun rememberExternalLinkOpener(onNoBrowser: () -> Unit): ExternalLinkOpener {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val latestOnNoBrowser by rememberUpdatedState(onNoBrowser)
    pending?.let { url ->
        AppAlertDialog(
            onDismissRequest = { pending = null },
            title = "打开外部链接",
            text = "将离开 Inkwell，用浏览器打开",
            confirmText = "打开",
            onConfirm = {
                pending = null
                if (!context.openExternalLink(url)) latestOnNoBrowser()
            },
        ) {
            // 链接原样亮出来、居中：人要核对的是域名。太长的（订阅文章带一串参数）截在三行，域名总在最前面
            Text(
                url,
                modifier = Modifier.fillMaxWidth(),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = LINK_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    return remember { ExternalLinkOpener { pending = it } }
}

private const val LINK_MAX_LINES = 3
