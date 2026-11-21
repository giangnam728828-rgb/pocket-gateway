package com.pocketgateway.app.core.gateway

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * 请求节流与模型级冷却。
 *
 * - 同一渠道串行发送并插入 150–400ms 抖动：避免突发请求被上游判为异常渠道
 *   （11128 的已知诱因之一就是同秒多请求）；
 * - 上游返回 11102（模型不可用/被限制）时，对该渠道 + 模型做短期负缓存，
 *   后续选路直接跳过，避免连续撞同一错误。
 */
object RequestThrottle {

    /** 每渠道每分钟最多 30 次上游请求（超出直接本地 429，避免爆发触发风控）。 */
    private const val LIMIT_PER_MINUTE = 30

    private val mapMutex = Mutex()
    private val channelLocks = mutableMapOf<String, Mutex>()
    private val lastAt = mutableMapOf<String, Long>()
    private val modelUntil = mutableMapOf<String, Long>()
    private val windowStart = mutableMapOf<String, Long>()
    private val windowCount = mutableMapOf<String, Int>()

    /**
     * 速率闸门。返回 0 表示放行；>0 表示本地限流，应告知客户端稍后重试。
     * 与 [before] 配合：先 acquire 再 before。
     */
    suspend fun acquire(channelId: String): Long = mapMutex.withLock {
        val now = System.currentTimeMillis()
        val start = windowStart[channelId] ?: now
        if (now - start >= 60_000L) {
            windowStart[channelId] = now
            windowCount[channelId] = 1
            return@withLock 0L
        }
        val used = windowCount[channelId] ?: 0
        if (used >= LIMIT_PER_MINUTE) {
            60_000L - (now - start)
        } else {
            windowCount[channelId] = used + 1
            0L
        }
    }

    /** 请求前调用：同渠道串行 + 随机间隔。 */
    suspend fun before(channelId: String) {
        val lock = mapMutex.withLock {
            channelLocks.getOrPut(channelId) { Mutex() }
        }
        lock.withLock {
            val now = System.currentTimeMillis()
            val gap = Random.nextLong(150, 400)
            val last = lastAt[channelId] ?: 0L
            val wait = (gap - (now - last)).coerceAtLeast(0L)
            if (wait > 0) delay(wait)
            lastAt[channelId] = System.currentTimeMillis()
        }
    }

    suspend fun isModelCooling(channelId: String, model: String?): Boolean = mapMutex.withLock {
        val m = model?.trim().orEmpty()
        if (m.isEmpty()) return@withLock false
        (modelUntil[key(channelId, m)] ?: 0L) > System.currentTimeMillis()
    }

    suspend fun penalizeModel(channelId: String, model: String?, durationMs: Long = 60_000L) =
        mapMutex.withLock {
            val m = model?.trim().orEmpty()
            if (m.isEmpty()) return@withLock
            modelUntil[key(channelId, m)] =
                System.currentTimeMillis() + durationMs.coerceIn(10_000L, 30 * 60_000L)
        }

    suspend fun clearModel(channelId: String, model: String?) = mapMutex.withLock {
        val m = model?.trim().orEmpty()
        if (m.isEmpty()) return@withLock
        modelUntil.remove(key(channelId, m))
        Unit
    }

    private fun key(channelId: String, model: String) = "$channelId|$model"
}
