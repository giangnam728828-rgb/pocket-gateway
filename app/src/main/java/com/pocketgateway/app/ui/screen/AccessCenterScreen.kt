package com.pocketgateway.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketgateway.app.core.access.AccessPreset
import com.pocketgateway.app.core.access.AccessPresets
import com.pocketgateway.app.core.appaccount.WorkBuddyApi
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.ui.viewmodel.GatewayViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 接入中心：把网关地址、密钥、模型名与常见客户端的配置一次给全。
 * 替代原来的「模型」Tab。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessCenterScreen(vm: GatewayViewModel) {
    val config by vm.config.collectAsState()
    val running by vm.isRunning.collectAsState()
    val localIp by vm.localIp.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val baseUrl = vm.baseUrl.value            // 含 /v1
    val hostUrl = baseUrl.removeSuffix("/v1") // 不含 /v1
    val apiKey = config.accessToken
    val allModels = config.channels
        .filter { it.enabled }
        .flatMap { ch -> ch.models + ch.modelMapping.keys }
        .distinct()
    var selectedModel by remember { mutableStateOf("") }
    LaunchedEffect(allModels) {
        if (selectedModel !in allModels) {
            selectedModel = allModels.firstOrNull().orEmpty()
        }
    }
    val model = selectedModel.ifBlank {
        config.channels.firstOrNull { it.models.isNotEmpty() }?.models?.firstOrNull() ?: "gpt-4o"
    }

    var query by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var modelMsg by remember { mutableStateOf<String?>(null) }
    var listenAll by remember(config.listenOnAllInterfaces) {
        mutableStateOf(config.listenOnAllInterfaces)
    }

    // 模型列表为空时自动尝试拉取（替代已移除的「模型」页的自动刷新）
    LaunchedEffect(config.channels) {
        val appChannels = config.channels.filter {
            it.enabled && it.type == ProviderType.APP_NATIVE && it.models.isEmpty()
        }
        if (appChannels.isEmpty()) return@LaunchedEffect
        refreshing = true
        var total = 0
        appChannels.forEach { ch ->
            WorkBuddyApi.fetchModels(ch).onSuccess { rows ->
                if (rows.isNotEmpty()) {
                    vm.upsertChannel(ch.copy(models = rows.map { it.id }))
                    total += rows.size
                }
            }
        }
        if (total > 0) modelMsg = "已自动获取 $total 个模型"
        refreshing = false
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("接入信息", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "含 /v1 与不含 /v1 两种地址都有，按下面各客户端的提示选择。点任意一行可复制。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InfoLine("BASE URL（含 /v1）", baseUrl) { copyToClipboard(ctx, baseUrl) }
                InfoLine("API HOST（不含 /v1）", hostUrl) { copyToClipboard(ctx, hostUrl) }
                InfoLine("API KEY", apiKey.ifBlank { "未设置（网关不鉴权）" }) {
                    copyToClipboard(ctx, apiKey)
                }
                InfoLine("模型", model) { copyToClipboard(ctx, model) }

                if (allModels.size > 1) {
                    Text(
                        "可用模型（点选后，下面所有客户端的配置会跟着切换）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        allModels.forEach { m ->
                            FilterChip(
                                selected = m == model,
                                onClick = { selectedModel = m },
                                label = { Text(m) },
                            )
                        }
                    }
                }

                if (!running) {
                    Text(
                        "网关当前未运行：请先到「概览」页启动，否则任何客户端都连不上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (localIp.isNullOrBlank()) {
                    Text(
                        "未获取到局域网 IP：请确认手机已连接 Wi-Fi。其他设备必须与本机在同一 Wi-Fi。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = listenAll,
                        onCheckedChange = {
                            listenAll = it
                            vm.updateConfig { cfg -> cfg.copy(listenOnAllInterfaces = it) }
                        },
                    )
                    Column {
                        Text("允许局域网访问", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "关闭后只有手机本机（127.0.0.1）能访问；修改后需重启网关生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                InfoLine("跨设备测试地址", "$hostUrl/health") {
                    copyToClipboard(ctx, "$hostUrl/health")
                }
                Text(
                    "在另一台设备的浏览器打开上面的测试地址：显示 ok 说明网络可达；打不开就是 Wi-Fi/AP 隔离或 IP 不对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = withContext(Dispatchers.IO) { probe(hostUrl, apiKey) }
                            testing = false
                        }
                    },
                ) { Text(if (testing) "测试中…" else "测试本机连接") }

                testResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("正常")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedButton(
                    enabled = !refreshing && !testing,
                    onClick = {
                        refreshing = true
                        modelMsg = null
                        scope.launch {
                            var total = 0
                            config.channels
                                .filter { it.enabled && it.type == ProviderType.APP_NATIVE }
                                .forEach { ch ->
                                    WorkBuddyApi.fetchModels(ch).onSuccess { rows ->
                                        if (rows.isNotEmpty()) {
                                            vm.upsertChannel(ch.copy(models = rows.map { it.id }))
                                            total += rows.size
                                        }
                                    }
                                }
                            modelMsg = if (total > 0) "已更新 $total 个模型"
                            else "未能获取模型，请检查账号登录态或网络"
                            refreshing = false
                        }
                    },
                ) { Text(if (refreshing) "获取模型中…" else "刷新可用模型") }

                modelMsg?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 按账号展示模型，切换账号/平台后自动刷新
        val channelModels = config.channels.filter { it.enabled && it.models.isNotEmpty() }
        if (channelModels.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("可用模型（按账号）", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    channelModels.forEach { ch ->
                        Text(ch.name, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            ch.models.joinToString(" / "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索客户端") },
        )

        val presets = AccessPresets.all.filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                it.category.contains(query, ignoreCase = true)
        }

        presets.forEach { preset ->
            PresetCard(preset, baseUrl, hostUrl, apiKey, model)
        }

        Text(
            "常见报错：连接被拒绝 → 网关未启动；连接超时 → 两台设备不在同一 Wi-Fi 或路由器开启了 AP 隔离；" +
                "401 → API KEY 不一致；404 → 地址多填或少填了 /v1，请按对应客户端提示填写。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetCard(
    preset: AccessPreset,
    baseUrl: String,
    hostUrl: String,
    apiKey: String,
    model: String,
) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    // 该客户端应填写的地址形态
    val url = if (preset.usesV1) baseUrl else hostUrl

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(preset.category, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }

            if (expanded) {
                Text(preset.step, style = MaterialTheme.typography.bodySmall)
                InfoLine(
                    if (preset.usesV1) "填入 BASE URL（含 /v1）" else "填入 API HOST（不含 /v1）",
                    url,
                ) { copyToClipboard(ctx, url) }
                InfoLine("API KEY", apiKey.ifBlank { "未设置" }) { copyToClipboard(ctx, apiKey) }
                InfoLine("模型", model) { copyToClipboard(ctx, model) }
                OutlinedButton(onClick = {
                    copyToClipboard(ctx, preset.jsonTemplate(url, apiKey, model))
                }) { Text("复制 JSON 配置") }
            }
        }
    }
}

/** 在本机探测 /health 与 /v1/models，验证网关与鉴权是否正常。 */
private fun probe(hostUrl: String, apiKey: String): String {
    return try {
        val health = (URL("$hostUrl/health").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            instanceFollowRedirects = false
        }
        val hc = health.responseCode
        health.disconnect()
        if (hc !in 200..299) return "失败：/health HTTP $hc（网关可能未启动）"

        val models = (URL("$hostUrl/v1/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 6000
            instanceFollowRedirects = false
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val mc = models.responseCode
        models.disconnect()
        when {
            mc in 200..299 -> "正常：网关可达，鉴权通过"
            mc == 401 -> "失败：401，API KEY 与网关设置不一致"
            else -> "失败：/v1/models HTTP $mc"
        }
    } catch (e: Exception) {
        "失败：${e.message ?: e.javaClass.simpleName}（网关未启动或端口被占用）"
    }
}
