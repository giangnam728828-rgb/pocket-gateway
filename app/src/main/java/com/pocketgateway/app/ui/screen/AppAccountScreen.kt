package com.pocketgateway.app.ui.screen

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketgateway.app.core.appaccount.AppAccount
import com.pocketgateway.app.core.appaccount.AppAccounts
import com.pocketgateway.app.core.appaccount.buildAppChannel
import com.pocketgateway.app.core.appaccount.extractCookies
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.ui.viewmodel.GatewayViewModel

/**
 * APP 账号页：在内置 WebView 登录 APP/网页账号，登录后自动生成接入信息与渠道。
 *
 * 这是与「渠道」页不同的独立流程：用户不需要知道 URL / API Key / 请求格式，
 * 只需登录一次，网关自动完成适配。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppAccountScreen(vm: GatewayViewModel) {
    val config by vm.config.collectAsStateSafe()
    var selected by remember { mutableStateOf<AppAccount?>(AppAccounts.ALL.firstOrNull()) }
    var loggingIn by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loginDone by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var cookie by remember { mutableStateOf<String?>(null) }

    // 已生成的 APP 直连渠道
    val appChannels = config.channels.filter { ch -> ch.type == com.pocketgateway.app.core.model.ProviderType.APP_NATIVE }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("APP 账号直连", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "在内置页面登录账号，自动生成 Base URL、API Key 与模型列表，无需手动配置接口地址。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 选择 APP
        if (!loggingIn) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("选择应用", style = MaterialTheme.typography.titleMedium)
                    AppAccounts.ALL.forEach { acc ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(acc.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    acc.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = {
                                selected = acc
                                loggingIn = true
                                loginDone = false
                                errorMsg = null
                                cookie = null
                            }) { Text("登录") }
                        }
                    }
                }
            }
        }

        // 登录 WebView
        if (loggingIn) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("登录 ${selected?.displayName ?: ""}", fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = { loggingIn = false }) { Text("返回") }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(480.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            // 页面加载完成后尝试提取 Cookie，判断是否已登录
                                            val domain = selected?.cookieDomain ?: return
                                            val c = extractCookies(domain)
                                            if (!c.isNullOrBlank()) {
                                                cookie = c
                                            }
                                        }
                                    }
                                    loadUrl(selected?.loginUrl ?: "")
                                    webView = this
                                }
                            },
                            update = { wv -> webView = wv },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            val domain = selected?.cookieDomain ?: return@Button
                            val c = extractCookies(domain)
                            if (c.isNullOrBlank()) {
                                errorMsg = "未检测到登录 Cookie，请先完成登录后再点此按钮。"
                            } else {
                                cookie = c
                                errorMsg = null
                            }
                        }) { Text("检测登录状态") }
                        OutlinedButton(onClick = {
                            webView?.clearCache(true)
                            CookieManager.getInstance().removeAllCookies(null)
                            webView?.reload()
                        }) { Text("清除并重登") }
                    }
                }
            }
        }

        // 登录成功 → 生成接入信息
        cookie?.let { c ->
            if (!loginDone) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("✅ 已检测到登录凭证", fontWeight = FontWeight.Bold)
                        Text(
                            "Cookie 长度：${c.length} 字符。点击下方按钮生成渠道与接入信息。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = {
                            val acc = selected ?: return@Button
                            vm.upsertChannel(buildAppChannel(acc, c))
                            loginDone = true
                            loggingIn = false
                        }) { Text("生成接入信息并保存渠道") }
                    }
                }
            }
        }

        errorMsg?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        // 已有 APP 直连渠道 → 展示接入信息
        if (appChannels.isNotEmpty()) {
            HorizontalDivider()
            Text("已接入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            appChannels.forEach { ch ->
                AccessInfoCard(
                    channel = ch,
                    baseUrl = vm.baseUrl.value,
                    accessToken = config.accessToken,
                    onDelete = { vm.deleteChannel(ch.id) },
                )
            }
        } else if (loginDone) {
            Text("渠道已保存，可在下方查看接入信息。", style = MaterialTheme.typography.bodyMedium)
        }

        // 说明
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("使用说明", fontWeight = FontWeight.SemiBold)
                Text(
                    "1. 点「登录」，在内置页面用你的账号登录\n" +
                        "2. 登录成功后点「检测登录状态」\n" +
                        "3. 生成渠道后，把下方 Base URL 与 API Key 填到你的 AI 客户端\n" +
                        "4. 模型名用列表中显示的即可",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 接入信息卡片：Base URL / API Key / 模型。 */
@Composable
private fun AccessInfoCard(
    channel: Channel,
    baseUrl: String,
    accessToken: String,
    onDelete: () -> Unit,
) {
    var copied by remember { mutableStateOf<String?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(channel.name, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onDelete) { Text("移除") }
            }
            InfoRow("BASE URL", baseUrl, onCopy = { copied = "base" })
            InfoRow(
                "API KEY",
                accessToken.ifBlank { "（未设置访问令牌，可留空）" },
                onCopy = { copied = "key" },
            )
            InfoRow("模型", channel.models.joinToString(", ").ifBlank { "workbuddy" }, onCopy = { copied = "models" })
            copied?.let {
                Text("已复制", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, onCopy: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onCopy) { Text("复制") }
        }
    }
}

/** 轻量封装：把 StateFlow 转为 Compose 可读状态。 */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateSafe() =
    this.collectAsState()
