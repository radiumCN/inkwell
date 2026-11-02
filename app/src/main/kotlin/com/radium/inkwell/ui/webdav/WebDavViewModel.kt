package com.radium.inkwell.ui.webdav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.net.OfficialWebDav
import com.radium.inkwell.data.prefs.WebDavPrefs
import com.radium.inkwell.data.prefs.WebDavProvider
import com.radium.inkwell.data.repo.OfficialWebDavRepository
import com.radium.inkwell.data.repo.WebDavRepository
import com.radium.inkwell.data.repo.describeSyncError
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.util.LocalNetworkAccess
import com.radium.inkwell.util.LocalNetworkPermissionMissing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebDavUiState(
    /**
     * 配置读到了没有。没读到时 [provider] 只是占位的 CUSTOM：从前首帧先画出「自备 WebDAV」表单，
     * 紧接着跳回「官方同步」，整页内容换一遍。
     */
    val loaded: Boolean = false,
    val provider: WebDavProvider = WebDavProvider.CUSTOM,
    val officialConnected: Boolean = false,
    val officialEmail: String = "",
    val registrationOpen: Boolean = true,
    val officialDavUrl: String = OfficialWebDav.DAV,
    val email: String = "",
    val code: String = "",
    val codeWait: Int = 0,
    val url: String = "",
    val davUsername: String = "",
    val davPassword: String = "",
    val configured: Boolean = false,
    val testing: Boolean = false,
    val syncing: Boolean = false,
    val lastSyncAt: Long = 0,
    /** 最近一次失败（比 [lastSyncAt] 新才显示），见 WebDavConfig.lastSyncError */
    val lastSyncError: String = "",
    val lastSyncErrorAt: Long = 0,
    val autoSync: Boolean = true,
    val showConnectionInfo: Boolean = false,
    val confirmSwitchToCustom: Boolean = false,
    val confirmDisconnect: Boolean = false,
    val confirmRegenerate: Boolean = false,
    /** 地址在局域网里、缺 Android 17 的局域网权限：先讲清楚为什么要「附近设备」，再弹系统授权 */
    val askLocalNetwork: Boolean = false,
) {
    val busy: Boolean get() = testing || syncing
}

