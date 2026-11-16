package com.pocketgateway.app.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketgateway.app.core.model.RouteStrategy
import com.pocketgateway.app.ui.viewmodel.GatewayViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: GatewayViewModel) {
    val context = LocalContext.current
    val cfg by vm.config.collectAsState()
    var port by remember(cfg.port) { mutableStateOf(cfg.port.toString()) }
    var token by remember(cfg.accessToken) { mutableStateOf(cfg.accessToken) }
    var timeout by remember(cfg.defaultTimeoutMs) { mutableStateOf(cfg.defaultTimeoutMs.toString()) }
    var logEnabled by remember(cfg.logEnabled) { mutableStateOf(cfg.logEnabled) }
    var listenAll by remember(cfg.listenOnAllInterfaces) { mutableStateOf(cfg.listenOnAllInterfaces) }
    var importText by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("网关设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = port, onValueChange = { port = it },
                        label = { Text("监听端口") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        value = token, onValueChange = { token = it },
                        label = { Text("访问令牌（可选）") },
                        supportingText = { Text("非空时客户端需提供 Bearer 令牌，留空则不鉴权") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        value = timeout, onValueChange = { timeout = it },
                        label = { Text("默认超时（毫秒）") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = listenAll, onCheckedChange = { listenAll = it })
                        Text("允许局域网访问（监听所有网卡）")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = logEnabled, onCheckedChange = { logEnabled = it })
                        Text("记录请求日志")
                    }
                    Button(
                        onClick = {
                            val p = port.toIntOrNull()?.coerceIn(1, 65535) ?: 8317
                            vm.updateConfig { it.copy(
                                port = p,
                                accessToken = token,
                                defaultTimeoutMs = timeout.toLongOrNull()?.coerceAtLeast(1000) ?: 120_000,
                                listenOnAllInterfaces = listenAll,
                                logEnabled = logEnabled,
                            ) }
                            Toast.makeText(context, "已保存（端口变更需重启网关）", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存网关设置") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("路由策略", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Column(modifier = Modifier.selectableGroup()) {
                        RouteStrategy.entries.forEach { strategy ->
                            val label = when (strategy) {
                                RouteStrategy.ROUND_ROBIN -> "轮询（按权重）"
                                RouteStrategy.RANDOM -> "随机"
                                RouteStrategy.FIRST -> "始终第一个"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = cfg.routeStrategy == strategy,
                                        onClick = { vm.updateConfig { it.copy(routeStrategy = strategy) } },
                                        role = Role.RadioButton,
                                    )
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = cfg.routeStrategy == strategy, onClick = null)
                                Text(label)
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("配置备份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val json = vm.exportConfig()
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("config", json))
                            Toast.makeText(context, "配置已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }) { Text("导出") }
                        OutlinedButton(onClick = { showImport = !showImport }) { Text("导入") }
                    }
                    if (showImport) {
                        OutlinedTextField(
                            value = importText, onValueChange = { importText = it },
                            label = { Text("粘贴配置 JSON") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                        )
                        Button(onClick = {
                            vm.importConfig(importText) { ok, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                if (ok) showImport = false
                            }
                        }) { Text("确认导入") }
                    }
                }
            }

            Text(
                "提示：本 App 仅提供渠道可插拔的通用 API 网关框架。请自行确保你有权访问所配置的" +
                    "上游服务，并遵守其服务条款。作者不对任何滥用行为负责。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
