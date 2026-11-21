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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
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

    /** 渠道冷却器：429/403 时临时停止路由到该渠道，反封禁。 */
    private val cooldown = ChannelCooldown()

    fun start(config: GatewayConfig) {
        if (server != null) return
        val host = if (config.listenOnAllInterfaces) "0.0.0.0" else "127.0.0.1"
        server = embeddedServer(CIO, host = host, port = config.port) {
            install(CORS) {
                // 浏览器/网页版客户端（NextChat Web、LobeChat Web、Open WebUI 等）需要跨域
                anyHost()
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Accept)
                allowHeader("x-api-key")
                allowHeader("anthropic-version")
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Options)
                allowNonSimpleContentTypes = true
            }
            routing {
                get("/") { call.respondInfo() }
                get("/health") { call.respondText("ok", ContentType.Text.Plain) }
                get("/v1/models") { call.handleModels() }
                post("/v1/chat/completions") { call.handleChat(explicitStream = false) }
                post("/v1/chat/completions/stream") { call.handleChat(explicitStream = true) }
                post("/v1/completions") { call.handleChat(explicitStream = false) }
                post("/v1/embeddings") { call.handleEmbeddings() }

                // ---- 兼容别名：部分客户端会把 /v1 自行拼上，或只接受不带 /v1 的地址 ----
                get("/models") { call.handleModels() }
                post("/chat/completions") { call.handleChat(explicitStream = false) }
                post("/chat/completions/stream") { call.handleChat(explicitStream = true) }
                post("/completions") { call.handleChat(explicitStream = false) }
                post("/embeddings") { call.handleEmbeddings() }

                // ---- 兼容重复前缀：用户误把 /v1 填进会自动追加 /v1 的客户端 ----
                get("/v1/v1/models") { call.handleModels() }
                post("/v1/v1/chat/completions") { call.handleChat(explicitStream = false) }
                post("/v1/v1/completions") { call.handleChat(explicitStream = false) }
                post("/v1/v1/embeddings") { call.handleEmbeddings() }
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

    /**
     * 从候选中剔除处于冷却的渠道；若全部在冷却则退回原列表，
     * 避免出现“所有渠道都被冷却导致彻底无路可走”的情况。
     */
    private suspend fun List<Channel>.excludeCooling(model: String?): List<Channel> {
        if (isEmpty()) return this
        val available = filter {
            !cooldown.isCooling(it.id) && !RequestThrottle.isModelCooling(it.id, model)
        }
        return if (available.isEmpty()) this else available
    }

    /** 解析 Retry-After（仅支持秒数形式）。 */
    private fun parseRetryAfterMs(value: String?): Long {
        val sec = value?.trim()?.toLongOrNull() ?: return 0L
        return (sec * 1000L).coerceIn(0L, 30 * 60_000L)
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
        // 模型列表为空时给出可用兜底，否则客户端“获取模型”会判失败/空列表
        if (ids.isEmpty()) {
            cfg.channels.filter { it.enabled }.forEach { ch ->
                if (ch.type == ProviderType.APP_NATIVE) {
                    ids.add("glm-5.2")
                    ids.add("deepseek-v4-flash")
                } else {
                    ids.add("gpt-4o")
                }
            }
        }
        if (ids.isEmpty()) ids.add("gpt-4o")
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
        val candidates = selectChannels(cfg, model).excludeCooling(model)
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

            // APP 直连渠道：用 HTTP/1.1 直连（官方实现禁用 h2，走 h2 会被边缘 302）
            if (channel.type == com.pocketgateway.app.core.model.ProviderType.APP_NATIVE) {
                try {
                    // 批次B：记录转换后的请求摘要（脱敏）与上游状态码，便于定位
                    val reqDigest = buildString {
                        append("model=").append(model ?: "?")
                        append(" bodyLen=").append(payload.body.length)
                        append(" bodyHead=").append(
                            payload.body.take(160).replace(Regex("\\s+"), " ")
                        )
                    }
                    RequestThrottle.before(channel.id)
                    val (st, text) = com.pocketgateway.app.core.appaccount.WorkBuddyApi
                        .chatStream(channel, payload.body, timeout)
                    val ok = st in 200..299
                    if (ok) {
                        cooldown.clear(channel.id)
                        RequestThrottle.clearModel(channel.id, model)
                    } else if (st == 429 || st == 403) {
                        cooldown.penalize(channel.id, st)
                    }
                    if (text.contains("11102")) {
                        RequestThrottle.penalizeModel(channel.id, model)
                    }
                    val respHead = text.take(200).replace(Regex("\\s+"), " ")
                    log(cfg, model, channel, st, ok, System.currentTimeMillis() - start,
                        "REQ[$reqDigest] RESP[$respHead]", wantStream)
                    log(cfg, model, channel, st, ok, System.currentTimeMillis() - start,
                        if (ok) "OK" else truncate(text, 200), wantStream)
                    response.header("X-PocketGateway-Channel", channel.name)
                    if (!ok) {
                        lastError = "HTTP $st: ${truncate(text, 160)}"
                        response.status(HttpStatusCode.fromValue(st.coerceIn(400, 599)))
                        respondText(text, ContentType.Application.Json)
                        return
                    }
                    if (wantStream) {
                        // 上游本身就是 SSE，直接透传给客户端
                        response.header(HttpHeaders.CacheControl, "no-cache")
                        response.header("X-Accel-Buffering", "no")
                        respondText(text, ContentType.Text.EventStream)
                    } else {
                        val json = com.pocketgateway.app.core.adapter.WorkBuddyAdapter
                            .aggregateSse(text, model ?: "workbuddy")
                            ?: adapter.transformResponse(channel, text)
                            ?: text
                        respondText(json, ContentType.Application.Json)
                    }
                    return
                } catch (e: Exception) {
                    lastError = e.message ?: e::class.java.simpleName
                    log(cfg, model, channel, 0, false, System.currentTimeMillis() - start, lastError, wantStream)
                    continue
                }
            }

            try {
                if (wantStream) {
                    forwardStream(channel, adapter, payload, timeout, model, start)
                    return
                } else {
                    RequestThrottle.before(channel.id)
                    val resp = upstream.call(channel, adapter, "chat", payload, timeout)
                    if (resp.status in 200..299) {
                        cooldown.clear(channel.id)
                        RequestThrottle.clearModel(channel.id, model)
                    } else if (resp.status == 429 || resp.status == 403) {
                        cooldown.penalize(
                            channel.id,
                            resp.status,
                            parseRetryAfterMs(resp.headers["retry-after"]),
                        )
                    }
                    if (resp.body.contains("11102")) {
                        RequestThrottle.penalizeModel(channel.id, model)
                    }
                    val out = if (resp.status in 200..299) {
                        // 上游只支持流式（非流式返回 11101），非流式客户端在此聚合 SSE
                        val looksSse = (resp.contentType?.contains("event-stream", true) == true) ||
                            resp.body.trimStart().startsWith("data:")
                        if (channel.type == com.pocketgateway.app.core.model.ProviderType.APP_NATIVE && looksSse) {
                            com.pocketgateway.app.core.adapter.WorkBuddyAdapter
                                .aggregateSse(resp.body, model ?: "workbuddy")
                                ?: adapter.transformResponse(channel, resp.body) ?: resp.body
                        } else {
                            adapter.transformResponse(channel, resp.body) ?: resp.body
                        }
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
        RequestThrottle.before(channel.id)
        val upstreamResp = upstream.prepareStream(channel, adapter, "chat", payload, timeout)
        val status = upstreamResp.status.value
        if (status in 200..299) {
            cooldown.clear(channel.id)
            RequestThrottle.clearModel(channel.id, model ?: "")
        } else if (status == 429 || status == 403) {
            cooldown.penalize(
                channel.id,
                status,
                parseRetryAfterMs(upstreamResp.headers["retry-after"]),
            )
        }
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
        val candidates = selectChannels(cfg, model)
            .filter { it.type == ProviderType.OPENAI_COMPAT }
            .excludeCooling(model)
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
                RequestThrottle.before(channel.id)
                val resp = upstream.call(channel, AdapterRegistry.get(channel), "embeddings", payload, timeout)
                if (resp.status in 200..299) {
                    cooldown.clear(channel.id)
                    RequestThrottle.clearModel(channel.id, model)
                } else if (resp.status == 429 || resp.status == 403) {
                    cooldown.penalize(
                        channel.id,
                        resp.status,
                        parseRetryAfterMs(resp.headers["retry-after"]),
                    )
                }
                if (resp.body.contains("11102")) {
                    RequestThrottle.penalizeModel(channel.id, model)
                }
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
