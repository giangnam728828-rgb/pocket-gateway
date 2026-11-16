package com.pocketgateway.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketgateway.app.PocketGatewayApp
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.GatewayConfig
import com.pocketgateway.app.core.model.RequestLog
import com.pocketgateway.app.core.store.ConfigStore
import com.pocketgateway.app.core.store.LogStore
import com.pocketgateway.app.service.GatewayService
import com.pocketgateway.app.util.buildBaseUrl
import com.pocketgateway.app.util.getLocalIpAddress
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GatewayViewModel(app: Application) : AndroidViewModel(app) {

    private val configStore: ConfigStore = (app as PocketGatewayApp).configStore
    private val logStore: LogStore = (app as PocketGatewayApp).logStore

    val config: StateFlow<GatewayConfig> = configStore.config
    val logs: StateFlow<List<RequestLog>> = logStore.logs
    val total: StateFlow<Long> = logStore.total
    val success: StateFlow<Long> = logStore.success

    /** 网关对局域网可用的 base URL。 */
    val baseUrl = config.map { cfg ->
        val ip = getLocalIpAddress(getApplication())
        buildBaseUrl(ip, cfg.port, cfg.listenOnAllInterfaces)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "http://127.0.0.1:8317/v1")

    val localIp = config.map { getLocalIpAddress(getApplication()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun startGateway() {
        val ctx = getApplication<Application>()
        ctx.startForegroundService(GatewayService.startIntent(ctx))
    }

    fun stopGateway() {
        val ctx = getApplication<Application>()
        ctx.startService(GatewayService.stopIntent(ctx))
    }

    fun upsertChannel(channel: Channel) = viewModelScope.launch {
        configStore.upsertChannel(channel)
    }

    fun deleteChannel(id: String) = viewModelScope.launch {
        configStore.deleteChannel(id)
    }

    fun setChannelEnabled(id: String, enabled: Boolean) = viewModelScope.launch {
        configStore.setChannelEnabled(id, enabled)
    }

    fun updateConfig(transform: (GatewayConfig) -> GatewayConfig) = viewModelScope.launch {
        configStore.update(transform)
    }

    fun clearLogs() = logStore.clear()

    fun exportConfig(): String = configStore.exportJson()

    fun importConfig(json: String, onResult: (Boolean, String) -> Unit) = viewModelScope.launch {
        configStore.importJson(json)
            .onSuccess { onResult(true, "导入成功") }
            .onFailure { onResult(false, "导入失败：${it.message}") }
    }
}
