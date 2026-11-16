package com.pocketgateway.app.core.adapter

import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.util.AppJson
import com.pocketgateway.app.util.modelName
import com.pocketgateway.app.util.withString
import kotlinx.serialization.json.JsonObject

/**
 * 自定义透传适配器：用于任何"请求体大致是 OpenAI 格式，只是地址/鉴权不同"的上游。
 * 与 OpenAI 兼容适配器的区别：默认不注入 Bearer 鉴权（由用户用 authHeader 完全自定义），
 * 仅做模型名映射，请求/响应全透传。适合私有网关、Cloudflare Workers 中转等场景。
 */
class CustomPassthroughAdapter : ProviderAdapter {

    override val type: ProviderType = ProviderType.CUSTOM

    override fun defaultAuthHeaders(channel: Channel): List<Pair<String, String>> = emptyList()

    override fun chatPath(): String = "/chat/completions"

    override fun modelsPath(): String? = "/models"

    override fun transformRequest(channel: Channel, body: JsonObject, stream: Boolean): RequestPayload {
        val mapped = body.modelName()?.let { model ->
            channel.modelMapping[model]?.let { target -> body.withString("model", target) }
        } ?: body
        return RequestPayload(AppJson.encodeToString(JsonObject.serializer(), mapped))
    }
}
