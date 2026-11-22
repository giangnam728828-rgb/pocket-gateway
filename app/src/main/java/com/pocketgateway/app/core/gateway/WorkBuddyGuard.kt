package com.pocketgateway.app.core.gateway

import com.pocketgateway.app.core.model.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * WorkBuddy 账号级防风控闸门。
 *
 * 现有 [RequestThrottle] 只保证「两次请求的开始时间」有间隔，并不能阻止
 * 并发请求同时在跑；而 WorkBuddy 对同一账号的并发会话比较敏感。
 * 这里补上：
 *
 *  - **账号级串行**：同一账号同一时刻只允许一个对话请求；
 *  - **随机间隔**：1.5–4.0 秒抖动，避免机器节奏；
 *  - **排队上限**：等待超过 90 秒直接失败，避免请求堆积；
 *  - **风控冷却**：命中 11128 / 额度不足 / 429 / 403 时，冷却期内完全
 *    不再向上游发请求，防止持续撞风控。
 */
object WorkBuddyGuard {

    private const val CHAT_MIN_GAP_MS = 1_500L
    private const val CHAT_JITTER_MS = 2_500L
    private const val ACQUIRE_TIMEOUT_MS = 90_000L

    private val stateMutex = Mutex()
    private val chatLocks = mutableMapOf<String, Mutex>()
    private val chatLastAt = mutableMapOf<String, Long>()
    private val riskUntil = mutableMapOf<String, Long>()

    private fun accountKey(ch: Channel): String =
        ch.appKind.ifBlank { ch.id }

    /** 一次账号占用租约；[release] 可重复调用。 */
    class Lease internal constructor(
        private val lock: Mutex,
        private val released: AtomicBoolean = AtomicBoolean(false),
    ) {
        fun release() {
            if (released.compareAndSet(false, true)) {
                runCatching { lock.unlock() }
            }
        }
    }

    /** 取得账号独占权，并保证与上一次请求的随机间隔。 */
    suspend fun leaseChat(ch: Channel): Lease {
        val key = accountKey(ch)
        val lock = stateMutex.withLock { chatLocks.getOrPut(key) { Mutex() } }
        withTimeoutOrNull(ACQUIRE_TIMEOUT_MS) { lock.lock() }
            ?: throw IllegalStateException("WorkBuddy 账号请求排队超时（防风控串行中），请稍后重试")

        try {
            val wait = stateMutex.withLock {
                val now = System.currentTimeMillis()
                val gap = CHAT_MIN_GAP_MS + Random.nextLong(0, CHAT_JITTER_MS)
                val last = chatLastAt[key] ?: 0L
                val w = (gap - (now - last)).coerceAtLeast(0L)
                chatLastAt[key] = now + w
                w
            }
            if (wait > 0) delay(wait)
        } catch (e: Exception) {
            runCatching { lock.unlock() }
            throw e
        }
        return Lease(lock)
    }

    /** 记录一次风控冷却；冷却期内网关不再向该账号发请求。 */
    suspend fun markRisk(ch: Channel, durationMs: Long) {
        val key = accountKey(ch)
        val until = System.currentTimeMillis() +
            durationMs.coerceIn(30_000L, 30 * 60_000L)
        stateMutex.withLock {
            riskUntil[key] = maxOf(riskUntil[key] ?: 0L, until)
        }
    }

    /** 剩余风控冷却毫秒数；0 表示可请求。 */
    suspend fun riskRemaining(ch: Channel): Long = stateMutex.withLock {
        ((riskUntil[accountKey(ch)] ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** 成功完成后清除风控冷却。 */
    suspend fun clearRisk(ch: Channel) {
        stateMutex.withLock { riskUntil.remove(accountKey(ch)) }
    }
}
