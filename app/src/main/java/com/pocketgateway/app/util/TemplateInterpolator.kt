package com.pocketgateway.app.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 请求体模板插值。
 *
 * 模板中形如 {{model}} / {{messages}} / {{key}} / {{stream}} 的占位符会被替换。
 * 用于把网关内部的 OpenAI 格式请求，映射成任意私有接口的请求格式。
 */
object TemplateInterpolator {

    /** 替换模板中的 {{name}} 占位符；未提供的变量替换为空串。 */
    fun interpolate(template: String, vars: Map<String, String>): String {
        val regex = Regex("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}")
        return regex.replace(template) { m ->
            vars[m.groupValues[1]] ?: ""
        }
    }

    /**
     * 从 OpenAI 请求体构造模板变量。
     * @param body 已做模型名映射后的请求体
     * @param apiKey 渠道 API Key
     * @param stream 是否流式
     */
    fun buildVars(body: JsonObject, apiKey: String, stream: Boolean): Map<String, String> = buildMap {
        body.modelName()?.let { put("model", it) }
        body.messages()?.let { put("messages", it.toString()) }
        body.string("max_tokens")?.let { put("max_tokens", it) }
        body.string("temperature")?.let { put("temperature", it) }
        put("key", apiKey)
        put("stream", stream.toString())
    }
}

/**
 * 简易 JSON 路径提取。
 *
 * 支持点号访问与数组下标，例如：
 *  - "data.text"
 *  - "data[0].text"
 *  - "choices[0].message.content"
 *  - "result.list[1].value"
 *
 * 取到的 JsonElement 会被规整为可读文本（字符串去引号，数组/对象序列化）。
 */
object JsonPath {

    /** 按路径从 JSON 字符串中提取文本；失败返回 null。 */
    fun extract(json: String, path: String): String? {
        if (path.isBlank()) return null
        return runCatching {
            val root = Json.parseToJsonElement(json)
            navigate(root, path.trim())?.let(::toText)
        }.getOrNull()
    }

    /** 从 JsonElement 按路径导航到目标元素。 */
    fun navigate(root: JsonElement, path: String): JsonElement? {
        var current: JsonElement? = root
        val parts = path.split('.')
        for (part in parts) {
            if (current == null) return null
            // 拆出键名与该段附带的下标，如 "list[1]" -> key="list", idx=[1]
            val keyRegex = Regex("^([^\\[]+)(([\\d+\\])*)$")
            val m = keyRegex.matchEntire(part) ?: return null
            val key = m.groupValues[1]
            current = if (current is JsonObject) current[key] else null
            if (current == null) return null
            // 处理该段附带的下标
            val idxRegex = Regex("\\[(\\d+)\\]")
            for (im in idxRegex.findAll(part)) {
                val idx = im.groupValues[1].toInt()
                current = if (current is JsonArray && idx in current.indices) current[idx] else null
                if (current == null) return null
            }
        }
        return current
    }

    /** 规整为可读文本。 */
    fun toText(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.content
        is JsonArray -> element.joinToString("") { toText(it) }
        is JsonObject -> element.entries.joinToString("") { (_, v) -> toText(v) }
    }
}
