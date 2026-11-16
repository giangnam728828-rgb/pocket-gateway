package com.pocketgateway.app.util

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 签名计算工具。
 *
 * 支持常见签名算法，用于对接带签名校验的 APP / 网页私有接口。
 * 签名内容由渠道配置的 [com.pocketgateway.app.core.model.Channel.signTemplate] 插值得到，
 * 签名结果作为请求头注入。签名配置持久保存在渠道里，不依赖临时生成。
 */
object SignUtil {

    /** 可用算法列表（供界面下拉使用）。 */
    val ALGORITHMS = listOf("none", "md5", "sha1", "sha256", "hmac-md5", "hmac-sha1", "hmac-sha256")

    /** 按算法对内容计算签名。algorithm 为空或 none 时原样返回内容。 */
    fun compute(algorithm: String, content: String, key: String): String {
        val algo = algorithm.trim().lowercase()
        return when (algo) {
            "md5" -> hash("MD5", content)
            "sha1" -> hash("SHA-1", content)
            "sha256" -> hash("SHA-256", content)
            "hmac-md5" -> hmac("HmacMD5", content, key)
            "hmac-sha1" -> hmac("HmacSHA1", content, key)
            "hmac-sha256" -> hmac("HmacSHA256", content, key)
            else -> content
        }
    }

    private fun hash(name: String, content: String): String {
        val md = MessageDigest.getInstance(name)
        return md.digest(content.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun hmac(name: String, content: String, key: String): String {
        val mac = Mac.getInstance(name)
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), name))
        return mac.doFinal(content.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
