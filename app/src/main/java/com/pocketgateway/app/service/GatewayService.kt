package com.pocketgateway.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pocketgateway.app.MainActivity
import com.pocketgateway.app.PocketGatewayApp
import com.pocketgateway.app.core.gateway.GatewayRuntime
import com.pocketgateway.app.core.gateway.GatewayServer
import com.pocketgateway.app.util.acquireWifiLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 前台服务：把网关跑在后台并常驻。
 * 持有 WakeLock（CPU 锁）与 WifiLock（Wi-Fi 锁），避免锁屏后网关失联。
 */
class GatewayService : Service() {

    companion object {
        const val CHANNEL_ID = "pocket_gateway_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        fun startIntent(context: Context) = Intent(context, GatewayService::class.java).apply {
            action = ACTION_START
        }

        fun stopIntent(context: Context) = Intent(context, GatewayService::class.java).apply {
            action = ACTION_STOP
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: GatewayServer
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as PocketGatewayApp
        server = GatewayServer(app.configStore, app.logStore)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                GatewayRuntime.markStopped("服务已停止")
                stopSelf()
            }
            else -> scope.launch { startGateway() }
        }
        return START_STICKY
    }

    private suspend fun startGateway() {
        val app = application as PocketGatewayApp
        val cfg = app.configStore.config.first()

        startForeground(NOTIFICATION_ID, buildNotification(cfg.port))
        acquireLocks()

        runCatching { server.start(cfg) }
            .onSuccess { GatewayRuntime.markRunning(cfg.port) }
            .onFailure { GatewayRuntime.markError("启动失败：${it.message}") }

        // 监听配置变化：端口变更时重启服务，保持通知同步
        scope.launch {
            app.configStore.config.collect { newCfg ->
                server.restartIfNeeded(newCfg)
            }
        }
    }

    override fun onDestroy() {
        GatewayRuntime.markStopped("服务已停止")
        runCatching { server.stop() }
        releaseLocks()
        super.onDestroy()
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketGateway::wake")
            .apply { setReferenceCounted(false); acquire() }
        wifiLock = acquireWifiLock(this)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "网关服务",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "本地模型网关运行状态" },
                )
            }
        }
    }

    private fun buildNotification(port: Int): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags,
        )
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent(this), flags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("口袋网关运行中")
            .setContentText("监听端口 $port · 点击管理")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "停止", stopPi)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
