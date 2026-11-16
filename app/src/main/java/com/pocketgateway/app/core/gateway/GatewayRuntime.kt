package com.pocketgateway.app.core.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网关运行状态（进程内全局单例）。
 *
 * 前台服务在启动/停止/销毁时更新，界面据此显示明确的「运行中 / 已停止」状态，
 * 避免用户点了启动却不知道是否真的在跑。
 */
object GatewayRuntime {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 最近一次状态变化的说明（如错误原因）。 */
    private val _lastMessage = MutableStateFlow("")
    val lastMessage: StateFlow<String> = _lastMessage.asStateFlow()

    /** 监听端口，便于界面显示。 */
    private val _port = MutableStateFlow(0)
    val port: StateFlow<Int> = _port.asStateFlow()

    fun markRunning(port: Int) {
        _port.value = port
        _lastMessage.value = "服务已启动，监听 127.0.0.1:$port"
        _running.value = true
    }

    fun markStopped(reason: String = "服务已停止") {
        _lastMessage.value = reason
        _running.value = false
    }

    fun markError(reason: String) {
        _lastMessage.value = reason
        _running.value = false
    }
}
