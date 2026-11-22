package com.pocketgateway.app.core.appaccount

import android.content.Context
import com.pocketgateway.app.core.model.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * TRAE 账号级防风控闸门。
 *
 * 在原有「串行 + 随机间隔 + 冷却」基础上继续加强：
 *
 *  - **账号级串行**：同一账号同一时刻只允许一个会话请求；
 *  - **全局并发上限**：所有 TRAE 账号合计最多 2 个请求同时在跑，避免多账号同时爆发；
 *  - **新账号热身**：每个账号前 10 次请求使用 6–10 秒更长间隔；
 *  - **每日上限**：每账号每天最多 300 次会话请求，超过后本地直接拒绝；
 *  - **冷却持久化**：风控冷却与刷新失败冷却写入 SharedPreferences，重启后仍生效；
 *  - **刷新单飞**：同一账号只跑一个 ExchangeToken，失败冷却 10 分钟。
 *
 * 所有计数按「账号」聚合（appKind/uid），同一账号的多个渠道共享额度。
 * 状态仅用于本地保护，不上报任何第三方。
 */
object TraeGuard {

    private const val CHAT_MIN_GAP_MS = 2_500L
    private const val CHAT_JITTER_MS = 2_000L
    private const val WARMUP_MIN_GAP_MS = 6_000L
    private const val WARMUP_JITTER_MS = 4_000L
    private const val WARMUP_REQUESTS = 10
    private const val BG_MIN_GAP_MS = 1_200L
    private const val BG_JITTER_MS = 800L
    private const val ACQUIRE_TIMEOUT_MS = 90_000L
    private const val REFRESH_FAIL_COOLDOWN_MS = 10 * 60_000L
    private const val DAILY_LIMIT = 1500
    private const val HOURLY_LIMIT = 300
    private const val GLOBAL_CONCURRENCY = 10

    private val stateMutex = Mutex()
    private val chatLocks = ConcurrentHashMap<String, Mutex>()
    private val chatLastAt = ConcurrentHashMap<String, Long>()
    private val chatCounts = ConcurrentHashMap<String, Int>()
    private val dailyCounts = ConcurrentHashMap<String, Pair<String, Int>>()
    private val hourlyCounts = ConcurrentHashMap<String, Pair<Long, Int>>()
    private val bgLastAt = mutableMapOf<String, Long>()
    private val refreshLocks = mutableMapOf<String, Mutex>()
    private val refreshBlockedUntil = mutableMapOf<String, Long>()
    private val riskUntil = mutableMapOf<String, Long>()
    private val globalPermits = Semaphore(GLOBAL_CONCURRENCY)

    private var store: TraeGuardStore? = null

    /** 在 Application 启动时调用，启用跨进程重启的持久化保护。 */
    fun init(context: Context) {
        store = runCatching { TraeGuardStore(context.applicationContext) }.getOrNull()
    }

    private fun accountKey(ch: Channel): String =
        ch.appKind.ifBlank { ch.id }

    private fun today(): String = LocalDate.now().toString()

    private fun hourBucket(): Long = System.currentTimeMillis() / 3_600_000L

    private fun dailyCount(key: String): Int =
        store?.dailyCount(key) ?: dailyCounts[key]
            ?.takeIf { it.first == today() }?.second ?: 0

    private fun hourlyCount(key: String): Int =
        store?.hourlyCount(key) ?: hourlyCounts[key]
            ?.takeIf { it.first == hourBucket() }?.second ?: 0

    private fun totalChatCount(key: String): Int =
        chatCounts[key] ?: store?.totalChatCount(key) ?: 0

    private suspend fun recordChat(key: String, startAt: Long, total: Int) {
        val day = today()
        val hour = hourBucket()
        val nextDaily = dailyCount(key) + 1
        val nextHourly = hourlyCount(key) + 1
        stateMutex.withLock {
            chatLastAt[key] = startAt
            chatCounts[key] = total
            dailyCounts[key] = day to nextDaily
            hourlyCounts[key] = hour to nextHourly
        }
        store?.recordChat(key, startAt, total, nextDaily, nextHourly)
    }

    /** 一次账号占用租约；[release] 可重复调用，同时归还全局并发名额。 */
    class Lease internal constructor(
        private val lock: Mutex,
        private val permit: Semaphore,
        private val released: AtomicBoolean = AtomicBoolean(false),
    ) {
        fun release() {
            if (released.compareAndSet(false, true)) {
                runCatching { lock.unlock() }
                runCatching { permit.release() }
            }
        }
    }

