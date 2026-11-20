package com.pocketgateway.app.core.appaccount

import com.pocketgateway.app.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToLong

/**
 * 积分响应解析（纯函数）。
 *
 * 上游有两套口径：
 *  - Web 计费域 `billing/meter/get-user-resource-summary`：`data.TotalCredits` 即客户端顶部
 *    “剩余积分”，`data.CreditGroups[]` 按 kind 分组（plan_base / plan_bonus / addon / benefit）。
 *  - Copilot 域 `billing/meter/get-user-resource`：`data.Response.Data.Accounts[]`，
 *    每笔含 `CycleCapacityRemain` / `ExpiredTime`。
 *
 * 解析集中在此，上游字段变化时只需改这里。
 */
internal object BillingParser {

    data class Summary(
        val remain: Long?,
        val total: Long?,
        val used: Long?,
        val groups: List<Group> = emptyList(),
    )

    data class Group(
        val kind: String,
        val remain: Long,
        val total: Long,
        val used: Long,
    )

    /** 解析 Web 计费域的 resource-summary 响应。 */
    fun parseSummary(text: String): Summary? {
        val data = unwrapData(text) as? JsonObject ?: return null

        fun num(vararg keys: String): Long? = keys.firstNotNullOfOrNull { k ->
            (data[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.roundToLong()
        }

        val groups = parseGroups(data["CreditGroups"] as? JsonArray)

        // TotalCredits 是“剩余积分”，与客户端顶部一致。
        val remain = num("TotalCredits", "totalCredits", "RemainCredits", "remainingCredits", "CreditBalance")
        val total = num("TotalQuota", "totalQuota", "Quota", "Total")
            ?: groups.takeIf { it.isNotEmpty() }?.sumOf { it.total }
        val used = num("UsedCredits", "usedCredits", "Used")
            ?: groups.takeIf { it.isNotEmpty() }?.sumOf { it.used }
            ?: if (remain != null && total != null) (total - remain).coerceAtLeast(0) else null

        if (remain == null && total == null) return null
        return Summary(remain, total, used, groups)
    }

    /** 解析 Copilot 域的逐笔账户响应（兜底口径）。 */
    fun parseCopilot(text: String): Summary? {
        val root = runCatching { AppJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val data = (root["data"] as? JsonObject) ?: root
        val response = (data["Response"] as? JsonObject) ?: data
        val payload = (response["Data"] as? JsonObject) ?: response
        val accounts = payload["Accounts"] as? JsonArray ?: return null

        var remain = 0L
        var total = 0L
        var found = false
        accounts.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            fun n(vararg keys: String): Long? = keys.firstNotNullOfOrNull { k ->
                (o[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.roundToLong()
            }
            val r = n("CycleCapacityRemain", "Remain", "RemainCredits")
            val t = n("CycleCapacity", "Total", "TotalCredits")
            if (r != null || t != null) {
                found = true
                remain += r ?: 0
                total += t ?: 0
            }
        }
        if (!found) return null
        val used = (total - remain).coerceAtLeast(0)
        return Summary(remain = remain, total = total.takeIf { it > 0 }, used = used)
    }

    private fun parseGroups(arr: JsonArray?): List<Group> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun n(vararg keys: String): Long? = keys.firstNotNullOfOrNull { k ->
                (o[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.roundToLong()
            }
            val kind = (o["Kind"] as? JsonPrimitive)?.content
                ?: (o["kind"] as? JsonPrimitive)?.content
                ?: (o["Name"] as? JsonPrimitive)?.content
                ?: ""
            val remain = n("Remain", "RemainCredits", "CycleCapacityRemain", "TotalCredits") ?: return@mapNotNull null
            val total = n("Total", "TotalCredits", "CycleCapacity") ?: remain
            val used = n("Used", "UsedCredits") ?: (total - remain).coerceAtLeast(0)
            Group(kind, remain, total, used)
        }
    }

    /** 兼容 {code,msg,data} 信封：业务码非 0 视为失败。 */
    private fun unwrapData(text: String): JsonElement? {
        val root = runCatching { AppJson.parseToJsonElement(text) }.getOrNull() ?: return null
        if (root is JsonObject) {
            val code = (root["code"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (code != null && code != 0) return null
            return root["data"] ?: root
        }
        return root
    }
}
