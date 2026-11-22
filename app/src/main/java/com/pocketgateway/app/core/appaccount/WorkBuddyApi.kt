package com.pocketgateway.app.core.appaccount

import com.pocketgateway.app.core.adapter.AdapterRegistry
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 账号可用的一个模型（含倍率与能力）。
 *
 * 字段来源 workbuddy2api 的 dynModelEntry：
 *  - credits：倍率字符串，"0.00" 表示免费，"" 表示上游未提供
 *  - maxInputTokens / maxOutputTokens：上下文与输出上限
 *  - supportsImages：可看图
 */
data class ModelRow(
    val id: String,
    val displayName: String = "",
    val credits: String = "",
    val contextLength: Long = 0,
    val maxOutput: Long = 0,
    val supportsImages: Boolean = false,
) {
    /** 倍率展示：免费 / x0.79 / — */
    /** 倍率展示：免费 / x0.79 / —（上游原文可能已带 x 前缀）。 */
    val creditLabel: String
        get() {
            val raw = credits.trim().removePrefix("x").removePrefix("X")
            if (raw.isBlank()) return "—"
            val v = raw.toDoubleOrNull() ?: return if (credits.isBlank()) "—" else credits
            return if (v == 0.0) "免费" else "x$raw"
        }

    val isFree: Boolean
        get() = credits.trim().removePrefix("x").removePrefix("X").toDoubleOrNull() == 0.0
}

/**
 * WorkBuddy / CodeBuddy 上游接口封装。
 *
 * 端点依据开源实现 workbuddy2api（internal/upstream/client.go、scripts/task_runner.py）：
 *
 * | 用途 | 国内 (copilot.tencent.com) | 国际 (www.workbuddy.ai) |
 * |---|---|---|
 * | 对话 | /v2/chat/completions | /v2/chat/completions |
 * | 模型 | /console/enterprises/personal/models | /v2/enterprises/personal/models |
 * | 配置 | /v3/config | /v3/config |
 * | 签到 | /billing/meter/daily-checkin | /v2/billing/meter/daily-checkin |
 * | 资源 | /billing/meter/get-user-resource | /v2/billing/meter/get-user-resource |
 * | 礼包 | /billing/meter/claim-gift | /v2/billing/meter/claim-gift |
 * | 任务列表 | /activity/growth/tasks | /v2/activity/growth/tasks |
 * | 接取任务 | /activity/growth/tasks/accept | 同 |
 * | 领奖 | /activity/growth/tasks/{code}/claim | 同 |
 * | 能量 | /v2/activity/growth/energy | 同 |
 */
object WorkBuddyApi {

    private fun isGlobal(ch: Channel): Boolean =
        ch.baseUrl.contains("workbuddy.ai", ignoreCase = true) || ch.name.contains("国际")

    /**
     * 解析上游根地址。
     *
     * **不信任渠道里保存的历史 baseUrl**（旧版本可能存了错误地址，导致 404/302）。
     * 一律按地域强制为官方域名：
     *  - 国内：https://copilot.tencent.com
     *  - 国际：https://www.workbuddy.ai
     */
    fun resolveBase(ch: Channel): String =
        if (isGlobal(ch)) "https://www.workbuddy.ai" else "https://copilot.tencent.com"

    /**
     * 计费域路径（实测口径，带真实凭证验证）：
     * /billing/meter/get-user-resource     → 404 page not found（不存在）
     * /v2/billing/meter/get-user-resource  → 需凭证，正确路由
     * 故统一使用 /v2 前缀。
     */
    private fun billingPrefix(ch: Channel): String = ""

    private fun modelsPath(ch: Channel): String =
        if (isGlobal(ch)) "/v2/enterprises/personal/models"
        else "/console/enterprises/personal/models"

    private fun resourcePath(ch: Channel): String = billingPrefix(ch) + "/billing/meter/get-user-resource"
    private fun checkinPath(ch: Channel): String = billingPrefix(ch) + "/billing/meter/daily-checkin"
    private fun claimGiftPath(ch: Channel): String = billingPrefix(ch) + "/billing/meter/claim-gift"

    /** 任务列表：/v2/activity/growth/tasks（chat base）。 */
    private fun tasksPath(ch: Channel): String = "/v2/activity/growth/tasks"
    private fun energyPath(ch: Channel): String = "/v2/activity/growth/energy"

    // ---------- 底层请求 ----------

