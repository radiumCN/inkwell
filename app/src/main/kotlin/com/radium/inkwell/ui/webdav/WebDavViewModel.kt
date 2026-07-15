package com.radium.inkwell.ui.webdav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.prefs.WebDavPrefs
import com.radium.inkwell.data.repo.WebDavRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class WebDavUiState(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val configured: Boolean = false,
    val busy: Boolean = false,
    val lastSyncAt: Long = 0,
    val autoSync: Boolean = true,
)

class WebDavViewModel(
    private val prefs: WebDavPrefs,
    private val repo: WebDavRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WebDavUiState())
    val state: StateFlow<WebDavUiState> = _state.asStateFlow()

    /** 一次性提示：与其他管理页一样走共享 MessageBus + CollectMessages */
    val messages = MessageBus()

    init {
        viewModelScope.launch {
            val c = prefs.config.first()
            _state.value = WebDavUiState(
                url = c.url, username = c.username, password = c.password,
                configured = c.isConfigured, lastSyncAt = c.lastSyncAt,
                autoSync = c.autoSync,
            )
        }
    }

    fun setAutoSync(on: Boolean) {
        _state.value = _state.value.copy(autoSync = on)
        viewModelScope.launch { prefs.setAutoSync(on) }
    }

    fun setUrl(v: String) = _state.value.let { _state.value = it.copy(url = v) }
    fun setUsername(v: String) = _state.value.let { _state.value = it.copy(username = v) }
    fun setPassword(v: String) = _state.value.let { _state.value = it.copy(password = v) }

    fun testAndSave() {
        val s = _state.value
        viewModelScope.launch {
            _state.value = s.copy(busy = true)
            val result = repo.testConnection(s.url, s.username, s.password)
            if (result.isSuccess) {
                prefs.save(s.url, s.username, s.password)
                _state.value = _state.value.copy(busy = false, configured = true)
                messages.emit("连接成功，已保存")
            } else {
                _state.value = _state.value.copy(busy = false)
                messages.emit("连接失败: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = repo.sync()
            _state.value = _state.value.copy(
                busy = false,
                lastSyncAt = if (result.isSuccess) System.currentTimeMillis() else _state.value.lastSyncAt,
            )
            messages.emit(result.getOrElse { "同步失败: ${it.message}" })
        }
    }
}
