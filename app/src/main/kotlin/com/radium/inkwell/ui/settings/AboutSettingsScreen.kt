package com.radium.inkwell.ui.settings

import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.SectionHeader
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.ui.components.NO_BROWSER_MESSAGE
import com.radium.inkwell.ui.components.openExternalLink
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.update.UpdateChecker
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import com.radium.inkwell.ui.components.AppScaffold
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.radium.inkwell.ui.components.AppToolbar
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.FlowBackground
import com.radium.inkwell.ui.components.ToolbarTitle
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.util.AppIconManager
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 网页版 / 官网，与 README 里的 book.skylark.run 同一处 */
private const val SITE_URL = "https://book.skylark.run"

/** 连点版本号几下打开开发者模式（同系统设置的 7 下）；从第 [DEV_TAPS_HINT_FROM] 下起提示还差几下 */
private const val DEV_TAPS = 7
private const val DEV_TAPS_HINT_FROM = 3
/** 两下之间超过这个间隔就从头数：隔了半天又点一下，不该接着上次的数 */
private const val DEV_TAP_WINDOW_MS = 1500L

/**
 * 关于页。顶上是 HyperOS「关于手机」那样的头部（[AboutHero]）：流动的彩色底、当前桌面图标、玻璃字的应用名、版本。
 *
 * 顶栏因此用窄栏 [AppToolbar] 而不是大标题栏：头部本身就是这一页的「标题」，再压一行 32sp 的「关于」是重复。
 * 停在顶部时栏是透明的（只糊不叠色，彩色底从栏下面透上来）、也不显示标题；头部往上滚到栏底下的过程中，
 * 栏渐渐叠上页面底色、标题淡出来 —— Miuix 示例关于页的做法。
 */
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    onOpenFeedback: () -> Unit,
    onOpenDisclaimer: () -> Unit,
    onOpenDeveloper: () -> Unit,
) {
    val context = LocalContext.current
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    val developerMode = appPrefs.developerModeState.collectAsStateWithLifecycle().value ?: false
    val messages = remember { MessageBus() }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(messages, snackbar)
    var versionTaps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    val currentVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    val pageColor = settingsPageColor()
    val scroll = rememberScrollState()
    var heroHeight by remember { mutableIntStateOf(0) }
    var barHeight by remember { mutableIntStateOf(0) }
    // 头部滚上去了多少：0 = 停在顶部，1 = 头部底边到了栏底边（整个头部都在栏下面了）
    val collapse by remember {
        derivedStateOf {
            val range = heroHeight - barHeight
            if (range <= 0) 0f else (scroll.value.toFloat() / range).coerceIn(0f, 1f)
        }
    }
    AppScaffold(
        containerColor = pageColor,
        topBar = {
            AppToolbar(
                modifier = Modifier.onSizeChanged { barHeight = it.height },
                navigationIcon = { BackButton(onClick = onBack) },
                containerColor = pageColor.copy(alpha = collapse),
            ) {
                // 标题等头部快滚完了才出来：头部里的应用名还看得见时，栏上再写一个「关于」是两个标题
                ToolbarTitle("关于", Modifier.graphicsLayer { alpha = ((collapse - 0.6f) / 0.4f).coerceIn(0f, 1f) })
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                // 栏的高度垫在头部**里面**（彩色底一直铺到状态栏），导航栏的高度垫在滚动内容末尾（见 AppScaffold）
                .padding(padding.horizontalOnly())
                .pageScroll()
                .verticalScroll(scroll, overscrollEffect = null)
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            AboutHero(
                version = currentVersion,
                topInset = padding.calculateTopPadding(),
                pageColor = pageColor,
                modifier = Modifier.onSizeChanged { heroHeight = it.height },
            )
            SectionHeader("支持")
            SettingGroup {
                SettingRow(
                    title = "意见反馈",
                    onClick = onOpenFeedback,
                    position = SettingGroupPosition.First,
                )
                SettingRow(
                    title = "用户协议与免责声明",
                    onClick = onOpenDisclaimer,
                    position = if (developerMode) SettingGroupPosition.Middle else SettingGroupPosition.Last,
                )
                if (developerMode) {
                    SettingRow(
                        title = "开发者选项",
                        onClick = onOpenDeveloper,
                        position = SettingGroupPosition.Last,
                    )
                }
            }
            SectionHeader("应用信息")
            SettingGroup {
                SettingRow(
                    title = "版本",
                    value = "v$currentVersion",
                    onClick = {
                        if (developerMode) {
                            messages.emit("已处于开发者模式")
                        } else {
                            val now = SystemClock.uptimeMillis()
                            versionTaps = if (now - lastTapAt > DEV_TAP_WINDOW_MS) 1 else versionTaps + 1
                            lastTapAt = now
                            val left = DEV_TAPS - versionTaps
                            when {
                                left <= 0 -> {
                                    versionTaps = 0
                                    scope.launch { appPrefs.setDeveloperMode(true) }
                                    messages.emit("已开启开发者模式")
                                }
                                versionTaps >= DEV_TAPS_HINT_FROM ->
                                    messages.emit("再点 $left 次进入开发者模式")
                            }
                        }
                    },
                    position = SettingGroupPosition.First,
                )
                SettingRow(
                    title = "开源许可",
                    value = "MIT License",
                    position = SettingGroupPosition.Middle,
                )
                SettingRow(
                    title = "官网（web）",
                    value = SITE_URL,
                    onClick = {
                        if (!context.openExternalLink(SITE_URL)) messages.emit(NO_BROWSER_MESSAGE)
                    },
                    position = SettingGroupPosition.Middle,
                )
                SettingRow(
                    title = "开源地址",
                    value = UpdateChecker.REPO_URL,
                    onClick = {
                        if (!context.openExternalLink(UpdateChecker.REPO_URL)) {
                            messages.emit(NO_BROWSER_MESSAGE)
                        }
                    },
                    position = SettingGroupPosition.Last,
                )
            }
            Spacer(Modifier.height(Dimens.gapXL))
        }
    }
}

/**
 * 关于页头部：HyperOS 流动彩色底（[FlowBackground]）上放当前桌面图标、应用名、版本，底边化进页面底色。
 *
 * 应用名是**玻璃字**（Miuix 文档「前景模糊」：textureBlur + DstIn，字形当遮罩，透出糊开、再混过色的背景），
 * 混色是 Miuix 示例关于页 Logo 的那组（浅色压暗、深色提亮，字从彩色底上浮出来）。它是 32sp 的粗体大字，
 * 只是品牌名 —— 读屏照样念得到（Text 的语义不受绘制方式影响）。
 *
 * 图标取 [AppIconManager.current]（桌面上摆着的那款），裁成平滑圆角。
 */
@Composable
private fun AboutHero(version: String, topInset: Dp, pageColor: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon = remember(context) { AppIconManager.current(context) }
    val dark = pageColor.luminance() < 0.5f
    // 只录彩色底（不含上面的图标和字）：字要糊的是背景本身
    val backdrop = rememberLayerBackdrop()
    val logoColors = BlurDefaults.blurColors(blendColors = if (dark) LogoBlendDark else LogoBlendLight)
    Box(modifier.fillMaxWidth()) {
        FlowBackground(
            baseColor = pageColor,
            modifier = Modifier.matchParentSize(),
            bgModifier = Modifier.layerBackdrop(backdrop),
        )
        // 下半截化进页面底色：下面的分组卡坐在页面底上，不是坐在彩色底上
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0.55f to pageColor.copy(alpha = 0f), 1f to pageColor)),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = topInset + Dimens.gapXXL, bottom = Dimens.gapXXL + Dimens.gapXL),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(icon.preview),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(Dimens.aboutHeroIcon)
                    .squircleClip(AppShapes.radiusExtraLarge),
            )
            Text(
                "Inkwell",
                style = MiuixTheme.textStyles.title1,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = Dimens.gapM)
                    .textureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = LOGO_BLUR_RADIUS,
                        colors = logoColors,
                        contentBlendMode = BlendMode.DstIn,
                    ),
            )
            Text(
                "v$version",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = Dimens.gapXS),
            )
        }
    }
}

/** dp。字形很细，糊得越狠字里透出来的越是一整片颜色、而不是背景的轮廓（示例用的就是上限 150） */
private const val LOGO_BLUR_RADIUS = 150f

// Miuix 示例关于页 Logo 的混色（example/shared/src/commonMain/kotlin/AboutPage.kt，Apache-2.0）
private val LogoBlendLight = listOf(
    BlendColorEntry(Color(0xCC4A4A4A), BlurBlendMode.ColorBurn),
    BlendColorEntry(Color(0xFF4F4F4F), BlurBlendMode.LinearLight),
    BlendColorEntry(Color(0xFF1AF200), BlurBlendMode.Lab),
)
private val LogoBlendDark = listOf(
    BlendColorEntry(Color(0xE6A1A1A1), BlurBlendMode.ColorDodge),
    BlendColorEntry(Color(0x4DE6E6E6), BlurBlendMode.LinearLight),
    BlendColorEntry(Color(0xFF1AF500), BlurBlendMode.Lab),
)
