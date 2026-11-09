package com.radium.inkwell.update

import android.content.Context
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.prefs.KnownUpdate
import com.radium.inkwell.data.prefs.updateCheckTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「有新版本」的红点：启动时在后台查一次，结果记在本机，书架的设置齿轮和设置页「检查更新」那一行读 [badge]。
 *
 * 不弹窗、不弹提示：打开 App 是来读书的，一进来就被更新弹窗拦住，比没有更新还烦。
 * 红点一直亮到看过那一版的更新弹窗（[markSeen]）；之后「检查更新」那一行仍写着「0.5.5 可更新」，直到装上新版。
 *
 * 应用级单例（不是 ViewModel）：启动时的那次检查跟着进程走，不跟某个页面；书架和设置两页读的是同一份状态。
 */
class UpdateNotifier(
    context: Context,
    private val appPrefs: AppPrefs,
    private val updateManager: UpdateManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 装着的版本（不带 v）。读不到时是「?」—— 那种情况下任何版本都算新的，宁可多亮一次 */
    val currentVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    val badge: StateFlow<UpdateBadge> = combine(
        appPrefs.knownUpdate,
        appPrefs.autoCheckUpdate,
        appPrefs.updateSource,
        appPrefs.updateChannel,
    ) { known, auto, source, channel ->
        updateBadge(known, auto, updateCheckTarget(source, channel), currentVersion)
    }.stateIn(scope, SharingStarted.Eagerly, UpdateBadge())

    /**
     * 这个进程里查到的完整信息（更新说明、下载地址）和查的是哪个「更新源/渠道」。
     * 设置页点「检查更新」时它在就直接弹弹窗，不再转一圈重查；不在（上次是别的进程查的，本机只记了版本号）就照常查。
     */
    private val latestInfo = MutableStateFlow<Pair<String, UpdateInfo>?>(null)

    fun cachedInfo(source: UpdateSource, channel: UpdateChannel): UpdateInfo? =
        latestInfo.value
            ?.takeIf { it.first == updateCheckTarget(source, channel) }
            ?.second
            ?.takeIf { UpdateChecker.isNewer(it.latestVersion, currentVersion) }

    private val launchScheduled = AtomicBoolean(false)

    /**
     * 启动时查一次。MainActivity 每次 onCreate 都调（转屏、换深色也会重建），进程里只排一次。
     *
     * 等 [LAUNCH_DELAY_MS] 再查：书架那几十毫秒的首屏不跟网络抢；用户点开书立刻退出的话，也就不查了 —— 进程还在就照查，无妨。
     * 离上次查成不到 [LAUNCH_THROTTLE_MS] 就不查（GitHub 不登录每小时 60 次的限额，也省电）；换过更新源 / 渠道不算在内，
     * 那时上次的结果已经不作数（见 [KnownUpdate.checkedFor]）。
     */
    fun scheduleLaunchCheck() {
        if (!launchScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(LAUNCH_DELAY_MS)
            try {
                if (!appPrefs.autoCheckUpdate.first()) return@launch
                val source = appPrefs.updateSource.first()
                val channel = appPrefs.updateChannel.first()
                val known = appPrefs.knownUpdate.first()
                val now = System.currentTimeMillis()
                val fresh = known.checkedFor == updateCheckTarget(source, channel) &&
                    now - known.checkedAt in 0 until LAUNCH_THROTTLE_MS
                if (fresh) return@launch
                record(updateManager.check(source, channel, currentVersion), source, channel)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 后台这一下查不成就算了：断网、GitHub 访问不了都是常事，下次启动再查。手动检查会把原因说出来
            }
        }
    }

    /**
     * 记下一次检查的结果（启动时那一次、设置页手动那一次都走这里），红点跟着变。
     * 查失败不记：上次查到的新版本照旧算数，别因为这回断网就把红点灭了。
     */
    suspend fun record(result: CheckResult, source: UpdateSource, channel: UpdateChannel) {
        val target = updateCheckTarget(source, channel)
        val now = System.currentTimeMillis()
        when (result) {
            is CheckResult.Available -> {
                latestInfo.value = target to result.info
                appPrefs.recordUpdateCheck(result.info.latestVersion, target, now)
            }
            CheckResult.UpToDate -> {
                latestInfo.value = null
                appPrefs.recordUpdateCheck(null, target, now)
            }
            is CheckResult.Failed -> Unit
        }
    }

    /** 弹出了 [version] 的更新弹窗：两处红点一起收 */
    suspend fun markSeen(version: String) = appPrefs.markUpdateSeen(version)

    private companion object {
        const val LAUNCH_DELAY_MS = 5_000L
        const val LAUNCH_THROTTLE_MS = 6 * 60 * 60 * 1000L
    }
}

/**
 * @param latest 比装着的版本新、且是按当前「更新源/渠道」查到的那一版；没有就是 null。「检查更新」那一行写「$latest 可更新」
 * @param dot 亮不亮红点：有新版、开着「启动时检查更新」、还没看过这一版的弹窗
 */
data class UpdateBadge(val latest: String? = null, val dot: Boolean = false)

/**
 * 由记在本机的状态算红点。拆成纯函数是为了单测：哪些情况亮、哪些情况不亮，一条一条钉住。
 *
 * - 上次查的是别的渠道 / 更新源：不作数（测试版渠道查到的 beta，切回稳定版不该亮）；
 * - 记着的版本已经不比装着的新（刚升级上来）：不作数；
 * - 关了「启动时检查更新」：不亮点，「可更新」照写 —— 关的是「别自己联网、别来提醒我」，手动查到的照样告诉他。
 */
internal fun updateBadge(known: KnownUpdate, autoCheck: Boolean, target: String, currentVersion: String): UpdateBadge {
    val latest = known.latest?.takeIf { known.checkedFor == target && UpdateChecker.isNewer(it, currentVersion) }
    return UpdateBadge(latest = latest, dot = latest != null && autoCheck && latest != known.seen)
}
