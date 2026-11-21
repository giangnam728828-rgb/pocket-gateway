package com.pocketgateway.app.core.adapter

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
 * 从上游自动拉取模型列表。
 *
 * 按渠道适配器拼出正确的端点、请求头（含会话头族/鉴权），请求 `/v1/models`，
 * 再从多种可能的响应结构里解析出模型名。
 */
object ModelFetcher {

    /**
     * 拉取模型列表。返回去重后的模型名列表；失败返回 null。
     */
    suspend fun fetch(channel: Channel): Result<List<String>> = withContext(Dispatchers.IO) {
        // APP 直连类型走专用接口（真实端点与通用 OpenAI 不同）
        if (channel.type == com.pocketgateway.app.core.model.ProviderType.APP_NATIVE) {
            val r = com.pocketgateway.app.core.appaccount.WorkBuddyApi.fetchModels(channel)
            return@withContext r.map { rows -> rows.map { it.id } }
        }
        // TRAE 的模型目录结构特殊，走专用解析
        if (channel.type == com.pocketgateway.app.core.model.ProviderType.TRAE) {
            val r = com.pocketgateway.app.core.appaccount.TraeApi.fetchModels(channel)
            return@withContext r.map { rows -> rows.map { it.id } }
        }
        runCatching {
            val adapter = AdapterRegistry.get(channel)
            val path = adapter.modelsPath() ?: throw IllegalStateException("该协议没有标准 models 端点")
            val base = channel.baseUrl.trim().trimEnd('/')
            if (base.isBlank()) throw IllegalStateException("Base URL 为空")
            val url = base + if (path.startsWith("/")) path else "/$path"

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            // 注入适配器解析出的完整请求头（鉴权 + 官方客户端身份 + 会话头族）
            adapter.resolveHeaders(channel).forEach { (name, value) ->
                if (!name.equals("Content-Type", ignoreCase = true)) {
                    conn.setRequestProperty(name, value)
                }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = try {
                BufferedReader(InputStreamReader(stream)).use { it.readText() }
            } finally {
                conn.disconnect()
            }

            if (code !in 200..299) {
                throw IllegalStateException("上游返回 HTTP $code：${text.take(200)}")
            }

            parseModels(text).ifEmpty {
                throw IllegalStateException("未能从响应中解析出模型：${text.take(200)}")
            }
        }
    }

    /** 从多种响应结构中解析模型名。 */
    fun parseModels(raw: String): List<String> {
        val root = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        collect(root, out, depth = 0)
        return out.toList()
    }

    private fun collect(el: JsonElement, out: MutableSet<String>, depth: Int) {
        if (depth > 6) return
        when (el) {
            is JsonArray -> el.forEach { collect(it, out, depth + 1) }
            is JsonObject -> {
                // OpenAI 风格：{data:[{id:"gpt-4o",...}]}
                val data = el["data"]
                if (data is JsonArray) {
                    data.forEach { item ->
                        if (item is JsonObject) {
                            val id = (item["id"] as? JsonPrimitive)?.content
                                ?: (item["model"] as? JsonPrimitive)?.content
                                ?: (item["name"] as? JsonPrimitive)?.content
                            if (!id.isNullOrBlank()) out.add(id)
                        } else if (item is JsonPrimitive && item.content.isNotBlank()) {
                            out.add(item.content)
                        }
                    }
                }
                // 其他可能结构：models / modelList / list / items
                for (k in listOf("models", "modelList", "list", "items", "available", "model_list")) {
                    el[k]?.let { v ->
                        when (v) {
                            is JsonArray -> v.forEach { item ->
                                when (item) {
                                    is JsonPrimitive -> if (item.content.isNotBlank()) out.add(item.content)
                                    is JsonObject -> {
                                        val id = (item["id"] as? JsonPrimitive)?.content
                                            ?: (item["model"] as? JsonPrimitive)?.content
                                            ?: (item["name"] as? JsonPrimitive)?.content
                                        if (!id.isNullOrBlank()) out.add(id)
                                    }
                                    else -> {}
                                }
                            }
                            is JsonObject -> v.keys.forEach { out.add(it) }
                            else -> {}
                        }
                    }
                }
                // 嵌套 data 对象（业务信封 {code,msg,data:{...}}）
                val d = el["data"]
                if (d is JsonObject) collect(d, out, depth + 1)
            }
            else -> {}
        }
    }
}
