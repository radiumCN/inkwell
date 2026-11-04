package com.radium.inkwell

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.nav.InkwellNavDisplay
import com.radium.inkwell.ui.theme.InkwellTheme
import com.radium.inkwell.ui.theme.rememberResolvedScheme
import com.radium.inkwell.ui.theme.ThemeConfig
import com.radium.inkwell.util.KeyEventBus
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 继承 FragmentActivity 而不是 ComponentActivity：BiometricPrompt 只认 FragmentActivity。
 * 除此之外与 ComponentActivity 没有区别（我们不用 Fragment）。
 */
class MainActivity : androidx.fragment.app.FragmentActivity() {

    private val keyEventBus: KeyEventBus by inject()
    private val appPrefs: AppPrefs by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 夹具开关先判：正式包里这个 extra 谁都能塞进来（桌面 alias 是 exported 的），见 ProfileSeed
        if (ProfileSeed.enabled(this) && intent.getBooleanExtra(ProfileSeed.EXTRA, false)) {
            lifecycleScope.launch {
                ProfileSeed.importIfRequested(this@MainActivity, true)
            }
        }
        holdFirstFrameUntilThemed()
        setContent {
            val loadedTheme by appPrefs.themeState.collectAsStateWithLifecycle()
            val themeConfig = loadedTheme ?: ThemeConfig()
            // 组合真用上了读到的主题才放首帧：值到了不等于这一帧已经按它重组过
            SideEffect { if (loadedTheme != null) themed = true }
            val systemDark = isSystemInDarkTheme()
            // 状态栏/导航栏图标明暗、以及 Activity 窗体底色，都跟随「当前生效的 App 主题」。
            // 用户强制了日/夜或换了纯黑等预设时，只靠 values-night 的默认暖黑盖不住；
            // 转场缝隙若再落到 XML 里写死的白底，深色模式就会闪一条白边。
            // （阅读页另有沉浸逻辑自行隐藏系统栏，不受这里影响。）
            val (scheme, dark) = rememberResolvedScheme(themeConfig, systemDark)
            val view = LocalView.current
            SideEffect {
                // 页面底是 surface（Miuix 的分层：background 是弹窗/面板的底），与 InkwellTheme 根上铺的同一色
                window.setBackgroundDrawable(ColorDrawable(scheme.surface.toArgb()))
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            InkwellTheme(config = themeConfig) {
                InkwellNavDisplay()
            }
        }
    }

    /** 首帧是否已按读到的主题组合过；见 [holdFirstFrameUntilThemed] */
    @Volatile
    private var themed = false

    /**
     * 主题读到之前不放首帧，系统 splash 跟着多留这一会儿（Android 12+ 的做法：content 的 OnPreDrawListener）。
     *
     * 从前首帧按默认主题画：强制夜间、纯黑、自定义强调色的用户每次冷启动都先闪一下暖纸配色，
     * 窗口底色也一起错，读到配置后整树换色重组一遍。DataStore 那一读在 InkwellApp 里就开始了，
     * 通常赶得上；赶不上也最多等 [THEME_WAIT_MS] 就按默认画 —— 闪一下总比卡着不出强。
     */
    private fun holdFirstFrameUntilThemed() {
        if (appPrefs.themeState.value != null && themed) return
        val content = findViewById<View>(android.R.id.content)
        val start = SystemClock.uptimeMillis()
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val go = themed || SystemClock.uptimeMillis() - start > THEME_WAIT_MS
                if (go) content.viewTreeObserver.removeOnPreDrawListener(this)
                return go
            }
        })
    }

    private companion object {
        const val THEME_WAIT_MS = 300L
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // 长按重复事件节流：只响应首次按下
            if (event?.repeatCount == 0 &&
                keyEventBus.onVolumeKey(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            ) return true
            if (keyEventBus.volumeFlipEnabled) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            keyEventBus.volumeFlipEnabled
        ) return true
        return super.onKeyUp(keyCode, event)
    }
}
