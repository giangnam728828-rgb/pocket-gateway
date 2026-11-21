package com.pocketgateway.app.core.appaccount

import com.pocketgateway.app.core.model.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * TRAE 账号级防风控闸门。
 *
 * TRAE 是面向个人用户的 IDE/网页产品，上游对「同一账号的请求节奏」比较敏感。
 * 这里从行为层面降低被判定为脚本/滥用的概率：
 *
 *  - **账号级串行**：同一账号同一时刻只允许一个会话请求在跑，模拟人工一次一问；
 *  - **随机间隔**：两次请求之间保持 2.5–4.5 秒随机抖动，避免固定毫秒节奏；
 *  - **排队上限**：等待超过 90 秒直接失败，防止请求无限堆积后集中爆发；
 *  - **后台请求轻节流**：模型目录等后台请求单独限速，不与对话抢通道；
 *  - **刷新单飞 + 冷却**：Token 刷新同一账号只跑一个，失败后 10 分钟内不再反复
 *    触发 ExchangeToken，避免登录态被安全策略盯上。
 *
 * 所有状态只存内存，进程重启自动复位。
 */
object TraeGuard {

    private const val CHAT_MIN_GAP_MS = 2_500L
    private const val CHAT_JITTER_MS = 2_000L
    private const val BG_MIN_GAP_MS = 1_200L
    private const val BG_JITTER_MS = 800L
    private const val ACQUIRE_TIMEOUT_MS = 90_000L
    private const val REFRESH_FAIL_COOLDOWN_MS = 10 * 60_000L

    private val stateMutex = Mutex()
    private val chatLocks = mutableMapOf<String, Mutex>()
    private val chatLastAt = mutableMapOf<String, Long>()
    private val bgLastAt = mutableMapOf<String, Long>()
    private val refreshLocks = mutableMapOf<String, Mutex>()
    private val refreshBlockedUntil = mutableMapOf<String, Long>()
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

    /**
     * 取得该账号的对话独占权，并保证与上一次请求的随机间隔。
     * 等待超过 90 秒会抛异常，由网关转为可读错误，避免请求无限堆积。
     */
    suspend fun leaseChat(ch: Channel): Lease {
        val key = accountKey(ch)
        val lock = stateMutex.withLock { chatLocks.getOrPut(key) { Mutex() } }
        withTimeoutOrNull(ACQUIRE_TIMEOUT_MS) { lock.lock() }
            ?: throw IllegalStateException("TRAE 账号请求排队超时（防风控串行中），请稍后重试")

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

    /** 后台请求（模型目录等）的轻量节流：只保证最小间隔，不独占账号。 */
    suspend fun paceBackground(ch: Channel) {
        val key = accountKey(ch)
        val wait = stateMutex.withLock {
            val now = System.currentTimeMillis()
            val gap = BG_MIN_GAP_MS + Random.nextLong(0, BG_JITTER_MS)
            val last = bgLastAt[key] ?: 0L
            val w = (gap - (now - last)).coerceAtLeast(0L)
            bgLastAt[key] = now + w
            w
        }
        if (wait > 0) delay(wait)
    }

    /** 记录一次风控冷却；冷却期内 [TraeApi] 不再向该账号发任何请求。 */
    suspend fun markRisk(ch: Channel, durationMs: Long) {
        val key = accountKey(ch)
        val until = System.currentTimeMillis() +
            durationMs.coerceIn(10_000L, 30 * 60_000L)
        stateMutex.withLock {
            riskUntil[key] = maxOf(riskUntil[key] ?: 0L, until)
        }
    }

    /** 剩余风控冷却毫秒数；0 表示可请求。 */
    suspend fun riskRemaining(ch: Channel): Long = stateMutex.withLock {
        ((riskUntil[accountKey(ch)] ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** 重新登录/换号成功后清除该账号的风控与刷新失败状态。 */
    suspend fun resetAccountState(ch: Channel) {
        val key = accountKey(ch)
        stateMutex.withLock {
            riskUntil.remove(key)
            refreshBlockedUntil.remove(key)
        }
    }

    /**
     * Token 刷新单飞：同一账号只允许一个刷新，失败后进入 10 分钟冷却。
     * [block] 返回 null 视为刷新失败。
     */
    suspend fun <T : Any> withRefresh(ch: Channel, block: suspend () -> T?): T? {
        val key = accountKey(ch)
        val lock = stateMutex.withLock { refreshLocks.getOrPut(key) { Mutex() } }
        return lock.withLock {
            val blocked = stateMutex.withLock {
                (refreshBlockedUntil[key] ?: 0L) > System.currentTimeMillis()
            }
            if (blocked) return@withLock null
            val result = block()
            if (result == null) {
                stateMutex.withLock {
                    refreshBlockedUntil[key] = System.currentTimeMillis() + REFRESH_FAIL_COOLDOWN_MS
                }
            } else {
                stateMutex.withLock { refreshBlockedUntil.remove(key) }
            }
            result
        }
    }
}
