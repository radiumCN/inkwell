package com.radium.inkwell.data.repo

import com.radium.inkwell.core.webdav.WebDavClient
import com.radium.inkwell.data.net.OfficialAuthData
import com.radium.inkwell.data.net.OfficialWebDav
import com.radium.inkwell.data.net.OfficialWebDavClient
import com.radium.inkwell.data.net.OfficialWebDavException
import com.radium.inkwell.data.prefs.WebDavPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 官方同步的账号编排：登录成功 → 发应用码 → 写入现有 WebDAV 配置。
 *
 * 日常双向同步仍走 [WebDavRepository]，不经过这里。JWT 只用来查看连接信息、重发应用码。
 */
class OfficialWebDavRepository(
    private val api: OfficialWebDavClient,
    private val prefs: WebDavPrefs,
) {

    suspend fun publicConfig() = wrapValue { api.publicConfig() }

    suspend fun sendRegisterCode(email: String) = wrap { api.sendRegisterCode(email) }

    suspend fun sendLoginCode(email: String) = wrap { api.sendLoginCode(email) }

    suspend fun sendResetCode(email: String) = wrap { api.sendResetCode(email) }

    suspend fun register(username: String, email: String, password: String, code: String) =
        provision { api.register(username, email, password, code) }

    suspend fun login(login: String, password: String) =
        provision { api.login(login, password) }

    suspend fun loginWithCode(email: String, code: String) =
        provision { api.loginWithCode(email, code) }

    suspend fun resetPassword(email: String, code: String, password: String) =
        provision { api.resetPassword(email, code, password) }

    suspend fun regenerateAppPassword(): Result<Unit> {
        return try {
            val issued = withFreshAccess { access -> issueAppPassword(access) }
            val config = prefs.config.first()
            check(issued.secret.isNotBlank()) { "服务端未返回应用码" }
            val url = config.url.ifBlank { OfficialWebDav.DAV }
            WebDavClient(url, config.username, issued.secret).check().getOrThrow()
            prefs.saveOfficial(
                url = url,
                username = config.username,
                password = issued.secret,
                email = config.officialEmail,
                accessToken = config.officialAccessToken,
                refreshToken = config.officialRefreshToken,
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun disconnect() {
        val config = prefs.config.first()
        if (config.officialAccessToken.isNotBlank() || config.officialRefreshToken.isNotBlank()) {
            api.logout(config.officialAccessToken, config.officialRefreshToken)
        }
        prefs.disconnectOfficial()
    }

    suspend fun switchToCustomKeepingDav() {
        val config = prefs.config.first()
        if (config.officialAccessToken.isNotBlank() || config.officialRefreshToken.isNotBlank()) {
            api.logout(config.officialAccessToken, config.officialRefreshToken)
        }
        prefs.switchToCustomKeepingDav()
    }

    private suspend fun provision(auth: suspend () -> OfficialAuthData): Result<Unit> {
        return try {
            val session = auth()
            val me = api.me(session.tokens.accessToken)
            val davUrl = me.webdavUrl.ifBlank { OfficialWebDav.DAV }.let { url ->
                if (url.endsWith("/")) url else "$url/"
            }
            val username = me.webdavUser.ifBlank { session.user.username }
            val issued = issueAppPassword(session.tokens.accessToken)
            check(issued.secret.isNotBlank()) { "服务端未返回应用码" }
            WebDavClient(davUrl, username, issued.secret).check().getOrThrow()
            prefs.saveOfficial(
                url = davUrl,
                username = username,
                password = issued.secret,
                email = session.user.email.ifBlank { me.user.email },
                accessToken = session.tokens.accessToken,
                refreshToken = session.tokens.refreshToken,
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 优先走服务端 `replace`（同名旧码直接作废）。旧版 API 不认这个字段时，
     * 换机几次就会撞上 10 条上限 —— 那时改成客户端先删同名再发。
     */
    private suspend fun issueAppPassword(access: String) = try {
        api.createAppPassword(access, OfficialWebDav.APP_PASSWORD_NAME, replace = true)
    } catch (e: OfficialWebDavException) {
        if (e.errorCode != "limit") throw e
        val existing = api.listAppPasswords(access).items
            .filter { it.name == OfficialWebDav.APP_PASSWORD_NAME }
        existing.forEach { api.deleteAppPassword(access, it.id) }
        api.createAppPassword(access, OfficialWebDav.APP_PASSWORD_NAME, replace = false)
    }

    private suspend fun <T> withFreshAccess(block: suspend (String) -> T): T {
        val config = prefs.config.first()
        val access = config.officialAccessToken
        val refresh = config.officialRefreshToken
        check(refresh.isNotBlank()) { "请重新登录官方账号后再试" }
        return try {
            block(access)
        } catch (e: OfficialWebDavException) {
            if (e.httpStatus != 401) throw e
            val tokens = api.refresh(refresh)
            prefs.saveOfficialTokens(tokens.accessToken, tokens.refreshToken)
            block(tokens.accessToken)
        }
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
