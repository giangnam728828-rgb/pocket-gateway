package com.pocketgateway.app.core.appaccount

import com.pocketgateway.app.core.model.Channel
import com.pocketgateway.app.core.model.ProviderType
import com.pocketgateway.app.util.AppJson
import com.pocketgateway.app.util.string
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.random.Random

/**
 * TRAE 账号 OAuth 登录。
 *
 * TRAE 不提供 API Key，只能通过网页授权拿到 Cloud-IDE-JWT：
 *  1. APP 在本机 `127.0.0.1:<随机端口>` 起一个 HTTP 回调监听；
 *  2. 打开 trae.cn 授权页，授权完成后浏览器跳回 127.0.0.1，回调参数带
 *     `userJwt`（含 Token/RefreshToken）或 `refreshToken`；
 *  3. 只有 refreshToken 时，用 ExchangeToken 换取 UserJWT；
 *  4. UserJWT 过期时用 refreshToken 自动续期（对应渠道的 refreshToken 字段）。
 *
 * 设备参数（machine_id / device_id 等）按官方 native_ide 授权页要求生成，
 * 属于登录协议的一部分，不保存、不上报给除官方域名外的任何地址。
 */
data class TraeCredential(
    val token: String,
    val refreshToken: String = "",
    val uid: String = "",
    val region: String = "CN",
    val expiresAt: String = "",
)

/** 一次登录会话：授权链接 + 本机回调监听。 */
data class TraeLoginSession(
    val authUrl: String,
    val port: Int,
    val traceId: String,
    internal val callback: CompletableDeferred<Map<String, String>>,
)

object TraeAuth {

