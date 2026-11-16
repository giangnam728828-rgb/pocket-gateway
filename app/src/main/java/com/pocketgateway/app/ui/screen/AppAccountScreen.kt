package com.pocketgateway.app.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketgateway.app.core.appaccount.AppAccount
import com.pocketgateway.app.core.appaccount.AppAccounts
import com.pocketgateway.app.core.appaccount.DeviceAuthClient
import com.pocketgateway.app.core.appaccount.LoginSession
import com.pocketgateway.app.core.adapter.ModelFetcher
import com.pocketgateway.app.core.appaccount.buildAppChannel
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.ui.viewmodel.GatewayViewModel
import kotlinx.coroutines.launch

/**
 * APP 账号页：设备授权（OAuth Device Flow）登录 WorkBuddy / CodeBuddy，
 * 登录后自动生成渠道与接入信息（Base URL / API Key / 模型）。
 */
@Composable
fun AppAccountScreen(vm: GatewayViewModel) {
    val config by vm.config.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { DeviceAuthClient() }

    var selected by remember { mutableStateOf<AppAccount?>(AppAccounts.ALL.firstOrNull()) }
    var session by remember { mutableStateOf<LoginSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var manualToken by remember { mutableStateOf("") }
    var manualUid by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }

    // 确保网关有访问令牌：为空时自动生成，避免「未设置访问令牌」导致无法接入
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.ensureAccessToken() }

    val appChannels = config.channels.filter { it.type == ProviderType.APP_NATIVE }
    // 待删除确认的渠道
    var pendingDelete by remember { mutableStateOf<Channel?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("APP 账号直连", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "用于只能在自己 APP 内使用、不公开接口地址的服务。用设备授权登录后自动生成接入信息。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 选择账号类型
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
                        Button(
                            enabled = !busy,
                            onClick = {
                                selected = acc
                                errorMsg = null
                                status = null
                                busy = true
                                scope.launch {
                                    val s = auth.startLogin(if (acc.kind.endsWith("_global")) "global" else "cn")
                                    busy = false
                                    if (s == null) {
                                        errorMsg = "获取授权链接失败，请检查网络或改用「手动填 Token」。"
                                    } else {
                                        session = s
                                        status = "已生成授权链接，请点下方按钮打开并完成授权。"
                                        // 自动打开浏览器
                                        runCatching {
                                            ctx.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(s.authUrl))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    }
                                }
                            },
                        ) { Text("登录") }
                    }
                }
            }
        }

        // 等待授权
        session?.let { s ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("等待授权", fontWeight = FontWeight.Bold)
                    Text(
                        "在浏览器完成授权后，回来点「我已完成授权」。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(s.authUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) { Text("重新打开授权页") }

                        Button(enabled = !busy, onClick = {
                            busy = true
                            errorMsg = null
                            scope.launch {
                                val cred = auth.pollToken(s)
                                busy = false
                                if (cred == null) {
                                    errorMsg = "尚未拿到凭证。确认已在浏览器完成授权，然后重试。"
                                } else {
                                    val acc = selected ?: AppAccounts.ALL.first()
                                    val ch = buildAppChannel(acc, cred)
                                    vm.upsertChannel(ch)
                                    session = null
                                    // 登录成功后自动从上游拉取真实模型列表
                                    status = "✅ 登录成功${if (cred.nickname.isNotBlank()) "：${cred.nickname}" else ""}，正在获取模型…"
                                    val fetched = ModelFetcher.fetch(ch)
                                    fetched.onSuccess { list ->
                                        if (list.isNotEmpty()) {
                                            vm.upsertChannel(ch.copy(models = list))
                                            status = "✅ 登录成功${if (cred.nickname.isNotBlank()) "：${cred.nickname}" else ""}，已获取 ${list.size} 个模型。"
                                        } else {
                                            status = "✅ 登录成功，渠道已保存（上游未返回模型，可手动获取）。"
                                        }
                                    }.onFailure {
                                        status = "✅ 登录成功，渠道已保存（模型获取失败：${it.message}）。"
                                    }
                                }
                            }
                        }) { Text("我已完成授权") }
                    }
                }
            }
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                Text("处理中…", style = MaterialTheme.typography.bodySmall)
            }
        }

        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        // 手动填 Token（设备授权不可用时的兜底）
        OutlinedButton(onClick = { showManual = !showManual }) {
            Text(if (showManual) "收起手动填写" else "手动填 Token（备用）")
        }
        if (showManual) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("手动填写凭证", fontWeight = FontWeight.SemiBold)
                    Text(
                        "如果你已从其他工具（如 workbuddy2api 的 auths/*.json）拿到 accessToken，可直接粘贴。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = manualToken, onValueChange = { manualToken = it },
                        label = { Text("Access Token") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    OutlinedTextField(
                        value = manualUid, onValueChange = { manualUid = it },
                        label = { Text("UID（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(onClick = {
                        if (manualToken.isBlank()) {
                            errorMsg = "请填写 Access Token。"
                        } else {
                            val acc = selected ?: AppAccounts.ALL.first()
                            vm.upsertChannel(
                                Channel(
                                    id = Channel.newId(),
                                    name = "${acc.displayName}（手动）",
                                    type = ProviderType.APP_NATIVE,
                                    baseUrl = if (acc.kind.endsWith("_global")) acc.baseUrlGlobal else acc.baseUrlCn,
                                    apiKey = manualToken.trim(),
                                    appKind = manualUid.trim(),
                                    models = acc.models,
                                )
                            )
                            status = "✅ 已保存手动凭证渠道。"
                            errorMsg = null
                            manualToken = ""
                        }
                    }) { Text("保存") }
                }
            }
        }

        // 已接入
        if (appChannels.isNotEmpty()) {
            HorizontalDivider()
            Text("已接入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            appChannels.forEach { ch ->
                AccessInfoCard(
                    channel = ch,
                    baseUrl = vm.baseUrl.value,
                    accessToken = config.accessToken,
                    onDelete = { pendingDelete = ch },
                    onRegenerateToken = { vm.regenerateAccessToken() },
                    onModelsUpdated = { models -> vm.upsertChannel(ch.copy(models = models)) },
                    onMessage = { status = it },
                )
            }
        }

        // 删除二次确认，避免误触
        pendingDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("删除渠道") },
                text = { Text("确定要删除「${target.name}」吗？删除后需重新登录才能恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteChannel(target.id)
                        pendingDelete = null
                        status = "已删除「${target.name}」"
                    }) { Text("确定删除") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                },
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("说明", fontWeight = FontWeight.SemiBold)
                Text(
                    "1. 点「登录」→ 自动打开浏览器授权页\n" +
                        "2. 用你的 WorkBuddy / CodeBuddy 账号登录并授权\n" +
                        "3. 回到本页点「我已完成授权」\n" +
                        "4. 把下方 Base URL 与 API Key 填到 AI 客户端，模型用列表里的名字\n\n" +
                        "仅用本人有权访问的账号，仅本地/受信网络使用。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AccessInfoCard(
    channel: Channel,
    baseUrl: String,
    accessToken: String,
    onDelete: () -> Unit,
    onRegenerateToken: () -> String,
    onModelsUpdated: (List<String>) -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var fetching by remember { mutableStateOf(false) }
    var currentToken by remember(accessToken) { mutableStateOf(accessToken) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 标题行：名称 + 可见的删除按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    channel.name,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onDelete,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text("删除") }
            }

            InfoRow("BASE URL", baseUrl, copyable = true)

            // API KEY：已自动生成，可复制/重新生成
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "API KEY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (currentToken.isBlank()) "（正在生成…）" else currentToken,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val t = if (currentToken.isBlank()) onRegenerateToken() else currentToken
                        currentToken = t
                        copyToClipboard(ctx, t)
                        onMessage("API KEY 已复制")
                    }) { Text("复制 Key") }
                    OutlinedButton(onClick = {
                        val t = onRegenerateToken()
                        currentToken = t
                        copyToClipboard(ctx, t)
                        onMessage("已重新生成 API KEY，旧 Key 立即失效")
                    }) { Text("重新生成") }
                }
            }

            // 模型列表：支持自动从上游获取
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "模型（${channel.models.size}）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    channel.models.joinToString(", ").ifBlank { "（空）" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !fetching,
                        onClick = {
                            fetching = true
                            scope.launch {
                                val r = ModelFetcher.fetch(channel)
                                fetching = false
                                r.onSuccess { models ->
                                    if (models.isEmpty()) {
                                        onMessage("上游未返回模型")
                                    } else {
                                        onModelsUpdated(models)
                                        onMessage("已获取 ${models.size} 个模型")
                                    }
                                }.onFailure { e ->
                                    onMessage("获取失败：${e.message}")
                                }
                            }
                        },
                    ) { Text(if (fetching) "获取中…" else "自动获取模型") }
                    OutlinedButton(onClick = {
                        copyToClipboard(ctx, channel.models.joinToString(","))
                        onMessage("模型列表已复制")
                    }) { Text("复制") }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, copyable: Boolean = false) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
        )
        if (copyable) {
            OutlinedButton(onClick = { copyToClipboard(ctx, value) }) { Text("复制") }
        }
    }
}

/** 复制到系统剪贴板。 */
private fun copyToClipboard(ctx: android.content.Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("pocketgateway", text))
    }
}
