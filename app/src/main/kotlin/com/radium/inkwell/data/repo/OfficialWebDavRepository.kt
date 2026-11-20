package com.radium.inkwell.data.repo

import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import android.os.Build
import com.radium.inkwell.core.webdav.WebDavClient
import com.radium.inkwell.core.webdav.WebDavQuota
import com.radium.inkwell.data.net.OfficialAppPassword
import com.radium.inkwell.data.net.OfficialAuthData
import com.radium.inkwell.data.net.OfficialSessionExpiredException
import com.radium.inkwell.data.net.OfficialWebDav
import com.radium.inkwell.data.net.OfficialWebDavClient
import com.radium.inkwell.data.net.OfficialWebDavException
import com.radium.inkwell.data.prefs.WebDavPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 官方同步的账号编排：登录成功 → 发应用码 → 写入现有 WebDAV 配置。
 *
 * 日常双向同步仍走 [WebDavRepository]，不经过这里。JWT 只用来查看连接信息、重发应用码。
 */
class OfficialWebDavRepository(
    private val api: OfficialWebDavClient,
    private val prefs: WebDavPrefs,
) {
    private val sessionMutex = Mutex()

    suspend fun publicConfig() = wrapValue { api.publicConfig() }

    /** 兼容尚未通过 DAV 返回配额的旧服务端。 */
    suspend fun storageQuota(): Result<WebDavQuota?> = wrapValue {
        val user = withFreshAccess { access -> api.me(access).user }
        val used = user.usedBytes
        val total = user.quotaBytes
        if (used != null && used >= 0 && total != null && total > 0) {
            WebDavQuota(used, total)
        } else {
            null
        }
    }

    suspend fun sendRegisterCode(email: String) = wrap { api.sendRegisterCode(email) }

    suspend fun sendLoginCode(email: String) = wrap { api.sendLoginCode(email) }

    suspend fun sendResetCode(email: String) = wrap { api.sendResetCode(email) }

    suspend fun register(username: String, email: String, password: String, code: String) =
        provision { api.register(username, email, password, code) }

    suspend fun login(login: String, password: String) =
        provision { api.login(login, password) }

    suspend fun loginWithCode(email: String, code: String) =
        provision { api.loginWithCode(email, code) }

    suspend fun reauthorizeWithCode(email: String, code: String): Result<Unit> = wrap {
        sessionMutex.withLock {
            val config = prefs.config.first()
            check(config.isOfficialConnected) { "请先连接官方同步" }
            check(config.officialEmail.isBlank() || config.officialEmail.equals(email, ignoreCase = true)) {
                "请使用当前已连接的官方账号验证"
            }
            val session = api.loginWithCode(email, code)
            val me = api.me(session.tokens.accessToken)
            if (me.davUsername(session.user.username) != config.username) {
                api.logout(session.tokens.accessToken, session.tokens.refreshToken)
                error("请使用当前已连接的官方账号验证")
            }
            prefs.saveOfficialTokens(
                username = config.username,
                accessToken = session.tokens.accessToken,
                refreshToken = session.tokens.refreshToken,
                email = session.user.email.ifBlank { me.user.email.ifBlank { email } },
            )
        }
    }

    suspend fun resetPassword(email: String, code: String, password: String) =
        provision { api.resetPassword(email, code, password) }

    suspend fun regenerateAppPassword(): Result<Unit> {
        return try {
            val (url, username, secret) = withFreshAccess { access ->
                // 见 provision：签发即作废旧码，签发与落盘必须一起完成
                withContext(NonCancellable) {
                    val issued = issueAppPassword(access)
                    check(issued.secret.isNotBlank()) { "服务端未返回应用码" }
                    // 签发之后再读配置：withFreshAccess 可能刚刷新过令牌并写回，开头那份是旧的
                    val config = prefs.config.first()
                    val url = OfficialWebDav.resolveDavUrl(config.url)
                    prefs.saveOfficial(
                        url = url,
                        username = config.username,
                        password = issued.secret,
                        email = config.officialEmail,
                        accessToken = config.officialAccessToken,
                        refreshToken = config.officialRefreshToken,
                    )
                    Triple(url, config.username, issued.secret)
                }
            }
            WebDavClient(url, username, secret).check().getOrThrow()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun disconnect() {
        logoutThen { prefs.disconnectOfficial() }
    }

    suspend fun switchToCustomKeepingDav() {
        logoutThen { prefs.switchToCustomKeepingDav() }
    }

    /**
     * 先请服务端注销会话，再清本地凭据 —— 后一步**无论如何都要做**。
     *
     * 用户在注销请求的当口离开页面（协程取消）时，服务端可能已经把会话作废了，本地却还留着令牌：
     * 界面显示「官方已连接」，刷新令牌一用就 401。所以本地这一步放在 finally 里、且不可取消；
     * 取消本身照常往外抛。
     */
    private suspend fun logoutThen(clearLocal: suspend () -> Unit) = sessionMutex.withLock {
        val config = prefs.config.first()
        try {
            if (config.officialAccessToken.isNotBlank() || config.officialRefreshToken.isNotBlank()) {
                api.logout(config.officialAccessToken, config.officialRefreshToken)
            }
        } finally {
            withContext(NonCancellable) { clearLocal() }
        }
    }

    private suspend fun provision(auth: suspend () -> OfficialAuthData): Result<Unit> {
        return try {
            val session = auth()
            val me = api.me(session.tokens.accessToken)
            val davUrl = OfficialWebDav.resolveDavUrl(me.webdavUrl)
            val username = me.davUsername(session.user.username)
            // 签发新码会**当场**作废本机上一次的码（replace）—— 从这一刻起，手里只有新码是有效的。
            // 所以先落盘、再做连通检查，且签发与落盘不可取消。从前是检查通过才保存：DAV 一时抽风、
            // 或用户在这当口离开页面（协程被取消），prefs 里留着的就是刚被作废的旧码 ——
            // 界面显示已连接，自动同步却一直静默 401。
            val issued = withContext(NonCancellable) {
                issueAppPassword(session.tokens.accessToken).also { issued ->
                    check(issued.secret.isNotBlank()) { "服务端未返回应用码" }
                    prefs.saveOfficial(
                        url = davUrl,
                        username = username,
                        password = issued.secret,
                        email = session.user.email.ifBlank { me.user.email },
                        accessToken = session.tokens.accessToken,
                        refreshToken = session.tokens.refreshToken,
                    )
                }
            }
            WebDavClient(davUrl, username, issued.secret).check().getOrThrow()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 优先走服务端 `replace`（同名旧码直接作废）。名字按设备区分，所以作废的只是本机上一次的码，
     * 别的设备不受影响。旧版 API 不认这个字段时，重登几次就会撞上 10 条上限 —— 那时改成客户端
     * 先删同名再发；0.3.7 及更早那个全设备共用的旧名字也一并清掉（从前撞上限时本来就会清它）。
     */
    private suspend fun issueAppPassword(access: String): OfficialAppPassword {
        val name = OfficialWebDav.appPasswordName(prefs.deviceId(), Build.MODEL)
        return try {
            api.createAppPassword(access, name, replace = true)
        } catch (e: OfficialWebDavException) {
            if (e.errorCode != "limit") throw e
            val stale = api.listAppPasswords(access).items
                .filter { it.name == name || it.name == OfficialWebDav.APP_PASSWORD_NAME }
            stale.forEach { api.deleteAppPassword(access, it.id) }
            api.createAppPassword(access, name, replace = false)
        }
    }

    private suspend fun <T> withFreshAccess(block: suspend (String) -> T): T = sessionMutex.withLock {
        val config = prefs.config.first()
        check(config.isOfficialConnected) { "请先连接官方同步" }
        val access = config.officialAccessToken
        if (access.isNotBlank()) {
            try {
                return@withLock block(access)
            } catch (e: OfficialWebDavException) {
                if (e.httpStatus != 401) throw e
            }
        }
        val refresh = config.officialRefreshToken
        if (refresh.isBlank()) throw OfficialSessionExpiredException()
        val tokens = withContext(NonCancellable) {
            val refreshed = api.refresh(refresh)
            prefs.saveOfficialTokens(config.username, refreshed.accessToken, refreshed.refreshToken)
            refreshed
        }
        block(tokens.accessToken)
    }

    private suspend fun wrap(block: suspend () -> Unit): Result<Unit> = wrapValue(block)

    private suspend fun <T> wrapValue(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
