package com.pocketgateway.app.core.gateway

import com.pocketgateway.app.core.adapter.RequestPayload
import com.pocketgateway.app.core.model.Channel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 上游返回的一次非流式调用结果。 */
data class UpstreamResponse(
    val status: Int,
    val body: String,
    val contentType: String?,
)

/**
 * 封装 Ktor HttpClient，负责把网关内部请求发送到上游。
 */
class UpstreamClient {

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 180_000
            requestTimeoutMillis = 180_000
        }
        // 跟随重定向，兼容部分中转站的跳转
        followRedirects = true
    }

    /** 组装完整 URL：baseUrl + 适配路径 + 覆盖路径 + 查询参数。 */
    private fun buildUrl(channel: Channel, payload: RequestPayload, endpointPath: String): String {
        if (!payload.fullUrl.isNullOrBlank()) {
            return appendQuery(payload.fullUrl, payload.queryParams)
        }
        val base = channel.baseUrl.trim().trimEnd('/')
        // 渠道覆盖路径优先（形如 "/v1/chat/completions"）
        val path = channel.pathOverrides[endpointPath]?.takeIf { it.isNotBlank() }
            ?: defaultPathFor(endpointPath)
        val url = base + (if (path.startsWith("/")) path else "/$path")
        return appendQuery(url, payload.queryParams)
    }

    private fun defaultPathFor(endpoint: String): String = when (endpoint) {
        "chat" -> "/chat/completions"
        "models" -> "/models"
        "embeddings" -> "/embeddings"
        else -> "/$endpoint"
    }

    private fun appendQuery(url: String, params: Map<String, String>): String {
        if (params.isEmpty()) return url
        val query = params.entries.joinToString("&") { (k, v) ->
            "${k.encodeURLParameter()}=${v.encodeURLParameter()}"
        }
        return if ("?" in url) "$url&$query" else "$url?$query"
    }

    /** 非流式请求。 */
    suspend fun call(
        channel: Channel,
        adapter: com.pocketgateway.app.core.adapter.ProviderAdapter,
        endpoint: String,
        payload: RequestPayload,
        timeoutMs: Long,
    ): UpstreamResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(channel, payload, endpoint)
        val method = runCatching { HttpMethod(payload.method) }.getOrDefault(HttpMethod.Post)
        val response: HttpResponse = client.request(url) {
            this.method = method
            applyHeaders(this, channel, adapter, payload)
            setBody(payload.body)
            if (timeoutMs > 0) {
                timeout { requestTimeoutMillis = timeoutMs }
            }
        }
        val status = response.status.value
        val bodyText = response.body<String>()
        UpstreamResponse(status, bodyText, response.contentType()?.toString())
    }

    /** 流式请求：返回 prepare 好的响应，由调用方读取字节流。 */
    suspend fun prepareStream(
        channel: Channel,
        adapter: com.pocketgateway.app.core.adapter.ProviderAdapter,
        endpoint: String,
        payload: RequestPayload,
        timeoutMs: Long,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(channel, payload, endpoint)
        val method = runCatching { HttpMethod(payload.method) }.getOrDefault(HttpMethod.Post)
        client.prepareRequest(url) {
            this.method = method
            applyHeaders(this, channel, adapter, payload)
            setBody(payload.body)
            if (timeoutMs > 0) {
                timeout { requestTimeoutMillis = timeoutMs }
            }
        }.execute()
    }

    private fun applyHeaders(
        builder: HttpRequestBuilder,
        channel: Channel,
        adapter: com.pocketgateway.app.core.adapter.ProviderAdapter,
        payload: RequestPayload,
    ) {
        val ct = payload.contentType ?: adapter.contentType()
        builder.contentType(ContentType.parse(ct))
        adapter.resolveHeaders(channel).forEach { (name, value) ->
            if (name.equals("Content-Type", ignoreCase = true)) return@forEach
            builder.headers.append(name, value)
        }
        // 合并适配器动态生成的请求头（如签名头）
        payload.headers.forEach { (name, value) ->
            if (name.equals("Content-Type", ignoreCase = true)) return@forEach
            builder.headers.append(name, value)
        }
    }

    fun close() = client.close()
}
