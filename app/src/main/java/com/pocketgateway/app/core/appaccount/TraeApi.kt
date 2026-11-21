package com.pocketgateway.app.core.appaccount

import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.util.AppJson
import com.pocketgateway.app.util.modelName
import com.pocketgateway.app.util.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * TRAE（Remote / Solo）上游接口。
 *
 * 协议（依据开源实现 Trae2api-cn 的 trae_remote_client.py 与 sse.py 实测）：
 *
 * 1. `POST {base}/api/remote/v1/chat_sessions` 创建会话，返回
 *    `data.chat_session_id` 与 `data.message_id`；
 * 2. `GET  {base}/api/remote/v1/chat_sessions/{id}/events?reply_to_message_id={mid}`
 *    订阅 SSE 事件流；
 * 3. 事件流里 `plan_item` / `message` 等事件的 `content` 是**累积快照**，
 *    网关按公共前后缀求增量后转成 OpenAI `chat.completion.chunk`；
 * 4. 结束事件为 `done`。
 *
 * 请求头使用官方 Web 客户端形态（Cloud-IDE-JWT + solo.trae.cn 来源），
 * 属于登录协议的必需部分；同时配合网关的本地节流/冷却降低风控命中率。
 */
object TraeApi {

    const val BASE = "https://trae-api-cn.mchost.guru"
    private const val REMOTE = "$BASE/api/remote/v1"
    private const val MODELS_URL =
        "$REMOTE/models?functions=solo_agent_remote%2Csolo_work_remote%2Csolo_design_remote&show_custom_model=true"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** 心跳事件：转发为 SSE 注释，避免客户端误判流已结束。 */
    val HEARTBEATS = setOf("heartbeat", "keepalive", "ping")

    private val modelCache = ConcurrentHashMap<String, JsonObject>()

    data class TraeModel(val id: String, val displayName: String = "")

    data class TraeEvent(val name: String, val data: JsonObject?)

    data class TraeAggregate(val text: String, val error: String = "")

    // ---------- 模型 ----------

