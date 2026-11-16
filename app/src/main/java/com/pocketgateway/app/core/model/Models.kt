package com.pocketgateway.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 上游协议类型。决定使用哪个适配器做请求/响应转换。 */
@Serializable
enum class ProviderType {
    /** OpenAI Chat Completions 兼容协议（含绝大多数第三方中转站）。字节级透传，适配最广。 */
    @SerialName("openai") OPENAI_COMPAT,

    /** Anthropic Claude Messages API (v1/messages)。 */
    @SerialName("claude") CLAUDE,

    /** Google Gemini generateContent API。 */
    @SerialName("gemini") GEMINI,

    /** 自定义：仅做鉴权头/请求头注入 + 模型名映射，请求体原样透传。 */
    @SerialName("custom") CUSTOM,
    ;

    val displayName: String
        get() = when (this) {
            OPENAI_COMPAT -> "OpenAI 兼容"
            CLAUDE -> "Claude (Anthropic)"
            GEMINI -> "Gemini (Google)"
            CUSTOM -> "自定义透传"
        }
}

/** 路由失败时的转移策略。 */
@Serializable
enum class RouteStrategy {
    @SerialName("round_robin") ROUND_ROBIN,
    @SerialName("random") RANDOM,
    @SerialName("first") FIRST,
    ;
}

/**
 * 一个"渠道"= 一个可被转发的上游模型服务。
 *
 * 设计目标：任何提供 HTTP API 的平台都能通过配置接入，无需改代码。
 */
@Serializable
data class Channel(
    val id: String,
    val name: String,
    val type: ProviderType = ProviderType.OPENAI_COMPAT,
    val enabled: Boolean = true,

    /** 上游根地址，例如 https://api.openai.com/v1 或 https://api.anthropic.com */
    val baseUrl: String,

    /** 上游 API Key；留空表示不使用鉴权（如本地 Ollama）。 */
    val apiKey: String = "",

    /**
     * 鉴权头模板。{key} 会被替换为 apiKey。
     * 例如 "Authorization: Bearer {key}"，Claude 用 "x-api-key: {key}"。
     * 留空时按 type 使用默认模板。
     */
    val authHeader: String = "",

    /** 额外请求头，每行一条 "Name: Value"，支持 {key} 占位符。 */
    val extraHeaders: String = "",

    /**
     * 端点路径覆盖。key 为统一端点名（chat/models/embeddings），value 为相对 baseUrl 的路径。
     * 留空时按 type 使用默认路径。
     */
    val pathOverrides: Map<String, String> = emptyMap(),

    /**
     * 请求方法。自定义透传渠道可改成 GET / PUT / PATCH 等，默认 POST。
     */
    val requestMethod: String = "POST",

    /**
     * 附加查询参数。每行一条 "key=value"，会拼接到请求 URL。
     * 适用于需要把鉴权或会话标识放进 URL 的私有接口。
     */
    val queryParams: String = "",

    /**
     * 请求体模板（仅自定义透传渠道生效）。
     * 留空：请求体按 OpenAI 格式原样透传（仅做模型名映射）。
     * 填写：用模板插值生成请求体，适配非 OpenAI 格式的私有接口。
     * 支持变量：{{model}} 模型名（已映射）、{{messages}} 完整 messages JSON、
     *           {{key}} API Key、{{stream}} true/false。
     */
    val requestTemplate: String = "",

    /**
     * 响应内容提取路径（仅自定义透传渠道生效）。
     * 留空：上游响应原样透传给客户端。
     * 填写：从上游 JSON 响应中按路径提取文本，组装成标准 OpenAI 响应。
     * 示例：data.text、data[0].text、choices[0].message.content。
     */
    val responseContentPath: String = "",

    /**
     * 签名配置（仅自定义透传渠道生效）。用于对接带签名校验的 APP / 网页私有接口。
     * 全部留空表示不启用签名。签名作为渠道配置**持久保存**，随渠道一起存储，无需每次临时生成或重建。
     */
    /** 签名头名称，如 X-Sign、sign、X-Signature。 */
    val signHeader: String = "",
    /** 签名算法：md5 / sha1 / sha256 / hmac-md5 / hmac-sha1 / hmac-sha256 / none。 */
    val signAlgorithm: String = "",
    /**
     * 签名内容模板。支持变量：{{timestamp}} {{nonce}} {{body}} {{model}} {{key}}。
     * 先插值得到待签名字符串，再按算法计算签名。
     * 示例：app_key={{key}}&timestamp={{timestamp}}&data={{body}}
     */
    val signTemplate: String = "",
    /** 签名密钥（HMAC 类算法使用；md5/sha 类可留空）。 */
    val signKey: String = "",

    /**
     * 模型名映射：对外暴露名 -> 上游真实模型名。
     * 例如 "gpt-4o" -> "gpt-4o-2024-08-06"。
     * 未命中的模型名原样传递。
     */
    val modelMapping: Map<String, String> = emptyMap(),

    /** 该渠道对外暴露的模型名列表，用于 /v1/models 聚合与路由匹配。为空时表示"接受任意模型名"。 */
    val models: List<String> = emptyList(),

    /** 负载均衡权重（>=1）。 */
    val weight: Int = 1,

    /** 单请求超时（毫秒）。0 表示使用全局配置。 */
    val timeoutMs: Long = 0L,
) {
    /** 解析后的额外请求头。 */
    fun parsedExtraHeaders(): List<Pair<String, String>> =
        extraHeaders.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && ":" in it }
            .map { line ->
                val idx = line.indexOf(':')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toList()

    companion object {
        fun empty(id: String = newId()) = Channel(
            id = id,
            name = "新渠道",
            baseUrl = "",
        )

        fun newId(): String = "ch_" + java.util.UUID.randomUUID().toString().take(8)
    }
}

/** 网关全局配置。 */
@Serializable
data class GatewayConfig(
    /** 监听端口。 */
    val port: Int = 8317,

    /** 是否监听所有网卡（允许局域网访问）。false 则仅本机 127.0.0.1。 */
    val listenOnAllInterfaces: Boolean = true,

    /** 入站鉴权令牌。非空时，客户端需提供 Authorization: Bearer <token>。留空表示不鉴权。 */
    val accessToken: String = "",

    /** 默认上游超时（毫秒）。 */
    val defaultTimeoutMs: Long = 120_000L,

    /** 是否记录请求日志。 */
    val logEnabled: Boolean = true,

    /** 内存中最多保留的日志条数。 */
    val maxLogEntries: Int = 300,

    /** 路由策略。 */
    val routeStrategy: RouteStrategy = RouteStrategy.ROUND_ROBIN,

    /** 渠道列表。 */
    val channels: List<Channel> = emptyList(),
)

/** 请求日志条目。 */
@Serializable
data class RequestLog(
    val id: String,
    val timestamp: Long,
    val method: String,
    val path: String,
    val model: String = "",
    val channelName: String = "",
    val status: Int = 0,
    val success: Boolean = true,
    val durationMs: Long = 0L,
    val message: String = "",
    val stream: Boolean = false,
) {
    companion object {
        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}
