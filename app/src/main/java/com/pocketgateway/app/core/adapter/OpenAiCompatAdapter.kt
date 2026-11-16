package com.pocketgateway.app.core.adapter

import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.util.AppJson
import com.pocketgateway.app.util.modelName
import com.pocketgateway.app.util.withString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI 兼容适配器：绝大多数平台（OpenAI、DeepSeek、Moonshot、各类中转站、
 * 本地 vLLM/Ollama 的 OpenAI 端点）都遵循此协议。
 *
 * 采用"透传优先"策略：除模型名映射外，请求/响应/流式均字节级透传，
 * 因此对新模型的兼容性最好——上游有什么新字段都能原样到达客户端。
 */
class OpenAiCompatAdapter : ProviderAdapter {

    override val type: ProviderType = ProviderType.OPENAI_COMPAT

    override fun defaultAuthHeaders(channel: Channel): List<Pair<String, String>> =
        if (channel.apiKey.isBlank()) emptyList()
        else listOf("Authorization" to "Bearer {key}")

    override fun chatPath(): String = pathFor("chat", "/chat/completions")

    override fun modelsPath(): String? = pathFor("models", "/models")

    override fun embeddingsPath(): String? = pathFor("embeddings", "/embeddings")

    private fun pathFor(key: String, default: String): String {
        // pathOverrides 在 transform 阶段由网关统一处理，这里返回默认。
        return default
    }

    override fun transformRequest(channel: Channel, body: JsonObject, stream: Boolean): RequestPayload {
        val mapped = body.modelName()?.let { model ->
            channel.modelMapping[model]?.let { target -> body.withString("model", target) }
        } ?: body
        // 强制同步 stream 参数，避免上游与客户端对流式状态理解不一致
        val finalBody = buildJsonObject {
            mapped.forEach { (k, v) -> put(k, v) }
            put("stream", stream)
        }
        return RequestPayload(AppJson.encodeToString(JsonObject.serializer(), finalBody))
    }

    override fun transformResponse(channel: Channel, rawBody: String): String? = null // 透传

    override fun streamConverter(channel: Channel): StreamConverter? = null // 字节级透传
}
