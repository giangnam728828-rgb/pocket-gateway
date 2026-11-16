package com.pocketgateway.app.core.appaccount

import android.webkit.CookieManager
import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType

/**
 * APP 账号直连的登录结果与接入信息生成。
 *
 * 用户在内置 WebView 里登录该服务（如 WorkBuddy 网页版），登录成功后从 CookieManager
 * 提取会话 Cookie，自动生成一个「APP 账号直连」渠道，并对外暴露：
 *  - Base URL：本机网关地址（http://127.0.0.1:端口/v1）
 *  - API Key：网关的访问令牌（未设置则为空）
 *  - 模型：该 APP 提供的模型名
 *
 * 这样用户无需手动配置 URL / API Key / 请求格式。
 */
data class AppAccount(
    /** 应用标识，如 workbuddy。 */
    val kind: String,
    /** 展示名称。 */
    val displayName: String,
    /** 登录页地址。 */
    val loginUrl: String,
    /** 提取 Cookie 用的域名（用于 CookieManager 查询）。 */
    val cookieDomain: String,
    /** 默认模型列表。 */
    val models: List<String>,
    /** 说明。 */
    val note: String = "",
)

/** 内置支持的 APP 列表。 */
object AppAccounts {

    val ALL: List<AppAccount> = listOf(
        AppAccount(
            kind = "workbuddy",
            displayName = "WorkBuddy",
            loginUrl = "https://workbuddy.tencent.com/",
            cookieDomain = "workbuddy.tencent.com",
            models = listOf("workbuddy", "workbuddy-pro"),
            note = "在内置页面登录 WorkBuddy 账号，登录后自动生成接入信息。",
        ),
    )

    fun find(kind: String): AppAccount? = ALL.firstOrNull { it.kind == kind }
}

/**
 * 从 CookieManager 提取指定域名的 Cookie 字符串。
 * 返回形如 "k1=v1; k2=v2" 的字符串；无 Cookie 时返回 null。
 */
fun extractCookies(domain: String): String? {
    val cm = CookieManager.getInstance()
    val cookie = cm.getCookie("https://$domain") ?: return null
    return cookie.takeIf { it.isNotBlank() }
}

/**
 * 由登录结果生成「APP 账号直连」渠道。
 */
fun buildAppChannel(account: AppAccount, cookie: String): Channel = Channel(
    id = Channel.newId(),
    name = "${account.displayName}（账号直连）",
    type = ProviderType.APP_NATIVE,
    baseUrl = "",
    apiKey = cookie,
    appKind = account.kind,
    models = account.models,
)
