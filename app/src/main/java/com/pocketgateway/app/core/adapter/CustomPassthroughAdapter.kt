package com.pocketgateway.app.core.adapter

import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.util.AppJson
import com.pocketgateway.app.util.JsonPath
import com.pocketgateway.app.util.SignUtil
import com.pocketgateway.app.util.TemplateInterpolator
import com.pocketgateway.app.util.modelName
import com.pocketgateway.app.util.withString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 自定义透传适配器：用于任何"请求体格式、鉴权、地址都不标准"的上游。
 *
 * 两种工作模式（由渠道配置决定）：
 *  1. 透传模式（requestTemplate 留空）：请求体按 OpenAI 格式原样转发，仅做模型名映射；
 *     响应原样透传。适合"格式就是 OpenAI 兼容，只是地址/鉴权不同"的场景。
 *  2. 模板模式（填写 requestTemplate）：用模板插值把 OpenAI 请求映射成私有接口的请求格式，
 *     并用 responseContentPath 从私有响应中提取内容、组装成标准 OpenAI 响应。
 *     适合只能在自己网站/APP 内部调用的、非 OpenAI 格式的接口（用户自行配置、自行负责）。
 */
class CustomPassthroughAdapter : ProviderAdapter {

    override val type: ProviderType = ProviderType.CUSTOM

    override fun defaultAuthHeaders(channel: Channel): List<Pair<String, String>> = emptyList()

    override fun chatPath(): String = "/chat/completions"

    override fun modelsPath(): String? = "/models"

    override fun transformRequest(channel: Channel, body: JsonObject, stream: Boolean): RequestPayload {
        // 1. 模型名映射（先做，保证模板里 {{model}} 是上游真实名）
        val mapped = body.modelName()?.let { model ->
            channel.modelMapping[model]?.let { target -> body.withString("model", target) }
        } ?: body

        // 2. 解析查询参数
        val queryParams = parseQueryParams(channel.queryParams)

        // 3. 模板模式：用 requestTemplate 插值生成请求体
        val finalBody = if (channel.requestTemplate.isNotBlank()) {
            val vars = TemplateInterpolator.buildVars(mapped, channel.apiKey, stream)
            TemplateInterpolator.interpolate(channel.requestTemplate, vars)
        } else {
            AppJson.encodeToString(JsonObject.serializer(), mapped)
        }

        // 4. 签名：按渠道持久配置计算签名头（含动态时间戳/随机数），无需每次手动处理
        val signHeaders = buildSignHeaders(channel, finalBody, mapped)

        return RequestPayload(
            body = finalBody,
            method = channel.requestMethod.ifBlank { "POST" },
            queryParams = queryParams,
            headers = signHeaders,
        )
    }

    /**
     * 计算签名头。签名配置持久保存在渠道中；每次请求动态生成时间戳/随机数后，
     * 对模板插值得到的待签名字符串按算法计算，结果作为请求头注入。
     */
    private fun buildSignHeaders(channel: Channel, body: String, mapped: JsonObject): Map<String, String> {
        if (channel.signHeader.isBlank() || channel.signAlgorithm.isBlank()) return emptyMap()
        val timestamp = System.currentTimeMillis().toString()
        val nonce = java.util.UUID.randomUUID().toString().take(8)
        val vars = TemplateInterpolator.buildVars(mapped, channel.apiKey, false) + mapOf(
            "timestamp" to timestamp,
            "nonce" to nonce,
            "body" to body,
        )
        val content = TemplateInterpolator.interpolate(channel.signTemplate, vars)
        val sign = SignUtil.compute(channel.signAlgorithm, content, channel.signKey)
        return mapOf(channel.signHeader.trim() to sign)
    }

    override fun transformResponse(channel: Channel, rawBody: String): String? {
        val path = channel.responseContentPath
        if (path.isBlank()) return null // 透传模式：原样返回

        // 从上游响应中按路径提取内容，组装成标准 OpenAI 响应
        val content = JsonPath.extract(rawBody, path) ?: return null
        val model = channel.models.firstOrNull()?.ifBlank { "custom" } ?: "custom"
        // 把提取到的文本转成带引号的 JSON 字符串值，便于拼入响应
        val contentJson = JsonPrimitive(content).toString()
        return buildJsonObject {
            put("id", JsonPrimitive("chatcmpl-${System.currentTimeMillis()}"))
            put("object", JsonPrimitive("chat.completion"))
            put("created", JsonPrimitive(System.currentTimeMillis() / 1000))
            put("model", JsonPrimitive(model))
            put("choices", Json.parseToJsonElement(
                """[{"index":0,"message":{"role":"assistant","content":$contentJson,"finish_reason":"stop"}]"""
            ))
            put("usage", Json.parseToJsonElement(
                """{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}"""
            ))
        }.toString()
    }

    /** 解析 "key=value" 每行一条的查询参数。 */
    private fun parseQueryParams(text: String): Map<String, String> = buildMap {
        text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && "=" in it }
            .forEach { line ->
                val i = line.indexOf('=')
                put(line.substring(0, i).trim(), line.substring(i + 1).trim())
            }
    }
}
