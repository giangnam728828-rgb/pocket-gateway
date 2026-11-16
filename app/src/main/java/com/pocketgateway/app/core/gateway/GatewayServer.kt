package com.pocketgateway.app.core.gateway

import com.pocketgateway.app.core.adapter.AdapterRegistry
import com.pocketgateway.app.core.adapter.ProviderAdapter
import com.pocketgateway.app.core.adapter.RequestPayload
import com.pocketgateway.app.core.adapter.StreamConverter
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.GatewayConfig
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.core.model.RequestLog
import com.pocketgateway.app.core.model.RouteStrategy
import com.pocketgateway.app.core.store.ConfigStore
import com.pocketgateway.app.core.store.LogStore
import com.pocketgateway.app.util.AppJson
import com.pocketgateway.app.util.modelName
import com.pocketgateway.app.util.withString
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内嵌 HTTP 网关服务器（基于 Ktor 2.3 / CIO 引擎，可在 Android JVM 运行）。
 *
 * 对外暴露 OpenAI 兼容 API；内部把请求路由到多个上游渠道，
 * 通过协议适配器做格式转换，支持流式转发与故障转移。
 */
class GatewayServer(
    private val configStore: ConfigStore,
    private val logStore: LogStore,
) {
    private var server: ApplicationEngine? = null
    private val rrCounter = AtomicInteger(0)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _boundPort = MutableStateFlow(0)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()

    private val upstream = UpstreamClient()

    fun start(config: GatewayConfig) {
        if (server != null) return
        val host = if (config.listenOnAllInterfaces) "0.0.0.0" else "127.0.0.1"
        server = embeddedServer(CIO, host = host, port = config.port) {
            routing {
                get("/") { call.respondInfo() }
                get("/health") { call.respondText("ok", ContentType.Text.Plain) }
                get("/v1/models") { call.handleModels() }
                post("/v1/chat/completions") { call.handleChat(explicitStream = false) }
                post("/v1/chat/completions/stream") { call.handleChat(explicitStream = true) }
                post("/v1/completions") { call.handleChat(explicitStream = false) }
                post("/v1/embeddings") { call.handleEmbeddings() }
            }
        }.also { it.start(wait = false) }

        _running.value = true
        _boundPort.value = config.port
    }

    fun stop() {
        server?.stop(500, 2_000)
        server = null
        _running.value = false
        _boundPort.value = 0
    }

    fun restartIfNeeded(config: GatewayConfig) {
        if (!_running.value) return
        if (_boundPort.value != config.port) {
            stop()
            start(config)
        }
    }

    fun close() = upstream.close()

    // ---- 鉴权 ----

    private fun ApplicationCall.authorized(cfg: GatewayConfig): Boolean {
        if (cfg.accessToken.isBlank()) return true
        val header = request.header(HttpHeaders.Authorization).orEmpty()
        return header.removePrefix("Bearer ").trim() == cfg.accessToken.trim() ||
            header.trim() == cfg.accessToken.trim()
    }

    private suspend fun ApplicationCall.respondUnauthorized() {
        response.header("WWW-Authenticate", "Bearer")
        response.status(HttpStatusCode.Unauthorized)
        respondText(errorBody("invalid_api_key", "Missing or invalid API key"), ContentType.Application.Json)
    }

    private suspend fun ApplicationCall.respondError(status: HttpStatusCode, type: String, message: String) {
        response.status(status)
        respondText(errorBody(type, message), ContentType.Application.Json)
    }

    // ---- 路由选择 ----

    private fun selectChannels(cfg: GatewayConfig, model: String?): List<Channel> {
        var list = cfg.channels.filter { it.enabled && it.baseUrl.isNotBlank() }
        if (!model.isNullOrBlank()) {
            val exact = list.filter { ch ->
                ch.models.isEmpty() || ch.models.contains(model) || ch.modelMapping.containsKey(model)
            }
            if (exact.isNotEmpty()) list = exact
        }
        if (list.isEmpty()) return emptyList()
        return when (cfg.routeStrategy) {
            RouteStrategy.FIRST -> list
            RouteStrategy.RANDOM -> list.shuffled()
            RouteStrategy.ROUND_ROBIN -> {
                val weighted = list.flatMap { ch -> List(ch.weight.coerceAtLeast(1)) { ch } }
                if (weighted.isEmpty()) return emptyList()
                val start = (rrCounter.getAndIncrement() and Int.MAX_VALUE) % weighted.size
                (0 until weighted.size).map { weighted[(start + it) % weighted.size] }.distinctById()
            }
        }
    }

    private fun List<Channel>.distinctById(): List<Channel> {
        val seen = mutableSetOf<String>()
        return filter { seen.add(it.id) }
    }

    // ---- 处理器 ----

    private suspend fun ApplicationCall.respondInfo() {
        val cfg = configStore.config.value
        val obj = buildJsonObject {
            put("name", "PocketGateway")
            put("version", "0.1.0")
            put("port", cfg.port)
            put("channels", cfg.channels.count { it.enabled })
        }
        respondText(AppJson.encodeToString(JsonObject.serializer(), obj), ContentType.Application.Json)
    }

    private suspend fun ApplicationCall.handleModels() {
        val cfg = configStore.config.value
        if (!authorized(cfg)) return respondUnauthorized()

        val ids = linkedSetOf<String>()
        cfg.channels.filter { it.enabled }.forEach { ch ->
            ch.models.forEach { m -> ids.add(m) }
            ch.modelMapping.keys.forEach { k -> ids.add(k) }
        }
        val data = buildJsonArray {
            ids.forEach { id ->
                add(buildJsonObject {
                    put("id", id)
                    put("object", "model")
                    put("created", System.currentTimeMillis() / 1000)
                    put("owned_by", "pocketgateway")
                })
            }
        }
        val obj = buildJsonObject {
            put("object", "list")
            put("data", data)
        }
        respondText(AppJson.encodeToString(JsonObject.serializer(), obj), ContentType.Application.Json)
    }

    private suspend fun ApplicationCall.handleChat(explicitStream: Boolean) {
        val cfg = configStore.config.value
        if (!authorized(cfg)) return respondUnauthorized()

        val start = System.currentTimeMillis()
        val raw = runCatching { receiveText() }.getOrNull().orEmpty()
        val parsed = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull()
            ?: return respondError(HttpStatusCode.BadRequest, "invalid_request", "Malformed JSON")
        if (parsed !is JsonObject) {
            return respondError(HttpStatusCode.BadRequest, "invalid_request", "Expected JSON object")
        }
        val body: JsonObject = parsed

        val model = body.modelName()
        val candidates = selectChannels(cfg, model)
        if (candidates.isEmpty()) {
            return respondError(
                HttpStatusCode.ServiceUnavailable,
                "no_channel",
                "No available channel for model: ${model ?: "?"}",
            )
        }

        val wantStream = explicitStream || body["stream"]?.let {
            runCatching { it.jsonPrimitive.content.toBooleanStrict() }.getOrNull()
        } == true

        var lastError = ""
        for (channel in candidates) {
            // APP 账号直连渠道：凭证为空时直接给出明确原因，避免上游返回 302/401 让用户困惑
            if (channel.type == com.pocketgateway.app.core.model.ProviderType.APP_NATIVE &&
                channel.apiKey.isBlank()
            ) {
                return respondError(
                    HttpStatusCode.Unauthorized,
                    "app_credential_missing",
                    "账号凭证为空：请到「账号」页重新登录，或手动粘贴 accessToken。",
                )
            }
            val adapter = AdapterRegistry.get(channel)
            val payload = runCatching { adapter.transformRequest(channel, body, wantStream) }
                .getOrElse {
                    lastError = "transform error: ${it.message}"
                    null
                } ?: continue
            val timeout = if (channel.timeoutMs > 0) channel.timeoutMs else cfg.defaultTimeoutMs

            try {
                if (wantStream) {
                    forwardStream(channel, adapter, payload, timeout, model, start)
                    return
                } else {
                    val resp = upstream.call(channel, adapter, "chat", payload, timeout)
                    val out = if (resp.status in 200..299) {
                        adapter.transformResponse(channel, resp.body) ?: resp.body
                    } else {
                        resp.body
                    }
                    val duration = System.currentTimeMillis() - start
                    log(cfg, model, channel, resp.status, resp.status in 200..299,
                        duration, if (resp.status in 200..299) "OK" else truncate(resp.body, 200), false)
                    response.header("X-PocketGateway-Channel", channel.name)
                    response.status(HttpStatusCode.fromValue(resp.status))
                    respondText(out, ContentType.Application.Json)
                    return
                }
            } catch (e: Exception) {
                lastError = e.message ?: e::class.java.simpleName
                log(cfg, model, channel, 0, false, System.currentTimeMillis() - start, lastError, wantStream)
            }
        }
        respondError(HttpStatusCode.BadGateway, "upstream_error", "All channels failed. Last error: $lastError")
    }

    private suspend fun ApplicationCall.forwardStream(
        channel: Channel,
        adapter: ProviderAdapter,
        payload: RequestPayload,
        timeout: Long,
        model: String?,
        start: Long,
    ) {
        val upstreamResp = upstream.prepareStream(channel, adapter, "chat", payload, timeout)
        val status = upstreamResp.status.value
        this.response.header("X-PocketGateway-Channel", channel.name)
        this.response.header(HttpHeaders.CacheControl, "no-cache")
        this.response.header("X-Accel-Buffering", "no")

        val converter: StreamConverter? = if (status in 200..299) adapter.streamConverter(channel) else null

        respond(object : OutgoingContent.WriteChannelContent() {
            override val contentType: ContentType? = ContentType.Text.EventStream
            override val status: HttpStatusCode? = HttpStatusCode.fromValue(status)
            override suspend fun writeTo(channelOut: ByteWriteChannel) {
                try {
                    val input: ByteReadChannel = upstreamResp.bodyAsChannel()
                    var line = input.readUTF8Line()
                    while (line != null) {
                        val out = if (converter != null) converter.onLine(line) else listOf(line + "\n")
                        out.forEach { channelOut.writeStringUtf8(it) }
                        channelOut.flush()
                        line = input.readUTF8Line()
                    }
                    converter?.onEnd()?.forEach { channelOut.writeStringUtf8(it) }
                    channelOut.flush()
                } finally {
                    val duration = System.currentTimeMillis() - start
                    this@forwardStream.log(
                        configStore.config.value, model, channel, status, status in 200..299, duration, "stream", true,
                    )
                }
            }
        })
    }

    private suspend fun ApplicationCall.handleEmbeddings() {
        val cfg = configStore.config.value
        if (!authorized(cfg)) return respondUnauthorized()

        val start = System.currentTimeMillis()
        val raw = runCatching { receiveText() }.getOrNull().orEmpty()
        val parsed = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull()
        if (parsed !is JsonObject) {
            return respondError(HttpStatusCode.BadRequest, "invalid_request", "Malformed JSON")
        }
        val body: JsonObject = parsed
        val model = body.modelName()
        val candidates = selectChannels(cfg, model).filter { it.type == ProviderType.OPENAI_COMPAT }
        if (candidates.isEmpty()) {
            return respondError(HttpStatusCode.ServiceUnavailable, "no_channel", "No channel supports embeddings")
        }

        var lastError = ""
        for (channel in candidates) {
            val mapped = body.modelName()?.let { m ->
                channel.modelMapping[m]?.let { body.withString("model", it) }
            } ?: body
            val payload = RequestPayload(AppJson.encodeToString(JsonObject.serializer(), mapped))
            val timeout = if (channel.timeoutMs > 0) channel.timeoutMs else cfg.defaultTimeoutMs
            try {
                val resp = upstream.call(channel, AdapterRegistry.get(channel), "embeddings", payload, timeout)
                log(cfg, model, channel, resp.status, resp.status in 200..299,
                    System.currentTimeMillis() - start, "", false)
                response.header("X-PocketGateway-Channel", channel.name)
                response.status(HttpStatusCode.fromValue(resp.status))
                return respondText(resp.body, ContentType.Application.Json)
            } catch (e: Exception) {
                lastError = e.message ?: e::class.java.simpleName
            }
        }
        respondError(HttpStatusCode.BadGateway, "upstream_error", "All channels failed: $lastError")
    }

    private fun ApplicationCall.log(
        cfg: GatewayConfig,
        model: String?,
        channel: Channel,
        status: Int,
        success: Boolean,
        duration: Long,
        message: String,
        isStream: Boolean,
    ) {
        if (!cfg.logEnabled) return
        logStore.push(
            RequestLog(
                id = RequestLog.newId(),
                timestamp = System.currentTimeMillis(),
                method = request.httpMethod.value,
                path = request.path(),
                model = model.orEmpty(),
                channelName = channel.name,
                status = status,
                success = success,
                durationMs = duration,
                message = message,
                stream = isStream,
            ),
        )
    }

    private fun errorBody(type: String, message: String): String {
        val obj = buildJsonObject {
            put("error", buildJsonObject {
                put("message", message)
                put("type", type)
                put("param", JsonNull)
                put("code", type)
            })
        }
        return AppJson.encodeToString(JsonObject.serializer(), obj)
    }

    private fun truncate(s: String, max: Int) = if (s.length <= max) s else s.take(max) + "…"
}
