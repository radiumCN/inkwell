package com.radium.inkwell.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.webDavDataStore by preferencesDataStore(name = "webdav")

enum class WebDavProvider {
    CUSTOM,
    OFFICIAL,
}

data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val lastSyncAt: Long = 0,
    /** 自动同步：冷启动 + 退到后台时静默同步 */
    val autoSync: Boolean = true,
    val provider: WebDavProvider = WebDavProvider.CUSTOM,
    val officialEmail: String = "",
    val officialAccessToken: String = "",
    val officialRefreshToken: String = "",
) {
    val isConfigured: Boolean get() = url.isNotBlank() && username.isNotBlank()
    val isOfficialConnected: Boolean get() = provider == WebDavProvider.OFFICIAL && isConfigured
}

class WebDavPrefs(
    private val context: Context,
    private val cipher: SecretCipher = KeystoreSecretCipher(),
) {

    private object Keys {
        val URL = stringPreferencesKey("url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val LAST_SYNC = longPreferencesKey("last_sync")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val AUTO_SYNC = androidx.datastore.preferences.core.booleanPreferencesKey("auto_sync")
        val PROVIDER = stringPreferencesKey("provider")
        val OFFICIAL_EMAIL = stringPreferencesKey("official_email")
        val OFFICIAL_ACCESS = stringPreferencesKey("official_access")
        val OFFICIAL_REFRESH = stringPreferencesKey("official_refresh")
    }

    val config: Flow<WebDavConfig> = context.webDavDataStore.data.map { p ->
        WebDavConfig(
            url = p[Keys.URL] ?: "",
            username = p[Keys.USERNAME] ?: "",
            // 这里只解不写。迁移放在 migrateSecrets()，别在 Flow 的 map 里做副作用 ——
            // 那会在每个订阅者上各触发一次写库，还可能把自己的写又喂回自己
            password = cipher.read(p[Keys.PASSWORD] ?: "").value,
            lastSyncAt = p[Keys.LAST_SYNC] ?: 0,
            autoSync = p[Keys.AUTO_SYNC] ?: true,
            provider = if (p[Keys.PROVIDER] == "official") WebDavProvider.OFFICIAL else WebDavProvider.CUSTOM,
            officialEmail = p[Keys.OFFICIAL_EMAIL] ?: "",
            officialAccessToken = cipher.read(p[Keys.OFFICIAL_ACCESS] ?: "").value,
            officialRefreshToken = cipher.read(p[Keys.OFFICIAL_REFRESH] ?: "").value,
        )
    }

    /**
     * 把升级前留下的明文口令原地换成密文。启动时跑一次即可。
     *
     * 不做的话，老用户的口令会一直以明文躺在磁盘上 —— 加密对他们等于没加。
     * 已是密文、或压根没配过 WebDAV 时什么都不做（不写库，免得白白改动文件）。
     */
    suspend fun migrateSecrets() {
        val stored = context.webDavDataStore.data.first()[Keys.PASSWORD] ?: return
        if (!cipher.read(stored).needsMigration) return
        context.webDavDataStore.edit { it[Keys.PASSWORD] = cipher.encrypt(stored) }
    }

    suspend fun setAutoSync(on: Boolean) {
        context.webDavDataStore.edit { it[Keys.AUTO_SYNC] = on }
    }

    suspend fun save(url: String, username: String, password: String) {
        // 口令加密后落盘；空口令原样存空串，别把空串也包成信封（读回来还得解一次，没意义）
        val stored = if (password.isEmpty()) "" else cipher.encrypt(password)
        context.webDavDataStore.edit { p ->
            p[Keys.URL] = url.trim()
            p[Keys.USERNAME] = username.trim()
            p[Keys.PASSWORD] = stored
            p[Keys.PROVIDER] = "custom"
            p.remove(Keys.OFFICIAL_EMAIL)
            p.remove(Keys.OFFICIAL_ACCESS)
            p.remove(Keys.OFFICIAL_REFRESH)
        }
    }

    suspend fun saveOfficial(
        url: String,
        username: String,
        password: String,
        email: String,
        accessToken: String,
        refreshToken: String,
    ) {
        val storedPass = if (password.isEmpty()) "" else cipher.encrypt(password)
        context.webDavDataStore.edit { p ->
            p[Keys.URL] = url.trim()
            p[Keys.USERNAME] = username.trim()
            p[Keys.PASSWORD] = storedPass
            p[Keys.PROVIDER] = "official"
            p[Keys.OFFICIAL_EMAIL] = email.trim()
            p[Keys.OFFICIAL_ACCESS] = if (accessToken.isEmpty()) "" else cipher.encrypt(accessToken)
            p[Keys.OFFICIAL_REFRESH] = if (refreshToken.isEmpty()) "" else cipher.encrypt(refreshToken)
        }
    }

    suspend fun saveOfficialTokens(accessToken: String, refreshToken: String) {
        context.webDavDataStore.edit { p ->
            p[Keys.OFFICIAL_ACCESS] = if (accessToken.isEmpty()) "" else cipher.encrypt(accessToken)
            p[Keys.OFFICIAL_REFRESH] = if (refreshToken.isEmpty()) "" else cipher.encrypt(refreshToken)
        }
    }

    /** 改用自备盘：DAV 三件套留下，官方登录态清掉 */
    suspend fun switchToCustomKeepingDav() {
        context.webDavDataStore.edit { p ->
            p[Keys.PROVIDER] = "custom"
            p.remove(Keys.OFFICIAL_EMAIL)
            p.remove(Keys.OFFICIAL_ACCESS)
            p.remove(Keys.OFFICIAL_REFRESH)
        }
    }

    /** 退出官方同步：连 DAV 凭据一起清，避免下一台设备误用旧应用码 */
    suspend fun disconnectOfficial() {
        context.webDavDataStore.edit { p ->
            p[Keys.URL] = ""
            p[Keys.USERNAME] = ""
            p[Keys.PASSWORD] = ""
            p[Keys.PROVIDER] = "custom"
            p.remove(Keys.OFFICIAL_EMAIL)
            p.remove(Keys.OFFICIAL_ACCESS)
            p.remove(Keys.OFFICIAL_REFRESH)
        }
    }

    suspend fun markSynced(at: Long) {
        context.webDavDataStore.edit { it[Keys.LAST_SYNC] = at }
    }

    suspend fun deviceId(): String {
        val existing = context.webDavDataStore.data.first()[Keys.DEVICE_ID]
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        context.webDavDataStore.edit { it[Keys.DEVICE_ID] = id }
        return id
    }
}
