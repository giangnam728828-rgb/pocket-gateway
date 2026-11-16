package com.pocketgateway.app.core.appaccount

import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * APP 账号直连：设备授权（OAuth Device Flow）登录。
 *
 * 流程依据开源实现 workbuddy2api 的 cmd/login：
 *  1. POST {base}/v2/plugin/auth/state?platform=CLI  → 拿 state + authUrl
 *  2. 用户在浏览器打开 authUrl 完成授权
 *  3. GET  {base}/v2/plugin/auth/token?state=       → 轮询拿 accessToken
 *  4. GET  {base}/v2/plugin/login/account?state=    → 拿 uid / nickname
 *
 * 与网页 Cookie 抓取不同，设备授权是官方支持的 CLI/插件登录路径，
 * 拿到的 accessToken 可直接用于 API 调用，不需要伪装网页会话。
 */
data class AppAccount(
    val kind: String,
    val displayName: String,
    /** 国内版上游 */
    val baseUrlCn: String = "https://copilot.tencent.com",
    /** 国际版上游 */
    val baseUrlGlobal: String = "https://www.workbuddy.ai",
    val models: List<String>,
    val note: String = "",
)

/** 登录会话：state + 用户需要打开的授权链接。 */
data class LoginSession(
    val state: String,
    val authUrl: String,
    val realm: String,
)

/** 登录成功后的凭证。 */
data class WorkBuddyCredential(
    val accessToken: String,
    val refreshToken: String = "",
    val uid: String = "",
    val nickname: String = "",
    val domain: String = "",
    val realm: String = "cn",
)

object AppAccounts {

    val ALL: List<AppAccount> = listOf(
        AppAccount(
            kind = "workbuddy",
            displayName = "WorkBuddy（国内版）",
            baseUrlCn = "https://copilot.tencent.com",
            models = listOf("glm-5.2", "glm-5v-turbo", "step-5-preview"),
            note = "设备授权登录：点「开始登录」→ 浏览器授权 → 自动完成。",
        ),
        AppAccount(
            kind = "workbuddy_global",
            displayName = "WorkBuddy（国际版）",
            baseUrlGlobal = "https://www.workbuddy.ai",
            models = listOf("glm-5.2", "glm-5v-turbo"),
            note = "国际版账号走 www.workbuddy.ai。",
        ),
    )

    fun find(kind: String): AppAccount? = ALL.firstOrNull { it.kind == kind }
}

/**
 * 设备授权登录客户端。
 */
class DeviceAuthClient {

    private fun baseFor(realm: String): String =
        if (realm == "global") "https://www.workbuddy.ai" else "https://copilot.tencent.com"

    private fun originFor(realm: String): String =
        if (realm == "global") "https://www.workbuddy.ai" else "https://www.codebuddy.cn"

    private fun httpJson(urlStr: String, realm: String, method: String = "GET"): JsonObject? {
        val origin = originFor(realm)
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("Origin", origin)
            setRequestProperty("Referer", "$origin/")
            setRequestProperty("User-Agent", "CLI/2.63.2 CodeBuddy/2.63.2")
            if (method == "POST") doOutput = true
        }
        return try {
            if (method == "POST") {
                OutputStreamWriter(conn.outputStream).use { it.write("{}") }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (code !in 200..299) return null
            val root = AppJson.parseToJsonElement(text).jsonObject
            // 业务信封 {code,msg,data}；code != 0 视为失败
            val bizCode = (root["code"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (bizCode != null && bizCode != 0) return null
            (root["data"] as? JsonObject) ?: root
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 第一步：申请 state 与授权链接。 */
    suspend fun startLogin(realm: String = "cn"): LoginSession? = withContext(Dispatchers.IO) {
        val base = baseFor(realm)
        val data = httpJson("$base/v2/plugin/auth/state?platform=CLI", realm, "POST") ?: return@withContext null
        val state = (data["state"] as? JsonPrimitive)?.content ?: return@withContext null
        val authUrl = (data["authUrl"] as? JsonPrimitive)?.content
            ?: (data["auth_url"] as? JsonPrimitive)?.content
            ?: (data["url"] as? JsonPrimitive)?.content
            ?: return@withContext null
        LoginSession(state, authUrl, realm)
    }

    /** 第三步：轮询换取 token（用户完成授权后调用）。 */
    suspend fun pollToken(session: LoginSession): WorkBuddyCredential? = withContext(Dispatchers.IO) {
        val base = baseFor(session.realm)
        val tok = httpJson("$base/v2/plugin/auth/token?state=${session.state}", session.realm) ?: return@withContext null
        val access = (tok["accessToken"] as? JsonPrimitive)?.content
            ?: (tok["access_token"] as? JsonPrimitive)?.content
            ?: return@withContext null
        val refresh = (tok["refreshToken"] as? JsonPrimitive)?.content
            ?: (tok["refresh_token"] as? JsonPrimitive)?.content ?: ""
        val domain = (tok["domain"] as? JsonPrimitive)?.content ?: ""

        // 第四步：拿账号信息
        val acc = httpJson("$base/v2/plugin/login/account?state=${session.state}", session.realm)
        val uid = (acc?.get("uid") as? JsonPrimitive)?.content
            ?: (acc?.get("userId") as? JsonPrimitive)?.content ?: ""
        val nick = (acc?.get("nickname") as? JsonPrimitive)?.content
            ?: (acc?.get("name") as? JsonPrimitive)?.content ?: ""

        WorkBuddyCredential(access, refresh, uid, nick, domain, session.realm)
    }
}

/** 由登录凭证生成「APP 账号直连」渠道。 */
fun buildAppChannel(account: AppAccount, cred: WorkBuddyCredential): Channel = Channel(
    id = Channel.newId(),
    name = if (cred.nickname.isNotBlank()) "${account.displayName} · ${cred.nickname}"
           else "${account.displayName}（账号直连）",
    type = ProviderType.APP_NATIVE,
    baseUrl = if (cred.realm == "global") account.baseUrlGlobal else account.baseUrlCn,
    apiKey = cred.accessToken,
    appKind = cred.uid,
    models = account.models,
)