    /**
     * 发请求。@param chat true 用对话头（三段式 UA + 会话头族），
     * false 用计费/任务头（单段 UA），对齐官方 BillingHeaders 形态。
     */
    private fun request(
        ch: Channel,
        urlStr: String,
        method: String,
        body: String? = null,
        chat: Boolean = false,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): Pair<Int, String> {
        val headers = if (ch.type == com.pocketgateway.app.core.model.ProviderType.APP_NATIVE) {
            com.pocketgateway.app.core.adapter.WorkBuddyAdapter().buildHeaders(ch, chat = chat)
        } else {
            AdapterRegistry.get(ch).resolveHeaders(ch)
        }
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 30000
            // 不自动跟随重定向：302 通常意味着鉴权/风控问题，跟随会拿到登录页 HTML 掩盖真实原因
            instanceFollowRedirects = false
            // 显式设置 Content-Type（适配器返回的 Content-Type 被过滤后必须补回）
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (n, v) ->
                if (!n.equals("Content-Type", ignoreCase = true)) setRequestProperty(n, v)
            }
            extraHeaders.forEach { (n, v) -> setRequestProperty(n, v) }
            if (method != "GET") doOutput = true
        }
        return try {
            if (method != "GET") {
                conn.outputStream.use { it.write((body ?: "{}").toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            code to text
        } catch (e: Exception) {
            -1 to (e.message ?: "网络错误")
        } finally {
            conn.disconnect()
        }
    }

    private fun unwrap(text: String): JsonElement? {
        val root = runCatching { AppJson.parseToJsonElement(text) }.getOrNull() ?: return null
        if (root is JsonObject) {
            val c = (root["code"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (c != null && c != 0) return null
            return root["data"] ?: root
        }
        return root
    }

    // ---------- 模型（仅账号可用 + 带倍率） ----------

    /**
     * 获取**当前账号可用**的模型列表。
     *
     * 过滤规则（对齐 workbuddy2api 的 fetchEnterpriseModels）：
     *  1. 只保留 `agents[name=="cli"].models` 里的模型（这才是账号实际可调的）
     *  2. 剔除 `disabled == true`
     *  3. 剔除非对话模型：id 以 nes- / completion- / codewise- 开头，
     *     或 maxOutputTokens <= 256，或 tags 含 text-to-image
     */
    suspend fun fetchModels(ch: Channel): Result<List<ModelRow>> = withContext(Dispatchers.IO) {
        val expectedModels = ch.models.map { it.removePrefix("workbuddy/") }
        val base = resolveBase(ch)
        if (base.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Base URL 为空"))
        }

        // 主路：企业模型端点（含 credits 倍率）；备用：/v3/config
        // /v3/config 实测可匿名返回完整目录（含 agents/models/credits），优先使用
        val attempts = listOf(base + "/v3/config", base + modelsPath(ch))
        var lastErr = "未知错误"

        for (url in attempts) {
            val (code, text) = request(ch, url, "GET", chat = true)
            if (code !in 200..299) {
                lastErr = "HTTP $code：${text.take(160)}"
                continue
            }
            val data = unwrap(text)
            if (data == null) {
                lastErr = "业务码非 0：${text.take(160)}"
                continue
            }
            val rows = parseUsableModels(data)
            if (rows.isNotEmpty()) {
                // 保持对外模型名（含前缀）稳定：上游返回的配置名可能与渠道里保存的别名大小写/写法不同
                val normalized = rows.map { row ->
                    val canonical = expectedModels.firstOrNull { it.equals(row.id, ignoreCase = true) }
                    if (canonical != null && canonical != row.id) row.copy(id = canonical) else row
                }
                return@withContext Result.success(normalized)
            }
            lastErr = "未解析到可用模型：${text.take(160)}"
        }
        Result.failure(IllegalStateException(lastErr))
    }

    /**
     * 从模型响应中解析「账号可用」模型（含倍率）。
     * 若无 agents 信息（v3/config 形态），则退化为全量 models 过滤非对话项。
     */
    fun parseUsableModels(root: JsonElement): List<ModelRow> {
        if (root !is JsonObject) return emptyList()

        // 1. CLI 通道白名单
        val cliWhitelist: Set<String> = run {
            val set = LinkedHashSet<String>()
            (root["agents"] as? JsonArray)?.forEach { a ->
                val obj = a as? JsonObject ?: return@forEach
                val name = (obj["name"] as? JsonPrimitive)?.content ?: return@forEach
                if (name == "cli") {
                    (obj["models"] as? JsonArray)?.forEach { m ->
                        when (m) {
                            is JsonPrimitive -> set.add(m.content)
                            is JsonObject -> (m["id"] as? JsonPrimitive)?.content?.let { set.add(it) }
                            else -> {}
                        }
                    }
                }
            }
            set
        }

        // 2. 全量模型明细
        val models = mutableListOf<ModelRow>()
        (root["models"] as? JsonArray)?.forEach { m ->
            val o = m as? JsonObject ?: return@forEach
            val id = (o["id"] as? JsonPrimitive)?.content
                ?: (o["name"] as? JsonPrimitive)?.content
                ?: return@forEach
            if (id.isBlank()) return@forEach

            val disabled = (o["disabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            if (disabled) return@forEach

            val maxOut = (o["maxOutputTokens"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
            val tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

            // 非对话模型剔除
            if (isNonChat(id, maxOut, tags)) return@forEach

            models.add(
                ModelRow(
                    id = id,
                    displayName = (o["name"] as? JsonPrimitive)?.content ?: id,
                    credits = (o["credits"] as? JsonPrimitive)?.content ?: "",
                    contextLength = (o["maxInputTokens"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                    maxOutput = maxOut,
                    supportsImages = (o["supportsImages"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                )
            )
        }

        if (models.isEmpty()) return emptyList()

        // 3. 有 CLI 白名单则按白名单过滤（这才是账号真正可用的）
        return if (cliWhitelist.isNotEmpty()) {
            models.filter { it.id in cliWhitelist }
        } else {
            models
        }
    }

    /** 非对话模型判定（对齐 workbuddy2api 的 nonChatModel）。 */
    private fun isNonChat(id: String, maxOutput: Long, tags: List<String>): Boolean {
        val lower = id.lowercase()
        if (lower.startsWith("nes-") || lower.startsWith("completion-") || lower.startsWith("codewise-")) return true
        if (maxOutput in 1..256) return true
        if (tags.any { it.equals("text-to-image", ignoreCase = true) }) return true
        return false
    }

    // ---------- 对话（HTTP/1.1 直连，绕开 Ktor 的 h2） ----------

    /**
     * 发起对话请求（流式），返回 (状态码, 原始 SSE 文本)。
     *
     * 关键：使用 HttpURLConnection（HTTP/1.1），不用 Ktor。
     * 官方实现明确禁用 h2（空 TLSNextProto），握手走 HTTP/2 会被边缘判定异常并 302。
     */
    suspend fun chatStream(ch: Channel, requestBody: String, timeoutMs: Long = 120_000): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val base = resolveBase(ch)
            val url = base + "/v2/chat/completions"
            // 从请求体里取出 conversationId，保证 Header 与 body 一致（协议一致性）
            val convId = runCatching {
                val o = AppJson.parseToJsonElement(requestBody) as? JsonObject
                (o?.get("conversationId") as? JsonPrimitive)?.content
            }.getOrNull()?.takeIf { it.isNotBlank() }
            val headers = com.pocketgateway.app.core.adapter.WorkBuddyAdapter()
                .buildHeaders(ch, chat = true, convId = convId ?: com.pocketgateway.app.core.adapter.WorkBuddyAdapter.newHexId())

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = timeoutMs.toInt().coerceIn(10_000, 300_000)
                    instanceFollowRedirects = false   // 不跟随，暴露真实 302
                    useCaches = false
                    setRequestProperty("Connection", "keep-alive")
                    setRequestProperty("Accept-Encoding", "identity") // 避免 gzip 干扰 SSE
                    // 显式设置 Content-Type（之前被过滤掉且未补回，属于缺陷）
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    headers.forEach { (n, v) ->
                        if (!n.equals("Content-Type", ignoreCase = true)) setRequestProperty(n, v)
                    }
                    doOutput = true
                }
                conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
                } ?: ""
                code to text
            } catch (e: Exception) {
                -1 to (e.message ?: "网络错误")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

    // ---------- 诊断 ----------

    private const val CHAT_PROBE_BODY =
        """{"model":"glm-5.2","messages":[{"role":"user","content":"hi"}],"stream":true}"""

    /**
     * 构造对比用探针体：模拟真实客户端（Claude Code 类）的 system prompt，
     * 其中包含上游可能按逐字匹配拦截的身份句。
     * @param sanitize true 时套用 BodySanitizer，用于对照验证净化是否有效。
     */
    private fun sanitizedProbe(sanitize: Boolean): String {
        val raw = """{"model":"glm-5.2","messages":[{"role":"system","content":"You are Claude Code, Anthropic's official CLI tool for Claude."},{"role":"user","content":"hi"}],"stream":true}"""
        if (!sanitize) return raw
        val obj = AppJson.parseToJsonElement(raw) as JsonObject
        val cleaned = com.pocketgateway.app.core.adapter.BodySanitizer.sanitizeBody(obj)
        return AppJson.encodeToString(JsonObject.serializer(), cleaned)
    }

    data class ProbeResult(val name: String, val url: String, val status: Int, val snippet: String)

    /**
     * 连接诊断：依次请求关键端点，返回状态码与响应片段。
     * 用于定位 302/401 到底发生在哪个环节（凭证缺失 / 路径错误 / 风控）。
     */
    suspend fun diagnose(ch: Channel): List<ProbeResult> = withContext(Dispatchers.IO) {
        val base = resolveBase(ch)
        val hasToken = ch.apiKey.isNotBlank()
        val probes = listOf(
            Triple("配置(匿名可通)", "$base/v3/config", true),
            Triple("积分①", "$base/billing/meter/get-user-resource", false),
            Triple("积分②", "$base/v2/billing/meter/get-user-resource", false),
            Triple("积分③", "$base/billing/meter/user-resource", false),
            Triple("能量", "$base/v2/activity/growth/energy", false),
            Triple("对话·最小请求", "$base/v2/chat/completions", false),
            Triple("对话·带Claude身份句(原始)", "PROBE_RAW", false),
            Triple("对话·带Claude身份句(净化)", "PROBE_SANITIZED", false),
            Triple("任务(需凭证)", "$base/activity/growth/tasks", false),
        )
        probes.map { (name, url, anonymous) ->
            val (code, text) = when {
                url == "PROBE_RAW" -> chatStream(ch, sanitizedProbe(sanitize = false), 30_000)
                url == "PROBE_SANITIZED" -> chatStream(ch, sanitizedProbe(sanitize = true), 30_000)
                url.endsWith("/v2/chat/completions") -> chatStream(ch, CHAT_PROBE_BODY, 30_000)
                else -> request(ch, url, "GET", chat = true)
            }
            val snippet = text.replace(Regex("\\s+"), " ").take(120)
            val note = when {
                !anonymous && !hasToken -> "（未填凭证，必然失败）$snippet"
                code == 302 -> "（302 重定向=被边缘风控拦截）$snippet"
                code == 401 -> "（401=凭证无效/过期）$snippet"
                else -> snippet
            }
            ProbeResult(name, url, code, note)
        }
    }

    // ---------- 积分 / 签到 ----------

    /** 账号资源与状态。 */
    data class AccountInfo(
        val uid: String = "",
        val nickname: String = "",
        val creditsRemain: Long? = null,
        val creditsTotal: Long? = null,
        val creditsUsed: Long? = null,
        val usable: Boolean = true,
        val statusReason: String = "",
        /** 积分来源：summary（Web 计费域）/ copilot（逐笔账户）。 */
        val creditsSource: String = "",
    ) {
        /** 使用百分比 0..100。 */
        val percent: Int
            get() {
                val used = creditsUsed ?: 0
                val total = creditsTotal ?: 0
                if (total <= 0) return 0
                return ((used * 100) / total).toInt().coerceIn(0, 100)
            }
    }

    /** 积分端点候选：运行时逐个尝试，取第一个非 404 的（不同环境路由不同）。 */
    private val resourceCandidates = listOf(
        "/billing/meter/get-user-resource",
        "/v2/billing/meter/get-user-resource",
        "/billing/meter/user-resource",
    )

    /**
     * Web 计费域候选。优先级：
     *  1. `www.workbuddy.cn`（国内 Web 域，resource-summary 实测所在域）
     *  2. `www.codebuddy.cn`（国内新域名备份）
     *  3. `www.workbuddy.ai`（国际）
     */
    private fun billingBases(ch: Channel): List<String> =
        if (isGlobal(ch)) listOf("https://www.workbuddy.ai")
        else listOf("https://www.workbuddy.cn", "https://www.codebuddy.cn")

    suspend fun fetchAccount(ch: Channel): AccountInfo? = withContext(Dispatchers.IO) {
        // 正确口径：POST /billing/meter/get-user-resource-summary → data.TotalCredits
        for (base in billingBases(ch)) {
            val (code, text) = request(
                ch,
                base + "/billing/meter/get-user-resource-summary",
                "POST",
                "{}",
            )
            if (code in 200..299) {
                BillingParser.parseSummary(text)?.let { s ->
                    return@withContext AccountInfo(
                        uid = ch.appKind,
                        creditsRemain = s.remain,
                        creditsTotal = s.total,
                        creditsUsed = s.used,
                        creditsSource = "summary",
                    )
                }
            }
        }

        // 兜底口径：Copilot 逐笔账户（data.Response.Data.Accounts[]）
        val base = resolveBase(ch)
        for (p in resourceCandidates) {
            val (c, t) = request(ch, base + p, "GET")
            if (c !in 200..299) continue
            BillingParser.parseCopilot(t)?.let { s ->
                return@withContext AccountInfo(
                    uid = ch.appKind,
                    creditsRemain = s.remain,
                    creditsTotal = s.total,
                    creditsUsed = s.used,
                    creditsSource = "copilot",
                )
            }
        }

        // 取不到积分就返回 null，绝不再拿 energy.balance 冒充积分。
        null
    }

    suspend fun fetchResource(ch: Channel): AccountInfo? = fetchAccount(ch)

    /** 能量与连续签到天数。 */
    suspend fun fetchEnergy(ch: Channel): Pair<Long?, Long?> = withContext(Dispatchers.IO) {
        val base = resolveBase(ch)
        val (code, text) = request(ch, base + energyPath(ch), "GET")
        if (code !in 200..299) return@withContext (null to null)
        val data = unwrap(text) as? JsonObject ?: return@withContext (null to null)
        fun num(vararg keys: String): Long? = keys.firstNotNullOfOrNull { k ->
            (data[k] as? JsonPrimitive)?.content?.toLongOrNull()
        }
        num("balance", "energy") to num("streak", "continuousDays", "streakDays")
    }

    suspend fun dailyCheckin(ch: Channel): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = resolveBase(ch)
        val (code, text) = request(ch, base + checkinPath(ch), "POST", "{}")
        if (code !in 200..299) return@withContext false to "HTTP $code：${text.take(120)}"
        if (unwrap(text) == null) return@withContext false to "签到失败：${text.take(120)}"
        true to "签到成功"
    }

    suspend fun claimGift(ch: Channel): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = resolveBase(ch)
        val (code, text) = request(ch, base + claimGiftPath(ch), "POST", "{}")
        if (code !in 200..299) return@withContext false to "HTTP $code：${text.take(120)}"
        if (unwrap(text) == null) return@withContext false to "领取失败：${text.take(120)}"
        true to "礼包已领取"
    }

    // ---------- 任务（活动积分） ----------

    data class GrowthTask(
        val code: String,
        val title: String,
        val desc: String = "",
        val credit: Long = 0,
        val current: Long = 0,
        val target: Long = 0,
        val accepted: Boolean = false,
        val claimable: Boolean = false,
        val claimed: Boolean = false,
        val locked: Boolean = false,
        val platform: String = "desktop",
        val tag: String = "",
    ) {
        val progressText: String
            get() = if (target > 0) "$current/$target" else ""

        val statusText: String
            get() = when {
                claimed -> "已领取"
                claimable -> "可领取"
                locked -> "未解锁"
                !accepted -> "未接取"
                target > 0 && current >= target -> "已完成"
                else -> "进行中"
            }
    }

    /** 小程序口径请求头：部分任务只在该口径下发/可操作。 */
    private val mpHeaders = listOf("X-Client-Platform" to "miniprogram")

    /** 拉取任务列表：合并桌面口径 + 小程序口径，按 task_code 去重。 */
    suspend fun fetchTasks(ch: Channel): Pair<List<GrowthTask>, String?> = withContext(Dispatchers.IO) {
        val desktop = fetchTasksOnce(ch, mini = false)
        val mp = fetchTasksOnce(ch, mini = true)
        val merged = LinkedHashMap<String, GrowthTask>()
        (desktop.first + mp.first).forEach { t -> if (!merged.containsKey(t.code)) merged[t.code] = t }
        val err = when {
            merged.isNotEmpty() -> null
            desktop.second != null -> desktop.second
            else -> mp.second
        }
        merged.values.toList() to err
    }

    private fun fetchTasksOnce(ch: Channel, mini: Boolean): Pair<List<GrowthTask>, String?> {
        val base = resolveBase(ch)
        val (code, text) = request(
            ch, base + tasksPath(ch), "GET",
            extraHeaders = if (mini) mpHeaders else emptyList(),
        )
        if (code !in 200..299) return emptyList<GrowthTask>() to "HTTP $code：${text.take(120)}"

        val data = unwrap(text) ?: return emptyList<GrowthTask>() to "业务码非 0：${text.take(120)}"
        var arr: JsonArray? = null
        if (data is JsonArray) arr = data
        else if (data is JsonObject) {
            arr = (data["tasks"] as? JsonArray) ?: (data["list"] as? JsonArray)
        }
        val list = arr ?: return emptyList<GrowthTask>() to "响应无 tasks 字段：${text.take(160)}"

        val tasks = list.mapNotNull { parseTask(it, mini) }
        return tasks to if (tasks.isEmpty() && list.isNotEmpty()) {
            "上游返回 ${list.size} 条但未解析出任务"
        } else null
    }

    private fun parseTask(e: JsonElement, mini: Boolean): GrowthTask? {
        val o = e as? JsonObject ?: return null
        val tc = (o["task_code"] as? JsonPrimitive)?.content
            ?: (o["code"] as? JsonPrimitive)?.content ?: return null
        val title = (o["title"] as? JsonPrimitive)?.content
            ?: (o["name"] as? JsonPrimitive)?.content ?: tc
        val desc = (o["description"] as? JsonPrimitive)?.content
            ?: (o["task_desc"] as? JsonPrimitive)?.content ?: ""
        val credit = (o["reward_credit"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: (o["credit"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

        // progress 可能是 {current,target} 对象，也可能平铺在任务对象上
        val progress = o["progress"] as? JsonObject
        fun pn(key: String): Long? = (progress?.get(key) as? JsonPrimitive)?.content?.toLongOrNull()
        val current = pn("current")
            ?: (o["current"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        val target = pn("target")
            ?: (o["target"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

        val acceptStatus = (o["accept_status"] as? JsonPrimitive)?.content ?: ""
        val claimed = acceptStatus == "claimed" ||
            ((o["claimed"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false)
        val accepted = acceptStatus in setOf("accepted", "completed", "accepted_rewarded", "claimed")
        val locked = (o["locked"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        val tag = (o["tag"] as? JsonPrimitive)?.content ?: ""

        return GrowthTask(
            code = tc,
            title = title,
            desc = desc,
            credit = credit,
            current = current,
            target = target,
            accepted = accepted,
            claimable = !claimed && !locked && target > 0 && current >= target,
            claimed = claimed,
            locked = locked,
            platform = if (mini) "mp" else "desktop",
            tag = tag,
        )
    }

    /** 活跃上报（growth 域）：POST /v2/report，用于点亮“对话”类任务的前置。 */
    suspend fun reportChatActivity(
        ch: Channel,
        modelId: String = "deepseek-v4-flash",
        modelName: String = "",
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = resolveBase(ch)
        val uid = com.pocketgateway.app.core.adapter.WorkBuddyAdapter().resolveUid(ch)
        val now = System.currentTimeMillis()
        val conv = "wb2a-$now"
        val req = "wb2a-$now-1"
        val mid = modelId.ifBlank { "deepseek-v4-flash" }
        val mname = modelName.ifBlank { mid }

        val event = buildJsonObject {
            put("eventCode", "chat_request_send")
            put("timestamp", now)
            put("reportDelay", 0)
            put("mode", "craft")
            put("conversationId", conv)
            put("requestId", req)
            put("inputLength", 12)
            put("requestModelId", mid)
            put("requestModelName", mname)
            put("isPlan", false)
            put("isAutoExecuteTerminal", false)
            put("isAutoModify", false)
            put("codebaseEnable", false)
            put("maxToken", 0)
            put("maxSteps", 0)
            put("temperature", 0)
            put("maxRetries", 0)
            put("mentionContexts", buildJsonArray { })
            put("knowledgeId", buildJsonArray { })
            put("knowledgeName", buildJsonArray { })
            put("codebaseId", "")
            put("mentionContextCount", 0)
            put("command", "")
            put("expertId", "")
            put("recommendId", "")
            put("skillId", "")
            put("skillCount", 0)
            put("totalCount", 0)
            put("fileUri", "")
            put("presentAt", now)
            put("traceId", "")
            put("rootRequestId", req)
            put("parentConversationId", conv)
            put("agentName", "default")
            put("agentType", "conversation")
            put("userId", uid)
        }
        val payload = buildJsonArray { add(event) }.toString()
        val (code, text) = request(ch, base + "/v2/report", "POST", payload)
        val ok = code in 200..299 && unwrap(text) != null
        if (ok) true to "活跃已上报" else false to "上报失败：HTTP $code ${text.take(80)}"
    }

    /** 领养首只 Buddy 的协议确认与领取（first_buddy 任务前置）。 */
    suspend fun buddyAgreement(ch: Channel): Pair<Boolean, String> =
        postGrowth(ch, "/activity/growth/buddy/agreement", "{\"agree\":true}")

    suspend fun buddyFirst(ch: Channel): Pair<Boolean, String> =
        postGrowth(ch, "/activity/growth/buddy/first", "{}")

    private suspend fun postGrowth(ch: Channel, path: String, body: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val (code, text) = request(ch, resolveBase(ch) + path, "POST", body)
            val ok = code in 200..299 && unwrap(text) != null
            if (ok) true to "ok" else false to "HTTP $code ${text.take(80)}"
        }

    /** 接取任务（POST /v2/activity/growth/tasks/accept）。 */
    suspend fun acceptTasks(ch: Channel, codes: List<String>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (codes.isEmpty()) return@withContext true to "无需接取"
        val base = resolveBase(ch)
        val body = codes.joinToString(",", "[", "]") { "\"$it\"" }
        val payload = "{\"task_codes\":$body}"
        val (http, text) = request(ch, base + "/v2/activity/growth/tasks/accept", "POST", payload)
        val ok = http in 200..299 && unwrap(text) != null
        if (ok) true to "已接取 ${codes.size} 个任务"
        else false to "接取失败：HTTP $http ${text.take(100)}"
    }

    /**
     * 领取单个任务奖励。按上游两种口径依次尝试：
     *  1. `POST /v2/activity/growth/tasks/reward/claim` + body
     *  2. `POST /activity/growth/tasks/{code}/claim`（小程序口径，需 mp 头）
     *  3. `POST /v2/activity/growth/tasks/{code}/claim`（兼容旧路由）
     */
    suspend fun claimTask(ch: Channel, code: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = resolveBase(ch)
        val (h1, t1) = request(
            ch, "$base/v2/activity/growth/tasks/reward/claim", "POST",
            "{\"task_code\":\"$code\"}",
        )
        if (h1 in 200..299 && unwrap(t1) != null) return@withContext true to "已领取"

        val (h2, t2) = request(
            ch, "$base/activity/growth/tasks/$code/claim", "POST", null,
            extraHeaders = mpHeaders,
        )
        if (h2 in 200..299 && unwrap(t2) != null) return@withContext true to "已领取"

        val (h3, t3) = request(ch, "$base/v2/activity/growth/tasks/$code/claim", "POST", null)
        if (h3 in 200..299 && unwrap(t3) != null) return@withContext true to "已领取"

        false to "领取失败：HTTP $h1/${h2}/${h3} ${t1.take(60)}"
    }

    /** 一键领取：先接取未接取任务，再逐个领取已完成任务（带请求间隔）。 */
    suspend fun claimAllTasks(ch: Channel): Triple<Int, Int, String> = withContext(Dispatchers.IO) {
        val tasks = fetchTasks(ch).first
        if (tasks.isEmpty()) return@withContext Triple(0, 0, "没有可领取的任务")

        val pending = tasks.filter { !it.claimed && !it.locked && !it.accepted }.map { it.code }
        if (pending.isNotEmpty()) acceptTasks(ch, pending)

        val claimable = fetchTasks(ch).first.filter { it.claimable }
        if (claimable.isEmpty()) {
            return@withContext Triple(0, 0, "暂无可领取任务（需先在客户端完成任务）")
        }

        var ok = 0
        var fail = 0
        for (t in claimable) {
            delay(kotlin.random.Random.nextLong(1200, 2600))
            val (good, _) = claimTask(ch, t.code)
            if (good) ok++ else fail++
        }
        Triple(ok, fail, "领取成功 $ok 个" + if (fail > 0) "，失败 $fail 个" else "")
    }
}
