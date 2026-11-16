package com.pocketgateway.app.core.adapter

import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.util.AppJson
import com.pocketgateway.app.util.flattenText
import com.pocketgateway.app.util.messages
import com.pocketgateway.app.util.modelName
import com.pocketgateway.app.util.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Google Gemini generateContent API 适配器。
 * 参考：https://ai.google.dev/gemini-api/docs
 *
 * Gemini 特点：模型名与 API Key 都在 URL 路径/查询参数中，请求体用 contents。
 */
class GeminiAdapter : ProviderAdapter {

    override val type: ProviderType = ProviderType.GEMINI

    override fun defaultAuthHeaders(channel: Channel): List<Pair<String, String>> = emptyList() // key 走 query

    override fun chatPath(): String = "" // 由 transformRequest 生成完整 URL

    override fun modelsPath(): String? = null

    override fun contentType(): String = "application/json"

    override fun transformRequest(channel: Channel, body: JsonObject, stream: Boolean): RequestPayload {
        val model = body.modelName() ?: "gemini-1.5-flash"
        val mappedModel = channel.modelMapping[model] ?: model
        val base = channel.baseUrl.trimEnd('/')
        val action = if (stream) "streamGenerateContent" else "generateContent"
        val suffix = if (stream) "?alt=sse" else ""
        val url = "$base/models/$mappedModel:$action$suffix"

        val systemParts = StringBuilder()
        val contents = buildJsonArray {
            body.messages()?.forEach { msgEl ->
                val msg = msgEl.jsonObject
                val role = msg.string("role") ?: "user"
                val text = msg["content"]?.flattenText().orEmpty()
                when (role) {
                    "system" -> {
                        if (systemParts.isNotEmpty()) systemParts.append("\n")
                        systemParts.append(text)
                    }
                    "assistant" -> add(buildJsonObject {
                        put("role", "model")
                        putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                    })
                    else -> add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                    })
                }
            }
        }

        val generationConfig = buildJsonObject {
            body["temperature"]?.let { put("temperature", it) }
            body["top_p"]?.let { put("topP", it) }
            body["max_tokens"]?.let { put("maxOutputTokens", it) }
            body["max_completion_tokens"]?.let { put("maxOutputTokens", it) }
            // 关闭 Gemini 的安全拦截，提升可用性
            putJsonArray("stopSequences") { }
        }

        val out = buildJsonObject {
            put("contents", contents)
            put("generationConfig", generationConfig)
            if (systemParts.isNotEmpty()) {
                put("systemInstruction", buildJsonObject {
                    putJsonArray("parts") { add(buildJsonObject { put("text", systemParts.toString()) }) }
                })
            }
        }

        val query = if (channel.apiKey.isBlank()) emptyMap() else mapOf("key" to channel.apiKey)
        return RequestPayload(
            body = AppJson.encodeToString(JsonObject.serializer(), out),
            fullUrl = url,
            queryParams = query,
        )
    }

    override fun transformResponse(channel: Channel, rawBody: String): String? = runCatching {
        val resp = AppJson.parseToJsonElement(rawBody).jsonObject
        val candidate = resp["candidates"]?.jsonObjectOrFirst()?.jsonObject
        val text = candidate?.get("content")?.jsonObject?.get("parts")?.let { parts ->
            when (parts) {
                is kotlinx.serialization.json.JsonArray ->
                    parts.joinToString("") { p -> p.jsonObject.string("text").orEmpty() }
                else -> parts.flattenText()
            }
        }.orEmpty()

        val reason = candidate?.string("finishReason") ?: "STOP"
        val finish = when (reason.uppercase()) {
            "STOP", "END_OF_SEQUENCE" -> "stop"
            "MAX_TOKENS" -> "length"
            "SAFETY" -> "content_filter"
            else -> "stop"
        }
        val usage = resp["usageMetadata"]?.jsonObject
        val promptTokens = usage?.get("promptTokenCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val completionTokens = usage?.get("candidatesTokenCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        val out = buildJsonObject {
            put("id", "chatcmpl-" + System.currentTimeMillis())
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", mappedModelFrom(channel))
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", text)
                    })
                    put("finish_reason", finish)
                })
            }
            put("usage", buildJsonObject {
                put("prompt_tokens", promptTokens)
                put("completion_tokens", completionTokens)
                put("total_tokens", promptTokens + completionTokens)
            })
        }
        AppJson.encodeToString(JsonObject.serializer(), out)
    }.getOrNull()

    override fun streamConverter(channel: Channel): StreamConverter = GeminiStreamConverter()

    private fun mappedModelFrom(channel: Channel): String =
        channel.models.firstOrNull().orEmpty()

    /** candidates 可能是数组，取第一个。 */
    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrFirst() =
        if (this is kotlinx.serialization.json.JsonArray) firstOrNull() else this
}

/** Gemini SSE -> OpenAI SSE 转换器。Gemini 的 alt=sse 每行 data: {candidates:[...]} */
private class GeminiStreamConverter : StreamConverter {

    private val id = "chatcmpl-" + System.currentTimeMillis()
    private var model = ""
    private var sentRole = false
    private var promptTokens = 0
    private var completionTokens = 0

    override fun onLine(line: String): List<String> {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return emptyList()
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return emptyList()

        val obj = runCatching { AppJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return emptyList()
        val candidates = obj["candidates"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val out = mutableListOf<String>()

        for (candidateEl in candidates) {
            val candidate = candidateEl.jsonObject
            val text = candidate["content"]?.jsonObject?.get("parts")?.let { parts ->
                when (parts) {
                    is kotlinx.serialization.json.JsonArray ->
                        parts.joinToString("") { p -> p.jsonObject.string("text").orEmpty() }
                    else -> parts.flattenText()
                }
            }.orEmpty()

            if (!sentRole) {
                out += chunk(buildJsonObject { put("role", "assistant") }, null)
                sentRole = true
            }
            if (text.isNotEmpty()) out += chunk(buildJsonObject { put("content", text) }, null)

            val reason = candidate.string("finishReason")
            if (reason != null) {
                val finish = when (reason.uppercase()) {
                    "STOP", "END_OF_SEQUENCE" -> "stop"
                    "MAX_TOKENS" -> "length"
                    else -> "stop"
                }
                out += chunk(buildJsonObject {}, finish, includeUsage = true)
            }
        }
        obj["usageMetadata"]?.jsonObject?.let { usage ->
            usage["promptTokenCount"]?.jsonPrimitive?.content?.toIntOrNull()?.let { promptTokens = it }
            usage["candidatesTokenCount"]?.jsonPrimitive?.content?.toIntOrNull()?.let { completionTokens = it }
        }
        return out
    }

    override fun onEnd(): List<String> = listOf("data: [DONE]\n\n")

    private fun chunk(delta: JsonObject, finish: String?, includeUsage: Boolean = false): String {
        val choice = buildJsonObject {
            put("index", 0)
            put("delta", delta)
            put("finish_reason", finish)
        }
        val o = buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            putJsonArray("choices") { add(choice) }
            if (includeUsage) {
                put("usage", buildJsonObject {
                    put("prompt_tokens", promptTokens)
                    put("completion_tokens", completionTokens)
                    put("total_tokens", promptTokens + completionTokens)
                })
            }
        }
        return "data: " + AppJson.encodeToString(JsonObject.serializer(), o) + "\n\n"
    }
}
