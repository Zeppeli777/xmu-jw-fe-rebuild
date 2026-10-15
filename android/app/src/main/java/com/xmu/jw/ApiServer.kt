package com.xmu.jw

import com.xmu.jw.core.JwClient
import com.xmu.jw.core.L
import com.xmu.jw.core.NeedWarmup
import com.xmu.jw.core.SessionExpired
import com.xmu.jw.core.SessionStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * /api 路由层 - 移植自 server/index.js。
 * 不依赖 android.* API, 因此既能被 WebView 拦截器调用, 也能在 JVM 单测里直接跑。
 */
object ApiServer {

    class ApiResponse(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val disposition: String? = null,
    )

    private lateinit var client: JwClient

    /** 演示模式读 assets 用 */
    private var demoContext: android.content.Context? = null

    fun init(
        store: SessionStore,
        baseUrl: String = JwClient.JW,
        context: android.content.Context? = null,
    ) {
        client = JwClient(store, baseUrl)
        this.demoContext = context
    }

    fun clientInstance(): JwClient = client

    // ---------- 入口 ----------

    fun handle(method: String, path: String, query: Map<String, String>, body: String): ApiResponse =
        try {
            route(method, path, query, body)
        } catch (e: SessionExpired) {
            json(401, JSONObject().put("error", "SESSION_EXPIRED").put("message", e.message ?: "会话已失效"))
        } catch (e: NeedWarmup) {
            json(500, JSONObject().put("error", "UPSTREAM").put("message", e.message ?: "会话需要重新激活"))
        } catch (e: Throwable) {
            L.e("api", "$method $path", e)
            json(500, JSONObject().put("error", "UPSTREAM").put("message", e.message ?: "服务内部错误"))
        }

