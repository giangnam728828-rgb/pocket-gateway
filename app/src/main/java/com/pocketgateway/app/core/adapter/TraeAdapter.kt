package com.pocketgateway.app.core.adapter

import com.pocketgateway.app.core.appaccount.TraeApi
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.util.AppJson
import kotlinx.serialization.json.JsonObject

/**
 * TRAE 账号直连适配器（占位）。
 *
 * TRAE 的对话是「先建 chat_session，再订阅 events」的两步协议，
 * 无法用单次 HTTP 请求表达，因此实际转发由 GatewayServer 中的
 * TRAE 分支调用 [TraeApi] 完成。这里只提供鉴权头与服务路径，
 * 供模型拉取等通用入口使用。
 */
class TraeAdapter : ProviderAdapter {

    override val type: ProviderType = ProviderType.TRAE

    override fun chatPath(): String = "/api/remote/v1/chat_sessions"

    /** TRAE 的模型列表结构特殊，统一走 TraeApi.fetchModels。 */
    override fun modelsPath(): String? = null

    override fun defaultAuthHeaders(channel: Channel): List<Pair<String, String>> =
        listOf("Authorization" to "Cloud-IDE-JWT ${channel.apiKey.trim()}")

    override fun transformRequest(channel: Channel, body: JsonObject, stream: Boolean): RequestPayload =
        RequestPayload(body = AppJson.encodeToString(JsonObject.serializer(), body))
}
