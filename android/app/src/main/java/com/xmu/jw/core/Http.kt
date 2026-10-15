package com.xmu.jw.core

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极简 HTTP 客户端(java.net 实现, 零第三方依赖)。
 * 手工处理重定向(教务系统 CAS 跳转必须逐步收集 cookie),
 * 显式关闭压缩(避免服务端返回 gzip 而客户端不自动解压)。
 */
object Http {

    class Resp(
        val status: Int,
        private val headerFields: Map<String, List<String>>,
        val body: ByteArray,
    ) {
        /** 取单个响应头(大小写不敏感) */
        fun header(name: String): String? = headers(name).firstOrNull()

        /** 取同名响应头全部值(Set-Cookie 会有多个) */
        fun headers(name: String): List<String> =
            headerFields.entries
                .firstOrNull { it.key != null && it.key.equals(name, ignoreCase = true) }
                ?.value
                ?: emptyList()

        val text: String get() = String(body, Charsets.UTF_8)
    }

    class HttpFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        connectTimeoutMs: Int = 15000,
        readTimeoutMs: Int = 40000,
    ): Resp {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = false
                useCaches = false
                doInput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                }
            }
            val status = conn.responseCode
            val stream: InputStream? = try {
                conn.inputStream
            } catch (_: IOException) {
                conn.errorStream
            }
            val bytes = stream?.readBytes() ?: ByteArray(0)
            val fields = conn.headerFields ?: emptyMap()
            return Resp(status, fields, bytes)
        } catch (e: IOException) {
            throw HttpFailure("网络请求失败: ${e.message ?: e.javaClass.simpleName}", e)
        } catch (e: Throwable) {
            throw HttpFailure("请求异常: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            conn?.disconnect()
        }
    }
}