class WebDavViewModel(
    private val prefs: WebDavPrefs,
    private val repo: WebDavRepository,
    private val official: OfficialWebDavRepository,
    private val localNetwork: LocalNetworkAccess,
) : ViewModel() {

    private val _state = MutableStateFlow(WebDavUiState())
    val state: StateFlow<WebDavUiState> = _state.asStateFlow()

    val messages = MessageBus()

    private var authJob: Job? = null
    private var cooldownJob: Job? = null

    /** 授权回来要接着做的那件事（测试并保存 / 立即同步）—— 用户点的是它，不是「授权」 */
    private var afterLocalNetworkGrant: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            val c = prefs.config.first()
            val provider = when {
                c.provider == WebDavProvider.OFFICIAL -> WebDavProvider.OFFICIAL
                c.isConfigured -> WebDavProvider.CUSTOM
                else -> WebDavProvider.OFFICIAL
            }
            _state.value = WebDavUiState(
                loaded = true,
                provider = provider,
                officialConnected = c.isOfficialConnected,
                officialEmail = c.officialEmail,
                url = OfficialWebDav.resolveDavUrl(c.url).takeIf { c.url.isNotBlank() }.orEmpty(),
                davUsername = c.username,
                davPassword = c.password,
                configured = c.isConfigured,
                lastSyncAt = c.lastSyncAt,
                lastSyncError = c.lastSyncError,
                lastSyncErrorAt = c.lastSyncErrorAt,
                autoSync = c.autoSync,
                email = c.officialEmail,
            )
            official.publicConfig().onSuccess { cfg ->
                _state.update {
                    it.copy(
                        registrationOpen = cfg.registrationEnabled,
                        officialDavUrl = OfficialWebDav.resolveDavUrl(cfg.webdavUrl),
                    )
                }
            }
        }
    }

    fun setProvider(provider: WebDavProvider) {
        val s = _state.value
        if (s.provider == provider) return
        if (s.officialConnected && provider == WebDavProvider.CUSTOM) {
            _state.update { it.copy(confirmSwitchToCustom = true) }
            return
        }
        _state.update { it.copy(provider = provider) }
    }

    fun confirmSwitchToCustom() {
        _state.update { it.copy(confirmSwitchToCustom = false) }
        authJob?.cancel()
        viewModelScope.launch {
            official.switchToCustomKeepingDav()
            _state.update {
                it.copy(
                    provider = WebDavProvider.CUSTOM,
                    officialConnected = false,
                    officialEmail = "",
                    configured = it.url.isNotBlank() && it.davUsername.isNotBlank(),
                )
            }
            messages.emit("已改为自备 WebDAV")
        }
    }

    fun dismissSwitchToCustom() = _state.update { it.copy(confirmSwitchToCustom = false) }

    fun setEmail(v: String) = _state.update { it.copy(email = v) }
    fun setCode(v: String) = _state.update { it.copy(code = v) }
    fun setUrl(v: String) = _state.update { it.copy(url = v) }
    fun setDavUsername(v: String) = _state.update { it.copy(davUsername = v) }
    fun setDavPassword(v: String) = _state.update { it.copy(davPassword = v) }

    fun setAutoSync(on: Boolean) {
        _state.update { it.copy(autoSync = on) }
        viewModelScope.launch { prefs.setAutoSync(on) }
    }

    fun sendCode() {
        val s = _state.value
        if (s.busy || s.codeWait > 0) return
        val email = s.email.trim()
        if (email.isBlank()) {
            viewModelScope.launch { messages.emit("请填写邮箱") }
            return
        }
        runAuth {
            val result = official.sendLoginCode(email)
            if (result.isSuccess) {
                startCooldown()
                messages.emit("验证码已发送")
            } else {
                messages.emit(result.exceptionOrNull()?.message ?: "验证码发送失败")
            }
        }
    }

    fun submitOfficial() {
        val s = _state.value
        if (s.busy) return
        runAuth {
            val result = official.loginWithCode(s.email.trim(), s.code.trim())
            if (result.isSuccess) {
                val c = prefs.config.first()
                _state.update {
                    it.copy(
                        officialConnected = true,
                        officialEmail = c.officialEmail,
                        url = OfficialWebDav.resolveDavUrl(c.url),
                        davUsername = c.username,
                        davPassword = c.password,
                        configured = true,
                        code = "",
                    )
                }
                messages.emit("已开启官方同步")
            } else {
                messages.emit(result.exceptionOrNull()?.message ?: "操作失败")
            }
        }
    }

    fun showConnectionInfo(show: Boolean) = _state.update { it.copy(showConnectionInfo = show) }

    fun askRegenerate() = _state.update { it.copy(confirmRegenerate = true) }
    fun dismissRegenerate() = _state.update { it.copy(confirmRegenerate = false) }

    fun regenerateAppPassword() {
        _state.update { it.copy(confirmRegenerate = false) }
        runAuth {
            val result = official.regenerateAppPassword()
            if (result.isSuccess) {
                val c = prefs.config.first()
                _state.update { it.copy(davPassword = c.password) }
                messages.emit("已生成新应用码，旧码已失效")
            } else {
                messages.emit(result.exceptionOrNull()?.message ?: "生成失败")
            }
        }
    }

    fun askDisconnect() = _state.update { it.copy(confirmDisconnect = true) }
    fun dismissDisconnect() = _state.update { it.copy(confirmDisconnect = false) }

    fun disconnectOfficial() {
        _state.update { it.copy(confirmDisconnect = false) }
        viewModelScope.launch {
            official.disconnect()
            _state.update {
                it.copy(
                    officialConnected = false,
                    officialEmail = "",
                    configured = false,
                    url = "",
                    davUsername = "",
                    davPassword = "",
                    lastSyncAt = 0,
                )
            }
            messages.emit("已退出官方同步")
        }
    }

    fun testAndSave() {
        val s = _state.value
        if (s.busy) return
        viewModelScope.launch {
            _state.update { it.copy(testing = true) }
            if (localNetwork.isBlocked(s.url)) {
                askLocalNetwork(then = ::testAndSave)
                return@launch
            }
            val result = repo.testConnection(s.url, s.davUsername, s.davPassword)
            if (result.isSuccess) {
                prefs.save(s.url, s.davUsername, s.davPassword)
                _state.update {
                    it.copy(
                        testing = false,
                        configured = true,
                        provider = WebDavProvider.CUSTOM,
                        officialConnected = false,
                        officialEmail = "",
                    )
                }
                messages.emit("连接成功，已保存")
            } else {
                _state.update { it.copy(testing = false) }
                messages.emit("连接失败：" + result.exceptionOrNull()?.let(::describeSyncError).orEmpty())
            }
        }
    }

    fun syncNow() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true) }
            val result = repo.sync()
            _state.update {
                it.copy(
                    syncing = false,
                    lastSyncAt = if (result.isSuccess) System.currentTimeMillis() else it.lastSyncAt,
                    lastSyncError = result.exceptionOrNull()?.let(::describeSyncError).orEmpty(),
                    lastSyncErrorAt = if (result.isFailure) System.currentTimeMillis() else 0,
                )
            }
            // 判定放在仓库里：同步连的是**已保存**的地址，页面上的输入框可能改了还没存
            if (result.exceptionOrNull() is LocalNetworkPermissionMissing) {
                askLocalNetwork(then = ::syncNow)
                return@launch
            }
            messages.emit(result.getOrElse { "同步失败：" + describeSyncError(it) })
        }
    }

    private fun askLocalNetwork(then: () -> Unit) {
        afterLocalNetworkGrant = then
        _state.update { it.copy(testing = false, syncing = false, askLocalNetwork = true) }
    }

    /** 说明框点了「去授权」：页面接着拉起系统授权框，结果回到 [onLocalNetworkPermissionResult] */
    fun confirmLocalNetwork() = _state.update { it.copy(askLocalNetwork = false) }

    fun dismissLocalNetwork() {
        afterLocalNetworkGrant = null
        _state.update { it.copy(askLocalNetwork = false) }
    }

    fun onLocalNetworkPermissionResult(granted: Boolean) {
        val then = afterLocalNetworkGrant
        afterLocalNetworkGrant = null
        if (granted) {
            then?.invoke()
        } else {
            // 拒过一次之后系统可能不再弹框、直接回「拒绝」—— 得告诉用户去哪儿手动开
            messages.emit("没有「附近设备」权限，连不上局域网里的 WebDAV。可在系统设置 → 应用 → Inkwell → 权限里开启")
        }
    }

    private fun runAuth(block: suspend () -> Unit) {
        if (_state.value.busy) return
        authJob?.cancel()
        authJob = viewModelScope.launch {
            _state.update { it.copy(testing = true) }
            try {
                block()
            } finally {
                _state.update { it.copy(testing = false) }
            }
        }
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (i in 60 downTo 1) {
                _state.update { it.copy(codeWait = i) }
                delay(1_000)
            }
            _state.update { it.copy(codeWait = 0) }
        }
    }
}
