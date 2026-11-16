package com.pocketgateway.app.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 全局共享的 Json 实例：宽松解析，忽略未知字段，允许注释与宽松命名。 */
val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = false
    coerceInputValues = true
}

/** 从 JsonObject 中安全取字符串。 */
fun JsonObject.string(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

/** 从 JsonObject 中安全取布尔。 */
fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    this[key]?.let { runCatching { it.jsonPrimitive.content.toBooleanStrict() }.getOrNull() } ?: default

/** 复制一个 JsonObject 并替换/新增某个字符串字段。 */
fun JsonObject.withString(key: String, value: String): JsonObject = buildJsonObject {
    this@withString.forEach { (k, v) -> put(k, v) }
    put(key, JsonPrimitive(value))
}

/** 复制一个 JsonObject 并删除某个字段。 */
fun JsonObject.without(key: String): JsonObject = buildJsonObject {
    this@without.forEach { (k, v) -> if (k != key) put(k, v) }
}

/** 取出 messages 数组（若存在）。 */
fun JsonObject.messages(): JsonArray? = this["messages"]?.let { runCatching { it.jsonArray }.getOrNull() }

/** 取模型名。 */
fun JsonObject.modelName(): String? = string("model")

/** 把任意 JsonElement 转成可读字符串。 */
fun JsonElement.asText(): String = when (this) {
    is JsonPrimitive -> content
    is JsonArray -> toString()
    is JsonObject -> toString()
}

/** 从其值中提取所有字符串（用于把任意 content 规整为纯文本）。 */
fun JsonElement.flattenText(): String = when (this) {
    is JsonPrimitive -> content
    is JsonArray -> joinToString("\n") { it.flattenText() }
    is JsonObject -> when {
        containsKey("text") -> this["text"]!!.flattenText()
        containsKey("content") -> this["content"]!!.flattenText()
        else -> entries.joinToString("\n") { it.value.flattenText() }
    }
}
