package com.radium.inkwell.ui.webdav

import com.radium.inkwell.util.UsageLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.net.OfficialSessionExpiredException
import com.radium.inkwell.data.net.OfficialWebDav
import com.radium.inkwell.data.prefs.WebDavPrefs
import com.radium.inkwell.data.prefs.WebDavProvider
import com.radium.inkwell.data.repo.BookFileBackupItem
import com.radium.inkwell.data.repo.LocalBookBackupRepository
import com.radium.inkwell.core.webdav.WebDavClient
import com.radium.inkwell.core.webdav.WebDavQuota
import com.radium.inkwell.data.repo.OfficialWebDavRepository
import com.radium.inkwell.data.repo.WebDavRepository
import com.radium.inkwell.data.repo.describeSyncError
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.util.LocalNetworkAccess
import com.radium.inkwell.util.LocalNetworkPermissionMissing
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
    val quota: WebDavQuota? = null,
    val quotaLoading: Boolean = false,
    val quotaAppPasswordInvalid: Boolean = false,
    val showOfficialReauth: Boolean = false,
    val bookFiles: BookFilesUiState? = null,
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

data class BookFilesUiState(
    val restore: Boolean = false,
    val items: List<BookFileBackupItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val loading: Boolean = true,
    val transferring: Boolean = false,
    val error: String? = null,
    val results: Map<String, String?> = emptyMap(),
)

