package com.radium.inkwell.data.prefs

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.radium.inkwell.data.net.OfficialWebDav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.webDavDataStore by preferencesDataStore(
    name = "webdav",
    // 文件损坏（写到一半被杀、存储出错）时 DataStore 抛 CorruptionException，而设置流是启动就收集的：
    // 不兜的话应用每次启动都崩，唯一的出路是清除数据 —— 连同书架数据库一起没了。退回默认值
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

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
        val SYNC_LOCAL_STAMP = longPreferencesKey("sync_local_stamp")
        val REMOTE_VERSION = stringPreferencesKey("remote_version")
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
        // 解密走 Keystore（跨进程）：WebDAV 设置页在 viewModelScope（主线程）上收这条流，别让它在主线程上解
        .flowOn(Dispatchers.IO)

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

    /**
     * 官方 DAV 根从 api 主机迁到站点主机。已连上的用户 DataStore 里还是旧 URL，
     * 不改写的话冷启动同步会 404。自备盘填了别的地址不动。
     */
    suspend fun migrateOfficialDavUrl() {
        val stored = context.webDavDataStore.data.first()[Keys.URL] ?: return
        if (!OfficialWebDav.isLegacyDav(stored)) return
        context.webDavDataStore.edit { it[Keys.URL] = OfficialWebDav.DAV }
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

    /**
     * 上次完整同步之后两边各自停在哪：拍本地快照时本地的「最新修改时间」[SyncMark.localStamp]，
     * 远端备份当时的版本 [SyncMark.remote]（连同是哪个地址、哪个账号 —— 换了账号，旧标记绝不能被当成「远端没变」）。
     * 自动同步据此判断两边都没动过、整次跳过，见 WebDavRepository.syncLocked。
     */
    data class SyncMark(val localStamp: Long, val remote: String?)

    suspend fun syncMark(): SyncMark {
        val p = context.webDavDataStore.data.first()
        return SyncMark(p[Keys.SYNC_LOCAL_STAMP] ?: -1L, p[Keys.REMOTE_VERSION])
    }

    /** [remote] 为 null（服务器不给版本标记）就清掉旧的：下次照常完整同步 */
    suspend fun saveSyncMark(localStamp: Long, remote: String?) {
        context.webDavDataStore.edit {
            it[Keys.SYNC_LOCAL_STAMP] = localStamp
            if (remote != null) it[Keys.REMOTE_VERSION] = remote else it.remove(Keys.REMOTE_VERSION)
        }
    }

    /**
     * 本机的设备 id，第一次要时生成。生成放在 edit 里做：edit 是串行的，读改写不会被插队。
     * 从前是「读一次、没有就生成、再写」，阅读记录和同步同时第一次要它时会各生成一个 ——
     * 阅读时长按设备 id 分行，两个 id 就把本机的时长劈成了两台「设备」。
     */
    suspend fun deviceId(): String {
        context.webDavDataStore.data.first()[Keys.DEVICE_ID]?.let { return it }
        var id = ""
        context.webDavDataStore.edit { p ->
            id = p[Keys.DEVICE_ID] ?: UUID.randomUUID().toString().also { p[Keys.DEVICE_ID] = it }
        }
        return id
    }
}
