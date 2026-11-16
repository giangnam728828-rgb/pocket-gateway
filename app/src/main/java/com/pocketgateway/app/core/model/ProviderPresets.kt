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

/**
 * 私有接口渠道模板（APP / 网站内部接口）。
 *
 * 用于**你自己有权访问**、但只在其 APP 或网页内部调用的服务。
 * 选中后一键填入自定义透传的配置骨架（请求方法、模板、签名、响应映射等），
 * 你只需补上自己抓到的地址、请求头和凭证。
 *
 * 本模板库不包含任何第三方平台的逆向逻辑、签名破解或凭证。
 */
data class PrivatePreset(
    val key: String,
    val label: String,
    val kind: String,
    val baseUrl: String = "",
    val requestMethod: String = "POST",
    val authHeader: String = "",
    val extraHeaders: String = "",
    val requestTemplate: String = "",
    val responseContentPath: String = "",
    val signHeader: String = "",
    val signAlgorithm: String = "",
    val signTemplate: String = "",
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

    /**
     * 私有接口渠道模板（APP / 网站内部接口）。
     *
     * 这些模板只提供「配置骨架」——把常见私有接口的调用方式抽象成可填字段，
     * 你用自己的账号抓包后填入地址/请求头/凭证即可接入**你自己有权访问**的服务。
     * 不含任何第三方平台的具体接口地址、密钥或逆向逻辑。
     */
    val PRIVATE: List<PrivatePreset> = listOf(
        PrivatePreset(
            key = "app_bearer", label = "APP 接口 · Bearer Token", kind = "APP",
            requestMethod = "POST",
            authHeader = "Authorization: Bearer {key}",
            extraHeaders = "Content-Type: application/json",
            requestTemplate = "{\"model\":\"{{model}}\",\"messages\":{{messages}},\"stream\":{{stream}}}",
            responseContentPath = "choices[0].message.content",
            note = "APP 用 Bearer Token 鉴权、请求体接近 OpenAI 格式时的骨架。",
        ),
        PrivatePreset(
            key = "app_cookie", label = "APP/网页 · Cookie 会话", kind = "APP/网页",
            requestMethod = "POST",
            extraHeaders = "Cookie: {key}\nContent-Type: application/json",
            requestTemplate = "{\"model\":\"{{model}}\",\"messages\":{{messages}}}",
            responseContentPath = "data.content",
            note = "用登录 Cookie 维持会话。API Key 一栏填完整 Cookie 字符串。",
        ),
        PrivatePreset(
            key = "app_query_sign", label = "APP 接口 · 时间戳+签名", kind = "APP",
            requestMethod = "POST",
            extraHeaders = "Content-Type: application/json",
            requestTemplate = "{\"model\":\"{{model}}\",\"messages\":{{messages}},\"timestamp\":\"{{timestamp}}\",\"nonce\":\"{{nonce}}\"}",
            responseContentPath = "data.text",
            signHeader = "X-Sign",
            signAlgorithm = "hmac-sha256",
            signTemplate = "app_key={{key}}&timestamp={{timestamp}}&nonce={{nonce}}&data={{body}}",
            note = "常见 APP 签名方式：时间戳+随机数参与签名，签名头随请求注入。",
        ),
        PrivatePreset(
            key = "web_md5_sign", label = "网页接口 · MD5 签名", kind = "网页",
            requestMethod = "POST",
            extraHeaders = "Content-Type: application/x-www-form-urlencoded",
            requestTemplate = "model={{model}}&data={{messages}}&t={{timestamp}}",
            responseContentPath = "result.content",
            signHeader = "sign",
            signAlgorithm = "md5",
            signTemplate = "model={{model}}&data={{messages}}&t={{timestamp}}&key={{key}}",
            note = "表单提交 + MD5 签名。适合网页端 ajax 接口。",
        ),
        PrivatePreset(
            key = "app_get_stream", label = "APP 接口 · GET + 查询参数", kind = "APP",
            requestMethod = "GET",
            authHeader = "Authorization: Bearer {key}",
            note = "GET 方式调用，参数放查询参数区（每行 key=value）。",
        ),
        PrivatePreset(
            key = "sse_text", label = "私有接口 · SSE 文本流", kind = "APP/网页",
            requestMethod = "POST",
            extraHeaders = "Accept: text/event-stream\nContent-Type: application/json",
            requestTemplate = "{\"prompt\":{{messages}},\"stream\":true}",
            responseContentPath = "delta",
            note = "上游返回 SSE 流式文本时的骨架，响应路径填每行的文本字段。",
        ),
        PrivatePreset(
            key = "blank", label = "空白自定义渠道", kind = "自定义",
            note = "全部留空，按需自行填写。",
        ),
    )
}