    suspend fun fetchModels(ch: Channel): Result<List<TraeModel>> = withContext(Dispatchers.IO) {
        if (ch.apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("TRAE 未登录：请先完成账号授权"))
        }
        val res = http(MODELS_URL, "GET", ch.apiKey, null, "application/json", 30_000)
        if (res.status !in 200..299) {
            return@withContext Result.failure(
                IllegalStateException("TRAE 模型列表 HTTP ${res.status}：${res.body.take(200)}")
            )
        }
        runCatching { parseModels(ch, res.body) }.mapCatching { list ->
            if (list.isEmpty()) error("未解析到可用模型：${res.body.take(200)}") else list
        }
    }

    private fun parseModels(ch: Channel, raw: String): List<TraeModel> {
        val root = AppJson.parseToJsonElement(raw) as? JsonObject ?: return emptyList()
        val data = (root["data"] as? JsonObject) ?: root
        val groups = (data["list"] as? JsonArray)
            ?: (data["model_list"] as? JsonArray)
            ?: (data["models"] as? JsonArray)
            ?: return emptyList()

        val groupObjects = groups.mapNotNull { it as? JsonObject }
        val preferred = groupObjects.filter { groupFunction(it) == "solo_agent_remote" }
            .ifEmpty { groupObjects.filter { it["models"] is JsonArray } }
            .ifEmpty { groupObjects }

        val out = LinkedHashMap<String, TraeModel>()
        preferred.forEach { group ->
            val models = group["models"] as? JsonArray ?: return@forEach
            models.forEach { item ->
                val o = item as? JsonObject ?: return@forEach
                val id = (o.string("config_name") ?: o.string("configName") ?: o.string("name")
                    ?: o.string("model_name") ?: o.string("modelName"))?.trim().orEmpty()
                if (id.isBlank()) return@forEach
                modelCache[cacheKey(ch, id)] = o
                val display = o.string("display_name") ?: o.string("displayName")
                    ?: o.string("name") ?: id
                out.putIfAbsent(id, TraeModel(id, display))
            }
        }
        return out.values.toList()
    }

    private fun groupFunction(group: JsonObject): String =
        (group.string("function") ?: group.string("agent_type") ?: "").trim().lowercase()

    private fun accountKey(ch: Channel): String =
        ch.appKind.ifBlank { ch.apiKey.takeLast(16) }

    private fun cacheKey(ch: Channel, model: String): String = "${accountKey(ch)}:$model"

    private fun descriptorFor(ch: Channel, model: String): JsonObject? =
        modelCache[cacheKey(ch, model)]

    // ---------- 会话与事件流 ----------

    /**
     * 创建会话并打开事件流。HTTP 错误通过 [TraeStream.status] 返回，
     * 网络异常才会抛出。
     */
    suspend fun open(ch: Channel, request: JsonObject, timeoutMs: Long): TraeStream =
        withContext(Dispatchers.IO) {
            val model = request.modelName().orEmpty()
            var descriptor = descriptorFor(ch, model)
            if (descriptor == null && model.isNotBlank() && !model.equals("auto", true)) {
                // 冷启动：先拉一次模型目录，拿到 manual 模式需要的完整 custom_model 描述
                runCatching { fetchModels(ch) }
                descriptor = descriptorFor(ch, model)
            }

            val session = createSession(ch, request, descriptor, timeoutMs)
            if (session.sessionId.isBlank() || session.messageId.isBlank()) {
                return@withContext TraeStream(
                    status = session.status,
                    errorBody = session.error.ifBlank { "TRAE 创建会话失败" },
                    conn = null,
                    reader = null,
                )
            }

            val url = "$REMOTE/chat_sessions/${session.sessionId}/events" +
                "?reply_to_message_id=${session.messageId}"
            val conn = connect(url, "GET", ch.apiKey, null, "text/event-stream", timeoutMs)
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                runCatching { conn.disconnect() }
                return@withContext TraeStream(0, e.message ?: "网络错误", null, null)
            }
            if (code !in 200..299) {
                val text = readText(conn, code)
                runCatching { conn.disconnect() }
                TraeStream(code, text, null, null)
            } else {
                TraeStream(code, "", conn, BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)))
            }
        }

    private data class SessionResult(
        val sessionId: String,
        val messageId: String,
        val status: Int,
        val error: String,
    )

    private fun createSession(
        ch: Channel,
        request: JsonObject,
        descriptor: JsonObject?,
        timeoutMs: Long,
    ): SessionResult {
        // 新版协议：initial_message 嵌套；失败时回退旧版平铺结构
        val nested = buildSessionPayload(ch, request, descriptor, flat = false)
        val first = http(
            "$REMOTE/chat_sessions", "POST", ch.apiKey, nested, "application/json", timeoutMs,
        )
        parseSession(first)?.let { return it }
        // 仅当协议结构不匹配时才回退旧版平铺体；401/403/429 直接返回，
        // 避免多打一次上游放大风控。
        if (first.status !in 200..299 && first.status !in setOf(400, 404, 405, 422)) {
            return SessionResult(
                "", "", first.status,
                "TRAE 创建会话失败：HTTP ${first.status} ${first.body.take(300)}",
            )
        }

        val flat = buildSessionPayload(ch, request, descriptor, flat = true)
        val second = http(
            "$REMOTE/chat_sessions", "POST", ch.apiKey, flat, "application/json", timeoutMs,
        )
        parseSession(second)?.let { return it }

        val status = when {
            second.status >= 400 -> second.status
            first.status >= 400 -> first.status
            else -> 502
        }
        val body = second.body.ifBlank { first.body }
        return SessionResult("", "", status, "TRAE 创建会话失败：HTTP $status ${body.take(300)}")
    }

    private fun parseSession(res: HttpResult): SessionResult? {
        if (res.status !in 200..299) return null
        val root = runCatching { AppJson.parseToJsonElement(res.body) as? JsonObject }.getOrNull()
            ?: return null
        val data = (root["data"] as? JsonObject) ?: root
        val sid = data.string("chat_session_id") ?: data.string("chatSessionId")
            ?: data.string("session_id") ?: ""
        val mid = data.string("message_id") ?: data.string("messageId")
            ?: data.string("user_message_id") ?: ""
        if (sid.isBlank() || mid.isBlank()) return null
        return SessionResult(sid, mid, res.status, "")
    }

    private fun buildSessionPayload(
        ch: Channel,
        request: JsonObject,
        descriptor: JsonObject?,
        flat: Boolean,
    ): String {
        val initial = buildInitialMessage(ch, request, descriptor)
        val payload = if (flat) {
            buildJsonObject { initial.entries.forEach { (k, v) -> put(k, v) } }
        } else {
            buildJsonObject {
                put("mode", "code")
                put("environment_id", "default")
                put("initial_message", initial)
                put("env", "remote")
                put("auto_create_project", false)
                put("origin", "web")
            }
        }
        return AppJson.encodeToString(JsonObject.serializer(), payload)
    }

    private fun buildInitialMessage(
        ch: Channel,
        request: JsonObject,
        descriptor: JsonObject?,
    ): JsonObject {
        val requested = request.modelName().orEmpty()
        val modelName = descriptor?.let { boundModelName(it, requested) }
            ?: requested.ifBlank { "auto" }

        val queryParts = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("data", buildJsonObject { put("content", flattenQuery(request)) })
            })
        }
        val commonParams = buildJsonObject {
            put("language", "zh-cn")
            put("app_language", "zh-CN")
            put("quality", "stable")
            put("app_version", "1.0.0.1229")
            put("web_id", "")
            put("user_identity", "Free")
            put("is_freshman", "0")
            put("biz_user_id", ch.appKind)
            put("user_unique_id", "")
            put("scope", "marscode-cn")
            put("tenant", "marscode")
            put("region", "cn")
            put("aiRegion", "cn")
            put("is_privacy_mode", 0)
            put("privacy_mode", "off")
            put("solo_chat_mode", "code")
            put("biz_session_id", stableSessionId(ch, modelName))
        }

        return buildJsonObject {
            put("chat_session_id", "")
            put("content", buildJsonArray { })
            put("query", AppJson.encodeToString(JsonArray.serializer(), queryParts))
            put("model_name", modelName)
            put("agent_type", "solo_agent_remote")
            put("agent_id", "solo_agent_remote")
            put("model_selection_strategy", if (descriptor != null) "manual" else "auto")
            put("common_params", AppJson.encodeToString(JsonObject.serializer(), commonParams))
            if (descriptor != null) {
                put("model_config_source", descriptor.intValue("config_source", 1))
                put("model_is_preset", descriptor.boolValue("is_preset", true))
                put("model_provider", descriptor.string("provider").orEmpty())
                put("custom_model", normalizeCustomModel(descriptor))
            }
        }
    }

    private fun boundModelName(descriptor: JsonObject, requested: String): String {
        val candidates = listOf("config_name", "model_name", "name")
            .mapNotNull { descriptor.string(it)?.trim() }
            .filter { it.isNotEmpty() }
        return candidates.firstOrNull { it.equals(requested, ignoreCase = true) }
            ?: candidates.firstOrNull()
            ?: requested
    }

    /** 把 models 接口返回的原始描述补全成 Web 端会发送的 custom_model。 */
    private fun normalizeCustomModel(raw: JsonObject): JsonObject {
        val name = (raw.string("config_name") ?: raw.string("configName")
            ?: raw.string("name") ?: raw.string("model_name")
            ?: raw.string("modelName") ?: "").trim()
        val map = raw.toMutableMap()
        map["config_name"] = JsonPrimitive(name)
        map["model_name"] = JsonPrimitive(
            (raw.string("model_name") ?: raw.string("modelName") ?: name).trim()
        )
        if (map["config_source"] == null) map["config_source"] = JsonPrimitive(1)
        if (map["provider"] == null) map["provider"] = JsonPrimitive("")
        if (map["ak"] == null) map["ak"] = JsonPrimitive("")
        if (map["sk"] == null) map["sk"] = JsonPrimitive("")
        if (map["base_url"] == null) map["base_url"] = JsonPrimitive("")
        if (map["auth_type"] == null) map["auth_type"] = JsonPrimitive(0)
        val clientConnect = raw.boolValue("client_connect", false) ||
            raw.boolValue("clientConnect", false)
        map["use_remote_service"] = JsonPrimitive(!clientConnect)
        val features = raw["features"]
        if (features is JsonPrimitive && features.isString) {
            runCatching { map["features"] = AppJson.parseToJsonElement(features.content) }
        }
        return JsonObject(map)
    }

    /** 账号 + 模型维度的稳定会话 ID（UUID 形态），便于上游复用会话。 */
    private fun stableSessionId(ch: Channel, model: String): String {
        val account = ch.appKind.ifBlank { "default" }
        val material = "$account\u001f$model\u001f"
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
        val hi = java.lang.Long.parseUnsignedLong(hex.substring(0, 16), 16)
        val lo = java.lang.Long.parseUnsignedLong(hex.substring(16, 32), 16)
        return UUID(hi, lo).toString()
    }

    /** 把 OpenAI messages 扁平化成 Trae 的单条 query 文本。 */
    private fun flattenQuery(request: JsonObject): String {
        val messages = request["messages"] as? JsonArray ?: return ""
        val parts = ArrayList<String>()
        messages.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val role = o.string("role") ?: "user"
            val text = contentToText(o["content"])
            when (role) {
                "system", "developer" -> if (text.isNotBlank()) parts.add("[System]\n$text")
                "assistant" -> if (text.isNotBlank()) parts.add("[Assistant]\n$text")
                "tool", "function" -> if (text.isNotBlank()) parts.add("[Tool Result]\n$text")
                else -> if (text.isNotBlank()) parts.add(text)
            }
        }
        return parts.joinToString("\n\n")
    }

    fun errorMessage(data: JsonObject?): String {
        if (data == null) return "上游返回错误"
        val errObj = data["error"] as? JsonObject
        val code = data.string("code") ?: data.string("err_code") ?: errObj?.string("code") ?: ""
        val msg = data.string("message") ?: data.string("msg") ?: data.string("detail")
            ?: data.string("reason") ?: errObj?.string("message")
            ?: data.toString().take(200)
        return if (code.isNotBlank()) "$code: $msg" else msg
    }

    /** 阻塞读完整个事件流（非流式客户端用）；调用方需在 IO 线程执行。 */
    fun readAggregate(stream: TraeStream): TraeAggregate {
        val acc = TraeTextAccumulator()
        while (true) {
            val event = stream.nextEvent() ?: break
            if (event.name == "done") break
            if (event.name == "error") {
                acc.accept(event)
                break
            }
            acc.accept(event)
        }
        return TraeAggregate(acc.visible(), acc.error())
    }

    // ---------- OpenAI 形态输出 ----------

    fun chunk(delta: String, model: String): String {
        val obj = buildJsonObject {
            put("id", "chatcmpl-${System.currentTimeMillis()}")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put(
                "choices",
                buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject { put("content", delta) })
                        put("finish_reason", JsonNull)
                    })
                },
            )
        }
        return "data: " + AppJson.encodeToString(JsonObject.serializer(), obj) + "\n\n"
    }

    /** 首帧：告知客户端角色，兼容要求 role 先行的客户端。 */
    fun roleChunk(model: String): String {
        val obj = buildJsonObject {
            put("id", "chatcmpl-${System.currentTimeMillis()}")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put(
                "choices",
                buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject { put("role", "assistant") })
                        put("finish_reason", JsonNull)
                    })
                },
            )
        }
        return "data: " + AppJson.encodeToString(JsonObject.serializer(), obj) + "\n\n"
    }

    fun finalChunk(model: String): String {
        val obj = buildJsonObject {
            put("id", "chatcmpl-${System.currentTimeMillis()}")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put(
                "choices",
                buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject { })
                        put("finish_reason", "stop")
                    })
                },
            )
        }
        return "data: " + AppJson.encodeToString(JsonObject.serializer(), obj) + "\n\n"
    }

    fun errorChunk(message: String): String {
        val obj = buildJsonObject {
            put("error", buildJsonObject {
                put("message", message)
                put("type", "upstream_error")
            })
        }
        return "data: " + AppJson.encodeToString(JsonObject.serializer(), obj) + "\n\n"
    }

    fun openAiResponse(text: String, model: String): String {
        val obj = buildJsonObject {
            put("id", "chatcmpl-${System.currentTimeMillis()}")
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put(
                "choices",
                buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("message", buildJsonObject {
                            put("role", "assistant")
                            put("content", text)
                        })
                        put("finish_reason", "stop")
                    })
                },
            )
            put("usage", buildJsonObject {
                put("prompt_tokens", 0)
                put("completion_tokens", 0)
                put("total_tokens", 0)
            })
        }
        return AppJson.encodeToString(JsonObject.serializer(), obj)
    }

    // ---------- HTTP ----------

    internal data class HttpResult(val status: Int, val body: String)

    private fun http(
        url: String,
        method: String,
        token: String,
        body: String?,
        accept: String,
        timeoutMs: Long,
    ): HttpResult {
        val conn = try {
            connect(url, method, token, body, accept, timeoutMs)
        } catch (e: Exception) {
            return HttpResult(-1, e.message ?: "网络错误")
        }
        return try {
            val status = conn.responseCode
            HttpResult(status, readText(conn, status))
        } catch (e: Exception) {
            HttpResult(-1, e.message ?: "网络错误")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun connect(
        url: String,
        method: String,
        token: String,
        body: String?,
        accept: String,
        timeoutMs: Long,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 20_000
        readTimeout = timeoutMs.toInt().coerceIn(15_000, 300_000)
        instanceFollowRedirects = false
        useCaches = false
        setRequestProperty("Authorization", "Cloud-IDE-JWT ${token.trim()}")
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Accept", accept)
        setRequestProperty("X-Trae-Client-Type", "web")
        setRequestProperty("X-Preferenced-Language", "zh-CN")
        setRequestProperty("x-user-region", "CN")
        setRequestProperty("Origin", "https://solo.trae.cn")
        setRequestProperty("Referer", "https://solo.trae.cn/")
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept-Encoding", "identity")
        if (method != "GET") doOutput = true
        if (body != null) outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }

    private fun readText(conn: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        return stream?.let { s ->
            BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
        }.orEmpty()
    }
}

/**
 * 把 Trae 的累积快照事件合并成可见文本，并计算增量。
 * 逻辑对齐 Trae2api-cn 的 ProtocolTextAccumulator / _merge_plan_message_text。
 */
class TraeTextAccumulator {
    private var planText = ""
    private var messageText = ""
    private var streamed = ""
    private var error = ""

    fun accept(event: TraeApi.TraeEvent): String {
        val data = event.data ?: return ""
        when (event.name) {
            "error" -> {
                error = TraeApi.errorMessage(data)
                return ""
            }
            "plan_item" -> planText = mergeSnapshot(planText, planSnapshot(data))
            "message", "assistant_message", "response", "text", "output" ->
                messageText = mergeSnapshot(messageText, eventText(data))
            else -> return ""
        }
        return takeDelta()
    }

    fun visible(): String = streamed

    fun error(): String = error

    private fun takeDelta(): String {
        val current = mergedVisible()
        val delta = when {
            current.startsWith(streamed) -> current.substring(streamed.length)
            streamed.startsWith(current) -> ""
            else -> current.substring(commonPrefixLength(streamed, current))
        }
        streamed = current
        return delta
    }

    private fun mergedVisible(): String {
        val plan = planText
        val message = messageText
        if (plan.isEmpty()) return message
        if (message.isEmpty()) return plan
        if (plan == message || plan.startsWith(message) || plan.endsWith(message)) return plan
        if (message.startsWith(plan)) return message
        val overlap = suffixPrefixOverlap(plan, message)
        return if (overlap > 0) plan + message.substring(overlap)
        else plan.trimEnd() + "\n\n" + message.trimStart()
    }
}

/** 一个已打开的 TRAE 事件流（SSE）。 */
class TraeStream internal constructor(
    val status: Int,
    val errorBody: String,
    private val conn: HttpURLConnection?,
    private val reader: BufferedReader?,
) {
    fun close() {
        runCatching { reader?.close() }
        runCatching { conn?.disconnect() }
    }

    /** 阻塞读取下一个 SSE 事件；流结束返回 null。 */
    fun nextEvent(): TraeApi.TraeEvent? {
        val input = reader ?: return null
        var eventName = ""
        val dataLines = ArrayList<String>()
        while (true) {
            val line = try {
                input.readLine()
            } catch (e: Exception) {
                null
            } ?: return flush(eventName, dataLines)

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (eventName.isNotEmpty() || dataLines.isNotEmpty()) {
                    return flush(eventName, dataLines)
                }
                continue
            }
            if (trimmed.startsWith(":")) continue
            when {
                trimmed.startsWith("event:") -> eventName = trimmed.removePrefix("event:").trim()
                trimmed.startsWith("data:") -> dataLines.add(trimmed.removePrefix("data:").trim())
            }
        }
    }

    private fun flush(eventName: String, dataLines: List<String>): TraeApi.TraeEvent? {
        if (eventName.isEmpty() && dataLines.isEmpty()) return null
        val raw = dataLines.joinToString("\n").trim()
        if (raw == "[DONE]") return TraeApi.TraeEvent("done", null)
        val data = runCatching {
            AppJson.parseToJsonElement(raw) as? JsonObject
        }.getOrNull()
        val resolved = normalizeEventName(
            eventName.ifBlank { data?.let { payloadEventName(it) } ?: "" }
        )
        return TraeApi.TraeEvent(resolved.ifBlank { "message" }, data)
    }

    private fun payloadEventName(data: JsonObject): String =
        data.string("event") ?: data.string("event_type") ?: data.string("eventType")
            ?: data.string("type") ?: ""
}

