package com.pocketgateway.app.core.adapter

import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.util.AppJson
import com.pocketgateway.app.util.JsonPath
import com.pocketgateway.app.util.messages
import com.pocketgateway.app.util.modelName
import com.pocketgateway.app.util.string
import com.pocketgateway.app.util.withString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * WorkBuddy / CodeBuddy APP 账号直连适配器。
 *
 * 协议依据开源实现 workbuddy2api（HanawaBanana fork，MIT）：
 *  - 上游：CN → https://copilot.tencent.com，Origin https://www.codebuddy.cn
 *  - 国际：https://www.workbuddy.ai，Origin 同域
 *  - 出站 UA：`WorkBuddy/<ver> WorkBuddy/<ver> CLI/<cliVer>`（官方桌面端形态）
 *  - 鉴权：Authorization: Bearer <accessToken> + X-User-Id
 *  - 关键：上游渠道校验要求会话头族恒在，尤其 X-Conversation-ID，
 *    缺失会被判「非官方客户端」并拒收 400 code=11128 / 11-128。
 *
 * 用户只需在渠道里填登录得到的 accessToken（和可选 uid），无需 URL / API Key。
 */
class WorkBuddyAdapter : ProviderAdapter {

    override val type: ProviderType = ProviderType.APP_NATIVE

    override fun chatPath(): String = "/v2/chat/completions"

    override fun modelsPath(): String? = "/v1/models"

    /**
     * 出站请求头：鉴权 + 官方客户端身份 + 会话头族。
     *
     * apiKey 存放 accessToken；appKind 存放 uid（可选，也可留空）。
     */
    override fun defaultAuthHeaders(channel: Channel): List<Pair<String, String>> {
        val token = channel.apiKey.trim()
        val uid = channel.appKind.trim()
        val isGlobal = channel.baseUrl.contains("workbuddy.ai", ignoreCase = true)
        val origin = if (isGlobal) GLOBAL_ORIGIN else CN_ORIGIN
        val platform = if (isGlobal) "WorkBuddy AI" else "WorkBuddy"

        val h = mutableListOf<Pair<String, String>>()

        // 鉴权
        if (token.isNotEmpty()) {
            h.add("Authorization" to "Bearer $token")
        }
        if (uid.isNotEmpty()) h.add("X-User-Id" to uid) else h.add("X-No-User-Id" to "1")

        // 官方桌面端身份（UA 三段式）
        h.add("User-Agent" to "WorkBuddy/$CLIENT_VER $platform/$CLIENT_VER CLI/$CLI_VER")
        h.add("Origin" to origin)
        h.add("Referer" to "$origin/")
        h.add("X-Requested-With" to "XMLHttpRequest")
        h.add("Accept" to "application/json, text/event-stream")

        // 企业/域头
        if (isGlobal) {
            h.add("X-No-Enterprise-Id" to "1")
            h.add("X-Domain" to "www.workbuddy.ai")
        } else {
            h.add("X-No-Enterprise-Id" to "1")
            h.add("X-No-Department-Info" to "1")
        }

        // 用量归属（伪装桌面端）
        h.add("X-Agent-Purpose" to "conversation")
        h.add("X-IDE-Name" to "WorkBuddy")
        h.add("X-IDE-Type" to "WorkBuddy")
        h.add("X-IDE-Version" to CLIENT_VER)
        h.add("X-Product" to "WorkBuddy")

        // 会话头族 —— 11128 的关键：X-Conversation-ID 必须恒在
        val convId = newHexId()
        val convReqId = newHexId()
        val msgId = newHexId()
        h.add("X-Conversation-ID" to convId)
        h.add("X-Conversation-Request-ID" to convReqId)
        h.add("X-Conversation-Message-ID" to msgId)
        h.add("X-Request-ID" to msgId)
        h.add("X-Root-Request-ID" to convReqId)
        h.add("X-Trace-ID" to convReqId)
        h.add("X-B3-TraceId" to convReqId)
        h.add("X-B3-SpanId" to msgId.take(16))
        h.add("X-B3-Sampled" to "1")

        return h
    }

    override fun contentType(): String = "application/json; charset=utf-8"

