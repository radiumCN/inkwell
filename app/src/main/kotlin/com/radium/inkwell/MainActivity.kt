package com.radium.inkwell

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.nav.InkwellNavDisplay
import com.radium.inkwell.ui.theme.AppThemes
import com.radium.inkwell.ui.theme.InkwellTheme
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
        if (intent.getBooleanExtra(ProfileSeed.EXTRA, false)) {
            lifecycleScope.launch {
                ProfileSeed.importIfRequested(this@MainActivity, true)
            }
        }
        setContent {
            val themeConfig by appPrefs.themeConfig.collectAsStateWithLifecycle(initialValue = ThemeConfig())
            val systemDark = isSystemInDarkTheme()
            // 状态栏/导航栏图标明暗、以及 Activity 窗体底色，都跟随「当前生效的 App 主题」。
            // 用户强制了日/夜或换了纯黑等预设时，只靠 values-night 的默认暖黑盖不住；
            // 转场缝隙若再落到 XML 里写死的白底，深色模式就会闪一条白边。
            // （阅读页另有沉浸逻辑自行隐藏系统栏，不受这里影响。）
            val (scheme, dark) = remember(themeConfig, systemDark) {
                AppThemes.resolve(themeConfig, systemDark)
            }
            val view = LocalView.current
            SideEffect {
                window.setBackgroundDrawable(ColorDrawable(scheme.background.toArgb()))
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