// ---------- 文本与事件工具 ----------

private fun contentToText(el: JsonElement?): String = when (el) {
    is JsonPrimitive -> el.content
    is JsonArray -> el.joinToString("") { part ->
        when (part) {
            is JsonPrimitive -> part.content
            is JsonObject -> {
                val type = part.string("type")?.lowercase()
                if (type == "image" || type == "image_url") ""
                else part.string("text") ?: part.string("content") ?: ""
            }
            else -> ""
        }
    }
    else -> ""
}

private fun planSnapshot(data: JsonObject): String {
    val content = contentToText(data["content"])
    if (content.isNotBlank()) return content
    val splitLayout = data.containsKey("reasoning_content") || data.containsKey("reasoningContent")
    if (splitLayout) return data.string("thought").orEmpty()
    return ""
}

private fun eventText(dataIn: JsonObject): String {
    var data = dataIn
    for (key in listOf("message", "agent_message", "assistant_message")) {
        val nested = data[key] as? JsonObject
        if (nested != null) {
            data = nested
            break
        }
    }
    val content = contentToText(data["content"])
    if (content.isNotBlank()) return content
    for (key in listOf("text", "response", "answer", "output")) {
        val text = contentToText(data[key])
        if (text.isNotBlank()) return text
    }
    return ""
}

