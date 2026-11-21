package com.pocketgateway.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketgateway.app.PocketGatewayApp
import com.pocketgateway.app.core.store.UsageDatabase
import com.pocketgateway.app.ui.viewmodel.GatewayViewModel
import kotlinx.coroutines.launch

/**
 * 用量统计页面。
 *
 * 显示自动任务成功率统计、最近任务记录，并支持清理历史。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun UsageScreen(vm: GatewayViewModel) {
    val context = LocalContext.current
    val usageDb = remember { (context.applicationContext as? PocketGatewayApp)?.usageDb }
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf<Map<String, UsageDatabase.ChannelStats>>(emptyMap()) }
    var typeStats by remember { mutableStateOf<List<UsageDatabase.TaskTypeStats>>(emptyList()) }
    var recentTasks by remember { mutableStateOf<List<UsageDatabase.TaskRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val gwTotal by vm.total.collectAsState()
    val gwSuccess by vm.success.collectAsState()

    // 加载统计数据（refreshKey 变化时重新加载）
    LaunchedEffect(refreshKey) {
        if (usageDb == null) {
            showError = "数据库未初始化"
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        showError = null
        runCatching {
            stats = usageDb.statsByChannel()
            typeStats = usageDb.statsByType()
            recentTasks = usageDb.recentTasks(limit = 50)
        }.onFailure { showError = "加载失败：${it.message}" }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("用量统计") },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Filled.Assessment, contentDescription = "刷新")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            usageDb?.pruneOld(7)
                            refreshKey++
                        }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "清理历史")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                showError != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = showError!!, color = MaterialTheme.colorScheme.error)
                    }
                }
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // 网关请求统计（日志口径）
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("网关请求（本次运行）", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val fail = (gwTotal - gwSuccess).coerceAtLeast(0)
                                    val rate = if (gwTotal == 0L) 0.0 else gwSuccess.toDouble() / gwTotal
                                    Text("总请求：$gwTotal")
                                    Text("成功：$gwSuccess · 失败：$fail (${String.format("%.1f", rate * 100)}%)")
                                    Text(
                                        "来源：网关日志，App 重启后清零；统计聊天与 embeddings 请求。",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // 总体统计卡片
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("总体统计", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val total = stats.values.sumOf { it.total }
                                    val success = stats.values.sumOf { it.success }
                                    val rate = if (total == 0) 0.0 else success.toDouble() / total
                                    Text("总任务数：$total")
                                    Text("成功数：$success (${String.format("%.1f", rate * 100)}%)")
                                    Text(
                                        "统计口径：签到 / 领奖 / 自动完成的执行记录；不含聊天请求。",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // 按任务类型统计
                        if (typeStats.isNotEmpty()) {
                            item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text("按任务类型", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        typeStats.forEach { t ->
                                            Text(
                                                "${taskTypeLabel(t.taskType)}：${t.success}/${t.total} 成功" +
                                                    "（${String.format("%.1f", t.rate * 100)}%）",
                                                fontSize = 14.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 空状态
                        if (stats.isEmpty() && recentTasks.isEmpty()) {
                            item {
                                Text(
                                    "暂无自动任务记录。\n启用 APP 直连渠道后，每日签到与任务领取会自动记录在这里。",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // 按渠道统计
                        items(stats.entries.sortedByDescending { it.value.total }) { entry ->
                            val chStats = entry.value
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (chStats.rate >= 0.8f) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                                ),
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = chStats.channelName.ifBlank { entry.key },
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 16.sp,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        if (chStats.rate >= 0.8f) {
                                            Icon(
                                                Icons.Filled.CheckCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        } else {
                                            Icon(
                                                Icons.Filled.Warning,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("总任务：${chStats.total}")
                                    Text("成功：${chStats.success} (${String.format("%.1f", chStats.rate * 100)}%)")
                                }
                            }
                        }

                        // 最近任务记录
                        item {
                            Text(
                                "最近任务",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(recentTasks) { task ->
                            TaskRow(task)
                        }
                    }
                }
            }
        }
    }
}

private fun taskTypeLabel(type: String): String = when {
    type == "daily_checkin" -> "每日签到"
    type == "claim_tasks" -> "任务领取"
    type.startsWith("auto_") -> "自动完成"
    else -> type
}

@Composable
private fun TaskRow(task: UsageDatabase.TaskRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (task.success) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (task.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = if (task.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "${taskTypeLabel(task.taskType)} · ${task.channelName}", fontSize = 14.sp)
                if (!task.message.isNullOrBlank()) {
                    Text(
                        text = task.message,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(task.createdAt)),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
