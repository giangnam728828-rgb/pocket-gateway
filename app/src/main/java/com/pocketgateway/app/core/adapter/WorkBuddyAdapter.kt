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

/**
 * WorkBuddy APP 账号直连适配器。
 *
 * 用于**强制只能在自己 APP 内部使用**的 WorkBuddy 服务：它不对外公开接口地址与 API Key，
 * 只能通过官方 APP 的登录态调用。本适配器内置 WorkBuddy 的端点、请求格式、官方客户端
 * 请求头伪装与响应转换，用户只需在渠道里填登录后的账号凭证（Cookie/Token），无需填写
 * Base URL 与 API Key。
 */
class WorkBuddyAdapter : ProviderAdapter {

    override val type: ProviderType = ProviderType.APP_NATIVE

    /** WorkBuddy 聊天端点（内置）。用户可通过 Base URL 覆盖。 */
    override fun chatPath(): String = "/v1/chat/completions"

    override fun modelsPath(): String? = "/v1/models"

    /**
     * 默认鉴权头：用户凭证（Cookie/Token）+ 官方客户端伪装头。
     * 用户凭证放在 apiKey 字段，appKind 可填设备标识用于伪装。
     */
    override fun defaultAuthHeaders(channel: Channel): List<Pair<String, String>> {
        val cred = channel.apiKey.trim()
        val headers = mutableListOf<Pair<String, String>>()
        if (cred.isNotEmpty()) {
            // 优先作为 Cookie；若含 "Bearer" 则作为 Authorization
            if (cred.contains("Bearer", ignoreCase = true)) {
                headers.add("Authorization" to cred)
            } else {
                headers.add("Cookie" to cred)
            }
        }
        // 官方客户端伪装头，避免被判定为非官方渠道
        headers.add("User-Agent" to WORKBUDDY_UA)
        headers.add("X-Client-Platform" to "android")
        headers.add("X-App-Version" to WORKBUDDY_VERSION)
        headers.add("X-Client-Version" to WORKBUDDY_VERSION)
        headers.add("Accept" to "application/json, text/plain, */*")
        return headers
    }

    override fun contentType(): String = "application/json; charset=utf-8"

    override fun transformRequest(channel: Channel, body: JsonObject, stream: Boolean): RequestPayload {
        // 1. 模型名映射
        val mapped = body.modelName()?.let { model ->
            channel.modelMapping[model]?.let { target -> body.withString("model", target) }
        } ?: body

        // 2. 组装 WorkBuddy 内部请求体
        val msgs: JsonArray = mapped.messages() ?: buildJsonArray { }
        val temp = mapped.string("temperature")
        val wbBody = buildJsonObject {
            put("model", JsonPrimitive(mapped.modelName() ?: "default"))
            put("messages", msgs)
            put("stream", JsonPrimitive(stream))
            put("temperature", if (temp != null) JsonPrimitive(temp) else JsonPrimitive(0.7))
        }

        return RequestPayload(body = AppJson.encodeToString(JsonObject.serializer(), wbBody))
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

    /** 流式转换：把 WorkBuddy 的 SSE 行转成 OpenAI SSE 行。 */
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

    /** 从原始响应提取文本：优先用配置的响应路径，否则尝试常见字段。 */
    private fun extractContent(rawBody: String, channel: Channel): String? {
        val path = channel.responseContentPath
        if (path.isNotBlank()) {
            JsonPath.extract(rawBody, path)?.let { return it }
        }
        return try {
            val root = Json.parseToJsonElement(rawBody)
            extractContentFromElement(root, channel)
        } catch (e: Exception) {
            null
        }
    }

    /** 从 JsonElement 提取文本，尝试多种常见结构。 */
    private fun extractContentFromElement(el: JsonElement, channel: Channel): String? {
        // 1. 标准 OpenAI 结构：choices[0].message.content
        if (el is JsonObject) {
            val choices = el["choices"]
            if (choices is JsonArray) {
                val first = choices.firstOrNull()
                if (first is JsonObject) {
                    val msg = first["message"]
                    if (msg is JsonObject) {
                        msg.string("content")?.let { return it }
                    }
                    msg?.let { m ->
                        if (m is JsonPrimitive) return m.content
                    }
                    // 流式：choices[0].delta.content
                    val delta = first["delta"]
                    if (delta is JsonObject) {
                        delta.string("content")?.let { return it }
                    }
                }
            }
        }
        // 2. 配置的通用路径
        val path = channel.responseContentPath
        if (path.isNotBlank()) {
            JsonPath.navigate(el, path)?.let { return JsonPath.toText(it) }
        }
        // 3. 兜底
        return when (el) {
            is JsonPrimitive -> el.content
            is JsonArray -> el.joinToString("\n") { extractContentFromElement(it, channel) ?: "" }
            is JsonObject -> el.entries.firstNotNullOfOrNull { (_, v) -> extractContentFromElement(v, channel) }
            else -> null
        }
    }

    private companion object {
        const val WORKBUDDY_VERSION = "3.8.0"
        const val WORKBUDDY_UA = "WorkBuddy/3.8.0 (Android 16; PocketGateway)"
    }
}
