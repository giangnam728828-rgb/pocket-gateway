package com.pocketgateway.app

import android.app.Application
import com.pocketgateway.app.core.store.ConfigStore
import com.pocketgateway.app.core.store.LogStore

/** 全局单例容器。 */
class PocketGatewayApp : Application() {

    lateinit var configStore: ConfigStore
        private set

    lateinit var logStore: LogStore
        private set

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(this)
        logStore = LogStore { configStore.config.value.maxLogEntries }
    }
}