    /**
     * 取得该账号的对话独占权，并保证随机间隔 / 热身间隔 / 每日上限 / 全局并发。
     * 等待超过 90 秒会抛异常，由网关转为可读错误。
     */
    suspend fun leaseChat(ch: Channel): Lease {
        val key = accountKey(ch)
        val usedToday = dailyCount(key)
        if (usedToday >= DAILY_LIMIT) {
            throw IllegalStateException(
                "TRAE 账号今日请求已达安全上限（$DAILY_LIMIT 次），请明日再试"
            )
        }
        if (hourlyCount(key) >= HOURLY_LIMIT) {
            throw IllegalStateException(
                "TRAE 账号本小时请求已达安全上限（$HOURLY_LIMIT 次），请稍后重试"
            )
        }

        val acquired = withTimeoutOrNull(ACQUIRE_TIMEOUT_MS) { globalPermits.acquire() }
            ?: throw IllegalStateException("TRAE 全局并发已满（防风控限制），请稍后重试")

        val lock = stateMutex.withLock { chatLocks.getOrPut(key) { Mutex() } }
        val accountLocked = withTimeoutOrNull(ACQUIRE_TIMEOUT_MS) { lock.lock() }
        if (accountLocked == null) {
            runCatching { globalPermits.release() }
            throw IllegalStateException("TRAE 账号请求排队超时（防风控串行中），请稍后重试")
        }

        try {
            val total = totalChatCount(key)
            val warmup = total < WARMUP_REQUESTS
            val gap = if (warmup) {
                WARMUP_MIN_GAP_MS + Random.nextLong(0, WARMUP_JITTER_MS)
            } else {
                CHAT_MIN_GAP_MS + Random.nextLong(0, CHAT_JITTER_MS)
            }
            val now = System.currentTimeMillis()
            val last = chatLastAt[key] ?: store?.lastChatAt(key) ?: 0L
            val wait = (gap - (now - last)).coerceAtLeast(0L)
            recordChat(key, now + wait, total + 1)
            if (wait > 0) delay(wait)
        } catch (e: Exception) {
            runCatching { lock.unlock() }
            runCatching { globalPermits.release() }
            throw e
        }

        return Lease(lock, globalPermits)
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
        val until = System.currentTimeMillis() + durationMs.coerceIn(10_000L, 30 * 60_000L)
        stateMutex.withLock {
            riskUntil[key] = maxOf(riskUntil[key] ?: 0L, until)
        }
        store?.setRiskUntil(key, until)
    }

    /** 剩余风控冷却毫秒数；0 表示可请求。 */
    suspend fun riskRemaining(ch: Channel): Long {
        val key = accountKey(ch)
        val memory = stateMutex.withLock { riskUntil[key] ?: 0L }
        val persisted = store?.riskUntil(key) ?: 0L
        return (maxOf(memory, persisted) - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** 成功完成后清除风控冷却。 */
    suspend fun clearRisk(ch: Channel) {
        val key = accountKey(ch)
        stateMutex.withLock { riskUntil.remove(key) }
        store?.clearRisk(key)
    }

    /**
     * Token 刷新单飞：同一账号只允许一个刷新，失败后进入 10 分钟冷却。
     * [block] 返回 null 视为刷新失败。
     */
    suspend fun <T : Any> withRefresh(ch: Channel, block: suspend () -> T?): T? {
        val key = accountKey(ch)
        val lock = stateMutex.withLock { refreshLocks.getOrPut(key) { Mutex() } }
        return lock.withLock {
            val now = System.currentTimeMillis()
            val blocked = stateMutex.withLock {
                (refreshBlockedUntil[key] ?: 0L) > now
            } || (store?.refreshBlockedUntil(key) ?: 0L) > now
            if (blocked) return@withLock null

            val result = block()
            if (result == null) {
                val until = System.currentTimeMillis() + REFRESH_FAIL_COOLDOWN_MS
                stateMutex.withLock { refreshBlockedUntil[key] = until }
                store?.setRefreshBlockedUntil(key, until)
            } else {
                stateMutex.withLock { refreshBlockedUntil.remove(key) }
                store?.clearRefreshBlocked(key)
            }
            result
        }
    }
}

/** TRAE 防风控状态的轻量持久化（SharedPreferences），重启后仍保留冷却与每日计数。 */
private class TraeGuardStore(context: Context) {

    private val prefs = context.getSharedPreferences("trae_guard", Context.MODE_PRIVATE)

    private fun today(): String = LocalDate.now().toString()

    private fun hourBucket(): Long = System.currentTimeMillis() / 3_600_000L

    fun lastChatAt(key: String): Long = prefs.getLong("last_$key", 0L)

    fun totalChatCount(key: String): Int = prefs.getInt("total_$key", 0)

    fun dailyCount(key: String): Int {
        if (prefs.getString("daily_date_$key", "") != today()) return 0
        return prefs.getInt("daily_$key", 0)
    }

    fun hourlyCount(key: String): Int {
        if (prefs.getLong("hour_bucket_$key", -1L) != hourBucket()) return 0
        return prefs.getInt("hour_$key", 0)
    }

    fun recordChat(key: String, lastAt: Long, total: Int, daily: Int, hourly: Int) {
        prefs.edit()
            .putLong("last_$key", lastAt)
            .putInt("total_$key", total)
            .putString("daily_date_$key", today())
            .putInt("daily_$key", daily)
            .putLong("hour_bucket_$key", hourBucket())
            .putInt("hour_$key", hourly)
            .apply()
    }

    fun riskUntil(key: String): Long = prefs.getLong("risk_$key", 0L)

    fun setRiskUntil(key: String, until: Long) {
        prefs.edit().putLong("risk_$key", until).apply()
    }

    fun clearRisk(key: String) {
        prefs.edit().remove("risk_$key").apply()
    }

    fun refreshBlockedUntil(key: String): Long = prefs.getLong("refresh_$key", 0L)

    fun setRefreshBlockedUntil(key: String, until: Long) {
        prefs.edit().putLong("refresh_$key", until).apply()
    }

    fun clearRefreshBlocked(key: String) {
        prefs.edit().remove("refresh_$key").apply()
    }
}
