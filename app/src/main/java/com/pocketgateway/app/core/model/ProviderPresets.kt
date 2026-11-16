package com.pocketgateway.app.core.model

/**
 * 官方 API 渠道预设。
 *
 * 只收录「提供公开、文档化 API」的平台，选中后一键填入 baseUrl / 鉴权头 / 模型名。
 * 不包含、也不支持任何逆向私有接口、会话复用或绕过付费的配置。
 * 模型名仅为常见示例，实际以各平台控制台为准，可在编辑框中修改。
 */
data class ChannelPreset(
    val key: String,
    val label: String,
    val type: ProviderType,
    val baseUrl: String,
    val authHeader: String,
    val models: List<String>,
    val extraHeaders: String = "",
    val note: String = "",
) {
    companion object {
        const val BEARER = "Authorization: Bearer {key}"
    }
}

/** 标准客户端请求头模板。仅含通用客户端标识头，用于让自建服务正确识别客户端类型。 */
data class HeaderTemplate(
    val key: String,
    val label: String,
    val headers: String,
    val note: String = "",
)

object ProviderPresets {

    /** 提供官方公开 API 的平台，一键配置。 */
    val OFFICIAL: List<ChannelPreset> = listOf(
        ChannelPreset(
            key = "openai", label = "OpenAI", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://api.openai.com/v1", authHeader = ChannelPreset.BEARER,
            models = listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1"),
        ),
        ChannelPreset(
            key = "zhipu", label = "智谱 BigModel", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://open.bigmodel.cn/api/paas/v4", authHeader = ChannelPreset.BEARER,
            models = listOf("glm-4.6", "glm-4.5", "glm-4-flash"),
            note = "官方 OpenAI 兼容端点；在 bigmodel.cn 创建 API Key。",
        ),
        ChannelPreset(
            key = "deepseek", label = "DeepSeek", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://api.deepseek.com/v1", authHeader = ChannelPreset.BEARER,
            models = listOf("deepseek-chat", "deepseek-reasoner"),
        ),
        ChannelPreset(
            key = "kimi", label = "月之暗面 Kimi", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://api.moonshot.cn/v1", authHeader = ChannelPreset.BEARER,
            models = listOf("moonshot-v1-8k", "moonshot-v1-32k"),
        ),
        ChannelPreset(
            key = "qwen", label = "阿里通义千问", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            authHeader = ChannelPreset.BEARER,
            models = listOf("qwen-plus", "qwen-turbo", "qwen-max"),
            note = "使用 DashScope 的 OpenAI 兼容模式。",
        ),
        ChannelPreset(
            key = "doubao", label = "火山引擎豆包", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3", authHeader = ChannelPreset.BEARER,
            models = listOf("doubao-seed-1-6"),
            note = "模型名填火山控制台里的接入点 ID（ep-xxx）。",
        ),
        ChannelPreset(
            key = "siliconflow", label = "硅基流动 SiliconFlow", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://api.siliconflow.cn/v1", authHeader = ChannelPreset.BEARER,
            models = listOf("Qwen/Qwen2.5-7B-Instruct", "deepseek-ai/DeepSeek-V3"),
        ),
        ChannelPreset(
            key = "minimax", label = "MiniMax", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://api.minimax.chat/v1", authHeader = ChannelPreset.BEARER,
            models = listOf("MiniMax-Text-01"),
        ),
        ChannelPreset(
            key = "groq", label = "Groq", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://api.groq.com/openai/v1", authHeader = ChannelPreset.BEARER,
            models = listOf("llama-3.3-70b-versatile"),
        ),
        ChannelPreset(
            key = "openrouter", label = "OpenRouter", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://openrouter.ai/api/v1", authHeader = ChannelPreset.BEARER,
            models = listOf("openai/gpt-4o", "deepseek/deepseek-chat"),
            extraHeaders = "HTTP-Referer: https://github.com/\nX-Title: PocketGateway",
            note = "部分模型按 HTTP-Referer 统计来源，可自行修改。",
        ),
        ChannelPreset(
            key = "ollama", label = "本地 Ollama", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "http://127.0.0.1:11434/v1", authHeader = "",
            models = listOf("qwen2.5:7b", "llama3.1"),
            note = "无需 API Key；同一设备或局域网内地址需改成实际 IP。",
        ),
        ChannelPreset(
            key = "claude", label = "Anthropic Claude", type = ProviderType.CLAUDE,
            baseUrl = "https://api.anthropic.com", authHeader = "x-api-key: {key}",
            models = listOf("claude-sonnet-4-5", "claude-3-5-haiku-latest"),
            note = "使用 Messages API；适配器会自动做格式转换。",
        ),
        ChannelPreset(
            key = "gemini", label = "Google Gemini", type = ProviderType.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com", authHeader = "",
            models = listOf("gemini-2.0-flash", "gemini-1.5-pro"),
            note = "Key 由适配器放入 URL 查询参数；填在 API Key 一栏。",
        ),
        ChannelPreset(
            key = "baidu", label = "百度文心千帆", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://qianfan.baidubce.com/v2", authHeader = ChannelPreset.BEARER,
            models = listOf("ernie-4.0-8k", "ernie-3.5-8k"),
            note = "千帆 V2 OpenAI 兼容模式；在千帆控制台创建应用并获取 API Key。",
        ),
        ChannelPreset(
            key = "xfyun", label = "讯飞星火", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://spark-api-open.xf-yun.com/v1", authHeader = ChannelPreset.BEARER,
            models = listOf("4.0Ultra", "generalv3.5"),
            note = "星火 OpenAI 兼容端点；在讯飞开放平台控制台获取 API Key。",
        ),
        ChannelPreset(
            key = "hunyuan", label = "腾讯混元", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://api.hunyuan.cloud.tencent.com/v1", authHeader = ChannelPreset.BEARER,
            models = listOf("hunyuan-turbo", "hunyuan-lite"),
            note = "腾讯云混元 OpenAI 兼容模式；在腾讯云控制台获取 SecretId/SecretKey 生成 Key。",
        ),
        ChannelPreset(
            key = "stepfun", label = "阶跃星辰 StepFun", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://api.stepfun.com/v1", authHeader = ChannelPreset.BEARER,
            models = listOf("step-2-16k", "step-1v-8k"),
            note = "阶跃 OpenAI 兼容端点；在 stepfun 开放平台获取 API Key。",
        ),
        ChannelPreset(
            key = "skywork", label = "天工 Skywork", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://api.tiangong.cn/v1", authHeader = ChannelPreset.BEARER,
            models = listOf("Skywork"),
            note = "天工 OpenAI 兼容端点；在天工开放平台获取 API Key。",
        ),
    )

    /**
     * 标准客户端请求头模板。
     * 只含通用客户端标识头（UA / Accept / Language），用于让自建或自有服务正确识别客户端类型，
     * 不含任何用于规避风控的字段（如 Sec-Fetch、伪造指纹、Cookie 自动采集等）。
     */
    val HEADER_TEMPLATES: List<HeaderTemplate> = listOf(
        HeaderTemplate("none", "不添加", "", "使用默认请求头。"),
        HeaderTemplate(
            key = "standard", label = "标准客户端 (通用 UA)",
            headers = "User-Agent: PocketGateway/1.0 (+Android)\nAccept: application/json, text/plain, */*\nAccept-Language: zh-CN,zh;q=0.9",
            note = "适合自建服务或自有 API，标明客户端身份。",
        ),
        HeaderTemplate(
            key = "web", label = "Web 客户端 UA",
            headers = "User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36\nAccept: application/json, text/plain, */*\nAccept-Language: zh-CN,zh;q=0.9",
            note = "仅设置通用 UA / Accept 头，用于兼容要求浏览器 UA 的自有服务。",
        ),
    )
}
