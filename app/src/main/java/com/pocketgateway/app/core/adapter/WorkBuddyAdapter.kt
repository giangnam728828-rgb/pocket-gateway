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
import java.security.MessageDigest
import java.util.UUID

/**
 * WorkBuddy / CodeBuddy APP 账号直连适配器。
 *
 * 请求头依据开源实现 workbuddy2api 的 internal/upstream/headers.go（CommonHeaders /
 * ChatHeaders / BillingHeaders）：
 *
 * | 头 | 值 | 说明 |
 * |---|---|---|
 * | X-CodeBuddy-Request | 1 | **官方客户端风控闸门头，所有 API 必带**；缺失会被边缘重定向/拒收 |
 * | User-Agent | WorkBuddy/5.5.4 WorkBuddy/5.5.4 CLI/2.137.1 | 对话/模型路径三段式 |
 * | User-Agent | WorkBuddy/5.5.4 | 计费/签到/任务路径单段式 |
 * | Origin / Referer | https://www.codebuddy.cn | 国内；国际为 workbuddy.ai |
 * | Accept-Language | zh-CN / en-US | 按地域 |
 * | X-Machine-ID / X-Session-ID | sha256("wb2a:machine:"+uid)[:36] | 账号级稳定设备标识 |
 * | X-Domain | www.codebuddy.cn | 域声明 |
 * | X-Agent-Purpose / X-IDE-* / X-Product | conversation / WorkBuddy | 用量归属 |
 * | 会话头族 | X-Conversation-* / X-B3-* | 渠道校验要求恒在 |
 */
class WorkBuddyAdapter : ProviderAdapter {

    override val type: ProviderType = ProviderType.APP_NATIVE

    override fun chatPath(): String = "/v2/chat/completions"

    override fun modelsPath(): String? = "/models"

    /** 对话/通用请求头（三段式 UA）。 */
    override fun defaultAuthHeaders(channel: Channel): List<Pair<String, String>> =
        buildHeaders(channel, chat = true)

    /**
     * 构造出站请求头。
     * @param chat true = 对话/模型路径（三段式 UA + 会话头族）；
     *             false = 计费/签到/任务路径（单段 UA，不带会话头族）
     */
    fun buildHeaders(channel: Channel, chat: Boolean): List<Pair<String, String>> {
        val token = channel.apiKey.trim()
        val uid = channel.appKind.trim()
        val global = channel.baseUrl.contains("workbuddy.ai", ignoreCase = true)
        val origin = if (global) GLOBAL_ORIGIN else CN_ORIGIN
        val platform = if (global) "WorkBuddy AI" else "WorkBuddy"

        val h = mutableListOf<Pair<String, String>>()

        // —— 公共头 ——
        h.add("Content-Type" to "application/json")
        h.add("Accept" to if (chat) "application/json, text/event-stream" else "application/json")
        h.add("X-Requested-With" to "XMLHttpRequest")
        h.add("Origin" to origin)
        h.add("Referer" to "$origin/")
        h.add(
            "User-Agent" to
                if (chat) "WorkBuddy/$CLIENT_VER $platform/$CLIENT_VER CLI/$CLI_VER"
                else "WorkBuddy/$CLIENT_VER"
        )
        // 风控闸门头：官方所有 API 必带，缺失会导致边缘 302/拒收
        h.add("X-CodeBuddy-Request" to "1")
        h.add("Accept-Language" to if (global) "en-US" else "zh-CN")

        // 账号级稳定设备标识（uid 为空则不注入，与官方一致）
        if (uid.isNotEmpty()) {
            h.add("X-Machine-ID" to deriveStableId(uid, "machine"))
            h.add("X-Session-ID" to deriveStableId(uid, "session"))
        }

        // —— 鉴权 ——
        if (token.isNotEmpty()) h.add("Authorization" to "Bearer $token")
        if (uid.isNotEmpty()) h.add("X-User-Id" to uid) else h.add("X-No-User-Id" to "1")

        // —— 域/企业声明 ——
        h.add("X-No-Enterprise-Id" to "1")
        if (global) {
            h.add("X-Domain" to "www.workbuddy.ai")
        } else {
            h.add("X-Domain" to "www.codebuddy.cn")
        }

        // —— 对话专属 ——
        if (chat) {
            h.add("X-Agent-Purpose" to "conversation")
            h.add("X-IDE-Name" to "WorkBuddy")
            h.add("X-IDE-Type" to "WorkBuddy")
            h.add("X-IDE-Version" to CLIENT_VER)
            h.add("X-Product" to "WorkBuddy")

            // 会话头族：渠道校验要求 X-Conversation-ID 恒在
            val convReqId = newHexId()
            val msgId = newHexId()
            h.add("X-Conversation-ID" to newHexId())
            h.add("X-Conversation-Request-ID" to convReqId)
            h.add("X-Conversation-Message-ID" to msgId)
            h.add("X-Request-ID" to msgId)
            h.add("X-Root-Request-ID" to convReqId)
            h.add("X-Trace-ID" to convReqId)
            h.add("X-B3-TraceId" to convReqId)
            h.add("X-B3-SpanId" to msgId.take(16))
            h.add("X-B3-Sampled" to "1")
        }
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
            // 业务信封 {code,msg,data}
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

    companion object {
        const val CLIENT_VER = "5.5.4"
        const val CLI_VER = "2.137.1"
        const val CN_ORIGIN = "https://www.codebuddy.cn"
        const val GLOBAL_ORIGIN = "https://www.workbuddy.ai"

        /** 32 位 hex ID（UUID 去横线）。 */
        fun newHexId(): String = UUID.randomUUID().toString().replace("-", "")

        /** 账号级稳定派生：sha256("wb2a:<purpose>:<uid>") 取 36 hex，与官方实现同口径。 */
        fun deriveStableId(uid: String, purpose: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest("wb2a:$purpose:$uid".toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return hex.take(36)
        }
    }
}
