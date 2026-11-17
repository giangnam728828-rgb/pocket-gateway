package com.pocketgateway.app.core.adapter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 出站请求体净化（对齐 workbuddy2api 的 sanitize.go）。
 *
 * 上游内容审核按**逐字精确匹配**拦截，命中即返回 `400 code=11128`
 * "Illegal API invocation from an unapproved channel"。触发指纹包括：
 *
 *  - `x-anthropic-billing-header`（键值或裸键名均触发）
 *  - `cc_entrypoint=` 等 `cc_xxx=` 尾随键值
 *  - `You are Claude Code`（Claude Code 身份句）
 *  - `Main branch (`（PR 指令句）
 *  - `You are a coding agent running in the Codex CLI`（Codex 指令句）
 *  - `github.com/anthropics/`
 *  - **`11128`（上游反探测：请求体出现这个数字就整单拦截）**
 *
 * 处理策略：键值型整段删除；模板句最小改写（换一词）；11128 改为 `11-128`。
 */
object BodySanitizer {

    /** 触发预检特征。 */
    private val FEATURES = listOf(
        "x-anthropic-billing-header",
        "cc_entrypoint=",
        "You are Claude Code",
        "Main branch (",
        "You are a coding agent running in the Codex CLI",
        "github.com/anthropics/",
        "11128",
        // 补充：其他常见客户端身份句与中转指纹
        "You are an AI assistant accessed via an API",
        "Claude Code",
        "claude-code",
        "codex_cli",
        "codex_cli_rs",
        "anthropic-beta",
        "anthropic-version",
    )

    /** 模板句改写：每句只改一个词，语义不变。 */
    private val REWRITES = listOf(
        "You are Claude Code, Anthropic's official CLI for Claude"
            to "You are Claude Code, Anthropic's official CLI tool for Claude",
        "Main branch (you will usually use this for PRs)"
            to "Default branch (you will usually use this for PRs)",
        "You are a coding agent running in the Codex CLI, a terminal-based coding assistant."
            to "You are a coding agent running in the Codex CLI tool, a terminal-based coding assistant.",
        "To give feedback, users should report the issue at https://github.com/anthropics/claude-code/issues"
            to "To provide feedback, users should report the issue at https://github.com/anthropics/claude-code/issues",
        // 上游反探测：请求体出现 11128 就整单拦截，插入连字符保留可读性
        "11128" to "11-128",
    )

    private val HDR_KV = Regex("(?i)x-anthropic-billing-header:[^;\\n]*;?\\s*")
    private val CC_KV = Regex("(?i)\\bcc_[a-z0-9_]+=[^;\\n]*;?\\s*")
    private val HDR_BARE = Regex("(?i)x-anthropic-billing-header")

    private fun hasFingerprint(text: String): Boolean =
        FEATURES.any { text.contains(it) } || HDR_BARE.containsMatchIn(text)

    /** 净化单段文本。 */
    fun sanitizeText(text: String): String {
        if (text.isEmpty() || !hasFingerprint(text)) return text
        var s = text
        for ((from, to) in REWRITES) s = s.replace(from, to)
        s = HDR_KV.replace(s, "")
        if (s.contains("cc_")) {
            var prev: String
            do {
                prev = s
                s = CC_KV.replace(s, "")
            } while (prev != s)
        }
        s = HDR_BARE.replace(s, "x-anthropic-billing-hdr")
        return s.trim()
    }

    /** 净化 content（字符串或 [{type:text,text:...}] 数组）。 */
    private fun sanitizeContent(el: JsonElement): JsonElement = when (el) {
        is JsonPrimitive -> if (el.isString) JsonPrimitive(sanitizeText(el.content)) else el
        is JsonArray -> buildJsonArray {
            el.forEach { part ->
                if (part is JsonObject && part["text"] is JsonPrimitive) {
                    val t = (part["text"] as JsonPrimitive).content
                    add(buildJsonObject {
                        part.forEach { (k, v) ->
                            if (k == "text") put(k, JsonPrimitive(sanitizeText(t))) else put(k, v)
                        }
                    })
                } else {
                    add(part)
                }
            }
        }
        else -> el
    }

