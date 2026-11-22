package com.pocketgateway.app

import android.app.Application
import com.pocketgateway.app.core.appaccount.TraeGuard
import com.pocketgateway.app.core.gateway.WorkBuddyGuard
import com.pocketgateway.app.core.scheduler.CheckInScheduler
import com.pocketgateway.app.core.store.ConfigStore
import com.pocketgateway.app.core.store.LogStore
import com.pocketgateway.app.core.store.UsageDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 全局单例容器。 */
class PocketGatewayApp : Application() {

    lateinit var configStore: ConfigStore
        private set

    lateinit var logStore: LogStore
        private set

    lateinit var usageDb: UsageDatabase
        private set

    lateinit var checkInScheduler: CheckInScheduler
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(this)
        logStore = LogStore { configStore.config.value.maxLogEntries }
        usageDb = UsageDatabase(this)

        // TRAE / WorkBuddy 防风控状态持久化：冷却、每日/每小时计数跨进程重启保留
        runCatching { TraeGuard.init(this) }
        runCatching { WorkBuddyGuard.init(this) }

        // 等首次配置加载完成后再同步，避免用空配置误取消已注册的周期任务。
        checkInScheduler = CheckInScheduler(this)
        appScope.launch {
            configStore.ready.first { it }
            configStore.config.collect { cfg ->
                runCatching { checkInScheduler.sync(cfg) }
            }
        }
    }
}
