package com.pocketgateway.app.core.store

import com.pocketgateway.app.core.model.RequestLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque

/** 内存环形日志缓冲。日志仅存在于本次运行期间，不落盘（避免敏感信息泄露）。 */
class LogStore(private val maxEntries: () -> Int) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deque = ConcurrentLinkedDeque<RequestLog>()
    private val _logs = MutableStateFlow<List<RequestLog>>(emptyList())
    val logs: StateFlow<List<RequestLog>> = _logs.asStateFlow()

    private val _total = MutableStateFlow(0L)
    val total: StateFlow<Long> = _total.asStateFlow()

    private val _success = MutableStateFlow(0L)
    val success: StateFlow<Long> = _success.asStateFlow()

    fun push(log: RequestLog) {
        deque.addLast(log)
        val limit = maxEntries().coerceAtLeast(10)
        while (deque.size > limit) deque.pollFirst()
        _logs.value = deque.toList().asReversed() // 最新在前
        scope.launch {
            _total.value += 1
            if (log.success) _success.value += 1
        }
    }

    fun clear() {
        deque.clear()
        _logs.value = emptyList()
        _total.value = 0
        _success.value = 0
    }
}