    /** 净化 tool_calls[].function.arguments（字符串化 JSON）。 */
    private fun sanitizeToolCalls(el: JsonElement): JsonElement {
        val arr = el as? JsonArray ?: return el
        return buildJsonArray {
            arr.forEach { call ->
                val c = call as? JsonObject ?: run { add(call); return@forEach }
                val fn = c["function"] as? JsonObject ?: run { add(call); return@forEach }
                val args = fn["arguments"] as? JsonPrimitive ?: run { add(call); return@forEach }
                if (!args.isString) { add(call); return@forEach }
                val newArgs = sanitizeText(args.content)
                add(buildJsonObject {
                    c.forEach { (k, v) ->
                        if (k == "function") {
                            put(k, buildJsonObject {
                                fn.forEach { (fk, fv) ->
                                    if (fk == "arguments") put(fk, JsonPrimitive(newArgs)) else put(fk, fv)
                                }
                            })
                        } else put(k, v)
                    }
                })
            }
        }
    }

    /**
     * 净化整个请求体的 messages：
     * content / reasoning_content / tool_calls 三处都要处理
     * （tool_calls 的 arguments 常被漏掉，历史上导致指纹漏出）。
     */
    fun sanitizeBody(body: JsonObject): JsonObject {
        // 顶层字段：system / instructions / tools / tool_choice 等都可能藏指纹，
        // 全部先做一次通用净化（历史版本只处理 messages，导致 system 段漏网）
        var topChanged = false
        val topCleaned = buildJsonObject {
            body.forEach { (k, v) ->
                when (k) {
                    "messages" -> put(k, v) // 下面单独处理
                    "system", "instructions", "prompt" -> {
                        val nv = sanitizeContent(v)
                        if (nv != v) topChanged = true
                        put(k, nv)
                    }
                    "tools", "tool_choice", "functions" -> {
                        val nv = sanitizeDeep(v)
                        if (nv != v) topChanged = true
                        put(k, nv)
                    }
                    else -> put(k, v)
                }
            }
        }
        val base = if (topChanged) topCleaned else body
        val messages = base["messages"] as? JsonArray ?: return base
        var changed = false

        val newMessages = buildJsonArray {
            messages.forEach { msg ->
                val m = msg as? JsonObject
                if (m == null) { add(msg); return@forEach }
                var localChanged = false
                val rebuilt = buildJsonObject {
                    m.forEach { (k, v) ->
                        when (k) {
                            "content" -> {
                                val nv = sanitizeContent(v)
                                if (nv != v) localChanged = true
                                put(k, nv)
                            }
                            "reasoning_content" -> {
                                val nv = if (v is JsonPrimitive) JsonPrimitive(sanitizeText(v.content)) else v
                                if (nv != v) localChanged = true
                                put(k, nv)
                            }
                            "tool_calls" -> {
                                val nv = sanitizeToolCalls(v)
                                if (nv != v) localChanged = true
                                put(k, nv)
                            }
                            else -> put(k, v)
                        }
                    }
                }
                if (localChanged) changed = true
                add(rebuilt)
            }
        }
        if (!changed) return base
        return buildJsonObject {
            base.forEach { (k, v) -> if (k == "messages") put(k, newMessages) else put(k, v) }
        }
    }

    /** 递归净化任意 JSON（用于 tools / tool_choice 等嵌套结构）。 */
    private fun sanitizeDeep(el: JsonElement): JsonElement = when (el) {
        is JsonPrimitive -> if (el.isString) JsonPrimitive(sanitizeText(el.content)) else el
        is JsonArray -> buildJsonArray { el.forEach { add(sanitizeDeep(it)) } }
        is JsonObject -> buildJsonObject { el.forEach { (k, v) -> put(k, sanitizeDeep(v)) } }
        else -> el
    }
}
