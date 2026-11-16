package com.pocketgateway.app.core.adapter

import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.util.AppJson
import com.pocketgateway.app.util.flattenText
import com.pocketgateway.app.util.messages
import com.pocketgateway.app.util.modelName
import com.pocketgateway.app.util.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Anthropic Claude Messages API 适配器。
 * 参考文档：https://docs.anthropic.com/en/api/messages
 *
 * 把 OpenAI Chat 格式转换为 Claude Messages 格式，并把响应转回 OpenAI 格式。
 * 支持普通与流式（SSE）两种模式。
 */
class ClaudeAdapter : ProviderAdapter {

    override val type: ProviderType = ProviderType.CLAUDE

    override fun defaultAuthHeaders(channel: Channel): List<Pair<String, String>> =
        listOf("x-api-key" to "{key}", "anthropic-version" to "2023-06-01")

    override fun chatPath(): String = "/v1/messages"

    override fun modelsPath(): String? = null // Claude 无标准模型列表端点，网关用配置中的 models 兜底

    override fun transformRequest(channel: Channel, body: JsonObject, stream: Boolean): RequestPayload {
        val model = body.modelName() ?: ""
        val mappedModel = channel.modelMapping[model] ?: model

        val systemParts = StringBuilder()
        val claudeMessages = buildJsonArray {
            body.messages()?.forEach { msgEl ->
                val msg = msgEl.jsonObject
                val role = msg.string("role") ?: "user"
                when (role) {
                    "system" -> {
                        if (systemParts.isNotEmpty()) systemParts.append("\n\n")
                        systemParts.append(msg["content"]?.flattenText().orEmpty())
                    }
                    "assistant", "user" -> {
                        add(buildJsonObject {
                            put("role", role)
                            put("content", msg["content"]?.flattenText().orEmpty())
                        })
                    }
                    else -> {
                        // tool / function 角色的消息尽力映射为 user 文本内容
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", msg["content"]?.flattenText().orEmpty())
                        })
                    }
                }
            }
        }

        val maxTokens = body["max_tokens"]?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }
            ?: body["max_completion_tokens"]?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }
            ?: 4096

        val out = buildJsonObject {
            put("model", mappedModel)
            put("max_tokens", maxTokens)
            if (systemParts.isNotEmpty()) put("system", systemParts.toString())
            put("messages", claudeMessages)
            body["temperature"]?.let { put("temperature", it) }
            body["top_p"]?.let { put("top_p", it) }
            put("stream", stream)
        }
        return RequestPayload(AppJson.encodeToString(JsonObject.serializer(), out))
    }

    override fun transformResponse(channel: Channel, rawBody: String): String? = runCatching {
        val resp = AppJson.parseToJsonElement(rawBody).jsonObject
        val text = resp["content"]?.let { content ->
            when (content) {
                is JsonArray -> content.joinToString("") { block ->
                    block.jsonObject.string("text").orEmpty()
                }
                else -> content.flattenText()
            }
        }.orEmpty()

        val stopReason = resp.string("stop_reason") ?: "stop"
        val finish = when (stopReason) {
            "end_turn", "stop_sequence" -> "stop"
            "max_tokens" -> "length"
            "tool_use" -> "tool_calls"
            else -> "stop"
        }
        val usage = resp["usage"]?.jsonObject
        val inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        val out = buildJsonObject {
            put("id", resp.string("id") ?: ("chatcmpl-" + System.currentTimeMillis()))
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", resp.string("model") ?: "")
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
                put("prompt_tokens", inputTokens)
                put("completion_tokens", outputTokens)
                put("total_tokens", inputTokens + outputTokens)
            })
        }
        AppJson.encodeToString(JsonObject.serializer(), out)
    }.getOrNull()

    override fun streamConverter(channel: Channel): StreamConverter = ClaudeStreamConverter()
}

/**
 * Claude SSE -> OpenAI SSE 增量转换器。
 *
 * Claude 的事件序列：
 *   message_start        -> 携带 usage.input_tokens
 *   content_block_start  -> 文本块开始
 *   content_block_delta  -> delta.text 增量文本
 *   content_block_stop
 *   message_delta        -> stop_reason + usage.output_tokens
 *   message_stop
 */
private class ClaudeStreamConverter : StreamConverter {

    private var id = "chatcmpl-" + System.currentTimeMillis()
    private var model = ""
    private var inputTokens = 0
    private var outputTokens = 0
    private var started = false
    private var sentRole = false
    private var finishReason: String? = null

    override fun onLine(line: String): List<String> {
        val trimmed = line.trim()
        if (trimmed.isBlank() || !trimmed.startsWith("data:")) return emptyList()
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") return emptyList()

        val event = runCatching { AppJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return emptyList()
        val type = event.string("type") ?: return emptyList()
        val out = mutableListOf<String>()

        when (type) {
            "message_start" -> {
                event["message"]?.jsonObject?.let { msg ->
                    msg.string("id")?.let { id = it }
                    msg.string("model")?.let { model = it }
                    msg["usage"]?.jsonObject?.get("input_tokens")?.jsonPrimitive?.content?.toIntOrNull()
                        ?.let { inputTokens = it }
                }
                started = true
            }
            "content_block_delta" -> {
                val deltaText = event["delta"]?.jsonObject?.string("text")
                if (!deltaText.isNullOrEmpty()) {
                    if (!sentRole) {
                        out += chunk(delta = buildJsonObject { put("role", "assistant") }, finish = null)
                        sentRole = true
                    }
                    out += chunk(delta = buildJsonObject { put("content", deltaText) }, finish = null)
                }
            }
            "message_delta" -> {
                val d = event["delta"]?.jsonObject
                val reason = d?.string("stop_reason")
                finishReason = when (reason) {
                    "end_turn", "stop_sequence" -> "stop"
                    "max_tokens" -> "length"
                    "tool_use" -> "tool_calls"
                    else -> "stop"
                }
                event["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.content?.toIntOrNull()
                    ?.let { outputTokens = it }
            }
            "message_stop" -> {
                // 收到 stop 时统一输出 finish_reason + usage + [DONE]
                out += chunk(
                    delta = buildJsonObject {},
                    finish = finishReason ?: "stop",
                    includeUsage = true,
                )
            }
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
        val obj = buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            putJsonArray("choices") { add(choice) }
            if (includeUsage) {
                put("usage", buildJsonObject {
                    put("prompt_tokens", inputTokens)
                    put("completion_tokens", outputTokens)
                    put("total_tokens", inputTokens + outputTokens)
                })
            }
        }
        return "data: " + AppJson.encodeToString(JsonObject.serializer(), obj) + "\n\n"
    }
}
