package com.pocketgateway.app.core.gateway

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * 渠道冷却器（反封禁）。
 *
 * 上游返回 429（限流）或 403（封禁）时，把该渠道临时标记为冷却，
 * 冷却期内不再路由请求到它，避免持续撞风控导致账号被进一步限制。
 * 冷却时长随连续失败次数递增（指数退避 + 随机抖动）；成功响应后立即清除。
 * 所有状态仅存内存，进程重启自动复位。
 */
class ChannelCooldown {

    private val mutex = Mutex()
    private data class State(val until: Long = 0L, val strikes: Int = 0)
    private val states = mutableMapOf<String, State>()

    /** 渠道当前是否处于冷却中。 */
    suspend fun isCooling(channelId: String): Boolean = mutex.withLock {
        (states[channelId]?.until ?: 0L) > System.currentTimeMillis()
    }

    /** 记录一次限流/封禁，返回本次冷却的毫秒数。 */
    suspend fun penalize(channelId: String, status: Int): Long = mutex.withLock {
        val now = System.currentTimeMillis()
        val prev = states[channelId] ?: State()
        val strikes = (prev.strikes + 1).coerceAtMost(6)
        val baseMs = when (status) {
            403 -> 300_000L // 疑似封禁：5 分钟起
            429 -> 60_000L  // 限流：1 分钟起
            else -> 30_000L
        }
        val backoff = (baseMs * (1 shl (strikes - 1))).coerceAtMost(30 * 60_000L)
        val jitter = (backoff * 0.2).toLong()
        val duration = (backoff - jitter + Random.nextLong(0, 2 * jitter + 1))
            .coerceAtLeast(1_000L)
        states[channelId] = State(until = now + duration, strikes = strikes)
        duration
    }

    /** 渠道成功响应后清除冷却与失败计数。 */
    suspend fun clear(channelId: String) = mutex.withLock {
        states.remove(channelId)
        Unit
    }

    /** 剩余冷却毫秒数（0 表示未冷却）。 */
    suspend fun remainingMs(channelId: String): Long = mutex.withLock {
        ((states[channelId]?.until ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    }
}
