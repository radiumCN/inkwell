package com.radium.inkwell

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
        appScope.launch { autoSync(koin) }

        // 退到后台时再同步一次。
        //
        // 从前**只有**冷启动同步：你读完书退出 App，这一程的进度根本没传上去 ——
        // 换台设备接着读，进度还停在上次开 App 的位置。而"读完退出"恰恰是进度变化最大的时刻。
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    appScope.launch { autoSync(koin) }
                }
            }
        )
    }

    /**
     * 静默同步。失败不打扰用户 —— 自动同步是背景行为，网络不好就下次再说，
     * 弹个错误提示只会让人莫名其妙。
     *
     * 两次同步至少隔 [MIN_SYNC_INTERVAL_MS]：来回切前后台会反复触发 onStop，
     * 每次都全量传一遍纯属浪费流量。
     */
    private suspend fun autoSync(koin: org.koin.core.Koin) {
        runCatching {
            val prefs = koin.get<WebDavPrefs>()
            val config = prefs.config.first()
            if (!config.isConfigured || !config.autoSync) return
            if (System.currentTimeMillis() - config.lastSyncAt < MIN_SYNC_INTERVAL_MS) return
            koin.get<WebDavRepository>().sync()
        }
    }

    private companion object {
        const val MIN_SYNC_INTERVAL_MS = 60_000L
    }
}
