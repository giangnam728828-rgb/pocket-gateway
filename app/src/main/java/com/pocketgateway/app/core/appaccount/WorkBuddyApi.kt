package com.pocketgateway.app.core.appaccount

import com.pocketgateway.app.core.adapter.AdapterRegistry
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        ch.baseUrl.contains("workbuddy.ai", ignoreCase = true)

    /**
     * 计费域前缀：源码 billingMeterPaths 口径 ——
     * CN 用 /v2/billing/meter 族，国际用 /billing/meter 族（无 v2），404 时互相回退。
     */
    private fun billingPrefix(ch: Channel): String = if (isGlobal(ch)) "" else "/v2"

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

    private fun request(ch: Channel, urlStr: String, method: String, body: String? = null): Pair<Int, String> {
        val adapter = AdapterRegistry.get(ch)
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            adapter.resolveHeaders(ch).forEach { (n, v) ->
                if (!n.equals("Content-Type", ignoreCase = true)) setRequestProperty(n, v)
            }
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
        val base = ch.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Base URL 为空"))
        }

        // 主路：企业模型端点（含 credits 倍率）；备用：/v3/config
        val attempts = listOf(base + modelsPath(ch), base + "/v3/config")
        var lastErr = "未知错误"

        for (url in attempts) {
            val (code, text) = request(ch, url, "GET")
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
            if (rows.isNotEmpty()) return@withContext Result.success(rows)
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

    suspend fun fetchAccount(ch: Channel): AccountInfo? = withContext(Dispatchers.IO) {
        val base = ch.baseUrl.trim().trimEnd('/')
        val (code, text) = request(ch, base + resourcePath(ch), "GET")
        if (code !in 200..299) return@withContext null
        val data = unwrap(text) as? JsonObject ?: return@withContext null

        fun num(vararg keys: String): Long? = keys.firstNotNullOfOrNull { k ->
            (data[k] as? JsonPrimitive)?.content?.toLongOrNull()
        }
        fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            (data[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        }

        val remain = num("credits", "credit", "balance", "remainCredits", "availableCredits", "remainingCredits")
        val total = num("totalCredits", "total", "total_credits", "quota", "totalQuota")
        val used = num("usedCredits", "used", "used_credits")
            ?: if (remain != null && total != null) (total - remain).coerceAtLeast(0) else null

        AccountInfo(
            uid = str("uid", "userId", "user_id") ?: ch.appKind,
            nickname = str("nickname", "name", "userName") ?: "",
            creditsRemain = remain,
            creditsTotal = total,
            creditsUsed = used,
            usable = !(str("status")?.equals("disabled", true) ?: false),
            statusReason = str("reason", "statusReason") ?: "",
        )
    }

    suspend fun fetchResource(ch: Channel): AccountInfo? = fetchAccount(ch)

    /** 能量与连续签到天数。 */
    suspend fun fetchEnergy(ch: Channel): Pair<Long?, Long?> = withContext(Dispatchers.IO) {
        val base = ch.baseUrl.trim().trimEnd('/')
        val (code, text) = request(ch, base + energyPath(ch), "GET")
        if (code !in 200..299) return@withContext (null to null)
        val data = unwrap(text) as? JsonObject ?: return@withContext (null to null)
        fun num(vararg keys: String): Long? = keys.firstNotNullOfOrNull { k ->
            (data[k] as? JsonPrimitive)?.content?.toLongOrNull()
        }
        num("balance", "energy") to num("streak", "continuousDays", "streakDays")
    }

    suspend fun dailyCheckin(ch: Channel): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = ch.baseUrl.trim().trimEnd('/')
        val (code, text) = request(ch, base + checkinPath(ch), "POST", "{}")
        if (code !in 200..299) return@withContext false to "HTTP $code：${text.take(120)}"
        if (unwrap(text) == null) return@withContext false to "签到失败：${text.take(120)}"
        true to "签到成功"
    }

    suspend fun claimGift(ch: Channel): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = ch.baseUrl.trim().trimEnd('/')
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
        val tag: String = "",
    ) {
        val progressText: String
            get() = if (target > 0) "$current/$target" else ""

        val statusText: String
            get() = when {
                claimable -> "可领取"
                !accepted -> "未接取"
                target > 0 && current >= target -> "已完成"
                else -> "进行中"
            }
    }

    /** 拉取任务列表（GET /v2/activity/growth/tasks）。 */
    suspend fun fetchTasks(ch: Channel): Pair<List<GrowthTask>, String?> = withContext(Dispatchers.IO) {
        val base = ch.baseUrl.trim().trimEnd('/')
        val (code, text) = request(ch, base + tasksPath(ch), "GET")
        if (code !in 200..299) return@withContext emptyList<GrowthTask>() to "HTTP $code：${text.take(120)}"

        val root = runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
        val data = unwrap(text)
        if (data == null) return@withContext emptyList<GrowthTask>() to "业务码非 0：${text.take(120)}"

        var arr: JsonArray? = null
        if (data is JsonArray) arr = data
        else if (data is JsonObject) {
            arr = (data["tasks"] as? JsonArray) ?: (data["list"] as? JsonArray)
        }
        val list = arr ?: return@withContext emptyList<GrowthTask>() to "响应无 tasks 字段：${text.take(160)}"

        val tasks = list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val tc = (o["task_code"] as? JsonPrimitive)?.content
                ?: (o["code"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val title = (o["title"] as? JsonPrimitive)?.content
                ?: (o["name"] as? JsonPrimitive)?.content ?: tc
            val desc = (o["description"] as? JsonPrimitive)?.content
                ?: (o["task_desc"] as? JsonPrimitive)?.content ?: ""
            val credit = (o["reward_credit"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: (o["credit"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val current = (o["current"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val target = (o["target"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val acceptStatus = (o["accept_status"] as? JsonPrimitive)?.content ?: ""
            val accepted = acceptStatus == "accepted" || acceptStatus == "accepted_rewarded"
            val claimed = (o["claimed"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            val tag = (o["tag"] as? JsonPrimitive)?.content ?: ""
            GrowthTask(
                code = tc, title = title, desc = desc, credit = credit,
                current = current, target = target, accepted = accepted,
                claimable = !claimed && target > 0 && current >= target,
                tag = tag,
            )
        }
        tasks to if (tasks.isEmpty()) "上游返回 ${list.size} 条但未解析出任务" else null
    }

    /** 接取任务（POST /v2/activity/growth/tasks/accept）。 */
    suspend fun acceptTasks(ch: Channel, codes: List<String>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = ch.baseUrl.trim().trimEnd('/')
        val body = codes.joinToString(",", "[", "]") { "\"$it\"" }
        val payload = "{\"task_codes\":$body}"
        val (http, text) = request(ch, base + "/v2/activity/growth/tasks/accept", "POST", payload)
        if (http !in 200..299) return@withContext false to "HTTP $http：${text.take(120)}"
        true to "已接取 ${codes.size} 个任务"
    }

    /** 领取单个任务奖励。 */
    suspend fun claimTask(ch: Channel, code: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = ch.baseUrl.trim().trimEnd('/')
        // 端点 A（社区实测）：路径含 task_code、无 body
        val (h1, t1) = request(ch, "$base/activity/growth/tasks/$code/claim", "POST", null)
        if (h1 in 200..299) return@withContext true to "已领取"
        // 端点 B（panel 分支口径）：reward/claim + body
        val (h2, t2) = request(
            ch, "$base/v2/activity/growth/tasks/reward/claim", "POST",
            "{\"task_code\":\"$code\"}",
        )
        if (h2 in 200..299) return@withContext true to "已领取"
        false to "HTTP $h1/${t1.take(60)}；备选 HTTP $h2/${t2.take(60)}"
    }

    /** 一键领取所有可领任务奖励。返回（成功数，失败数，说明）。 */
    suspend fun claimAllTasks(ch: Channel): Triple<Int, Int, String> = withContext(Dispatchers.IO) {
        val tasks = fetchTasks(ch).first
        if (tasks.isEmpty()) return@withContext Triple(0, 0, "没有可领取的任务")

        val base = ch.baseUrl.trim().trimEnd('/')
        var ok = 0
        var fail = 0
        for (t in tasks.filter { it.claimable }) {
            val (code, _) = request(ch, "$base/activity/growth/tasks/${t.code}/claim", "POST", null)
            if (code in 200..299) ok++ else fail++
        }
        if (ok == 0 && fail == 0) {
            Triple(0, 0, "没有可领取的任务")
        } else {
            Triple(ok, fail, "领取成功 $ok 个" + if (fail > 0) "，失败 $fail 个" else "")
        }
    }
}
