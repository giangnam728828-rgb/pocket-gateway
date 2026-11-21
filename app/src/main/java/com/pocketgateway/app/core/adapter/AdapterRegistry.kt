package com.pocketgateway.app.core.adapter

import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType

/** 根据渠道类型返回对应适配器。 */
object AdapterRegistry {
    private val openai = OpenAiCompatAdapter()
    private val claude = ClaudeAdapter()
    private val gemini = GeminiAdapter()
    private val custom = CustomPassthroughAdapter()
    private val workbuddy = WorkBuddyAdapter()
    private val trae = TraeAdapter()

    fun get(type: ProviderType): ProviderAdapter = when (type) {
        ProviderType.OPENAI_COMPAT -> openai
        ProviderType.CLAUDE -> claude
        ProviderType.GEMINI -> gemini
        ProviderType.CUSTOM -> custom
        ProviderType.APP_NATIVE -> workbuddy
        ProviderType.TRAE -> trae
    }

    fun get(channel: Channel): ProviderAdapter = get(channel.type)
}