    const val WEB_HOST = "https://www.trae.cn"
    const val API_HOST = "https://api.trae.cn"
    const val CHAT_BASE = "https://trae-api-cn.mchost.guru"
    const val CLIENT_ID = "ono9krqynydwx5"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 生成授权链接并开始监听回调。调用方拿到 [TraeLoginSession.authUrl] 后用浏览器打开，
     * 用户完成授权后调用 [finishLogin] 换取凭证。
     */
    fun startLogin(): Result<TraeLoginSession> = runCatching {
        // 优先使用官方授权助手惯用的 8765；被占用时退回随机端口。
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", 8765))
            }
        } catch (e: Exception) {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", 0))
            }
        }
        val port = server.localPort
        val traceId = java.util.UUID.randomUUID().toString()
        val callback = CompletableDeferred<Map<String, String>>()

        scope.launch {
            try {
                server.soTimeout = 300_000
                server.accept().use { sock ->
                    sock.soTimeout = 10_000
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                    val requestLine = reader.readLine().orEmpty()
                    val rawQuery = requestLine.split(" ").getOrNull(1)
                        ?.substringAfter("?", "")
                        .orEmpty()
                    val html = resultHtml()
                    val bytes = html.toByteArray(Charsets.UTF_8)
                    sock.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/html; charset=utf-8\r\n" +
                                    "Content-Length: ${bytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.UTF_8)
                        )
                        write(bytes)
                        flush()
                    }
                    callback.complete(parseQuery(rawQuery))
                }
            } catch (e: Exception) {
                callback.completeExceptionally(e)
            } finally {
                runCatching { server.close() }
            }
        }

        val machineId = randomHex(32)
        val deviceId = randomDigits(19)
        val params = linkedMapOf(
            "login_version" to "1",
            "auth_from" to "solo",
            "login_channel" to "native_ide",
            "plugin_version" to "2.3.24254",
            "auth_type" to "local",
            "client_id" to CLIENT_ID,
            "redirect" to "0",
            "login_trace_id" to traceId,
            "auth_callback_url" to "http://127.0.0.1:$port/authorize",
            "machine_id" to machineId,
            "device_id" to deviceId,
            "x_device_id" to deviceId,
            "x_machine_id" to machineId,
            "x_device_brand" to "ASUS TUF Gaming A15 FA507RM_FA507RM",
            "x_device_type" to "windows",
            "x_os_version" to "Windows 10 Pro",
            "x_env" to "",
            "x_app_version" to "3.3.65",
            "x_app_type" to "stable",
            "hide_saas_login" to "true",
        )
        val authUrl = WEB_HOST + "/authorization?" + params.entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }

        TraeLoginSession(authUrl = authUrl, port = port, traceId = traceId, callback = callback)
    }

    /** 等待浏览器回调并换取凭证（最多等 4 分钟）。 */
    suspend fun finishLogin(session: TraeLoginSession): Result<TraeCredential> =
        withContext(Dispatchers.IO) {
            runCatching {
                val params = withTimeoutOrNull(240_000) { session.callback.await() }
                    ?: throw IllegalStateException("登录超时：请重新发起 TRAE 登录")
                parseCallback(params)
            }
        }

    /** 用渠道里保存的 refreshToken 续期 UserJWT。 */
    suspend fun refresh(ch: Channel): TraeCredential? = withContext(Dispatchers.IO) {
        val rt = ch.refreshToken.trim()
        if (rt.isBlank()) return@withContext null
        exchangeToken(rt, userId = ch.appKind.trim())
    }

    /** 由登录凭证生成 TRAE 渠道。 */
    fun buildChannel(cred: TraeCredential, models: List<String> = emptyList()): Channel = Channel(
        id = Channel.newId(),
        name = if (cred.uid.isNotBlank()) "TRAE · ${cred.uid}" else "TRAE（账号登录）",
        type = ProviderType.TRAE,
        baseUrl = CHAT_BASE,
        apiKey = cred.token,
        refreshToken = cred.refreshToken,
        appKind = cred.uid,
        models = models,
    )

    /** 从 UserJWT 里解析用户 ID（手动粘贴 Token 时兜底）。 */
    fun uidFromJwt(token: String): String {
        val parts = token.trim().split('.')
        if (parts.size < 2) return ""
        return runCatching {
            val payload = parts[1]
            val json = String(
                android.util.Base64.decode(
                    payload,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
                ),
                Charsets.UTF_8,
            )
            val obj = AppJson.parseToJsonElement(json) as? JsonObject ?: return@runCatching ""
            val data = obj["data"] as? JsonObject
            listOf("id", "user_id", "userId", "sub").firstNotNullOfOrNull { k ->
                (data?.get(k) as? JsonPrimitive)?.content ?: (obj[k] as? JsonPrimitive)?.content
            }.orEmpty()
        }.getOrNull().orEmpty()
    }

    // ---------- 回调解析 ----------

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            // OAuth 回调里的 JSON/JWT 可能含字面量 '+'，不能按表单空格处理
            val raw = pair.substring(idx + 1).replace("+", "%2B")
            val value = URLDecoder.decode(raw, "UTF-8")
            key to value
        }.toMap()
    }

    private fun parseCallback(params: Map<String, String>): TraeCredential {
        val userJwt = params["userJwt"]?.let { raw ->
            runCatching { AppJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        }
        val userInfo = params["userInfo"]?.let { raw ->
            runCatching { AppJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        }

        var token = userJwt?.string("Token") ?: userJwt?.string("token") ?: ""
        var refresh = userJwt?.string("RefreshToken") ?: userJwt?.string("refreshToken")
            ?: params["refreshToken"] ?: params["data"] ?: ""
        var expiresAt = userJwt?.string("TokenExpireAt") ?: userJwt?.string("tokenExpireAt") ?: ""
        var uid = userInfo?.string("UserID") ?: userInfo?.string("userId")
            ?: userInfo?.string("userID") ?: params["userId"] ?: ""
        var region = userInfo?.string("Region") ?: userInfo?.string("region")
            ?: params["region"] ?: "CN"

        if (token.isBlank() && refresh.isNotBlank()) {
            val exchanged = exchangeToken(refresh, userId = uid)
            if (exchanged != null) {
                token = exchanged.token
                refresh = exchanged.refreshToken.ifBlank { refresh }
                expiresAt = exchanged.expiresAt
                uid = uid.ifBlank { exchanged.uid }
                region = exchanged.region.ifBlank { region }
            }
        }
        if (token.isBlank()) {
            throw IllegalStateException("授权未返回有效凭证（可能取消了授权）")
        }
        if (uid.isBlank()) uid = uidFromJwt(token)
        return TraeCredential(token, refresh, uid, region, expiresAt)
    }

    // ---------- ExchangeToken ----------

    private fun exchangeToken(
        refreshToken: String,
        clientId: String = CLIENT_ID,
        userId: String = "",
    ): TraeCredential? {
        val body = """
            {"ClientID":${JsonPrimitive(clientId)},"RefreshToken":${JsonPrimitive(refreshToken)},"ClientSecret":"-","UserID":${JsonPrimitive(userId)}}
        """.trimIndent()
        val conn = (URL("$API_HOST/cloudide/api/v3/trae/oauth/ExchangeToken").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            if (code !in 200..299) return null

            val root = AppJson.parseToJsonElement(text).jsonObject
            val data = (root["Result"] as? JsonObject) ?: (root["result"] as? JsonObject) ?: root
            val token = data.string("Token") ?: data.string("token")
                ?: data.string("AccessToken") ?: data.string("accessToken") ?: return null
            val refresh2 = data.string("RefreshToken") ?: data.string("refreshToken") ?: refreshToken
            val userInfo = (data.string("UserInfo") ?: data.string("userInfo"))?.let { raw ->
                runCatching { AppJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            }
            TraeCredential(
                token = token,
                refreshToken = refresh2,
                uid = userInfo?.string("UserID") ?: userInfo?.string("userId")
                    ?: data.string("UserID") ?: data.string("userId") ?: "",
                region = userInfo?.string("Region") ?: data.string("Region") ?: "CN",
                expiresAt = data.string("TokenExpireAt") ?: data.string("tokenExpireAt") ?: "",
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ---------- 工具 ----------

    private fun randomHex(bytes: Int): String {
        val sb = StringBuilder(bytes * 2)
        repeat(bytes * 2) { sb.append("0123456789abcdef"[Random.nextInt(16)]) }
        return sb.toString()
    }

    private fun randomDigits(count: Int): String {
        val sb = StringBuilder(count)
        repeat(count) { sb.append(Random.nextInt(10)) }
        return sb.toString()
    }

    private fun resultHtml(): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>TRAE 登录完成</title>
        <style>body{font-family:-apple-system,Roboto,sans-serif;background:#0f1115;color:#e6e6e6;
        display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
        .box{text-align:center;padding:32px}.ok{font-size:48px;color:#3ddc84}
        p{color:#9aa0a6;font-size:15px}</style></head>
        <body><div class="box"><div class="ok">✓</div>
        <h2>授权完成</h2><p>请返回 PocketGateway 点击「完成登录」。</p></div></body></html>
    """.trimIndent()
}
