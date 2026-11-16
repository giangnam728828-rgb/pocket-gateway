package com.pocketgateway.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.ui.viewmodel.GatewayViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(vm: GatewayViewModel) {
    val cfg by vm.config.collectAsState()
    var editing by remember { mutableStateOf<Channel?>(null) }
    var confirmDelete by remember { mutableStateOf<Channel?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("渠道管理（${cfg.channels.size}）") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Channel.empty() }) {
                Icon(Icons.Filled.Add, contentDescription = "添加渠道")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (cfg.channels.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有渠道", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "点击右下角 + 添加你的第一个模型渠道",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(cfg.channels, key = { it.id }) { channel ->
                        ChannelItem(
                            channel = channel,
                            onToggle = { vm.setChannelEnabled(channel.id, it) },
                            onEdit = { editing = channel },
                            onDelete = { confirmDelete = channel },
                        )
                    }
                }
            }
        }
    }

    editing?.let { channel ->
        ChannelEditDialog(
            initial = channel,
            onDismiss = { editing = null },
            onSave = { vm.upsertChannel(it); editing = null },
        )
    }

    confirmDelete?.let { channel ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除渠道") },
            text = { Text("确定删除「${channel.name}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteChannel(channel.id); confirmDelete = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ChannelItem(
    channel: Channel,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (channel.enabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface,
        ),
        onClick = onEdit,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Switch(checked = channel.enabled, onCheckedChange = onToggle)
            }
            Text(
                "${channel.type.displayName} · ${channel.baseUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.models.isNotEmpty()) {
                Text(
                    "模型：${channel.models.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
