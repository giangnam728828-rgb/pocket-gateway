package com.pocketgateway.app

import android.app.Application
import com.pocketgateway.app.core.scheduler.CheckInScheduler
import com.pocketgateway.app.core.store.ConfigStore
import com.pocketgateway.app.core.store.LogStore
import com.pocketgateway.app.core.store.UsageDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
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

        // 配置变化时自动同步每日签到/领任务调度（无 APP_NATIVE 渠道时为空操作）。
        checkInScheduler = CheckInScheduler(this)
        appScope.launch {
            configStore.config.collect { cfg ->
                runCatching { checkInScheduler.sync(cfg) }
            }
        }
    }
}
