package com.radium.inkwell.ui.webdav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.net.OfficialWebDav
import com.radium.inkwell.data.prefs.WebDavPrefs
import com.radium.inkwell.data.prefs.WebDavProvider
import com.radium.inkwell.data.repo.OfficialWebDavRepository
import com.radium.inkwell.data.repo.WebDavRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OfficialAuthPage { LOGIN, REGISTER, RESET }

enum class OfficialLoginMode { PASSWORD, CODE }

data class WebDavUiState(
    val provider: WebDavProvider = WebDavProvider.CUSTOM,
    val officialConnected: Boolean = false,
    val officialEmail: String = "",
    val officialAuthPage: OfficialAuthPage = OfficialAuthPage.LOGIN,
    val officialLoginMode: OfficialLoginMode = OfficialLoginMode.PASSWORD,
    val registrationOpen: Boolean = true,
    val officialDavUrl: String = OfficialWebDav.DAV,
    val login: String = "",
    val email: String = "",
    val username: String = "",
    val accountPassword: String = "",
    val code: String = "",
    val codeWait: Int = 0,
    val url: String = "",
    val davUsername: String = "",
    val davPassword: String = "",
    val configured: Boolean = false,
    val testing: Boolean = false,
    val syncing: Boolean = false,
    val lastSyncAt: Long = 0,
    val autoSync: Boolean = true,
    val showConnectionInfo: Boolean = false,
    val confirmSwitchToCustom: Boolean = false,
    val confirmDisconnect: Boolean = false,
    val confirmRegenerate: Boolean = false,
) {
    val busy: Boolean get() = testing || syncing
}

class WebDavViewModel(
    private val prefs: WebDavPrefs,
    private val repo: WebDavRepository,
    private val official: OfficialWebDavRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WebDavUiState())
    val state: StateFlow<WebDavUiState> = _state.asStateFlow()

    val messages = MessageBus()

    private var authJob: Job? = null
    private var cooldownJob: Job? = null

    init {
        viewModelScope.launch {
            val c = prefs.config.first()
            val provider = when {
                c.provider == WebDavProvider.OFFICIAL -> WebDavProvider.OFFICIAL
                c.isConfigured -> WebDavProvider.CUSTOM
                else -> WebDavProvider.OFFICIAL
            }
            _state.value = WebDavUiState(
                provider = provider,
                officialConnected = c.isOfficialConnected,
                officialEmail = c.officialEmail,
                url = c.url,
                davUsername = c.username,
                davPassword = c.password,
                configured = c.isConfigured,
                lastSyncAt = c.lastSyncAt,
                autoSync = c.autoSync,
                login = c.officialEmail,
                email = c.officialEmail,
            )
            official.publicConfig().onSuccess { cfg ->
                _state.update {
                    it.copy(
                        registrationOpen = cfg.registrationEnabled,
                        officialDavUrl = cfg.webdavUrl.ifBlank { OfficialWebDav.DAV },
                        officialAuthPage = if (!cfg.registrationEnabled &&
                            it.officialAuthPage == OfficialAuthPage.REGISTER
                        ) OfficialAuthPage.LOGIN else it.officialAuthPage,
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

    fun setOfficialAuthPage(page: OfficialAuthPage) {
        _state.update { it.copy(officialAuthPage = page, code = "") }
    }

    fun setOfficialLoginMode(mode: OfficialLoginMode) {
        _state.update { it.copy(officialLoginMode = mode, code = "") }
    }

    fun setLogin(v: String) = _state.update { it.copy(login = v) }
    fun setEmail(v: String) = _state.update { it.copy(email = v) }
    fun setUsername(v: String) = _state.update { it.copy(username = v) }
    fun setAccountPassword(v: String) = _state.update { it.copy(accountPassword = v) }
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
        val email = when {
            s.officialAuthPage == OfficialAuthPage.LOGIN && s.officialLoginMode == OfficialLoginMode.CODE -> s.email
            else -> s.email
        }.trim()
        if (email.isBlank()) {
            viewModelScope.launch { messages.emit("请填写邮箱") }
            return
        }
        runAuth {
            val result = when (s.officialAuthPage) {
                OfficialAuthPage.REGISTER -> official.sendRegisterCode(email)
                OfficialAuthPage.RESET -> official.sendResetCode(email)
                OfficialAuthPage.LOGIN -> official.sendLoginCode(email)
            }
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
            val result = when (s.officialAuthPage) {
                OfficialAuthPage.REGISTER -> official.register(
                    s.username.trim(),
                    s.email.trim(),
                    s.accountPassword,
                    s.code.trim(),
                )
                OfficialAuthPage.RESET -> official.resetPassword(
                    s.email.trim(),
                    s.code.trim(),
                    s.accountPassword,
                )
                OfficialAuthPage.LOGIN -> when (s.officialLoginMode) {
                    OfficialLoginMode.PASSWORD -> official.login(s.login.trim(), s.accountPassword)
                    OfficialLoginMode.CODE -> official.loginWithCode(s.email.trim(), s.code.trim())
                }
            }
            if (result.isSuccess) {
                val c = prefs.config.first()
                _state.update {
                    it.copy(
                        officialConnected = true,
                        officialEmail = c.officialEmail,
                        url = c.url,
                        davUsername = c.username,
                        davPassword = c.password,
                        configured = true,
                        accountPassword = "",
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
                messages.emit("连接失败: ${result.exceptionOrNull()?.message}")
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
                )
            }
            messages.emit(result.getOrElse { "同步失败: ${it.message}" })
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
