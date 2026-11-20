package com.pocketgateway.app.core.scheduler

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pocketgateway.app.PocketGatewayApp
import com.pocketgateway.app.core.appaccount.WorkBuddyApi
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.GatewayConfig
import com.pocketgateway.app.core.model.ProviderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val PREFS_NAME = "pocket_gateway_scheduler"
private const val KEY_CHANNEL_ID = "channel_id"
private const val KEY_SCHEDULED_IDS = "scheduled_channel_ids"
private const val SCHEDULER_TAG = "checkin"

private fun uniqueCheckin(id: String) = "checkin_$id"
private fun uniqueTasks(id: String) = "tasks_$id"

private fun today(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

private fun errorMessage(t: Throwable): String =
    "异常：${t.message ?: t.javaClass.simpleName}"

/** 网络/HTTP 类错误可重试；业务失败不重试，避免无意义地反复撞接口。 */
private fun isTransient(msg: String): Boolean =
    msg.startsWith("HTTP") || msg.startsWith("异常")

private fun currentChannel(app: PocketGatewayApp, id: String): Channel? =
    app.configStore.config.value.channels.find {
        it.id == id && it.enabled && it.type == ProviderType.APP_NATIVE
    }

/**
 * 每日签到 + 任务奖励领取调度器（仅 APP_NATIVE 渠道）。
 *
 * 行为：
 * - 依据当前配置，为每个 enabled 的 APP_NATIVE 渠道注册两个周期性任务
 *   （签到 / 领奖励），周期 24h，要求联网；
 * - 渠道被停用或删除时自动取消对应的调度；
 * - 每个任务按自然日去重（SharedPreferences 记录当天是否已执行）；
 * - 执行结果写入 [com.pocketgateway.app.core.store.UsageDatabase]。
 *
 * 使用方式：随配置变化调用 [sync]（已在 PocketGatewayApp 中接入）。
 */
class CheckInScheduler(private val context: Context) {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 按当前配置同步调度：只保留 enabled 的 APP_NATIVE 渠道。 */
    fun sync(config: GatewayConfig) {
        val targets = config.channels.filter { it.enabled && it.type == ProviderType.APP_NATIVE }
        val desired = targets.map { it.id }.toSet()
        val wm = WorkManager.getInstance(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 取消已移除/停用渠道的调度
        val previous = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty()
        (previous - desired).forEach { oldId ->
            wm.cancelUniqueWork(uniqueCheckin(oldId))
            wm.cancelUniqueWork(uniqueTasks(oldId))
        }
        prefs.edit().putStringSet(KEY_SCHEDULED_IDS, desired).apply()

        targets.forEach { ch ->
            wm.enqueueUniquePeriodicWork(
                uniqueCheckin(ch.id),
                ExistingPeriodicWorkPolicy.UPDATE,
                checkinRequest(ch),
            )
            wm.enqueueUniquePeriodicWork(
                uniqueTasks(ch.id),
                ExistingPeriodicWorkPolicy.UPDATE,
                tasksRequest(ch),
            )
        }
        _running.value = targets.isNotEmpty()
    }

    /** 取消所有已注册的自动任务。 */
    fun stop() {
        val wm = WorkManager.getInstance(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty().forEach { id ->
            wm.cancelUniqueWork(uniqueCheckin(id))
            wm.cancelUniqueWork(uniqueTasks(id))
        }
        prefs.edit().remove(KEY_SCHEDULED_IDS).apply()
        _running.value = false
    }

    private fun checkinRequest(ch: Channel): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<CheckInWorker>(1, TimeUnit.DAYS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInputData(inputFor(ch))
            .addTag(SCHEDULER_TAG)
            .build()

    private fun tasksRequest(ch: Channel): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<TasksWorker>(1, TimeUnit.DAYS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInputData(inputFor(ch))
            .addTag(SCHEDULER_TAG)
            .build()

    private fun inputFor(ch: Channel): Data =
        Data.Builder().putString(KEY_CHANNEL_ID, ch.id).build()

    private fun networkConstraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    // ---- Worker 实现 ----

    /** 每日签到。 */
    class CheckInWorker(appContext: Context, params: WorkerParameters) :
        CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            val app = applicationContext as? PocketGatewayApp ?: return Result.failure()
            val channelId = inputData.getString(KEY_CHANNEL_ID) ?: return Result.failure()
            val ch = currentChannel(app, channelId) ?: return Result.success()

            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "checkin_last_$channelId"
            val today = today()
            if (prefs.getString(key, null) == today) return Result.success()

            val (ok, msg) = runCatching { WorkBuddyApi.dailyCheckin(ch) }
                .getOrElse { false to errorMessage(it) }
            runCatching {
                app.usageDb.recordAutoTask(channelId, ch.name, "daily_checkin", ok, msg)
            }

            return when {
                ok -> {
                    prefs.edit().putString(key, today).apply()
                    Result.success()
                }
                isTransient(msg) -> Result.retry()
                else -> {
                    prefs.edit().putString(key, today).apply()
                    Result.success()
                }
            }
        }
    }

    /** 一键领取可领任务奖励。 */
    class TasksWorker(appContext: Context, params: WorkerParameters) :
        CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            val app = applicationContext as? PocketGatewayApp ?: return Result.failure()
            val channelId = inputData.getString(KEY_CHANNEL_ID) ?: return Result.failure()
            val ch = currentChannel(app, channelId) ?: return Result.success()

            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "tasks_last_$channelId"
            val today = today()
            if (prefs.getString(key, null) == today) return Result.success()

            val (_, failCount, msg) = runCatching { WorkBuddyApi.claimAllTasks(ch) }
                .getOrElse { Triple(0, 1, errorMessage(it)) }
            val success = failCount == 0 && !isTransient(msg)
            runCatching {
                app.usageDb.recordAutoTask(channelId, ch.name, "claim_tasks", success, msg)
            }

            return when {
                success -> {
                    prefs.edit().putString(key, today).apply()
                    Result.success()
                }
                isTransient(msg) -> Result.retry()
                else -> {
                    prefs.edit().putString(key, today).apply()
                    Result.success()
                }
            }
        }
    }
}
