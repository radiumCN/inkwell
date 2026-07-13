package com.radium.inkwell

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.radium.inkwell.ui.nav.InkwellNavHost
import com.radium.inkwell.ui.theme.InkwellTheme
import com.radium.inkwell.util.KeyEventBus
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val keyEventBus: KeyEventBus by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            InkwellTheme {
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
