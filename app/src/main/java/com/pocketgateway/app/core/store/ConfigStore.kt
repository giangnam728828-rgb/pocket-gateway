package com.pocketgateway.app.core.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.GatewayConfig
import com.pocketgateway.app.util.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gateway_config")

/**
 * 配置仓库：把 [GatewayConfig] 序列化为 JSON 存入 DataStore。
 * 同时维护一份内存中的 StateFlow，UI 与网关引擎都读它。
 */
class ConfigStore(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val keyConfig = stringPreferencesKey("config_json")

    private val _config = MutableStateFlow(GatewayConfig())
    val config: StateFlow<GatewayConfig> = _config.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** 首次配置加载（DataStore 读取）完成后置为 true。 */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** 供需要一次性读取最新配置的调用方使用。 */
    val configFlow: Flow<GatewayConfig> = _config

    init {
        scope.launch {
            val stored = runCatching {
                context.dataStore.data.first()[keyConfig]?.let {
                    AppJson.decodeFromString<GatewayConfig>(it)
                }
            }.getOrNull()
            _config.value = normalize(stored ?: GatewayConfig())
            _ready.value = true
        }
    }

    /**
     * 配置迁移/归一化：
     * APP 直连渠道的 baseUrl 一律强制为官方域名，避免历史版本存错的地址
     * 导致 404 / 302（旧版曾把 baseUrl 存空或存成其它域）。
     */
    private fun normalize(cfg: GatewayConfig): GatewayConfig {
        val fixed = cfg.channels.map { ch ->
            var out = ch
            when {
                ch.type == com.pocketgateway.app.core.model.ProviderType.APP_NATIVE -> {
                    val global = ch.baseUrl.contains("workbuddy.ai", true) || ch.name.contains("国际")
                    val base = if (global) "https://www.workbuddy.ai" else "https://copilot.tencent.com"
                    if (ch.baseUrl != base) out = out.copy(baseUrl = base)
                }
                ch.type == com.pocketgateway.app.core.model.ProviderType.TRAE && ch.baseUrl.isBlank() ->
                    out = out.copy(baseUrl = "https://trae-api-cn.mchost.guru")
            }

            // 同一客户端可能有多个账号：旧渠道名没有区分标识时，补一次稳定的 ID 后缀。
            // 只追加一次（名称里已有 ID: 或该后缀时跳过），不会反复改用户名称。
            if (out.type == com.pocketgateway.app.core.model.ProviderType.APP_NATIVE ||
                out.type == com.pocketgateway.app.core.model.ProviderType.TRAE
            ) {
                val identity = out.appKind.ifBlank { out.apiKey.takeLast(6) }
                if (identity.length >= 4) {
                    val suffix = identity.takeLast(6)
                    if (!out.name.contains("ID:") && !out.name.contains(suffix)) {
                        out = out.copy(name = "${out.name} · ID:$suffix")
                    }
                }
            }
            out
        }
        return if (fixed == cfg.channels) cfg else cfg.copy(channels = fixed)
    }

    /** 等待初始化完成。 */
    suspend fun awaitReady() {
        // DataStore 读取在 init 中已 launch；这里简单等待 config 流首次有真实值。
        // 通过一次直接读取确保已落库/已加载。
        context.dataStore.data.first()
    }

    suspend fun update(transform: (GatewayConfig) -> GatewayConfig) = withContext(Dispatchers.Default) {
        mutex.withLock {
            val next = transform(_config.value)
            _config.value = next
            persist(next)
        }
    }

    suspend fun replace(next: GatewayConfig) = withContext(Dispatchers.Default) {
        mutex.withLock {
            _config.value = next
            persist(next)
        }
    }

    private suspend fun persist(config: GatewayConfig) {
        val json = AppJson.encodeToString(config)
        context.dataStore.edit { it[keyConfig] = json }
    }

    // ---- 渠道快捷操作 ----

    suspend fun upsertChannel(channel: Channel) = update { cfg ->
        val list = cfg.channels.toMutableList()
        val idx = list.indexOfFirst { it.id == channel.id }
        if (idx >= 0) list[idx] = channel else list.add(channel)
        cfg.copy(channels = list)
    }

    suspend fun deleteChannel(id: String) = update { cfg ->
        cfg.copy(channels = cfg.channels.filterNot { it.id == id })
    }

    suspend fun setChannelEnabled(id: String, enabled: Boolean) = update { cfg ->
        cfg.copy(channels = cfg.channels.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    // ---- 导入导出 ----

    fun exportJson(): String = AppJson.encodeToString(_config.value)

    suspend fun importJson(json: String): Result<Unit> = runCatching {
        val parsed = AppJson.decodeFromString<GatewayConfig>(json)
        replace(parsed)
    }
}