class WebDavViewModel(
    private val prefs: WebDavPrefs,
    private val repo: WebDavRepository,
    private val official: OfficialWebDavRepository,
    private val localNetwork: LocalNetworkAccess,
    private val bookBackups: LocalBookBackupRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WebDavUiState())
    val state: StateFlow<WebDavUiState> = _state.asStateFlow()

    val messages = MessageBus()

    private var authJob: Job? = null
    private var cooldownJob: Job? = null
    private var quotaJob: Job? = null
    private var bookFilesJob: Job? = null

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
                quotaLoading = c.isOfficialConnected,
            )
            refreshQuota()
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
        quotaJob?.cancel()
        viewModelScope.launch {
            official.switchToCustomKeepingDav()
            _state.update {
                it.copy(
                    provider = WebDavProvider.CUSTOM,
                    officialConnected = false,
                    officialEmail = "",
                    quota = null,
                    quotaLoading = false,
                    quotaAppPasswordInvalid = false,
                    showOfficialReauth = false,
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
        UsageLog.event("ui.click", "action" to "webdav.send_code")
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
        if (s.busy || (s.officialConnected && !s.showOfficialReauth)) return
        val reauth = s.showOfficialReauth
        UsageLog.event("ui.click", "action" to if (reauth) "webdav.reauthorize" else "webdav.connect")
        runAuth {
            val email = s.email.trim()
            val code = s.code.trim()
            val result = if (reauth) official.reauthorizeWithCode(email, code) else official.loginWithCode(email, code)
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
                        quotaAppPasswordInvalid = false,
                        showOfficialReauth = false,
                    )
                }
                refreshQuota()
                messages.emit(if (reauth) "已重新验证官方账号" else "已开启官方同步")
            } else {
                messages.emit(result.exceptionOrNull()?.message ?: "操作失败")
            }
        }
    }

    fun dismissOfficialReauth() = _state.update {
        if (it.busy) it else it.copy(showOfficialReauth = false, code = "")
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
                refreshQuota()
                messages.emit("已生成新应用码，旧码已失效")
            } else {
                if (result.exceptionOrNull() is OfficialSessionExpiredException) {
                    _state.update { it.copy(showOfficialReauth = true, email = it.officialEmail, code = "") }
                } else {
                    messages.emit(result.exceptionOrNull()?.message ?: "生成失败")
                }
            }
        }
    }

    fun askDisconnect() = _state.update { it.copy(confirmDisconnect = true) }
    fun dismissDisconnect() = _state.update { it.copy(confirmDisconnect = false) }

    fun disconnectOfficial() {
        UsageLog.event("ui.click", "action" to "webdav.disconnect")
        _state.update { it.copy(confirmDisconnect = false) }
        quotaJob?.cancel()
        viewModelScope.launch {
            official.disconnect()
            _state.update {
                it.copy(
                    officialConnected = false,
                    officialEmail = "",
                    quota = null,
                    quotaLoading = false,
                    quotaAppPasswordInvalid = false,
                    showOfficialReauth = false,
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
        UsageLog.event("ui.click", "action" to "webdav.test_save")
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
        UsageLog.event("ui.click", "action" to "webdav.sync")
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
            refreshQuota()
            messages.emit(result.getOrElse { "同步失败：" + describeSyncError(it) })
        }
    }

    fun refreshQuota() {
        if (!_state.value.officialConnected) return
        quotaJob?.cancel()
        _state.update { it.copy(quotaLoading = true) }
        quotaJob = viewModelScope.launch {
            val dav = repo.officialQuota()
            val result = if (dav.isSuccess && dav.getOrNull() == null) official.storageQuota() else dav
            val invalid = (dav.exceptionOrNull() as? WebDavClient.WebDavException)?.code == 401
            val quota = result.getOrNull()
            val outcome = when {
                invalid -> "invalid_app_password"
                quota != null -> "ok"
                else -> "unavailable"
            }
            UsageLog.event("webdav.quota", "outcome" to outcome)
            _state.update { it.copy(quota = quota, quotaLoading = false, quotaAppPasswordInvalid = invalid) }
        }
    }

    fun openBookFiles() {
        val s = _state.value
        if (s.busy || !s.configured) return
        loadBookFiles(restore = false)
    }

    fun closeBookFiles() {
        if (_state.value.bookFiles?.transferring == true) return
        bookFilesJob?.cancel()
        dismissLocalNetwork()
        _state.update { it.copy(bookFiles = null) }
    }

    fun loadBookFiles(restore: Boolean, syncShelf: Boolean = false) {
        if (_state.value.bookFiles?.transferring == true) return
        bookFilesJob?.cancel()
        _state.update { it.copy(bookFiles = BookFilesUiState(restore = restore)) }
        bookFilesJob = viewModelScope.launch {
            try {
                if (syncShelf) {
                    repo.sync().getOrThrow()
                    updateSyncStatus()
                }
                val items = bookBackups.list(restore)
                updateBookFiles { it.copy(items = items, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateBookFiles { it.copy(loading = false, error = describeSyncError(e)) }
                if (e is LocalNetworkPermissionMissing) {
                    askLocalNetwork { loadBookFiles(restore, syncShelf) }
                }
            }
        }
    }

    fun selectBookFile(item: BookFileBackupItem) = updateBookFiles { page ->
        if (page.transferring || item.key in page.results && page.results[item.key] == null) {
            return@updateBookFiles page
        }
        val selection = if (item.key in page.selected) {
            page.selected - item.key
        } else {
            val otherVersions = page.items.filter { it.bookId == item.bookId }.map { it.key }
            page.selected - otherVersions.toSet() + item.key
        }
        page.copy(selected = selection)
    }

    fun selectAllBookFiles() = updateBookFiles { page ->
        if (page.transferring) return@updateBookFiles page
        val keys = page.items.filter { it.key !in page.results || page.results[it.key] != null }
            .groupBy { it.bookId }
            .values.mapNotNull { versions -> versions.firstOrNull { it.key in page.selected } ?: versions.singleOrNull() }
            .map { it.key }.toSet()
        page.copy(selected = if (keys == page.selected) emptySet() else keys)
    }

    fun transferBookFiles() {
        val page = _state.value.bookFiles ?: return
        val selection = page.items.filter { it.key in page.selected }
        if (page.loading || page.transferring || selection.isEmpty()) return
        updateBookFiles { it.copy(transferring = true, error = null) }
        bookFilesJob = viewModelScope.launch {
            try {
                bookBackups.transfer(page.restore, selection) { item, error ->
                    updateBookFiles { current ->
                        val discardedKeys = if (page.restore && error == null) {
                            current.items.filter { it.bookId == item.bookId && it.key != item.key }.map { it.key }.toSet()
                        } else {
                            emptySet()
                        }
                        current.copy(
                            items = current.items.filterNot { it.key in discardedKeys },
                            results = (current.results - discardedKeys) + (item.key to error),
                            selected = if (error == null) current.selected - item.key else current.selected,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateBookFiles { it.copy(error = describeSyncError(e)) }
                if (e is LocalNetworkPermissionMissing) askLocalNetwork(then = ::transferBookFiles)
            } finally {
                updateBookFiles { it.copy(transferring = false) }
                if (!page.restore) refreshQuota()
            }
            if (!page.restore) updateSyncStatus()
        }
    }

    fun cancelBookFileTransfer() {
        if (_state.value.bookFiles?.transferring == true) bookFilesJob?.cancel()
    }

    private suspend fun updateSyncStatus() {
        val config = prefs.config.first()
        _state.update {
            it.copy(
                lastSyncAt = config.lastSyncAt,
                lastSyncError = config.lastSyncError,
                lastSyncErrorAt = config.lastSyncErrorAt,
            )
        }
    }

    private fun updateBookFiles(update: (BookFilesUiState) -> BookFilesUiState) {
        _state.update { it.copy(bookFiles = it.bookFiles?.let(update)) }
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
