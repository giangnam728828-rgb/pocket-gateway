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
 * WorkBuddy / CodeBuddy 上游接口封装。
 *
 * 端点依据开源实现 workbuddy2api 的 internal/upstream/client.go：
 *
 * | 用途 | 国内 (copilot.tencent.com) | 国际 (www.workbuddy.ai) |
 * |---|---|---|
 * | 对话 | /v2/chat/completions | /v2/chat/completions |
 * | 模型 | /console/enterprises/personal/models | /v2/enterprises/personal/models |
 * | 配置 | /v3/config | /v3/config |
 * | 签到 | /billing/meter/daily-checkin | /v2/billing/meter/daily-checkin |
 * | 资源 | /billing/meter/get-user-resource | /v2/billing/meter/get-user-resource |
 * | 礼包 | /billing/meter/claim-gift | /v2/billing/meter/claim-gift |
 * | 补签 | /billing/meter/claim-compensation | /v2/billing/meter/claim-compensation |
 */
object WorkBuddyApi {

    /** 判断是否国际版（按 baseUrl 域名）。 */
    private fun isGlobal(ch: Channel): Boolean =
        ch.baseUrl.contains("workbuddy.ai", ignoreCase = true)

    /** 业务路径前缀：国际版统一加 /v2。 */
    private fun prefix(ch: Channel): String = if (isGlobal(ch)) "/v2" else ""

    /** 模型端点（按版本区分）。 */
    private fun modelsPath(ch: Channel): String =
        if (isGlobal(ch)) "/v2/enterprises/personal/models"
        else "/console/enterprises/personal/models"

    /** 资源端点。 */
    private fun resourcePath(ch: Channel): String = prefix(ch) + "/billing/meter/get-user-resource"

    /** 签到端点。 */
    private fun checkinPath(ch: Channel): String = prefix(ch) + "/billing/meter/daily-checkin"

    /** 礼包端点。 */
    private fun claimGiftPath(ch: Channel): String = prefix(ch) + "/billing/meter/claim-gift"

    // ---------- 底层请求 ----------

    private fun request(
        ch: Channel,
        urlStr: String,
        method: String,
        body: String? = null,
    ): Pair<Int, String> {
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

    /** 解析业务信封 {code,msg,data}，code!=0 视为失败。 */
    private fun unwrap(text: String): JsonElement? {
        val root = runCatching { AppJson.parseToJsonElement(text) }.getOrNull() ?: return null
        if (root is JsonObject) {
            val c = (root["code"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (c != null && c != 0) return null
            return root["data"] ?: root
        }
        return root
    }

    // ---------- 模型列表 ----------

    /**
     * 获取模型列表。依次尝试：
     *  1. 企业模型端点（CN /console/...，Global /v2/...）
     *  2. /v3/config
     * 返回去重后的模型名；失败返回 Result.failure。
     */
    suspend fun fetchModels(ch: Channel): Result<List<String>> = withContext(Dispatchers.IO) {
        val base = ch.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Base URL 为空"))
        }
        var lastErr = "未知错误"
        for (url in listOf(base + modelsPath(ch), base + "/v3/config")) {
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
            val models = parseModelNames(data)
            if (models.isNotEmpty()) {
                return@withContext Result.success(models)
            }
            lastErr = "响应中无模型：${text.take(160)}"
        }
        Result.failure(IllegalStateException(lastErr))
    }

    /** 从模型响应中解析模型名（兼容 agents[].models 与 models[]）。 */
    private fun parseModelNames(root: JsonElement): List<String> {
        val out = LinkedHashSet<String>()
        if (root !is JsonObject) return emptyList()

        // agents: [{name:"cli", models:["glm-5.2", ...]}]
        (root["agents"] as? JsonArray)?.forEach { agent ->
            (agent as? JsonObject)?.let { a ->
                (a["models"] as? JsonArray)?.forEach { m ->
                    when (m) {
                        is JsonPrimitive -> if (m.content.isNotBlank()) out.add(m.content)
                        is JsonObject -> (m["id"] as? JsonPrimitive)?.content?.let { if (it.isNotBlank()) out.add(it) }
                        else -> {}
                    }
                }
            }
        }
        // models: [{id:"glm-5.2", name:..., disabled:false}]
        (root["models"] as? JsonArray)?.forEach { m ->
            if (m is JsonObject) {
                val disabled = (m["disabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                if (disabled) return@forEach
                val id = (m["id"] as? JsonPrimitive)?.content
                    ?: (m["name"] as? JsonPrimitive)?.content
                if (!id.isNullOrBlank()) out.add(id)
            } else if (m is JsonPrimitive && m.content.isNotBlank()) {
                out.add(m.content)
            }
        }
        // 兜底：data/items/list
        for (k in listOf("data", "items", "list", "modelList")) {
            val v = root[k] ?: continue
            when (v) {
                is JsonArray -> v.forEach { it ->
                    when (it) {
                        is JsonPrimitive -> if (it.content.isNotBlank()) out.add(it.content)
                        is JsonObject -> ((it["id"] ?: it["name"] ?: it["model"]) as? JsonPrimitive)
                            ?.content?.let { s -> if (s.isNotBlank()) out.add(s) }
                        else -> {}
                    }
                }
                is JsonObject -> v.keys.forEach { out.add(it) }
                else -> {}
            }
        }
        return out.toList()
    }

    // ---------- 签到 / 积分 ----------

    /** 账户资源（积分等信息）。 */
    data class Resource(
        val credits: Long? = null,
        val totalCredits: Long? = null,
        val nickname: String? = null,
    )

    suspend fun fetchResource(ch: Channel): Resource? = withContext(Dispatchers.IO) {
        val base = ch.baseUrl.trim().trimEnd('/')
        val (code, text) = request(ch, base + resourcePath(ch), "GET")
        if (code !in 200..299) return@withContext null
        val data = unwrap(text) as? JsonObject ?: return@withContext null
        fun num(vararg keys: String): Long? = keys.firstNotNullOfOrNull { k ->
            (data[k] as? JsonPrimitive)?.content?.toLongOrNull()
        }
        Resource(
            credits = num("credits", "credit", "balance", "remainCredits", "availableCredits"),
            totalCredits = num("totalCredits", "total", "total_credits"),
            nickname = (data["nickname"] as? JsonPrimitive)?.content
                ?: (data["name"] as? JsonPrimitive)?.content,
        )
    }

    /** 一键签到。返回是否成功及提示。 */
    suspend fun dailyCheckin(ch: Channel): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = ch.baseUrl.trim().trimEnd('/')
        val (code, text) = request(ch, base + checkinPath(ch), "POST", "{}")
        if (code !in 200..299) return@withContext false to "HTTP $code：${text.take(160)}"
        val data = unwrap(text)
        if (data == null) return@withContext false to "签到失败：${text.take(160)}"
        val reward = runCatching {
            val o = data as? JsonObject
            (o?.get("credits") ?: o?.get("reward") ?: o?.get("amount"))?.toString()
        }.getOrNull()
        true to ("签到成功" + if (!reward.isNullOrBlank() && reward != "null") "，+$reward 积分" else "")
    }

    /** 领取礼包（claim-gift）。 */
    suspend fun claimGift(ch: Channel): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = ch.baseUrl.trim().trimEnd('/')
        val (code, text) = request(ch, base + claimGiftPath(ch), "POST", "{}")
        if (code !in 200..299) return@withContext false to "HTTP $code：${text.take(160)}"
        if (unwrap(text) == null) return@withContext false to "领取失败：${text.take(160)}"
        true to "礼包已领取"
    }
}
