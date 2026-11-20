package com.pocketgateway.app.core.access

/**
 * 客户端接入预设。
 *
 * @param name 客户端名称
 * @param category 分类（桌面 / 移动 / 浏览器 / 插件）
 * @param step 接入步骤（展示给用户）
 * @param jsonTemplate 生成可复制的 JSON 配置
 */
data class AccessPreset(
    val name: String,
    val category: String,
    val step: String,
    val jsonTemplate: (baseUrl: String, apiKey: String, model: String) -> String,
)

/** 内置的 13+ 客户端接入预设（参考主流 AI 客户端/网关的通用 OpenAI 兼容配置方式）。 */
object AccessPresets {

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    val all: List<AccessPreset> = listOf(
        AccessPreset(
            "Chatbox", "桌面 / 移动",
            "设置 → 模型提供方 → 选择 OpenAI API → 填入 API 域名与 API Key → 模型填下方名称。",
        ) { b, k, m ->
            """{"provider":"openai","apiHost":"${esc(b)}","apiKey":"${esc(k)}","model":"${esc(m)}"}"""
        },
        AccessPreset(
            "Cherry Studio", "桌面 / 移动",
            "设置 → 模型服务 → OpenAI → API 地址填 Base URL，密钥填 API Key，添加模型后选中。",
        ) { b, k, m ->
            """{"type":"openai","baseUrl":"${esc(b)}","apiKey":"${esc(k)}","models":["${esc(m)}"]}"""
        },
        AccessPreset(
            "LobeChat", "浏览器 / 桌面",
            "设置 → 语言模型 → OpenAI → 开启自定义接口代理，填 Base URL、API Key，模型填下方名称。",
        ) { b, k, m ->
            """{"openai":{"endpoint":"${esc(b)}","apiKey":"${esc(k)}","model":"${esc(m)}"}}"""
        },
        AccessPreset(
            "NextChat", "浏览器 / 桌面",
            "设置 → 自定义接口 → 接口地址填 Base URL，API Key 填密钥，模型填下方名称。",
        ) { b, k, m ->
            """{"custom":{"baseUrl":"${esc(b)}","apiKey":"${esc(k)}","model":"${esc(m)}"}}"""
        },
        AccessPreset(
            "Open WebUI", "浏览器",
            "设置 → 连接 → OpenAI API → API Base URL 填 Base URL，API Key 填密钥。",
        ) { b, k, m ->
            """{"openai":{"base_url":"${esc(b)}","api_key":"${esc(k)}","model":"${esc(m)}"}}"""
        ),
        AccessPreset(
            "Dify", "浏览器",
            "模型供应商 → OpenAI-API-compatible → Base URL 填 Base URL，API Key 填密钥，模型名填下方名称。",
        ) { b, k, m ->
            """{"provider":"openai_api_compatible","base_url":"${esc(b)}","api_key":"${esc(k)}","model":"${esc(m)}"}"""
        },
        AccessPreset(
            "LibreChat", "浏览器 / 自建",
            "librechat.yaml 的 endpoints.custom 中填写 baseURL、apiKey 与 models.default。",
        ) { b, k, m ->
            """{"endpoints":{"custom":[{"name":"PocketGateway","baseURL":"${esc(b)}","apiKey":"${esc(k)}","models":{"default":["${esc(m)}"]}}]}}"""
        },
        AccessPreset(
            "Jan", "桌面",
            "设置 → 模型提供商 → OpenAI → 添加自定义提供商，填 Base URL 与 API Key。",
        ) { b, k, m ->
            """{"provider":"openai","base_url":"${esc(b)}","api_key":"${esc(k)}","model":"${esc(m)}"}"""
        },
        AccessPreset(
            "AnythingLLM", "桌面",
            "设置 → LLM 首选项 → Generic OpenAI → Base URL、API Key、Chat Model 填下方名称。",
        ) { b, k, m ->
            """{"llm":"generic-openai","base_url":"${esc(b)}","api_key":"${esc(k)}","model":"${esc(m)}"}"""
        },
        AccessPreset(
            "Cline (VS Code)", "编辑器插件",
            "Cline 设置 → API Provider 选 OpenAI Compatible → Base URL、API Key、Model ID。",
        ) { b, k, m ->
            """{"apiProvider":"openai","openAiBaseUrl":"${esc(b)}","openAiApiKey":"${esc(k)}","openAiModelId":"${esc(m)}"}"""
        },
        AccessPreset(
            "Roo Code", "编辑器插件",
            "Roo Code 设置 → API Provider 选 OpenAI Compatible → Base URL、API Key、Model。",
        ) { b, k, m ->
            """{"apiProvider":"openai","openAiBaseUrl":"${esc(b)}","openAiApiKey":"${esc(k)}","openAiModelId":"${esc(m)}"}"""
        },
        AccessPreset(
            "Cursor", "编辑器",
            "Settings → Models → OpenAI API Key 填密钥，Override OpenAI Base URL 填 Base URL。",
        ) { b, k, m ->
            """{"openai_api_key":"${esc(k)}","openai_base_url":"${esc(b)}","model":"${esc(m)}"}"""
        },
        AccessPreset(
            "ChatWise", "桌面",
            "设置 → 模型 → OpenAI → API Host 填 Base URL，API Key 填密钥。",
        ) { b, k, m ->
            """{"provider":"openai","apiHost":"${esc(b)}","apiKey":"${esc(k)}","model":"${esc(m)}"}"""
        },
    )
}
