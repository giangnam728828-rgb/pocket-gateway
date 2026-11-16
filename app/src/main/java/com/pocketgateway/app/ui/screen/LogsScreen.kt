package com.pocketgateway.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketgateway.app.core.model.RequestLog
import com.pocketgateway.app.ui.viewmodel.GatewayViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(vm: GatewayViewModel) {
    val logs by vm.logs.collectAsState()
    val total by vm.total.collectAsState()
    val success by vm.success.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("请求日志") },
                actions = { TextButton(onClick = { vm.clearLogs() }) { Text("清空") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("总计 $total", fontSize = 13.sp)
                Text("成功 $success", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text(
                    "失败 ${total - success}", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无请求记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(logs, key = { it.id }) { log -> LogItem(log) }
                }
            }
        }
    }
}

@Composable
private fun LogItem(log: RequestLog) {
    val time = rememberTime(log.timestamp)
    val statusColor = when {
        log.status in 200..299 -> MaterialTheme.colorScheme.primary
        log.status in 400..599 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (log.success) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                time, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "  ${log.status}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = statusColor,
            )
            if (log.stream) {
                Text("  STREAM", fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            Text("  ${log.durationMs}ms", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Text(
            "${log.method} ${log.path}",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row {
            if (log.model.isNotBlank()) {
                Text("模型 ${log.model}  ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (log.channelName.isNotBlank()) {
                Text("渠道 ${log.channelName}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (log.message.isNotBlank() && !log.success) {
            Text(log.message, fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun rememberTime(timestamp: Long): String = androidx.compose.runtime.remember(timestamp) {
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
