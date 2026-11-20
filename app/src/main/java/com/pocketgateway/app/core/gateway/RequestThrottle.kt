package com.pocketgateway.app.core.gateway

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * 请求节流与模型级冷却。
 *
 * - 同一渠道的请求之间插入 80–220ms 抖动，避免固定频率特征；
 * - 上游返回 11102（模型不可用/被限制）时，对该渠道 + 模型做短期负缓存，
 *   后续选路直接跳过，避免连续撞同一错误。
 */
object RequestThrottle {

    private val mutex = Mutex()
    private val lastAt = mutableMapOf<String, Long>()
    private val modelUntil = mutableMapOf<String, Long>()

    /** 请求前调用：保证同渠道最小间隔 + 随机抖动。 */
    suspend fun before(channelId: String) {
        val wait = mutex.withLock {
            val now = System.currentTimeMillis()
            val gap = Random.nextLong(80, 220)
            val last = lastAt[channelId] ?: 0L
            val w = (gap - (now - last)).coerceAtLeast(0L)
            lastAt[channelId] = now + w
            w
        }
        if (wait > 0) delay(wait)
    }

    suspend fun isModelCooling(channelId: String, model: String?): Boolean = mutex.withLock {
        val m = model?.trim().orEmpty()
        if (m.isEmpty()) return@withLock false
        (modelUntil[key(channelId, m)] ?: 0L) > System.currentTimeMillis()
    }

    suspend fun penalizeModel(channelId: String, model: String?, durationMs: Long = 60_000L) =
        mutex.withLock {
            val m = model?.trim().orEmpty()
            if (m.isEmpty()) return@withLock
            modelUntil[key(channelId, m)] =
                System.currentTimeMillis() + durationMs.coerceIn(10_000L, 30 * 60_000L)
        }

    suspend fun clearModel(channelId: String, model: String?) = mutex.withLock {
        val m = model?.trim().orEmpty()
        if (m.isEmpty()) return@withLock
        modelUntil.remove(key(channelId, m))
        Unit
    }

    private fun key(channelId: String, model: String) = "$channelId|$model"
}
