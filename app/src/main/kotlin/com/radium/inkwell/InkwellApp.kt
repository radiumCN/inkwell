package com.radium.inkwell

import android.app.Application
import com.radium.inkwell.data.prefs.WebDavPrefs
import com.radium.inkwell.data.repo.BookSourceRepository
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

        // 转换器升级后把旧转换器转过的书源重新转一遍。
        // 书源在导入时就转换好存库了，不重转的话我们修的每个转换器 bug 都只对新导入的书源生效。
        appScope.launch {
            runCatching { koin.get<BookSourceRepository>().reconvertOutdated() }
        }

        // 首次启动预置几条常见的广告净化规则（默认关闭，用户自己决定开哪条）
        appScope.launch {
            runCatching {
                koin.get<com.radium.inkwell.data.repo.ReplaceRuleRepository>().seedIfEmpty()
            }
        }

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