    private fun route(method: String, path: String, query: Map<String, String>, bodyStr: String): ApiResponse {
        // 演示模式(仅 debug): 用样本数据渲染界面, 不访问教务系统
        DemoData.handle(method, path, demoContext)?.let { return it }

        val body = parseBody(bodyStr)

        // ---------- 会话与登录 ----------
        if (path == "/api/session" && method == "GET") {
            return json(
                200,
                JSONObject()
                    .put("loggedIn", client.hasSession())
                    .put("user", client.currentUser() ?: JSONObject.NULL),
            )
        }

        if (path == "/api/login/by-cookie" && method == "POST") {
            val jar = parseCookieInput(body.optString("cookies"))
            if (jar.isEmpty()) {
                return json(200, JSONObject().put("ok", false).put("message", "未能解析 Cookie, 请检查粘贴内容"))
            }
            client.setJar(jar)
            return try {
                client.initSession()
                client.getStudentInfo()
                json(200, JSONObject().put("ok", true).put("user", client.currentUser() ?: JSONObject.NULL))
            } catch (e: Throwable) {
                client.clearSession()
                L.e("api", "[by-cookie]", e)
                json(200, JSONObject().put("ok", false).put("message", "Cookie 无效或已过期: " + (e.message ?: "")))
            }
        }

        if (path == "/api/login/by-chrome" && method == "POST") {
            // 安卓没有本机 Chrome 的 cookie 数据库可读
            // 安卓读不到其他 App(Chrome)的 cookie 数据库; 官方登录在应用内 WebView 里完成
            return json(
                200,
                JSONObject()
                    .put("ok", false)
                    .put("message", "安卓端不支持 Chrome 导入, 请用「官方登录」或粘贴 Cookie"),
            )
        }

        if (path == "/api/login/by-ticket" && method == "POST") {
            val st = extractTicket(body.optString("ticket"))
            if (st.isEmpty()) {
                return json(400, JSONObject().put("error", "未能识别票据, 请粘贴登录后的完整网址或 ST- 开头的票据"))
            }
            return try {
                val user = client.loginByTicket(st)
                json(200, JSONObject().put("ok", true).put("user", user))
            } catch (e: com.xmu.jw.core.BadTicket) {
                json(200, JSONObject().put("ok", false).put("message", e.message ?: "票据无效或已过期"))
            } catch (e: Throwable) {
                L.e("api", "[by-ticket]", e)
                json(500, JSONObject().put("error", e.message ?: "票据交换失败"))
            }
        }

        if (path == "/api/logout" && method == "POST") {
            client.clearSession()
            return json(200, JSONObject().put("ok", true))
        }

        // ---------- 学业完成查询 ----------
        if (path == "/api/xywccx/overview" && method == "GET") {
            return withSession { client.getXywccxOverview() }
        }

        // ---------- GPA 排名 ----------
        if (path == "/api/jdfpm/info" && method == "GET") {
            return withSession { client.getJdfpmInfo() }
        }

        if (path == "/api/jdfpm/apply" && method == "POST") {
            val cjfwWid = body.optString("cjfwWid")
            return withSession {
                client.applyJdfpm(cjfwWid)
                client.getJdfpmInfo() // 返回最新记录
            }
        }

        if (path == "/api/jdfpm/detail" && method == "GET") {
            return withSession { JSONArray(client.getJdfpmDetail(query["cjfwWid"])) }
        }

        if (path == "/api/jdfpm/print" && method == "GET") {
            return try {
                val html = client.printJdfpm(
                    query["wid"],
                    query["lx"] ?: "1",
                    query["dynr"] ?: "1",
                    query["sfxypm"] ?: "0",
                )
                ApiResponse(200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
            } catch (e: SessionExpired) {
                json(401, JSONObject().put("error", "SESSION_EXPIRED").put("message", e.message ?: "会话已失效"))
            } catch (e: Throwable) {
                json(500, JSONObject().put("error", e.message ?: "打印失败"))
            }
        }

        // ---------- 证明文件申请 ----------
        if (path == "/api/zmsq/applications" && method == "GET") {
            return withSession { JSONArray(client.getZmApplications()) }
        }

        if (path == "/api/zmsq/available" && method == "GET") {
            return withSession { JSONArray(client.getZmAvailable()) }
        }

        if (path == "/api/zmsq/apply" && method == "POST") {
            val zmidsArr = body.optJSONArray("zmids")
            val zmids = (0 until (zmidsArr?.length() ?: 0)).map { zmidsArr!!.optString(it) }
            if (zmids.isEmpty()) return json(400, JSONObject().put("error", "请选择要申请的证明"))
            val formObj = body.optJSONObject("form") ?: JSONObject()
            val form = mutableMapOf<String, Any?>()
            formObj.keys().forEach { form[it] = formObj.opt(it) }
            return withSession {
                val r = client.applyZm(zmids, form) as? JSONObject
                val success = isTrue(r?.opt("success")) || r?.optString("code") == "0"
                val msg = r?.optString("msg")?.takeIf { it.isNotEmpty() }
                    ?: if (success) "" else "申请失败"
                JSONObject()
                    .put("ok", success)
                    .put("message", msg)
                    .put("raw", r ?: JSONObject.NULL)
            }
        }

        if (path == "/api/zmsq/download" && method == "GET") {
            val wid = query["wid"]
            if (wid.isNullOrEmpty()) return json(400, JSONObject().put("error", "缺少 wid"))
            return try {
                val f = client.downloadZm(wid)
                if (f.contentType.contains("application/json")) {
                    // 无效证明等: 返回 JSON 错误
                    ApiResponse(200, "application/json; charset=utf-8", f.data)
                } else {
                    val name = Regex("filename\\*?=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                        .find(f.disposition)?.groupValues?.get(1) ?: "证明文件"
                    ApiResponse(
                        200,
                        f.contentType,
                        f.data,
                        disposition = "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(name, "UTF-8"),
                    )
                }
            } catch (e: SessionExpired) {
                json(401, JSONObject().put("error", "SESSION_EXPIRED").put("message", e.message ?: "会话已失效"))
            } catch (e: Throwable) {
                json(500, JSONObject().put("error", e.message ?: "下载失败"))
            }
        }

        if (path == "/api/zmsq/email" && method == "POST") {
            val widsArr = body.optJSONArray("wids")
            val wids = (0 until (widsArr?.length() ?: 0)).map { widsArr!!.optString(it) }
            val mail = body.optString("mail").trim()
            if (wids.isEmpty() || mail.isEmpty()) {
                return json(400, JSONObject().put("error", "参数不完整"))
            }
            return withSession {
                val r = client.emailZm(wids, mail) as? JSONObject
                val success = isTrue(r?.opt("success")) || r?.optString("code") == "0"
                JSONObject()
                    .put("ok", success)
                    .put("message", r?.optString("msg") ?: "")
            }
        }

        return json(404, JSONObject().put("error", "Not Found"))
    }

    // ---------- 原生能力(下载保存 / 打印页) ----------

    /** JsBridge 下载: 返回 (是否 PDF, 内容类型, 文件名, 数据) */
    class DownloadResult(val isJson: Boolean, val contentType: String, val name: String, val data: ByteArray)

    fun download(wid: String): DownloadResult {
        // 演示模式也走这里(原生保存按钮不经过 /api 路由)
        DemoData.handle("GET", "/api/zmsq/download", demoContext)?.let { resp ->
            return if (resp.contentType.contains("pdf")) {
                DownloadResult(false, resp.contentType, "demo-proof.pdf", resp.body)
            } else {
                DownloadResult(true, resp.contentType, "", resp.body)
            }
        }
        val f = client.downloadZm(wid)
        if (f.contentType.contains("application/json")) {
            return DownloadResult(true, f.contentType, "", f.data)
        }
        val name = Regex("filename\\*?=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(f.disposition)?.groupValues?.get(1) ?: "证明文件"
        return DownloadResult(false, f.contentType, name, f.data)
    }

    /** JsBridge 打印: 把 /api/jdfpm/print?wid=... 解析成教务系统真实地址 */
    fun resolveUpstream(path: String, query: String): String? {
        val params = query.split("&").filter { it.isNotEmpty() }
        return when (path) {
            "/api/jdfpm/print" ->
                JwClient.JW + "/jwapp/sys/jdfpm/api/jdjs/printZm.do?" + params.joinToString("&")
            else -> null
        }
    }

    /** 同步给 WebView 的 CookieManager 用的 cookie 列表 */
    fun cookiesForHost(host: String) = client.cookiesForHost(host)

    // ---------- 工具 ----------

    /** 接受完整 URL(如 https://jw.xmu.edu.cn/new/index.html?ticket=ST-xxx)或裸票据 */
    fun extractTicket(raw: String): String {
        val s = raw.trim()
        val m = Regex("""ticket=([^&\s]+)""").find(s)
        if (m != null) {
            return try {
                java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
            } catch (_: Exception) {
                m.groupValues[1]
            }
        }
        return if (s.startsWith("ST-")) s else ""
    }

    /**
     * 解析粘贴的 Cookie, 支持三种常见来源(与 server/index.js 的 parseCookieInput 一致):
     * 1. 请求头字符串 "name=value; name2=value2"
     * 2. DevTools → Application → Cookies 表格多选复制(TSV: Name/Value/Domain/Path...)
     * 3. JSON 数组: [{"name":..,"value":..,"domain":..,"path":..}]
     */
    fun parseCookieInput(raw: String): com.xmu.jw.core.CookieJar {
        val jar = com.xmu.jw.core.CookieJar()
        val text = raw.trim()
        if (text.isEmpty()) return jar

        fun put(name: String?, value: String?, domain: String = "", path: String = "/") {
            val n = (name ?: "").trim()
            if (n.isEmpty() || n.equals("name", ignoreCase = true)) return
            val d = domain.trim().removePrefix(".")
            val p = path.trim().ifEmpty { "/" }
            // 借用 addSetCookie 的解析逻辑, 顺便带上 seq
            jar.addSetCookie("$n=${(value ?: "").trim()}; Domain=$d; Path=$p")
        }

        if (text.startsWith("[")) {
            try {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    put(c.optString("name"), c.optString("value"), c.optString("domain"), c.optString("path"))
                }
                return jar
            } catch (_: Exception) {
                // 不是合法 JSON, 按文本继续解析
            }
        }
        for (line in text.split("\r?\n".toRegex())) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.contains("	")) {
                val cols = t.split("	")
                put(cols.getOrNull(0), cols.getOrNull(1), cols.getOrNull(2) ?: "", cols.getOrNull(3) ?: "")
            } else if (t.contains("=")) {
                for (part in t.split(";")) {
                    val p2 = part.trim()
                    if (p2.isEmpty() || !p2.contains("=")) continue
                    val i = p2.indexOf('=')
                    put(p2.substring(0, i), p2.substring(i + 1))
                }
            }
        }
        return jar
    }

    private fun withSession(fn: () -> Any): ApiResponse =
        try {
            json(200, fn())
        } catch (e: SessionExpired) {
            json(401, JSONObject().put("error", "SESSION_EXPIRED").put("message", e.message ?: "会话已失效"))
        } catch (e: Throwable) {
            L.e("api", "业务接口错误", e)
            json(500, JSONObject().put("error", "UPSTREAM").put("message", e.message ?: "服务内部错误"))
        }

    private fun json(status: Int, obj: Any): ApiResponse =
        ApiResponse(status, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))

    private fun parseBody(body: String): JSONObject =
        if (body.isBlank()) JSONObject() else try {
            JSONObject(body)
        } catch (_: Exception) {
            JSONObject()
        }

    /** EMAP 的 success 字段可能是布尔也可能是字符串 */
    private fun isTrue(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is String -> v == "true"
        is Number -> v.toInt() != 0
        else -> false
    }
}
