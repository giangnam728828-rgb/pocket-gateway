package com.pocketgateway.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketgateway.app.core.access.AccessPreset
import com.pocketgateway.app.core.access.AccessPresets
import com.pocketgateway.app.ui.viewmodel.GatewayViewModel

/**
 * 接入中心：把网关地址、密钥、模型名与常见客户端的配置一次给全。
 * 替代原来的「模型」Tab。
 */
@Composable
fun AccessCenterScreen(vm: GatewayViewModel) {
    val config by vm.config.collectAsState()
    val ctx = LocalContext.current

    val baseUrl = vm.baseUrl.value
    val apiKey = config.accessToken
    val model = config.channels
        .firstOrNull { it.models.isNotEmpty() }
        ?.models?.firstOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: "gpt-4o"

    var query by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("接入信息", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "任何支持 OpenAI 兼容接口的客户端都可以接入。点任意一行可复制。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InfoLine("BASE URL", baseUrl) { copyToClipboard(ctx, baseUrl) }
                InfoLine("API KEY", apiKey.ifBlank { "未设置（网关不鉴权）" }) {
                    copyToClipboard(ctx, apiKey)
                }
                InfoLine("模型", model) { copyToClipboard(ctx, model) }
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
            PresetCard(preset, baseUrl, apiKey, model)
        }

        Text(
            "提示：手机上的其他 App 若只能填一个地址，请使用上方 BASE URL；" +
                "若客户端要求填写 /v1，请确保地址已包含 /v1。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetCard(
    preset: AccessPreset,
    baseUrl: String,
    apiKey: String,
    model: String,
) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

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
                InfoLine("BASE URL", baseUrl) { copyToClipboard(ctx, baseUrl) }
                InfoLine("API KEY", apiKey.ifBlank { "未设置" }) { copyToClipboard(ctx, apiKey) }
                InfoLine("模型", model) { copyToClipboard(ctx, model) }
                OutlinedButton(onClick = {
                    copyToClipboard(ctx, preset.jsonTemplate(baseUrl, apiKey, model))
                }) { Text("复制 JSON 配置") }
            }
        }
    }
}
