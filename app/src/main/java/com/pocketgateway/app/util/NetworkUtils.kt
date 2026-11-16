package com.pocketgateway.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** 获取本机在局域网中的 IPv4 地址（用于生成对手机可用的 API 地址）。 */
fun getLocalIpAddress(context: Context): String? {
    // 优先从 Wi-Fi 的 LinkProperties 获取，最准确
    runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { network ->
            val props: LinkProperties? = cm.getLinkProperties(network)
            props?.linkAddresses?.firstOrNull { it.address is Inet4Address }
                ?.address?.hostAddress?.let { if (it != "127.0.0.1") return it }
        }
    }
    // 兜底：遍历网卡
    runCatching {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback || intf.name.contains("dummy")) continue
            val addresses = Collections.list(intf.inetAddresses)
            for (addr in addresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val host = addr.hostAddress ?: continue
                    if (host != "127.0.0.1" && !host.startsWith("169.254")) return host
                }
            }
        }
    }
    return null
}

/** 拿到 Wi-Fi 信号锁，避免手机锁屏后 Wi-Fi 休眠导致网关不可达。 */
fun acquireWifiLock(context: Context): WifiManager.WifiLock? = runCatching {
    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PocketGateway").apply { acquire() }
}.getOrNull()

/** 把端口拼成可访问的 base URL。 */
fun buildBaseUrl(ip: String?, port: Int, listenAll: Boolean): String {
    val host = if (listenAll && !ip.isNullOrBlank()) ip else "127.0.0.1"
    return "http://$host:$port/v1"
}
