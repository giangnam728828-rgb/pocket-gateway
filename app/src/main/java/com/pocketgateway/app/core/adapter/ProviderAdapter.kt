package com.pocketgateway.app.core.adapter

import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType
import kotlinx.serialization.json.JsonObject

/**
 * 统一上游协议适配器。
 *
 * 网关内部一律使用 OpenAI Chat Completions 格式作为"中间语言"：
 *  - 入站请求按 OpenAI 格式解析；
 *  - [transformRequest] 把它转成上游能懂的格式；
 *  - 上游响应经 [transformResponse] / [streamConverter] 转回 OpenAI 格式发给客户端。
 *
 * 这样上层应用只需对接一套 OpenAI 兼容 API，就能访问任意平台。
 */
interface ProviderAdapter {

    val type: ProviderType

    /** 默认鉴权请求头。{key} 由 [resolveHeaders] 统一替换。 */
    fun defaultAuthHeaders(channel: Channel): List<Pair<String, String>> = emptyList()

    /** 默认 Content-Type。 */
    fun contentType(): String = "application/json; charset=utf-8"

    /** chat 端点的默认路径（相对于 baseUrl）。 */
    fun chatPath(): String

    /** models 端点的默认路径；返回 null 表示该协议没有标准 models 端点。 */
    fun modelsPath(): String? = "/models"

    /** embeddings 端点默认路径；null 表示不支持。 */
    fun embeddingsPath(): String? = null

    /** 把 OpenAI 格式的请求体转成上游格式。 */
    fun transformRequest(channel: Channel, body: JsonObject, stream: Boolean): RequestPayload

    /**
     * 把上游的非流式响应（字符串）转成 OpenAI Chat Completion 响应字符串。
     * 返回 null 表示原样透传。
     */
    fun transformResponse(channel: Channel, rawBody: String): String? = null

    /** 为上游响应流式转换创建一个状态ful转换器；返回 null 表示字节级透传。 */
    fun streamConverter(channel: Channel): StreamConverter? = null

    /**
     * 汇总最终请求头：默认鉴权头（可被 channel.authHeader 覆盖）+ 额外头，统一替换 {key}。
     */
    fun resolveHeaders(channel: Channel): List<Pair<String, String>> {
        val key = channel.apiKey
        val auth = if (channel.authHeader.isBlank()) {
            defaultAuthHeaders(channel)
        } else {
            parseHeaderLine(channel.authHeader)
        }
        val extra = channel.parsedExtraHeaders()
        return (auth + extra).map { (name, value) -> name to value.replace("{key}", key) }
            .filter { it.first.isNotBlank() }
    }

    private fun parseHeaderLine(line: String): List<Pair<String, String>> {
        val idx = line.indexOf(':')
        if (idx <= 0) return emptyList()
        return listOf(line.substring(0, idx).trim() to line.substring(idx + 1).trim())
    }
}

/** 转换后的上游请求载荷。 */
data class RequestPayload(
    /** 序列化后的请求体。 */
    val body: String,
    /** 是否需要覆盖 Content-Type。 */
    val contentType: String? = null,
    /**
     * 完整覆盖请求 URL。为 null 时网关按 baseUrl + resolvePath() 拼接。
     * Gemini 需要把模型名放进路径、key 放进 query，故用此字段。
     */
    val fullUrl: String? = null,
    /** 附加到 URL 的查询参数。 */
    val queryParams: Map<String, String> = emptyMap(),
    /** 请求方法，默认 POST。 */
    val method: String = "POST",
)

/**
 * 流式转换器：把上游的每一行（SSE data 行或 JSON 行）转成 0..n 条 OpenAI SSE 输出行。
 * 输出应为完整的 "data: {...}\n\n" 文本（已含 data: 前缀和空行分隔）。
 */
interface StreamConverter {
    /** 输入上游的一行文本（不含换行符），返回要写给客户端的文本块。 */
    fun onLine(line: String): List<String>

    /** 流结束时调用，返回收尾需要额外写出的文本块（如 [DONE]）。 */
    fun onEnd(): List<String> = emptyList()
}
