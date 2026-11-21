package com.pocketgateway.app.core.access

/**
 * 客户端接入预设。
 *
 * 说明：这里只列**支持“自定义 OpenAI 兼容地址 + API Key”**的客户端。
 * 只能通过账号登录、不能填写自定义 API 地址的客户端（如 TRAE）无法接入本网关。
 */
data class AccessPreset(
    val name: String,
    val category: String,
    val step: String,
    val jsonTemplate: (baseUrl: String, apiKey: String) -> String,
    /** true = 该客户端需要带 /v1 的地址；false = 只填主机地址（客户端会自行追加 /v1）。 */
    val usesV1: Boolean = true,
)

/** 内置的客户端接入预设（均要求客户端支持自定义 OpenAI 兼容接口）。 */
object AccessPresets {

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    val all: List<AccessPreset> = listOf(
        AccessPreset(
            name = "Chatbox",
            category = "桌面 / 移动",
            step = "设置 → 模型提供方 → 选择 OpenAI API → 填入 API Host 与 API Key，模型点「获取」从网关读取。",
            jsonTemplate = { b, k ->
                """{"provider":"openai","apiHost":"${esc(b)}","apiKey":"${esc(k)}"}"""
            },
            usesV1 = false,
        ),
        AccessPreset(
            name = "Cherry Studio",
            category = "桌面 / 移动",
            step = "设置 → 模型服务 → OpenAI → API 地址填主机地址，密钥填 API Key，点「获取模型」。",
            jsonTemplate = { b, k ->
                """{"type":"openai","baseUrl":"${esc(b)}","apiKey":"${esc(k)}"}"""
            },
            usesV1 = false,
        ),
        AccessPreset(
            name = "LobeChat",
            category = "浏览器 / 桌面",
            step = "设置 → 语言模型 → OpenAI → 开启自定义接口代理，填 BASE URL 与 API Key，模型从列表获取。",
            jsonTemplate = { b, k ->
                """{"openai":{"endpoint":"${esc(b)}","apiKey":"${esc(k)}"}}"""
            },
        ),
        AccessPreset(
            name = "NextChat",
            category = "浏览器 / 桌面",
            step = "设置 → 自定义接口 → 接口地址填主机地址，API Key 填密钥，模型可点获取。",
            jsonTemplate = { b, k ->
                """{"custom":{"baseUrl":"${esc(b)}","apiKey":"${esc(k)}"}}"""
            },
            usesV1 = false,
        ),
        AccessPreset(
            name = "Open WebUI",
            category = "浏览器",
            step = "设置 → 连接 → OpenAI API → API Base URL 填 BASE URL，API Key 填密钥。",
            jsonTemplate = { b, k ->
                """{"openai":{"base_url":"${esc(b)}","api_key":"${esc(k)}"}}"""
            },
        ),
        AccessPreset(
            name = "Dify",
            category = "浏览器",
            step = "模型供应商 → OpenAI-API-compatible → Base URL 填 BASE URL，API Key 填密钥。",
            jsonTemplate = { b, k ->
                """{"provider":"openai_api_compatible","base_url":"${esc(b)}","api_key":"${esc(k)}"}"""
            },
        ),
        AccessPreset(
            name = "LibreChat",
            category = "浏览器 / 自建",
            step = "librechat.yaml 的 endpoints.custom 中填写 baseURL 与 apiKey。",
            jsonTemplate = { b, k ->
                """{"endpoints":{"custom":[{"name":"PocketGateway","baseURL":"${esc(b)}","apiKey":"${esc(k)}"}]}}"""
            },
        ),
        AccessPreset(
            name = "Jan",
            category = "桌面",
            step = "设置 → 模型提供商 → OpenAI → 添加自定义提供商，填 Base URL 与 API Key。",
            jsonTemplate = { b, k ->
                """{"provider":"openai","base_url":"${esc(b)}","api_key":"${esc(k)}"}"""
            },
        ),
        AccessPreset(
            name = "AnythingLLM",
            category = "桌面",
            step = "设置 → LLM 首选项 → Generic OpenAI → 填 Base URL 与 API Key。",
            jsonTemplate = { b, k ->
                """{"llm":"generic-openai","base_url":"${esc(b)}","api_key":"${esc(k)}"}"""
            },
        ),
        AccessPreset(
            name = "Cline (VS Code)",
            category = "编辑器插件",
            step = "Cline 设置 → API Provider 选 OpenAI Compatible → 填 Base URL 与 API Key。",
            jsonTemplate = { b, k ->
                """{"apiProvider":"openai","openAiBaseUrl":"${esc(b)}","openAiApiKey":"${esc(k)}"}"""
            },
        ),
        AccessPreset(
            name = "Roo Code",
            category = "编辑器插件",
            step = "Roo Code 设置 → API Provider 选 OpenAI Compatible → 填 Base URL 与 API Key。",
            jsonTemplate = { b, k ->
                """{"apiProvider":"openai","openAiBaseUrl":"${esc(b)}","openAiApiKey":"${esc(k)}"}"""
            },
        ),
        AccessPreset(
            name = "Cursor",
            category = "编辑器",
            step = "Settings → Models → 填入 OpenAI API Key，Override OpenAI Base URL 填 BASE URL。",
            jsonTemplate = { b, k ->
                """{"openai_api_key":"${esc(k)}","openai_base_url":"${esc(b)}"}"""
            },
        ),
        AccessPreset(
            name = "ChatWise",
            category = "桌面",
            step = "设置 → 模型 → OpenAI → API Host 填主机地址，API Key 填密钥。",
            jsonTemplate = { b, k ->
                """{"provider":"openai","apiHost":"${esc(b)}","apiKey":"${esc(k)}"}"""
            },
            usesV1 = false,
        ),
    )
}