/** 累积快照合并：新快照更长则替换；旧快照忽略；否则追加。 */
private fun mergeSnapshot(previous: String, snapshot: String): String {
    if (snapshot.isBlank()) return previous
    if (previous.isEmpty()) return snapshot
    if (snapshot.startsWith(previous)) return snapshot
    if (previous.startsWith(snapshot)) return previous
    if (previous.endsWith(snapshot)) return previous
    val common = commonPrefixLength(previous, snapshot)
    val shorter = minOf(previous.length, snapshot.length)
    return if (common >= 4 || (shorter > 0 && common * 2 >= shorter)) snapshot
    else previous + snapshot
}

private fun commonPrefixLength(left: String, right: String): Int {
    val limit = minOf(left.length, right.length)
    var i = 0
    while (i < limit && left[i] == right[i]) i++
    return i
}

private fun suffixPrefixOverlap(left: String, right: String, minimum: Int = 4): Int {
    val limit = minOf(left.length, right.length)
    for (size in limit downTo minimum) {
        if (left.endsWith(right.substring(0, size))) return size
    }
    return 0
}

/** 事件名归一化为 snake_case。 */
internal fun normalizeEventName(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return ""
    val snake = text
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_")
        .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), "_")
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_')
        .lowercase()
    return when (snake.replace("_", "")) {
        "modelconfig" -> "model_config"
        "planitem" -> "plan_item"
        "requestwaitinqueue" -> "request_wait_in_queue"
        "responsedone", "streamdone" -> "done"
        "tokenusage" -> "token_usage"
        else -> snake
    }
}

private fun JsonObject.intValue(key: String, default: Int): Int =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: default

private fun JsonObject.boolValue(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default
