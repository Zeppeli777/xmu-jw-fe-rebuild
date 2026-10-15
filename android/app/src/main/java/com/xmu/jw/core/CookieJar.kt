package com.xmu.jw.core

/** 单个 cookie(seq 为设置序号, 仅运行时使用, 不持久化) */
data class Cookie(val name: String, val value: String, val domain: String, val path: String, val seq: Int = 0)

/**
 * cookie 罐: 与 server/cas.js 的 addCookie/cookieHeader 语义一致。
 * key = name|domain|path, 保证同名不同域的 cookie 不互相覆盖。
 */
class CookieJar {

    private val map = LinkedHashMap<String, Cookie>()
    private var seq = 0

    val size: Int get() = map.size

    fun isEmpty(): Boolean = map.isEmpty()

    fun clear() {
        map.clear()
        seq = 0
    }

    /** 解析一条 Set-Cookie 值(可能带 Domain/Path 等属性) */
    fun addSetCookie(setCookie: String) {
        if (setCookie.isEmpty()) return
        val pair = setCookie.split(";")[0]
        val idx = pair.indexOf('=')
        if (idx < 0) return
        val name = pair.substring(0, idx).trim()
        val value = pair.substring(idx + 1).trim()
        if (name.isEmpty()) return
        val domain = Regex("domain=([^;]+)", RegexOption.IGNORE_CASE)
            .find(setCookie)?.groupValues?.get(1)?.trim()?.removePrefix(".") ?: ""
        val path = Regex("path=([^;]+)", RegexOption.IGNORE_CASE)
            .find(setCookie)?.groupValues?.get(1)?.trim() ?: "/"
        map[key(name, domain, path)] = Cookie(name, value, domain, path, ++seq)
    }

    /**
     * 生成 Cookie 请求头; host 非空时只带域名匹配的 cookie。
     * 同名 cookie 只发最新设置的那条: 服务端轮换 _WEU 时按 Path 写入新值,
     * 旧 Path 的条目(如导入 cookie 时丢失 Path 信息)若同时发出, 服务端可能取到旧令牌导致 403。
     */
    fun header(host: String = ""): String {
        val latest = LinkedHashMap<String, Cookie>()
        for (c in map.values) {
            if (c.value.isEmpty()) continue
            if (host.isNotEmpty() && c.domain.isNotEmpty() && !host.endsWith(c.domain)) continue
            val prev = latest[c.name]
            if (prev == null || c.seq >= prev.seq) latest[c.name] = c
        }
        return latest.values.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** 给指定 host 用的 cookie 列表(同步给 WebView 的 CookieManager), 同样按名去重取最新 */
    fun forHost(host: String): List<Cookie> {
        val latest = LinkedHashMap<String, Cookie>()
        for (c in map.values) {
            if (c.value.isEmpty()) continue
            if (c.domain.isNotEmpty() && !host.endsWith(c.domain)) continue
            val prev = latest[c.name]
            if (prev == null || c.seq >= prev.seq) latest[c.name] = c
        }
        return latest.values.toList()
    }

    fun all(): List<Cookie> = map.values.toList()

    fun toJson(): String {
        val sb = StringBuilder("[")
        map.values.forEachIndexed { i, c ->
            if (i > 0) sb.append(",")
            sb.append("{\"name\":").append(jsonStr(c.name))
            sb.append(",\"value\":").append(jsonStr(c.value))
            sb.append(",\"domain\":").append(jsonStr(c.domain))
            sb.append(",\"path\":").append(jsonStr(c.path))
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    companion object {
        fun fromJson(json: String): CookieJar {
            val jar = CookieJar()
            if (json.isBlank()) return jar
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val c = Cookie(
                        o.optString("name"),
                        o.optString("value"),
                        o.optString("domain"),
                        o.optString("path").ifEmpty { "/" },
                        i + 1, // 恢复时按数组顺序当设置序号, 保证"后来的覆盖前面的"
                    )
                    if (c.name.isNotEmpty()) jar.map[key(c.name, c.domain, c.path)] = c
                }
            } catch (_: Exception) {
                // 登录态文件损坏时当作未登录处理
            }
            return jar
        }

        private fun key(name: String, domain: String, path: String) = "$name|$domain|$path"

        private fun jsonStr(s: String): String {
            val sb = StringBuilder("\"")
            for (ch in s) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
