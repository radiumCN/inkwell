package com.radium.inkwell

import android.app.Application
import com.radium.inkwell.data.prefs.WebDavPrefs
import com.radium.inkwell.data.repo.WebDavRepository
import com.radium.inkwell.di.appModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class InkwellApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidContext(this@InkwellApp)
            modules(appModule)
        }.koin

        // 冷启动静默同步（已配置 WebDAV 时）；失败不打扰用户
        appScope.launch {
            runCatching {
                val prefs = koin.get<WebDavPrefs>()
                if (prefs.config.first().isConfigured) {
                    koin.get<WebDavRepository>().sync()
                }
            }
        }
    }
}
