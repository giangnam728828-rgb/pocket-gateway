package com.pocketgateway.app.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketgateway.app.core.appaccount.AppAccounts
import com.pocketgateway.app.core.appaccount.DeviceAuthClient
import com.pocketgateway.app.core.appaccount.LoginSession
import com.pocketgateway.app.core.appaccount.WorkBuddyApi
import com.pocketgateway.app.core.appaccount.buildAppChannel
import com.pocketgateway.app.core.model.AccountMode
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ChannelPreset
import com.pocketgateway.app.core.model.ProviderPresets
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.ui.viewmodel.GatewayViewModel
import kotlinx.coroutines.launch

/**
 * 账号页：OAuth 设备授权登录 + 账号卡片（UID / 积分 / 进度条 / 状态）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAccountScreen(vm: GatewayViewModel) {
    val config by vm.config.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { DeviceAuthClient() }

    var session by remember { mutableStateOf<LoginSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var manualToken by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Channel?>(null) }

    var showPlatformDialog by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf<ChannelPreset?>(null) }
    var platformKey by remember { mutableStateOf("") }
    var platformBase by remember { mutableStateOf("") }
    var platformModel by remember { mutableStateOf("") }

    val appAccounts = config.channels.filter { it.type == ProviderType.APP_NATIVE }
    val preferredAccount = appAccounts.firstOrNull { it.id == config.preferredAccountId }
    val appCh = if (config.accountMode == AccountMode.SINGLE && preferredAccount != null) {
        preferredAccount
    } else {
        appAccounts.firstOrNull()
    }

    var info by remember { mutableStateOf<WorkBuddyApi.AccountInfo?>(null) }
    var loadingInfo by remember { mutableStateOf(false) }
    var probes by remember { mutableStateOf<List<WorkBuddyApi.ProbeResult>>(emptyList()) }
    var diagnosing by remember { mutableStateOf(false) }

    fun reloadInfo() {
        val c = appCh ?: return
        loadingInfo = true
        scope.launch {
            info = WorkBuddyApi.fetchAccount(c)
            loadingInfo = false
        }
    }

    LaunchedEffect(appCh?.id) { if (appCh != null) reloadInfo() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 操作区
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("账号管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "OAuth 登录或导入凭证。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = !busy, onClick = {
                        busy = true; errorMsg = null; status = null
                        scope.launch {
                            val acc = AppAccounts.ALL.first()
                            val s = auth.startLogin("cn")
                            busy = false
                            if (s == null) errorMsg = "获取授权链接失败，可改用「手动填 Token」"
                            else {
                                session = s
                                status = "已生成授权链接，浏览器完成授权后回来点「完成登录」"
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(s.authUrl))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        }
                    }) { Text("登录账号") }

                    OutlinedButton(enabled = !busy && session != null, onClick = {
                        val s = session ?: return@OutlinedButton
                        busy = true; errorMsg = null
                        scope.launch {
                            val cred = auth.pollToken(s)
                            busy = false
                            if (cred == null) {
                                errorMsg = "未拿到凭证，确认已在浏览器完成授权"
                            } else {
                                val acc = AppAccounts.ALL.first()
                                val ch = buildAppChannel(acc, cred)
                                vm.upsertChannel(ch)
                                session = null
                                status = "✅ 登录成功" + if (cred.nickname.isNotBlank()) "：${cred.nickname}" else ""
                                runCatching { vm.startGateway() }
                                // 自动拉模型
                                WorkBuddyApi.fetchModels(ch).onSuccess {
                                    if (it.isNotEmpty()) vm.upsertChannel(ch.copy(models = it.map { m -> m.id }))
                                }
                                info = WorkBuddyApi.fetchAccount(ch)
                            }
                        }
                    }) { Text("完成登录") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled = appCh != null && !loadingInfo, onClick = { reloadInfo() }) {
                        Text(if (loadingInfo) "刷新中…" else "刷新积分")
                    }
                    OutlinedButton(enabled = appCh != null && !busy, onClick = {
                        val c = appCh ?: return@OutlinedButton
                        busy = true
                        scope.launch {
                            val (ok, m) = WorkBuddyApi.dailyCheckin(c)
                            status = m
                            info = WorkBuddyApi.fetchAccount(c)
                            busy = false
                        }
                    }) { Text("一键签到") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled = appCh != null && !diagnosing, onClick = {
                        val c = appCh ?: return@OutlinedButton
                        diagnosing = true
                        scope.launch {
                            probes = WorkBuddyApi.diagnose(c)
                            diagnosing = false
                        }
                    }) { Text(if (diagnosing) "诊断中…" else "连接诊断") }
                    OutlinedButton(onClick = { showManual = !showManual }) {
                        Text(if (showManual) "收起手动填写" else "手动填 Token")
                    }
                }

                // 凭证状态提示
                if (appCh != null) {
                    val tk = appCh.apiKey
                    Text(
                        if (tk.isBlank()) "⚠ 凭证为空：请在下方「手动填 Token」粘贴 accessToken"
                        else "凭证：${tk.take(6)}…（长度 ${tk.length}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tk.isBlank()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (probes.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        probes.forEach { p ->
                            Text(
                                "${p.status}  ${p.name}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (p.status in 200..299) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                            )
                            Text(p.snippet, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (showManual) {
                    OutlinedTextField(
                        value = manualToken, onValueChange = { manualToken = it },
                        label = { Text("Access Token") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                    Button(onClick = {
                        if (manualToken.isBlank()) {
                            errorMsg = "请填写 Access Token"
                        } else {
                            val acc = AppAccounts.ALL.first()
                            val ch = Channel(
                                id = Channel.newId(),
                                name = "${acc.displayName}（手动）",
                                type = ProviderType.APP_NATIVE,
                                baseUrl = acc.baseUrlCn,
                                apiKey = manualToken.trim(),
                                appKind = "",
                                models = emptyList(),
                            )
                            vm.upsertChannel(ch)
                            status = "✅ 已保存"
                            manualToken = ""
                            scope.launch { info = WorkBuddyApi.fetchAccount(ch) }
                        }
                    }) { Text("保存") }
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall) }
                errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        }

        // 其他平台账号（API Key）
        run {
            val otherChannels = config.channels.filter { it.type != ProviderType.APP_NATIVE }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("其他平台账号（API Key）", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text(
                        "WorkBuddy 用 OAuth 登录；OpenAI / Claude / Gemini / DeepSeek 等其他平台直接填 API Key 即可，" +
                            "不需要登录它们的客户端。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        selectedPreset = null
                        platformKey = ""
                        platformBase = ""
                        platformModel = ""
                        showPlatformDialog = true
                    }) { Text("添加平台账号") }

                    val official = otherChannels.filter { it.type != ProviderType.CUSTOM }
                    val custom = otherChannels.filter { it.type == ProviderType.CUSTOM }
                    if (official.isNotEmpty()) {
                        Text("官方平台", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        official.forEach { ch ->
                            PlatformRow(ch) { en ->
                                vm.updateConfig { cfg ->
                                    cfg.copy(channels = cfg.channels.map {
                                        if (it.id == ch.id) it.copy(enabled = en) else it
                                    })
                                }
                            }
                        }
                    }
                    if (custom.isNotEmpty()) {
                        Text("自定义 / 私有接口", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        custom.forEach { ch ->
                            PlatformRow(ch) { en ->
                                vm.updateConfig { cfg ->
                                    cfg.copy(channels = cfg.channels.map {
                                        if (it.id == ch.id) it.copy(enabled = en) else it
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }

        // 账号路由：账号池轮询 / 固定账号
        if (appAccounts.isNotEmpty()) {
            val active = appAccounts.filter { it.enabled }
            val strategyLabel = when (config.routeStrategy) {
                com.pocketgateway.app.core.model.RouteStrategy.ROUND_ROBIN -> "轮询"
                com.pocketgateway.app.core.model.RouteStrategy.RANDOM -> "随机"
                com.pocketgateway.app.core.model.RouteStrategy.FIRST -> "优先第一个"
            }
            val fixedOk = config.accountMode == AccountMode.SINGLE &&
                preferredAccount != null && preferredAccount.enabled
            val weightText = if (active.size > 1) {
                active.joinToString(" / ") { ch -> "${ch.name}:${ch.weight}" }
            } else ""

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("账号路由", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            fixedOk -> "当前：固定使用「${preferredAccount!!.name}」"
                            config.accountMode == AccountMode.SINGLE ->
                                "当前：固定账号不可用，已回退为账号池（${active.size} 个启用账号）"
                            else -> "当前：账号池 ${active.size} 个启用账号，按「$strategyLabel」分配" +
                                if (weightText.isNotEmpty()) "（权重 $weightText）" else ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = config.accountMode == AccountMode.POOL,
                            onClick = { vm.updateConfig { it.copy(accountMode = AccountMode.POOL) } },
                            label = { Text("账号池轮询") },
                        )
                        FilterChip(
                            selected = config.accountMode == AccountMode.SINGLE,
                            onClick = {
                                vm.updateConfig {
                                    it.copy(
                                        accountMode = AccountMode.SINGLE,
                                        preferredAccountId = it.preferredAccountId.ifBlank {
                                            appAccounts.firstOrNull()?.id.orEmpty()
                                        },
                                    )
                                }
                            },
                            label = { Text("固定账号") },
                        )
                    }

                    Text(
                        "说明：多个账号默认按权重轮询；选「固定账号」后，只有该账号参与路由。" +
                            "账号的启用/停用开关在下方「账号列表」中管理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 账号列表：按国内/国际分组，逐账号管理
        if (appAccounts.isNotEmpty()) {
            val cnAccounts = appAccounts.filter { !it.baseUrl.contains("workbuddy.ai", true) }
            val intlAccounts = appAccounts.filter { it.baseUrl.contains("workbuddy.ai", true) }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("账号列表", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text(
                        "共 ${appAccounts.size} 个账号。开关控制是否参与路由；点账号可设为固定账号。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (cnAccounts.isNotEmpty()) {
                        Text("国内账号", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        cnAccounts.forEach { ch ->
                            AccountRowItem(
                                ch = ch,
                                pinned = config.accountMode == AccountMode.SINGLE &&
                                    config.preferredAccountId == ch.id,
                                onPin = {
                                    vm.updateConfig {
                                        it.copy(accountMode = AccountMode.SINGLE, preferredAccountId = ch.id)
                                    }
                                },
                                onToggle = { en ->
                                    vm.updateConfig { cfg ->
                                        cfg.copy(channels = cfg.channels.map {
                                            if (it.id == ch.id) it.copy(enabled = en) else it
                                        })
                                    }
                                },
                            )
                        }
                    }

                    if (intlAccounts.isNotEmpty()) {
                        Text("国际账号", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        intlAccounts.forEach { ch ->
                            AccountRowItem(
                                ch = ch,
                                pinned = config.accountMode == AccountMode.SINGLE &&
                                    config.preferredAccountId == ch.id,
                                onPin = {
                                    vm.updateConfig {
                                        it.copy(accountMode = AccountMode.SINGLE, preferredAccountId = ch.id)
                                    }
                                },
                                onToggle = { en ->
                                    vm.updateConfig { cfg ->
                                        cfg.copy(channels = cfg.channels.map {
                                            if (it.id == ch.id) it.copy(enabled = en) else it
                                        })
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // 当前账号详情
        if (appCh != null) {
            Text("当前账号详情", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
        }

        // 账号卡片
        appCh?.let { ch ->
            AccountCard(
                channel = ch,
                info = info,
                loading = loadingInfo,
                onDelete = { pendingDelete = ch },
            )
        }

        if (showPlatformDialog) {
            AlertDialog(
                onDismissRequest = { showPlatformDialog = false },
                title = { Text("添加平台账号") },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "选择平台后填入 API Key。这里添加的是「上游平台账号」，不是客户端登录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ProviderPresets.OFFICIAL.forEach { p ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        selectedPreset = p
                                        platformBase = p.baseUrl
                                        platformModel = p.models.firstOrNull().orEmpty()
                                    }
                                    .padding(vertical = 6.dp),
                            ) {
                                Column {
                                    Text(
                                        p.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selectedPreset?.key == p.key) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    if (p.note.isNotBlank()) {
                                        Text(
                                            p.note,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        selectedPreset?.let { p ->
                            HorizontalDivider()
                            OutlinedTextField(
                                value = platformKey, onValueChange = { platformKey = it },
                                label = { Text("API Key") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            OutlinedTextField(
                                value = platformBase, onValueChange = { platformBase = it },
                                label = { Text("Base URL") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            OutlinedTextField(
                                value = platformModel, onValueChange = { platformModel = it },
                                label = { Text("模型（可选）") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = selectedPreset != null && platformKey.isNotBlank(),
                        onClick = {
                            val p = selectedPreset ?: return@TextButton
                            val ch = Channel(
                                id = Channel.newId(),
                                name = p.label,
                                type = p.type,
                                baseUrl = platformBase.ifBlank { p.baseUrl },
                                apiKey = platformKey.trim(),
                                authHeader = p.authHeader,
                                extraHeaders = p.extraHeaders,
                                models = listOf(platformModel.trim())
                                    .filter { it.isNotBlank() }
                                    .ifEmpty { p.models },
                            )
                            vm.upsertChannel(ch)
                            showPlatformDialog = false
                            status = "✅ 已添加 ${p.label}"
                        },
                    ) { Text("添加") }
                },
                dismissButton = {
                    TextButton(onClick = { showPlatformDialog = false }) { Text("取消") }
                },
            )
        }

        pendingDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("删除账号") },
                text = { Text("确定要删除「${target.name}」吗？删除后需重新登录才能恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteChannel(target.id)
                        pendingDelete = null
                        info = null
                        status = "已删除"
                    }) { Text("确定删除") }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun AccountRowItem(
    ch: Channel,
    pinned: Boolean,
    onPin: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RadioButton(selected = pinned, onClick = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(ch.name, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
                Box(
                    modifier = Modifier
                        .background(
                            when {
                                !ch.enabled -> MaterialTheme.colorScheme.errorContainer
                                pinned -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        when {
                            !ch.enabled -> "已停用"
                            pinned -> "固定中"
                            else -> "轮询中"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            !ch.enabled -> MaterialTheme.colorScheme.error
                            pinned -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text(
                "UID ${ch.appKind.ifBlank { "—" }} · 权重 ${ch.weight} · 模型 ${ch.models.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = ch.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun PlatformRow(ch: Channel, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ch.name, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
            Text(
                "${providerTypeLabel(ch.type)} · ${ch.baseUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = ch.enabled, onCheckedChange = onToggle)
    }
}

private fun providerTypeLabel(type: ProviderType): String = when (type) {
    ProviderType.OPENAI_COMPAT -> "OpenAI 兼容"
    ProviderType.CLAUDE -> "Claude"
    ProviderType.GEMINI -> "Gemini"
    ProviderType.CUSTOM -> "自定义"
    ProviderType.APP_NATIVE -> "APP 直连"
}

@Composable
private fun AccountCard(
    channel: Channel,
    info: WorkBuddyApi.AccountInfo?,
    loading: Boolean,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(channel.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .background(
                            if (info?.usable != false) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        if (info?.usable == false) "不可用" else "可用",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (info?.usable != false) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                    )
                }
            }

            val uid = info?.uid?.takeIf { it.isNotBlank() } ?: channel.appKind
            if (uid.isNotBlank()) {
                Text("UID $uid", style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val remain = info?.creditsRemain
            val total = info?.creditsTotal
            Text(
                if (loading) "积分加载中…"
                else if (remain != null && total != null && total > 0) "剩余 $remain / $total 积分"
                else if (remain != null) "剩余 $remain 积分"
                else "积分未获取",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (info != null && info.creditsTotal != null && info.creditsTotal > 0) {
                // 进度条
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((info.percent / 100f).coerceIn(0f, 1f))
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                    )
                }
                Text(
                    "已用 ${info.creditsUsed ?: 0} · ${info.percent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}
