package com.pocketgateway.app.ui.screen

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.pocketgateway.app.core.appaccount.ModelRow
import com.pocketgateway.app.core.appaccount.WorkBuddyApi
import com.pocketgateway.app.core.appaccount.task.TaskActionRegistry
import com.pocketgateway.app.core.appaccount.task.TaskAutomationEngine
import com.pocketgateway.app.PocketGatewayApp
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.ui.viewmodel.GatewayViewModel
import kotlinx.coroutines.launch

/** 取当前第一个 APP 直连渠道（WorkBuddy 账号）。 */
fun currentAppChannel(vm: GatewayViewModel): Channel? =
    vm.config.value.channels.firstOrNull { it.type == ProviderType.APP_NATIVE }

// ======================= 概览 =======================

@Composable
fun OverviewScreen(vm: GatewayViewModel) {
    val config by vm.config.collectAsState()
    val ctx = LocalContext.current
    val appCh = config.channels.firstOrNull { it.type == ProviderType.APP_NATIVE }

    LaunchedEffect(Unit) { vm.ensureAccessToken() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 接入信息
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("接入信息", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "OpenAI 兼容地址与密钥，填到客户端里即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InfoLine("BASE URL", vm.baseUrl.value) { copyToClipboard(ctx, vm.baseUrl.value) }
                InfoLine(
                    "API KEY",
                    config.accessToken.ifBlank { "生成中…" },
                ) { copyToClipboard(ctx, config.accessToken) }
            }
        }

        // 运行状态（醒目）
        val running by vm.isRunning.collectAsState()
        val statusMsg by vm.runtimeMessage.collectAsState()
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (running) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (running) "● 运行中" else "○ 已停止",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (running) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (running) "网关正在监听，客户端可以调用"
                            else "网关未运行，客户端无法连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                if (running) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(14.dp),
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            if (running) "RUNNING" else "STOPPED",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (running) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.surface,
                        )
                    }
                }
                if (statusMsg.isNotBlank()) {
                    Text(statusMsg, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "已接入：${appCh?.name ?: "尚未登录账号"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (running) {
                    Button(onClick = { vm.stopGateway() }) { Text("停止网关") }
                } else {
                    Button(onClick = { vm.startGateway() }) { Text("启动网关") }
                }
            }
        }

        // 最近日志
        val logs by vm.logs.collectAsState()
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("最近请求", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (logs.isEmpty()) {
                    Text("暂无请求", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    logs.takeLast(8).reversed().forEach { l ->
                        Text(
                            "${if (l.success) "✓" else "✗"} ${l.model.ifBlank { l.path }}  ${l.status}  ${l.durationMs}ms",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

// ======================= 模型 =======================

@Composable
fun ModelsScreen(vm: GatewayViewModel) {
    val config by vm.config.collectAsState()
    val scope = rememberCoroutineScope()
    val ch = config.channels.firstOrNull { it.type == ProviderType.APP_NATIVE }

    var rows by remember { mutableStateOf<List<ModelRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun reload() {
        val c = ch ?: return
        loading = true
        msg = null
        scope.launch {
            WorkBuddyApi.fetchModels(c).onSuccess {
                rows = it
                vm.upsertChannel(c.copy(models = it.map { m -> m.id }))
            }.onFailure { msg = it.message }
            loading = false
        }
    }

    LaunchedEffect(ch?.id) { if (ch != null && rows.isEmpty()) reload() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("模型列表", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    OutlinedButton(enabled = !loading, onClick = { reload() }) {
                        Text(if (loading) "加载中…" else "刷新")
                    }
                }
                Text(
                    "共 ${rows.size} 个模型 · 倍率 0 为免费，— 为上游未提供",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                msg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                if (rows.isEmpty() && !loading) {
                    Text(
                        "暂无模型，点「刷新」获取",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rows.forEachIndexed { idx, m ->
                    if (idx > 0) HorizontalDivider()
                    ModelRowItem(m)
                }
            }
        }
    }
}

@Composable
private fun ModelRowItem(m: ModelRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(m.id, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val parts = buildList {
                if (m.contextLength > 0) add("上下文 ${m.contextLength / 1000}k")
                if (m.maxOutput > 0) add("输出 ${m.maxOutput / 1000}k")
                if (m.supportsImages) add("可看图")
            }
            Text(
                parts.joinToString(" · ").ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CreditBadge(m)
    }
}

@Composable
private fun CreditBadge(m: ModelRow) {
    val label = m.creditLabel
    val free = m.isFree
    Box(
        modifier = Modifier
            .background(
                if (free) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (free) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ======================= 任务 =======================

@Composable
fun TasksScreen(vm: GatewayViewModel) {
    val config by vm.config.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val ch = config.channels.firstOrNull { it.type == ProviderType.APP_NATIVE }

    var tasks by remember { mutableStateOf<List<WorkBuddyApi.GrowthTask>>(emptyList()) }
    var energy by remember { mutableStateOf<Long?>(null) }
    var streak by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showAutoConfirm by remember { mutableStateOf(false) }

    fun reload() {
        val c = ch ?: return
        busy = true
        scope.launch {
            val (list, err) = WorkBuddyApi.fetchTasks(c)
            tasks = list
            if (err != null) msg = err
            val (e, s) = WorkBuddyApi.fetchEnergy(c)
            energy = e
            streak = s
            busy = false
        }
    }

    LaunchedEffect(ch?.id) { if (ch != null) reload() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("成长任务", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "能量 ${energy ?: "—"} · 连续签到 ${streak ?: "—"} 天",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { reload() }) {
                        Text(if (busy) "刷新中…" else "刷新")
                    }
                    Button(enabled = !busy && ch != null, onClick = {
                        val c = ch ?: return@Button
                        busy = true
                        scope.launch {
                            val (ok, fail, m) = WorkBuddyApi.claimAllTasks(c)
                            msg = m
                            tasks = WorkBuddyApi.fetchTasks(c).first
                            busy = false
                        }
                    }) { Text("一键领取") }
                    OutlinedButton(enabled = !busy && ch != null, onClick = { showAutoConfirm = true }) {
                        Text("自动完成")
                    }
                }
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "只能领取已完成的任务；需要真实使用行为的任务（如对话、使用专家）请到任务中心完成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                if (tasks.isEmpty()) {
                    Text(
                        if (busy) "加载中…" else "暂无任务",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                tasks.forEachIndexed { idx, t ->
                    if (idx > 0) HorizontalDivider()
                    TaskItem(
                        t = t,
                        onClaim = {
                            val c = ch ?: return@TaskItem
                            scope.launch {
                                val (ok, m) = WorkBuddyApi.claimTask(c, t.code)
                                msg = m
                                tasks = WorkBuddyApi.fetchTasks(c).first
                            }
                        },
                        onAccept = {
                            val c = ch ?: return@TaskItem
                            scope.launch {
                                val (ok, m) = WorkBuddyApi.acceptTasks(c, listOf(t.code))
                                msg = m
                                tasks = WorkBuddyApi.fetchTasks(c).first
                            }
                        },
                    )
                }
            }
        }
    }

    if (showAutoConfirm) {
        AlertDialog(
            onDismissRequest = { showAutoConfirm = false },
            title = { Text("自动完成任务") },
            text = {
                Text(
                    "将对可自动化的任务执行真实操作（对话、领养 Buddy 等），会消耗少量积分，" +
                        "并可能触发上游风控。仅建议在自己的账号上低频使用。是否继续？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAutoConfirm = false
                    val c = ch ?: return@TextButton
                    busy = true
                    msg = "准备执行…"
                    scope.launch {
                        val results = TaskAutomationEngine().run(c) { progress -> msg = progress }
                        // 把自动完成结果写入用量库，保证「用量」页统计完整
                        val app = ctx.applicationContext as? PocketGatewayApp
                        results.forEach { r ->
                            app?.usageDb?.recordAutoTask(
                                c.id, c.name, "auto_${r.code}", r.ok, r.message,
                            )
                        }
                        msg = if (results.isEmpty()) "没有可自动执行的任务"
                        else "自动完成：成功 ${results.count { it.ok }} / ${results.size}"
                        tasks = WorkBuddyApi.fetchTasks(c).first
                        val (e, s) = WorkBuddyApi.fetchEnergy(c)
                        energy = e
                        streak = s
                        busy = false
                    }
                }) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { showAutoConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TaskItem(
    t: WorkBuddyApi.GrowthTask,
    onClaim: () -> Unit,
    onAccept: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            when {
                t.claimable -> Button(onClick = onClaim) { Text("领取") }
                !t.accepted -> OutlinedButton(onClick = onAccept) { Text("接取") }
            }
        }
        if (t.desc.isNotBlank()) {
            Text(t.desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val meta = buildList {
            if (t.progressText.isNotBlank()) add(t.progressText)
            if (t.credit > 0) add("+${t.credit} 积分")
            add(t.statusText)
        }
        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!t.claimable && !t.claimed && !t.locked) {
            Text(
                if (TaskActionRegistry.canAuto(t.code)) "可自动完成"
                else "需手动：${TaskActionRegistry.manualReason(t.code)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ======================= 通用 =======================

@Composable
fun InfoLine(label: String, value: String, onCopy: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            onCopy?.let { OutlinedButton(onClick = it) { Text("复制") } }
        }
    }
}

/** 复制到剪贴板。 */
fun copyToClipboard(ctx: android.content.Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("pocketgateway", text))
    }
}
