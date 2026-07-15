package com.radium.inkwell

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.nav.InkwellNavHost
import com.radium.inkwell.ui.theme.InkwellTheme
import com.radium.inkwell.ui.theme.ThemeConfig
import com.radium.inkwell.ui.theme.ThemeMode
import com.radium.inkwell.util.KeyEventBus
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
        setContent {
            val themeConfig by appPrefs.themeConfig.collectAsState(initial = ThemeConfig())
            // 状态栏/导航栏图标明暗跟随「当前生效的 App 主题」，而不是系统深浅 ——
            // 用户强制了日/夜主题时，enableEdgeToEdge 默认按系统判明暗会和背景撞。
            // （阅读页另有沉浸逻辑自行隐藏系统栏，不受这里影响。）
            val dark = when (themeConfig.mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            InkwellTheme(config = themeConfig) {
                InkwellNavHost()
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
