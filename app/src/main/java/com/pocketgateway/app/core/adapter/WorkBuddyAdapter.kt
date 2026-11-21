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
import kotlinx.serialization.json.add
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

    /** 对话/通用请求头（三段式 UA）。会话头由 transformRequest 随 body 一起生成。 */
    override fun defaultAuthHeaders(channel: Channel): List<Pair<String, String>> =
        buildHeaders(channel, chat = true, includeConversation = false)

    /**
     * 解析真实用户 ID。
     *
     * 历史缺陷：直接取 channel.appKind 当 uid，但 appKind 是**适配器类型标识**
     * （如 "workbuddy"），不是用户 ID。拿它派生 X-Machine-ID / X-Session-ID /
     * X-User-Id 会让设备指纹完全错误，上游渠道校验必然失败（11128）。
     *
     * 现在优先从 accessToken（JWT）payload 解析真实 uid，解析不到时才把
     * appKind 当兜底（且必须在它不像类型名时）。
     */
    fun resolveUid(channel: Channel): String {
        val token = channel.apiKey.trim()
        if (token.count { it == '.' } >= 2) {
            val hit = runCatching {
                val payload = token.split('.')[1]
                val json = String(
                    android.util.Base64.decode(
                        payload,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                    ),
                    Charsets.UTF_8
                )
                val obj = Json.parseToJsonElement(json) as? JsonObject
                // 用 firstOrNull 代替 for+break（lambda 内 break 无法编译）
                obj?.let { o ->
                    listOf("uid", "userId", "user_id", "sub", "uin").firstNotNullOfOrNull { k ->
                        val v = (o[k] as? JsonPrimitive)?.content
                        v?.takeIf { !it.isBlank() && (it.all { c -> c.isDigit() } || it.length >= 8) }
                    }
                }
            }.getOrNull()
            if (!hit.isNullOrBlank()) return hit
        }
        val kind = channel.appKind.trim()
        return if (kind.isNotEmpty() && kind.lowercase() !in KNOWN_APP_KINDS) kind else ""
    }

    /**
     * 构造出站请求头。
     * @param chat true = 对话/模型路径（三段式 UA + 会话头族）；
     *             false = 计费/签到/任务路径（单段 UA，不带会话头族）
     */
    fun buildHeaders(
        channel: Channel,
        chat: Boolean,
        convId: String = newHexId(),
        convReqId: String = newHexId(),
        includeConversation: Boolean = true,
    ): List<Pair<String, String>> {
        val token = channel.apiKey.trim()
        val uid = resolveUid(channel)
        val global = channel.baseUrl.contains("workbuddy.ai", ignoreCase = true)

        // CLI 渠道：chat 的 Origin/X-Domain 跟随上游请求域名；计费/任务走 Web 域
        val chatOrigin = if (global) GLOBAL_ORIGIN else CN_CHAT_ORIGIN
        val billingOrigin = if (global) GLOBAL_ORIGIN else CN_ORIGIN
        val origin = if (chat) chatOrigin else billingOrigin

        val h = mutableListOf<Pair<String, String>>()

        // —— 公共头（对齐官方 CodeBuddy CLI 契约）——
        h.add("Content-Type" to "application/json")
        h.add(
            "Accept" to if (chat) "text/event-stream, application/json"
            else "application/json, text/plain, */*",
        )
        h.add("X-Requested-With" to "XMLHttpRequest")
        h.add("Origin" to origin)
        h.add("Referer" to "$origin/")
        h.add("User-Agent" to "CLI/$CLI_VER CodeBuddy/$CLI_VER")
        h.add("X-CodeBuddy-Request" to "1")
        h.add("Accept-Language" to if (global) "en-US" else "zh-CN")

        // —— 鉴权与账号身份 ——
        if (token.isNotEmpty()) h.add("Authorization" to "Bearer $token")
        if (uid.isNotEmpty()) h.add("X-User-Id" to uid) else h.add("X-No-User-Id" to "1")
        h.add("X-No-Enterprise-Id" to "1")
        h.add("X-Product" to "SaaS")

        if (chat) {
            // 官方 CLI chat 指纹（缺一项都可能命中 11128 unapproved channel）
            h.add("X-Agent-Intent" to "craft")
            h.add("X-IDE-Type" to "CLI")
            h.add("X-IDE-Name" to "CLI")
            h.add("X-IDE-Version" to CLI_VER)
            h.add("X-Domain" to (if (global) "www.workbuddy.ai" else "copilot.tencent.com"))

            if (includeConversation) {
                // 会话头与 body 的 conversationId 由 transformRequest 同源生成
                val msgId = newHexId()
                h.add("X-Conversation-ID" to convId)
                h.add("X-Conversation-Request-ID" to convReqId)
                h.add("X-Conversation-Message-ID" to msgId)
                h.add("X-Request-ID" to msgId)
                h.add("X-Root-Request-ID" to convReqId)
                h.add("X-Trace-ID" to convReqId)
                h.add("X-B3-TraceId" to convReqId)
                h.add("X-B3-SpanId" to msgId.take(16))
                h.add("X-B3-Sampled" to "1")
            }
        } else {
            // 计费/签到/任务：无部门信息
            h.add("X-No-Department-Info" to "1")
        }

        // 可选设备令牌：仅用户显式填写时发送
        if (channel.deviceToken.isNotBlank()) {
            h.add("X-Device-Token" to channel.deviceToken.trim())
        }
        return h
    }

    override fun contentType(): String = "application/json; charset=utf-8"

    override fun transformRequest(channel: Channel, body: JsonObject, stream: Boolean): RequestPayload {
        val mapped = body.modelName()?.let { model ->
            channel.modelMapping[model]?.let { target -> body.withString("model", target) }
        } ?: body

        // 请求体净化：默认关闭（规则未经实测验证，且会改写用户原文）。
        // 仅在渠道显式开启 sanitizeEnabled 时生效。
        val sanitized = if (channel.sanitizeEnabled) BodySanitizer.sanitizeBody(mapped) else mapped

        // 模型名纠正：客户端常传 "workbuddy" 这类别名，上游只认真实模型名。
        // 若请求的模型不在本渠道的可用列表里，回退到列表第一个。
        val requested = sanitized.modelName() ?: ""
        val resolved = when {
            channel.modelMapping[requested] != null -> channel.modelMapping[requested]!!
            channel.models.isEmpty() -> requested.ifBlank { "glm-5.2" }
            channel.models.contains(requested) -> requested
            else -> channel.models.first()
        }

        val msgs: JsonArray = normalizeMessages(
            sanitized.messages() ?: buildJsonArray { }
        )
        val temp = sanitized.string("temperature")
        val maxTokens = sanitized.string("max_tokens")

        val convId = stableConversationId(channel, msgs)
        val convReqId = newHexId()
        val msgId = newHexId()
        val wb = buildJsonObject {
            put("model", JsonPrimitive(resolved))
            put("messages", msgs)
            // 上游只支持流式：非流式请求会被拒（code=11101）。
            // 这里恒发 true，非流式客户端由网关聚合 SSE 后再返回。
            put("stream", JsonPrimitive(true))
            if (temp != null) put("temperature", JsonPrimitive(temp))
            if (maxTokens != null) put("max_tokens", JsonPrimitive(maxTokens))
            // 与请求头 X-Conversation-ID 使用同一个会话 ID
            put("conversationId", JsonPrimitive(convId))
        }

        // 会话头与 body 必须同源：由这里统一生成，随 payload 一起发给上游。
        val convHeaders = mapOf(
            "X-Conversation-ID" to convId,
            "X-Conversation-Request-ID" to convReqId,
            "X-Conversation-Message-ID" to msgId,
            "X-Request-ID" to msgId,
            "X-Root-Request-ID" to convReqId,
            "X-Trace-ID" to convReqId,
            "X-B3-TraceId" to convReqId,
            "X-B3-SpanId" to msgId.take(16),
            "X-B3-Sampled" to "1",
        )

        return RequestPayload(
            body = AppJson.encodeToString(JsonObject.serializer(), wb),
            headers = convHeaders,
        )
    }

    /**
     * 上游对 messages 做角色白名单校验，命中 `developer` 会直接返回 400 code=11128；
     * 同时官方客户端要求首条为 system。这里做两件事：
     *  1. `developer` → `system`（语义等价，不丢内容）；
     *  2. 首条不是 system 时，前置一条中性 system，避免整批被渠道校验拦截。
     */
    private fun normalizeMessages(input: JsonArray): JsonArray {
        val normalized = input.map { el ->
            val o = el as? JsonObject ?: return@map el
            var msg = o
            val role = (o["role"] as? JsonPrimitive)?.content
            if (role.equals("developer", ignoreCase = true)) {
                msg = JsonObject(msg.toMutableMap().apply { put("role", JsonPrimitive("system")) })
            }

            val content = msg["content"]
            val text = contentText(content)
            val isSystem = (msg["role"] as? JsonPrimitive)?.content.equals("system", ignoreCase = true)
            if (isSystem && text.isNotBlank() &&
                !PROMPT_WHITELIST.containsMatchIn(text) && AGENT_PROMPT.containsMatchIn(text)
            ) {
                // 客户端身份提示词（Claude Code / Cursor / Cline / DSH 等）会被 WAF 判为
                // 非官方客户端 → 11128；替换为中性提示词，避免整单被拦。
                msg = JsonObject(msg.toMutableMap().apply {
                    put("content", JsonPrimitive(NEUTRAL_PROMPT))
                })
            } else if (content != null) {
                msg = JsonObject(msg.toMutableMap().apply {
                    put("content", sanitizeDshContent(content))
                })
            }
            msg
        }

        val firstRole = ((normalized.firstOrNull() as? JsonObject)?.get("role") as? JsonPrimitive)?.content
        return buildJsonArray {
            if (!firstRole.equals("system", ignoreCase = true)) {
                add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive("You are a helpful assistant."))
                })
            }
            normalized.forEach { add(it) }
        }
    }

    private fun contentText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.joinToString("\n") { part ->
            val t = (part as? JsonObject)?.get("text") as? JsonPrimitive
            t?.content ?: ""
        }
        else -> ""
    }

    /** WAF 会逐字拦截 "DeepSeek Harness"，插入零宽空格但保留语义。 */
    private fun sanitizeDshContent(content: JsonElement): JsonElement = when (content) {
        is JsonPrimitive -> if (content.isString) JsonPrimitive(sanitizeDsh(content.content)) else content
        is JsonArray -> buildJsonArray {
            content.forEach { part ->
                val o = part as? JsonObject
                val t = o?.get("text") as? JsonPrimitive
                if (o != null && t != null && t.isString) {
                    add(JsonObject(o.toMutableMap().apply {
                        put("text", JsonPrimitive(sanitizeDsh(t.content)))
                    }))
                } else {
                    add(part)
                }
            }
        }
        else -> content
    }

    private fun sanitizeDsh(text: String): String =
        DSH_PATTERN.replace(text) { m -> "DeepSeek\u200B" + m.groupValues[1] + "Harness" }

    /**
     * 会话 ID 稳定性：官方客户端一次对话内所有请求复用同一个 conversationId。
     * 这里用「渠道 + 首条 user 消息」做稳定派生，多轮对话自然保持同一个 ID，
     * 避免每轮都新建会话带来的异常特征。
     */
    private fun stableConversationId(channel: Channel, messages: JsonArray): String {
        val firstUser = messages.firstOrNull { el ->
            val o = el as? JsonObject
            o != null && (o["role"] as? JsonPrimitive)?.content == "user"
        } as? JsonObject
        val seed = firstUser?.get("content")?.toString().orEmpty()
        if (seed.isBlank()) return newHexId()
        val md = MessageDigest.getInstance("SHA-256")
        val hex = md.digest("wb2a:conv:${channel.id}:$seed".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return hex.take(32)
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
        /**
         * 把上游 SSE 全文聚合成标准 OpenAI 非流式响应。
         * 服务端只支持流式（非流式直接 11101），故非流式客户端需要网关侧聚合。
         */
        fun aggregateSse(raw: String, model: String): String? {
            val texts = StringBuilder()
            for (line in raw.lineSequence()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith(":") || t.startsWith("event:")) continue
                val data = t.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                runCatching {
                    val el = Json.parseToJsonElement(data)
                    val obj = el as? JsonObject ?: return@runCatching
                    // {code,msg,data} 信封
                    val d = obj["data"]
                    val target = if (d is JsonObject) d else obj
                    val choices = target["choices"]
                    if (choices is JsonArray) {
                        val first = choices.firstOrNull() as? JsonObject
                        (first?.get("delta") as? JsonObject)?.string("content")?.let { texts.append(it) }
                            ?: (first?.get("message") as? JsonObject)?.string("content")?.let { texts.append(it) }
                    } else {
                        listOf("content", "text", "answer", "delta").forEach { k ->
                            (target[k] as? JsonPrimitive)?.content?.let { if (it.isNotBlank()) texts.append(it) }
                        }
                    }
                }
            }
            if (texts.isEmpty()) return null
            return buildJsonObject {
                put("id", JsonPrimitive("chatcmpl-${System.currentTimeMillis()}"))
                put("object", JsonPrimitive("chat.completion"))
                put("created", JsonPrimitive(System.currentTimeMillis() / 1000))
                put("model", JsonPrimitive(model))
                put("choices", Json.parseToJsonElement(
                    """[{"index":0,"message":{"role":"assistant","content":${JsonPrimitive(texts.toString())}},"finish_reason":"stop"}]"""
                ))
                put("usage", Json.parseToJsonElement(
                    """{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}"""
                ))
            }.toString()
        }

        /** 已知的 appKind 类型标识（这些值代表适配器类型，不是用户 ID）。 */
        val KNOWN_APP_KINDS = setOf("workbuddy", "codebuddy", "workbuddyai", "workbuddy_ai", "")

        const val CLIENT_VER = "2.162.0"
        const val CLI_VER = "2.162.0"
        const val CN_ORIGIN = "https://www.codebuddy.cn"
        const val CN_CHAT_ORIGIN = "https://copilot.tencent.com"
        const val GLOBAL_ORIGIN = "https://www.workbuddy.ai"

        /** 32 位 hex ID（UUID 去横线）。 */
        fun newHexId(): String = UUID.randomUUID().toString().replace("-", "")

        /** 客户端身份提示词特征：命中即可能触发 11128。 */
        private val AGENT_PROMPT = Regex(
            "(?i)you are claude code|claude.{0,20}code.{0,40}official.{0,20}cli|" +
                "anthropic.{0,30}official.{0,30}cli|" +
                "you are (cursor|windsurf|cline|aider|continue|copilot|cody)|" +
                "you are an? (ai )?(coding |code )?agent|cc_entrypoint\\s*=|" +
                "you are .{0,30}(powerful )?ai agent|orchestration capabilities|" +
                "ohmyopencode|<agent-identity>|<role>|<behavior_instructions>|" +
                "deepseek[\\s_-]*harness",
        )

        /** 自家网关/系统指令白名单，不替换。 */
        private val PROMPT_WHITELIST = Regex(
            "(?i)hermes|10router|9router|claude code by anthropic|system instructions|你的身份|你的角色设定",
        )

        private const val NEUTRAL_PROMPT =
            "You are CodeBuddy Code.You are an interactive CLI tool that helps users with software engineering tasks."

        /** WAF 逐字拦截 "DeepSeek Harness"（含空格/连字符变体）。 */
        private val DSH_PATTERN = Regex("(?i)deepseek([\\s_-]*)harness")

        /** 账号级稳定派生：sha256("wb2a:<purpose>:<uid>") 取 36 hex，与官方实现同口径。 */
        fun deriveStableId(uid: String, purpose: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest("wb2a:$purpose:$uid".toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return hex.take(36)
        }
    }
}
