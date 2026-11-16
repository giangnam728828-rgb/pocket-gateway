package com.pocketgateway.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketgateway.app.ui.viewmodel.GatewayViewModel
import com.pocketgateway.app.util.generateQrBitmap

@Composable
fun DashboardScreen(vm: GatewayViewModel) {
    val context = LocalContext.current
    val cfg by vm.config.collectAsState()
    val baseUrl by vm.baseUrl.collectAsState()
    val total by vm.total.collectAsState()
    val success by vm.success.collectAsState()
    val enabledCount = cfg.channels.count { it.enabled }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "口袋网关",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        // 状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (enabledCount > 0) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = if (enabledCount > 0) "● 就绪" else "○ 未配置渠道",
                    color = if (enabledCount > 0) Color(0xFF2E7D32) else Color(0xFFEF6C00),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text("已启用 $enabledCount 个渠道 · 端口 ${cfg.port}", fontSize = 13.sp)
            }
        }

        // 服务控制
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { vm.startGateway(); Toast.makeText(context, "网关已启动", Toast.LENGTH_SHORT).show() },
                modifier = Modifier.weight(1f),
            ) { Text("启动网关") }
            OutlinedButton(
                onClick = { vm.stopGateway(); Toast.makeText(context, "已停止", Toast.LENGTH_SHORT).show() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("停止") }
        }

        // API 地址 + 二维码
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("API 地址（OpenAI 兼容）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                val qr = rememberQr(baseUrl)
                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "API 地址二维码",
                        modifier = Modifier.size(180.dp).background(Color.White).padding(8.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    text = baseUrl,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    copyToClipboard(context, baseUrl)
                    Toast.makeText(context, "已复制 API 地址", Toast.LENGTH_SHORT).show()
                }) { Text("复制地址") }
            }
        }

        // 统计
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Row(modifier = Modifier.padding(20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem("总请求", total.toString())
                StatItem("成功", success.toString())
                StatItem("失败", (total - success).toString())
            }
        }

        Text(
            text = "用法：把任意 OpenAI SDK 的 base_url 设为上方地址即可。" +
                "若设置了访问令牌，需在 api_key 中填入该令牌。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun rememberQr(text: String): android.graphics.Bitmap? =
    androidx.compose.runtime.remember(text) { runCatching { generateQrBitmap(text, 512) }.getOrNull() }

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("baseUrl", text))
}
