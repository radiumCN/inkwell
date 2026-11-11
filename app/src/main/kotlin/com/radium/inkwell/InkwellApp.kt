package com.radium.inkwell

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.prefs.ReaderPrefs
import com.radium.inkwell.data.prefs.WebDavPrefs
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.RssFeedRepository
import com.radium.inkwell.data.repo.RssRepository
import com.radium.inkwell.data.repo.WebDavRepository
import com.radium.inkwell.di.appModule
import com.radium.inkwell.util.RunLogRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import com.radium.inkwell.data.repo.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.radium.inkwell.core.source.guardPrivateNetwork
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class InkwellApp : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidContext(this@InkwellApp)
            modules(appModule)
        }.koin

        // 预热阅读设置 —— 这一行不是可有可无的。
        //
        // ReaderPrefs.settings 是 stateIn(Eagerly)，注释也一直写着"App 启动即预热"，
        // 但 Koin 的 single 是**懒**的：在此之前全 App 第一个注入它的就是 ReaderViewModel
        // 自己。于是预热永远赢不了自己的构造函数 —— VM 构造时读 .value 拿到的是占位默认值，
        // 用户真实的设置要等 DataStore 从磁盘读完（实测 ~90ms）才落地。结果是**每本书每次
        // 点开都排两遍版**：先按默认边距排一遍，再在 260ms 入场动画的正中间推翻重排，
        // 正文跟着跳一下 —— 白做的那一遍还正好跟动画抢主线程。
        //
        // 放在这里，磁盘那一读提前到冷启动；用户点书时 .value 已是真值，只排一遍。
        koin.get<ReaderPrefs>()
        // App 主题同理（AppPrefs.themeState）：MainActivity 要等它到了才放首帧，越早开读越不用等
        koin.get<AppPrefs>()
        // 有没有开着的订阅源（RssRepository.hasEnabledSourceState）：书架顶栏的「订阅」按钮跟着它。
        // 在库里，比偏好晚到，提前拉起来，免得按钮在首帧之后才冒出来、把旁边的按钮挤一下。
        // 放后台：构造它要连带建网络客户端，别占 onCreate 的主线程
        appScope.launch { runCatching { koin.get<RssRepository>() } }

        // 开发者模式下的运行日志：开关跟着偏好走，设置页拨开关、关开发者模式都在这里生效。
        // 放得靠前 —— 越早起 logcat，越不用担心启动那段被系统缓冲区挤掉
        appScope.launch {
            val recorder = koin.get<RunLogRecorder>()
            koin.get<AppPrefs>().logRecording
                .distinctUntilChanged()
                .collect { on -> withContext(Dispatchers.IO) { if (on) recorder.start() else recorder.stop() } }
        }

        // 首次启动预置几条常见的广告净化规则（默认关闭，用户自己决定开哪条）
        appScope.launch {
            runCatching {
                koin.get<com.radium.inkwell.data.repo.ReplaceRuleRepository>().seedIfEmpty()
            }
        }

        // 冷启动静默同步（已配置 WebDAV 时）；失败不打扰用户。
        // 先把升级前留下的明文口令换成密文，再同步 —— 顺序要紧：同步会读口令，
        // 两件事都在这一个协程里顺序做，不会撞在一起。
        appScope.launch {
            runCatching {
                val webDavPrefs = koin.get<WebDavPrefs>()
                webDavPrefs.migrateSecrets()
                webDavPrefs.migrateOfficialDavUrl()
            }
            // 不推迟：换台设备接着读，靠的就是开 App 当场把对面的进度拉下来 —— 晚几秒，
            // 用户先点开书，读到的是旧进度，随后存下的「更新」的进度反而把对面盖掉。
            // 两边都没动时这一趟只是一次 HEAD（见 WebDavRepository.syncLocked），不怎么和首帧抢
            autoSync(koin)
        }

        // 正文缓存的全书架清理：进书时只清那一本，不再打开的书没人管（见 BookRepository.sweepContentCache）。
        // 同样让开冷启动；失败不打扰用户，下次启动再来
        appScope.launch {
            delay(COLD_START_SWEEP_DELAY_MS)
            runCatching {
                val days = koin.get<AppPrefs>().cacheAutoPruneDays.first()
                koin.get<BookRepository>().sweepContentCache(days)
                // 订阅页缓存的文章跟同一个天数走：订阅源里撤下、这么多天没再取到的清掉（见 RssFeedRepository.prune）
                koin.get<RssFeedRepository>().prune(days)
            }.onFailure { if (it is CancellationException) throw it }
        }

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
     * 封面缩略图专用 ImageLoader。内存缓存按设备内存给，夹在 [COVER_CACHE_MIN_BYTES]..[COVER_CACHE_MAX_BYTES]：
     * 不用 Coil 默认的「进程内存 20%」，那在大内存机器上是几百 MB。
     *
     * 从前固定 15MB，三列网格一张封面解出来半 MB 多，只装得下二十几本 —— 书架超过两屏，
     * 往回滑就从磁盘重解，默认封面先露一下、真封面再冒出来。当初卡这么死是怕把**原图**堆进内存；
     * 现在 BookCover 按格子的布局尺寸解码，缓存里只有缩略图，放宽到几十 MB 就能把常见的书架整架留住。
     * 不靠 RGB565 省内存：渐变插画封面会出色带。
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(coverMemoryCacheBytes())
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil").toOkioPath())
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .components {
                // 封面地址来自书源（第三方），同样不许打到内网。从前走 Coil 默认的 OkHttp 客户端：
                // coverUrl 写成 http://192.168.1.1/… 就是一次对路由器后台的盲 GET —— 不少路由器的
                // 改设置接口就是 GET。装上与书源抓取同一套拦截（手动加的组件优先于自动加载的那个）
                add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient.Builder().guardPrivateNetwork().build() }))
            }
            .build()

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
            koin.get<WebDavRepository>().sync(auto = true)
        }
    }

    /** 应用可用堆的十分之一，夹在上下限之间（见 [newImageLoader]） */
    private fun coverMemoryCacheBytes(): Long {
        val memoryClassMb = getSystemService(android.app.ActivityManager::class.java)?.memoryClass ?: 0
        return (memoryClassMb * 1024L * 1024L / 10).coerceIn(COVER_CACHE_MIN_BYTES, COVER_CACHE_MAX_BYTES)
    }

    private companion object {
        const val COVER_CACHE_MIN_BYTES = 15L * 1024 * 1024
        const val COVER_CACHE_MAX_BYTES = 48L * 1024 * 1024
        const val MIN_SYNC_INTERVAL_MS = 60_000L
        const val COLD_START_SWEEP_DELAY_MS = 10_000L
    }
}