    override fun transformRequest(channel: Channel, body: JsonObject, stream: Boolean): RequestPayload {
        val mapped = body.modelName()?.let { model ->
            channel.modelMapping[model]?.let { target -> body.withString("model", target) }
        } ?: body

        val msgs: JsonArray = mapped.messages() ?: buildJsonArray { }
        val temp = mapped.string("temperature")
        val maxTokens = mapped.string("max_tokens")

        val wb = buildJsonObject {
            put("model", JsonPrimitive(mapped.modelName() ?: "default"))
            put("messages", msgs)
            put("stream", JsonPrimitive(stream))
            if (temp != null) put("temperature", JsonPrimitive(temp))
            if (maxTokens != null) put("max_tokens", JsonPrimitive(maxTokens))
            // 会话级 ID 同时放进 body，与请求头一致
            put("conversationId", JsonPrimitive(newHexId()))
        }

        return RequestPayload(body = AppJson.encodeToString(JsonObject.serializer(), wb))
    }

    override fun transformResponse(channel: Channel, rawBody: String): String? {
        val content = extractContent(rawBody, channel) ?: return null
        val model = channel.models.firstOrNull()?.ifBlank { "workbuddy" } ?: "workbuddy"
        return buildJsonObject {
            put("id", JsonPrimitive("chatcmpl-${System.currentTimeMillis()}"))
            put("object", JsonPrimitive("chat.completion"))
            put("created", JsonPrimitive(System.currentTimeMillis() / 1000))
            put("model", JsonPrimitive(model))
            put(
                "choices",
                Json.parseToJsonElement(
                    """[{"index":0,"message":{"role":"assistant","content":${JsonPrimitive(content)}},"finish_reason":"stop"}]"""
                )
            )
            put(
                "usage",
                Json.parseToJsonElement("""{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}""")
            )
        }.toString()
    }

    override fun streamConverter(channel: Channel): StreamConverter? = object : StreamConverter {
        private val model = channel.models.firstOrNull()?.ifBlank { "workbuddy" } ?: "workbuddy"

        override fun onLine(line: String): List<String> {
            if (line.isBlank() || line.startsWith(":")) return emptyList()
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]" || data.isEmpty()) return emptyList()
            return try {
                val el = Json.parseToJsonElement(data)
                val text = extractContentFromElement(el, channel) ?: return emptyList()
                val sse = """data: {"id":"chatcmpl-${System.currentTimeMillis()}","object":"chat.completion.chunk","created":${System.currentTimeMillis() / 1000},"model":"$model","choices":[{"index":0,"delta":{"content":${JsonPrimitive(text)}},"finish_reason":null}]}"""
                listOf(sse, "")
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun onEnd(): List<String> = listOf("data: [DONE]", "")
    }

    private fun extractContent(rawBody: String, channel: Channel): String? {
        val path = channel.responseContentPath
        if (path.isNotBlank()) {
            JsonPath.extract(rawBody, path)?.let { return it }
        }
        return try {
            extractContentFromElement(Json.parseToJsonElement(rawBody), channel)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractContentFromElement(el: JsonElement, channel: Channel): String? {
        if (el is JsonObject) {
            // WorkBuddy 业务信封：{code,msg,data}，data 里才是 OpenAI 结构
            val data = el["data"]
            if (data != null && data !is JsonPrimitive) {
                extractContentFromElement(data, channel)?.let { return it }
            }
            val choices = el["choices"]
            if (choices is JsonArray) {
                val first = choices.firstOrNull()
                if (first is JsonObject) {
                    (first["message"] as? JsonObject)?.string("content")?.let { return it }
                    (first["delta"] as? JsonObject)?.string("content")?.let { return it }
                    (first["message"] as? JsonPrimitive)?.let { return it.content }
                }
            }
            // 常见文本字段
            for (k in listOf("content", "text", "answer", "result", "message")) {
                el[k]?.let { v ->
                    if (v is JsonPrimitive && v.content.isNotBlank()) return v.content
                }
            }
        }
        val path = channel.responseContentPath
        if (path.isNotBlank()) {
            JsonPath.navigate(el, path)?.let { return JsonPath.toText(it) }
        }
        return when (el) {
            is JsonPrimitive -> el.content
            is JsonArray -> el.joinToString("") { extractContentFromElement(it, channel) ?: "" }
            is JsonObject -> el.entries.firstNotNullOfOrNull { (_, v) -> extractContentFromElement(v, channel) }
            else -> null
        }
    }

    private companion object {
        const val CLIENT_VER = "5.5.4"
        const val CLI_VER = "2.137.1"
        const val CN_ORIGIN = "https://www.codebuddy.cn"
        const val GLOBAL_ORIGIN = "https://www.workbuddy.ai"

        /** 32 位 hex ID（UUID 去横线），与官方客户端一致。 */
        fun newHexId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
