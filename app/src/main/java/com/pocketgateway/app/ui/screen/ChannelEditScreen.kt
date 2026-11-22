package com.pocketgateway.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.pocketgateway.app.core.adapter.ModelFetcher
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderPresets
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.util.SignUtil

/**
 * 渠道编辑对话框。覆盖 Channel 的全部配置字段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelEditDialog(
    initial: Channel,
    onDismiss: () -> Unit,
    onSave: (Channel) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var type by remember { mutableStateOf(initial.type) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var authHeader by remember { mutableStateOf(initial.authHeader) }
    var extraHeaders by remember { mutableStateOf(initial.extraHeaders) }
    var modelsText by remember { mutableStateOf(initial.models.joinToString("\n")) }
    var mappingText by remember { mutableStateOf(initial.modelMapping.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
    var pathOverridesText by remember { mutableStateOf(initial.pathOverrides.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
    var requestMethod by remember { mutableStateOf(initial.requestMethod) }
    var queryParamsText by remember { mutableStateOf(initial.queryParams) }
    var requestTemplate by remember { mutableStateOf(initial.requestTemplate) }
    var responseContentPath by remember { mutableStateOf(initial.responseContentPath) }
    var signHeader by remember { mutableStateOf(initial.signHeader) }
    var signAlgorithm by remember { mutableStateOf(initial.signAlgorithm) }
    var signTemplate by remember { mutableStateOf(initial.signTemplate) }
    var signKey by remember { mutableStateOf(initial.signKey) }
    var appKind by remember { mutableStateOf(initial.appKind) }
    var deviceToken by remember { mutableStateOf(initial.deviceToken) }
    var refreshToken by remember { mutableStateOf(initial.refreshToken) }
    var sanitizeEnabled by remember { mutableStateOf(initial.sanitizeEnabled) }
    var methodExpanded by remember { mutableStateOf(false) }
    var signAlgoExpanded by remember { mutableStateOf(false) }
    var privateExpanded by remember { mutableStateOf(false) }
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchMsg by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var privateLabel by remember { mutableStateOf("私有接口模板（APP/网页，一键填骨架）") }
    var weight by remember { mutableStateOf(initial.weight.toString()) }
    var timeout by remember { mutableStateOf(if (initial.timeoutMs > 0) initial.timeoutMs.toString() else "") }
    var typeExpanded by remember { mutableStateOf(false) }
    var presetExpanded by remember { mutableStateOf(false) }
    var headerTplExpanded by remember { mutableStateOf(false) }
    var presetLabel by remember { mutableStateOf("官方 API 预设（一键填充）") }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.95f),
        title = { Text(if (initial.id.startsWith("ch_") && initial.name == "新渠道") "添加渠道" else "编辑渠道") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 私有接口模板（APP / 网站内部接口）——放在最上方，最常用
                ExposedDropdownMenuBox(
                    expanded = privateExpanded,
                    onExpandedChange = { privateExpanded = it },
                ) {
                    OutlinedTextField(
                        value = privateLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("私有接口模板（APP / 网页）") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(privateExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        supportingText = { Text("一键填入 APP/网页内部接口的配置骨架，补上你自己的地址与凭证") },
                    )
                    ExposedDropdownMenu(
                        expanded = privateExpanded,
                        onDismissRequest = { privateExpanded = false },
                    ) {
                        ProviderPresets.PRIVATE.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("[${p.kind}] ${p.label}")
                                        if (p.note.isNotBlank()) {
                                            Text(p.note, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                onClick = {
                                    name = p.label
                                    type = ProviderType.CUSTOM
                                    if (p.baseUrl.isNotBlank()) baseUrl = p.baseUrl
                                    requestMethod = p.requestMethod
                                    if (p.authHeader.isNotBlank()) authHeader = p.authHeader
                                    if (p.extraHeaders.isNotBlank()) extraHeaders = p.extraHeaders
                                    if (p.requestTemplate.isNotBlank()) requestTemplate = p.requestTemplate
                                    if (p.responseContentPath.isNotBlank()) responseContentPath = p.responseContentPath
                                    if (p.signHeader.isNotBlank()) signHeader = p.signHeader
                                    if (p.signAlgorithm.isNotBlank()) signAlgorithm = p.signAlgorithm
                                    if (p.signTemplate.isNotBlank()) signTemplate = p.signTemplate
                                    privateLabel = "已选择：[${p.kind}] ${p.label}"
                                    privateExpanded = false
                                },
                            )
                        }
                    }
                }

                // 官方 API 预设（一键填充）
                ExposedDropdownMenuBox(
                    expanded = presetExpanded,
                    onExpandedChange = { presetExpanded = it },
                ) {
                    OutlinedTextField(
                        value = presetLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("官方 API 预设") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(presetExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        supportingText = { Text("选择官方公开 API 平台，自动填入地址/鉴权/模型名") },
                    )
                    ExposedDropdownMenu(
                        expanded = presetExpanded,
                        onDismissRequest = { presetExpanded = false },
                    ) {
                        ProviderPresets.OFFICIAL.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(p.label)
                                        if (p.note.isNotBlank()) {
                                            Text(p.note, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                onClick = {
                                    name = p.label
                                    type = p.type
                                    baseUrl = p.baseUrl
                                    authHeader = p.authHeader
                                    extraHeaders = p.extraHeaders
                                    modelsText = p.models.joinToString("\n")
                                    presetLabel = "已选择：${p.label}"
                                    presetExpanded = false
                                },
                            )
                        }
                        ProviderPresets.APP_DIRECT.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(p.label)
                                        if (p.note.isNotBlank()) {
                                            Text(p.note, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                onClick = {
                                    name = p.label
                                    type = ProviderType.APP_NATIVE
                                    appKind = p.appKind
                                    modelsText = p.models.joinToString("\n")
                                    // APP 直连不需要 Base URL / 鉴权头 / 模板，清空避免干扰
                                    baseUrl = ""
                                    authHeader = ""
                                    extraHeaders = ""
                                    requestTemplate = ""
                                    responseContentPath = ""
                                    presetLabel = "已选择：${p.label}"
                                    presetExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("渠道名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // 协议类型
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it },
                ) {
                    OutlinedTextField(
                        value = type.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("协议类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false },
                    ) {
                        ProviderType.entries.forEach { pt ->
                            DropdownMenuItem(
                                text = { Text(pt.displayName) },
                                onClick = { type = pt; typeExpanded = false },
                            )
                        }
                    }
                }

                if (type != ProviderType.APP_NATIVE && type != ProviderType.TRAE) {
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = {
                        Text(
                            when (type) {
                                ProviderType.APP_NATIVE -> "账号凭证（Cookie/Token）"
                                ProviderType.TRAE -> "UserJWT（登录凭证）"
                                else -> "API Key（可留空）"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        when (type) {
                            ProviderType.APP_NATIVE -> Text("登录 APP 后复制 Cookie 或 Token；APP 直连无需 URL 与 API Key")
                            ProviderType.TRAE -> Text("在「账号」页登录 TRAE 后自动写入；也可手动粘贴 UserJWT")
                            else -> {}
                        }
                    },
                )

                if (type == ProviderType.TRAE) {
                    OutlinedTextField(
                        value = refreshToken, onValueChange = { refreshToken = it },
                        label = { Text("RefreshToken（用于自动续期）") },
                        supportingText = { Text("登录 TRAE 时自动保存；UserJWT 过期后用它换取新的 Token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                if (type == ProviderType.APP_NATIVE) {
                    OutlinedTextField(
                        value = appKind, onValueChange = { appKind = it },
                        label = { Text("APP 类型") },
                        placeholder = { Text("workbuddy") },
                        supportingText = { Text("决定使用哪套内置 APP 适配逻辑") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = deviceToken, onValueChange = { deviceToken = it },
                        label = { Text("X-Device-Token（可选）") },
                        placeholder = { Text("留空则不发送该头") },
                        supportingText = { Text("从官方桌面端登录态获取；有则填写可进一步对齐客户端指纹") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    // 请求体净化开关（会改写消息原文中的特定字符串）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("请求体净化", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "剥离可能触发上游拦截的指纹字符串（会改写消息原文）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = sanitizeEnabled, onCheckedChange = { sanitizeEnabled = it })
                    }
                }

                OutlinedTextField(
                    value = authHeader, onValueChange = { authHeader = it },
                    label = { Text("鉴权头（留空用默认值）") },
                    placeholder = { Text("Authorization: Bearer {key}") },
                    supportingText = { Text("支持 {key} 占位符；Claude 可填 x-api-key: {key}") },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = extraHeaders, onValueChange = { extraHeaders = it },
                    label = { Text("额外请求头") },
                    placeholder = { Text("每行一条，如：\nHTTP-Referer: https://xxx\nX-Title: MyApp") },
                    supportingText = { Text("支持 {key} 占位符；Cookie 也可在此自行添加") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                // 标准客户端请求头模板
                ExposedDropdownMenuBox(
                    expanded = headerTplExpanded,
                    onExpandedChange = { headerTplExpanded = it },
                ) {
                    OutlinedTextField(
                        value = "请求头模板（标准客户端）",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("请求头模板") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(headerTplExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        supportingText = { Text("仅标准 UA/Accept 头，用于自有服务识别客户端；不含风控绕过字段") },
                    )
                    ExposedDropdownMenu(
                        expanded = headerTplExpanded,
                        onDismissRequest = { headerTplExpanded = false },
                    ) {
                        ProviderPresets.HEADER_TEMPLATES.forEach { tpl ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(tpl.label)
                                        if (tpl.note.isNotBlank()) {
                                            Text(tpl.note, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                onClick = {
                                    extraHeaders = tpl.headers
                                    headerTplExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = modelsText, onValueChange = { modelsText = it },
                    label = { Text("对外模型列表（每行一个）") },
                    placeholder = { Text("gpt-4o\ndeepseek-chat") },
                    supportingText = { Text("留空表示接受任意模型名") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                // 自动从上游拉取模型列表
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = !fetchingModels,
                        onClick = {
                            fetchingModels = true
                            fetchMsg = null
                            scope.launch {
                                val probe = Channel(
                                    id = initial.id,
                                    name = name,
                                    type = type,
                                    baseUrl = baseUrl.trim(),
                                    apiKey = apiKey,
                                    authHeader = authHeader.trim(),
                                    extraHeaders = extraHeaders,
                                    appKind = appKind.trim(),
                                    deviceToken = deviceToken.trim(),
                    sanitizeEnabled = sanitizeEnabled,
                                    pathOverrides = parsePairs(pathOverridesText),
                                )
                                val r = ModelFetcher.fetch(probe)
                                fetchingModels = false
                                r.onSuccess { list ->
                                    if (list.isEmpty()) {
                                        fetchMsg = "上游未返回模型"
                                    } else {
                                        modelsText = list.joinToString("\n")
                                        fetchMsg = "已获取 ${list.size} 个模型"
                                    }
                                }.onFailure { e ->
                                    fetchMsg = "获取失败：${e.message}"
                                }
                            }
                        },
                    ) { Text(if (fetchingModels) "获取中…" else "自动获取模型") }
                    fetchMsg?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                OutlinedTextField(
                    value = mappingText, onValueChange = { mappingText = it },
                    label = { Text("模型名映射") },
                    placeholder = { Text("每行一条，如：\ngpt-4o=gpt-4o-2024-08-06") },
                    supportingText = { Text("对外名=上游真实名") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                OutlinedTextField(
                    value = pathOverridesText, onValueChange = { pathOverridesText = it },
                    label = { Text("端点路径覆盖（高级）") },
                    placeholder = { Text("每行一条，如：\nchat=/v1/chat/completions") },
                    supportingText = { Text("键：chat / models / embeddings") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                // 自定义透传专属配置：请求方法 / 查询参数 / 请求体模板 / 响应映射
                if (type == ProviderType.CUSTOM) {
                    ExposedDropdownMenuBox(
                        expanded = methodExpanded,
                        onExpandedChange = { methodExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = requestMethod,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("请求方法") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(methodExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = methodExpanded,
                            onDismissRequest = { methodExpanded = false },
                        ) {
                            listOf("POST", "GET", "PUT", "PATCH", "DELETE").forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = { requestMethod = m; methodExpanded = false },
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = queryParamsText, onValueChange = { queryParamsText = it },
                        label = { Text("查询参数") },
                        placeholder = { Text("每行一条 key=value，如：\nsession=abc123") },
                        supportingText = { Text("拼接到请求 URL 的 ? 之后") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )

                    OutlinedTextField(
                        value = requestTemplate, onValueChange = { requestTemplate = it },
                        label = { Text("请求体模板（留空=透传 OpenAI 格式）") },
                        placeholder = { Text("{\"model\":\"{{model}}\",\"prompt\":{{messages}},\"key\":\"{{key}}\"}") },
                        supportingText = { Text("变量：{{model}} {{messages}} {{key}} {{stream}}；适配非 OpenAI 格式的私有接口") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )

                    OutlinedTextField(
                        value = responseContentPath, onValueChange = { responseContentPath = it },
                        label = { Text("响应内容路径（留空=原样透传）") },
                        placeholder = { Text("如：data.text 或 choices[0].message.content") },
                        supportingText = { Text("从上游 JSON 响应提取文本，组装成 OpenAI 格式") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    // 签名配置：适配带签名校验的 APP / 网页私有接口，持久保存在渠道中
                    OutlinedTextField(
                        value = signHeader, onValueChange = { signHeader = it },
                        label = { Text("签名头名称（留空=不签名）") },
                        placeholder = { Text("如：X-Sign、sign、X-Signature") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    ExposedDropdownMenuBox(
                        expanded = signAlgoExpanded,
                        onExpandedChange = { signAlgoExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = signAlgorithm.ifBlank { "none" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("签名算法") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(signAlgoExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = signAlgoExpanded,
                            onDismissRequest = { signAlgoExpanded = false },
                        ) {
                            SignUtil.ALGORITHMS.forEach { a ->
                                DropdownMenuItem(
                                    text = { Text(a) },
                                    onClick = { signAlgorithm = a; signAlgoExpanded = false },
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = signTemplate, onValueChange = { signTemplate = it },
                        label = { Text("签名内容模板") },
                        placeholder = { Text("app_key={{key}}&timestamp={{timestamp}}&data={{body}}") },
                        supportingText = { Text("变量：{{timestamp}} {{nonce}} {{body}} {{model}} {{key}}") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )

                    OutlinedTextField(
                        value = signKey, onValueChange = { signKey = it },
                        label = { Text("签名密钥（HMAC 用）") },
                        placeholder = { Text("md5/sha 可留空；hmac 类必填") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = weight, onValueChange = { weight = it },
                        label = { Text("权重") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = timeout, onValueChange = { timeout = it },
                        label = { Text("超时(ms)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val channel = Channel(
                    id = initial.id,
                    name = name.ifBlank { "未命名渠道" },
                    type = type,
                    enabled = initial.enabled,
                    baseUrl = if (type == ProviderType.TRAE && baseUrl.isBlank()) {
                        com.pocketgateway.app.core.appaccount.TraeAuth.CHAT_BASE
                    } else {
                        baseUrl.trim()
                    },
                    apiKey = apiKey,
                    authHeader = authHeader.trim(),
                    extraHeaders = extraHeaders,
                    models = modelsText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
                    modelMapping = parsePairs(mappingText),
                    pathOverrides = parsePairs(pathOverridesText),
                    requestMethod = requestMethod.ifBlank { "POST" },
                    queryParams = queryParamsText,
                    requestTemplate = requestTemplate,
                    responseContentPath = responseContentPath.trim(),
                    signHeader = signHeader.trim(),
                    signAlgorithm = signAlgorithm.trim(),
                    signTemplate = signTemplate,
                    signKey = signKey,
                    appKind = if (type == ProviderType.TRAE && appKind.isBlank() && apiKey.isNotBlank()) {
                        com.pocketgateway.app.core.appaccount.TraeAuth.uidFromJwt(apiKey)
                    } else {
                        appKind.trim()
                    },
                    deviceToken = deviceToken.trim(),
                    refreshToken = refreshToken.trim(),
                    sanitizeEnabled = sanitizeEnabled,
                    weight = weight.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    timeoutMs = timeout.toLongOrNull() ?: 0L,
                )
                onSave(channel)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun parsePairs(text: String): Map<String, String> = buildMap {
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && "=" in it }.forEach { line ->
        val i = line.indexOf('=')
        put(line.substring(0, i).trim(), line.substring(i + 1).trim())
    }
}
