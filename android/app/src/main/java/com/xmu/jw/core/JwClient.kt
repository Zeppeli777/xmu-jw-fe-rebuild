package com.xmu.jw.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** 会话失效(401/302 到 CAS/响应不是 JSON) */
class SessionExpired(message: String) : RuntimeException(message)

/** _WEU 令牌未激活或陈旧(403), 需要重新预热再重试 */
class NeedWarmup(message: String = "会话需要重新激活") : RuntimeException(message)

/** 票据无效或过期(换会话失败, 但不影响已有登录态) */
class BadTicket(message: String) : RuntimeException(message)

/**
 * 教务系统(jw.xmu.edu.cn, 金智 EMAP 平台)客户端 - 移植自 server/jw.js。
 *
 * 关键机制(逆向实测, 与桌面版一致):
 * 1. 登录后服务端下发 GS_SESSIONID(HttpOnly) + _WEU(轮换令牌)
 * 2. _WEU 每个响应都会轮换, 客户端必须始终用最新值 → 所有上游请求串行执行 + 每响应更新 cookie
 * 3. 新会话的 _WEU 是"未激活"态(约 113 字符), 访问 /jwapp/sys/jwai/index.do 后升级为完整态,
 *    未升级时调业务 API 返回 403
 * 4. 各应用按角色鉴权, 新会话必须 changeAppRole + setJwCommonAppRole, 否则业务 API 403
 */
class JwClient(
    private val store: SessionStore,
    /** 教务系统地址; 单测里可指向本地桩服务 */
    private val base: String = JW,
) {

    private val lock = Any()
    private var jar = CookieJar()
    private var xh: String? = null
    private var user: JSONObject? = null

    init {
        load()
    }

    // ---------- 会话持久化 ----------

    private fun load() {
        val d = store.load() ?: return
        jar = CookieJar.fromJson(d.jar)
        xh = d.xh
        user = d.user?.takeIf { it.isNotBlank() }?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        }
    }

    private fun persist() {
        store.save(jar.toJson(), xh, user?.toString())
    }

    fun hasSession(): Boolean = !jar.isEmpty()

    fun currentUser(): JSONObject? = user

    fun currentXh(): String? = xh

    fun setJar(newJar: CookieJar) {
        jar = newJar
    }

    /** 接受浏览器粘贴的 "name=value; name2=value2" 字符串 */
    fun setJarFromCookieHeader(cookies: String) {
        val j = CookieJar()
        cookies.split(";").forEach { j.addSetCookie(it.trim()) }
        jar = j
    }

    fun clearSession() {
        jar = CookieJar()
        xh = null
        user = null
        store.clear()
    }

    /** 从备份恢复会话(jarJson 为 cookie 数组的 JSON 字符串, 与桌面版 .session.json 同格式) */
    fun restoreSession(jarJson: String, xh: String?, userJson: String?) {
        jar = CookieJar.fromJson(jarJson)
        this.xh = xh
        user = userJson?.takeIf { it.isNotBlank() }?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        }
        persist()
    }

    /** 给 WebView 的 CookieManager 同步会话(打印页直接加载学校地址时用) */
    fun cookiesForHost(host: String): List<Cookie> = jar.forHost(host)

    /** 导出当前 jar(备份/迁移登录态用) */
    fun exportJarJson(): String = jar.toJson()

    // ---------- 请求串行化(_WEU 轮换竞态保护) ----------

    private fun expire(): SessionExpired = SessionExpired("会话已失效, 请重新登录")

    /** 通用请求: 自动带 cookie、收集 Set-Cookie(轮换 _WEU)、识别会话失效 */
    private fun request(
        url: String,
        method: String = "POST",
        params: Map<String, String> = emptyMap(),
        raw: Boolean = false,
        referer: String = PORTAL_REFERER,
    ): Any {
        val headers = mutableMapOf(
            "User-Agent" to UA,
            "Cookie" to jar.header("jw.xmu.edu.cn"),
            "Referer" to referer,
        )
        var body: String? = null
        if (method == "POST") {
            body = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
            headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
            headers["X-Requested-With"] = "XMLHttpRequest"
            headers["Accept"] = "application/json, text/javascript, */*; q=0.01"
        }
        val r = Http.request(url, method, headers, body)
        r.headers("Set-Cookie").forEach { jar.addSetCookie(it) }

        val weu = jar.all().firstOrNull { it.name == "_WEU" }
        L.d(TAG, "$method ${url.removePrefix(base).take(60)} → ${r.status} _WEU=${weu?.value?.length ?: 0}")

        if (r.status == 302 || r.status == 401) {
            val loc = r.header("location") ?: ""
            if (loc.contains("ids.xmu.edu.cn") || r.status == 401) throw expire()
        }
        if (r.status == 403) throw NeedWarmup()
        if (r.status !in 200..299) throw RuntimeException("教务接口错误 HTTP ${r.status}")
        val text = r.text
        if (raw) return text
        return try {
            if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        } catch (_: Exception) {
            throw expire()
        }
    }

    /** 会话预热(裸请求, 不进串行锁, 避免死锁) */
    private fun warmUpDirect() {
        // 中文界面(否则 _DISPLAY 字段返回英文)
        jar.addSetCookie("EMAP_LANG=zh; Path=/")
        jar.addSetCookie("amp.locale=zh_CN; Path=/")
        val seq = listOf(
            "GET" to "$base/jwapp/sys/jwai/*default/index.do",
            "GET" to "$base/jwapp/sys/jwai/index.do",
            "POST" to "$base/jwapp/sys/jwai/api/user/getCurrentUser.do",
        )
        for ((m, u) in seq) {
            try {
                val headers = mutableMapOf(
                    "User-Agent" to UA,
                    "Cookie" to jar.header("jw.xmu.edu.cn"),
                    "Referer" to PORTAL_REFERER,
                    "X-Requested-With" to "XMLHttpRequest",
                )
                var body: String? = null
                if (m == "POST") {
                    headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
                    body = ""
                }
                val r = Http.request(u, m, headers, body)
                r.headers("Set-Cookie").forEach { jar.addSetCookie(it) }
            } catch (e: Exception) {
                L.w(TAG, "预热忽略: $u ${e.message}")
            }
        }
        persist()
    }

    /**
     * 设置应用角色(新会话必须!)。
     * 每个应用有各自的角色(如 zmsqxmu 是"证明申请本科生"), 需分别 changeAppRole。
     * 角色 ID 从各应用自己的配置接口动态获取。内部用裸请求, 避免 403 重试递归。
     */
    private fun setupAppRole() {
        fun post(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
            val r = Http.request(
                base + path,
                "POST",
                mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Cookie" to jar.header("jw.xmu.edu.cn"),
                    "User-Agent" to UA,
                    "Referer" to PORTAL_REFERER,
                ),
                params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" },
            )
            r.headers("Set-Cookie").forEach { jar.addSetCookie(it) }
            return try {
                if (r.text.trimStart().startsWith("[")) null else JSONObject(r.text)
            } catch (_: Exception) {
                null
            }
        }

        try {
            val APPS = listOf("xywccx", "jdfpm", "zmsqxmu")
            for (appName in APPS) {
                // 1. 从应用页提取 appId
                val page = Http.request(
                    "$base/jwapp/sys/$appName/*default/index.do",
                    "GET",
                    mapOf("User-Agent" to UA, "Cookie" to jar.header("jw.xmu.edu.cn"), "Referer" to PORTAL_REFERER),
                )
                page.headers("Set-Cookie").forEach { jar.addSetCookie(it) }
                val appId = Regex("\"appId\":\"(\\d+)\"").find(page.text)?.groupValues?.get(1)
                if (appId.isNullOrEmpty()) continue

                // 2. 该应用的配置(角色列表)
                val cfg = post("/jwapp/sys/funauthapp/api/getAppConfig/$appName-$appId.do")
                val role = pickRole(cfg?.optJSONObject("HEADER")?.optJSONArray("dropMenu"))
                if (role == null) continue

                // 3. 切换该应用的角色
                val res = post("/jwapp/sys/funauthapp/api/changeAppRole/$appName/${role.optString("id")}.do")
                L.d(TAG, "changeAppRole $appName/${role.optString("text")} → $res")
            }

            // 4. 公共角色(用 xywccx 的本科生角色)
            val xpage = Http.request(
                "$base/jwapp/sys/xywccx/*default/index.do",
                "GET",
                mapOf("User-Agent" to UA, "Cookie" to jar.header("jw.xmu.edu.cn"), "Referer" to PORTAL_REFERER),
            )
            val xappId = Regex("\"appId\":\"(\\d+)\"").find(xpage.text)?.groupValues?.get(1)
            if (!xappId.isNullOrEmpty()) {
                val xcfg = post("/jwapp/sys/funauthapp/api/getAppConfig/xywccx-$xappId.do")
                val xmenu = xcfg?.optJSONObject("HEADER")?.optJSONArray("dropMenu")
                val xrole = pickRole(xmenu) { it.optString("text") == "本科生" }
                if (xrole != null) {
                    post("/jwapp/sys/jwpubapp/pub/setJwCommonAppRole.do", mapOf("ROLEID" to xrole.optString("id")))
                }
            }
        } catch (e: Exception) {
            L.w(TAG, "角色设置失败: ${e.message}")
        }
        persist()
    }

    /** 403 时先预热+设角色再重试一次(预热在串行锁之外) */
    private fun <T> withWarmupRetry(fn: () -> T): T =
        try {
            fn()
        } catch (e: NeedWarmup) {
            synchronized(lock) {
                warmUpDirect()
                setupAppRole()
            }
            fn()
        }

    fun jwPost(path: String, params: Map<String, String> = emptyMap(), referer: String = PORTAL_REFERER): Any =
        withWarmupRetry {
            synchronized(lock) { request(url(path), "POST", params, referer = referer) }
        }

    fun jwGet(path: String, referer: String = PORTAL_REFERER): Any =
        withWarmupRetry {
            synchronized(lock) { request(url(path), "GET", referer = referer) }
        }

    /** 新会话建立后调用: 预热 + 设置角色 + 持久化 */
    fun initSession() {
        synchronized(lock) {
            warmUpDirect()
            setupAppRole()
            persist()
        }
    }

    // ---------- 业务接口 ----------

    /** 学生基本信息(同时用于获取学号 XH 与校验登录态) */
    fun getStudentInfo(): JSONObject {
        val res = jwPost("/jwapp/sys/jdfpm/modules/jdjs/cxxsjbxx.do")
        val r = rows(res, "cxxsjbxx").firstOrNull() ?: throw expire()
        xh = r.optString("XH")
        user = JSONObject().apply {
            put("XH", r.opt("XH"))
            put("XM", r.opt("XM"))
            put("XZNJ", r.opt("XZNJ_DISPLAY"))
            put("YXDM", r.opt("YXDM_DISPLAY"))
            put("ZYDM", r.opt("ZYDM_DISPLAY"))
            put("BJDM", r.opt("BJDM_DISPLAY"))
            put("XZ", r.opt("XZ"))
            put("XJZT", r.opt("XJZTDM_DISPLAY"))
        }
        persist()
        return user!!
    }

    /**
     * 学业完成查询 - 全量聚合:
     * 方案摘要 + 课组树(模块/课组) + 全部课程(方案内+方案外) + 未修统计
     */
    fun getXywccxOverview(): JSONObject {
        if (xh == null) getStudentInfo()
        val XH = xh ?: throw expire()

        // 1. 先查学生的培养方案(拿到 PYFADM; 空参数时服务端按会话取当前学生)
        val scfaRes = jwPost("/jwapp/sys/xywccx/modules/xywccx/cxxsscfa.do")
        val fa = rows(scfaRes, "cxxsscfa").firstOrNull() ?: return JSONObject().put("hasPlan", false)

        val PYFADM = fa.optString("PYFADM")
        val kzRes = jwPost(
            "/jwapp/sys/xywccx/modules/xywccx/cxscfakz.do",
            mapOf(
                "XH" to XH, "PYFADM" to PYFADM, "BYNJDM" to "-", "PCDM" to "-",
                "SCLBDM" to "04", "XNXQDM" to currentXnxq(),
            ),
        )
        val kcRes = jwPost(
            "/jwapp/sys/xywccx/modules/xywccx/cxscfakzkchxkqkx.do",
            mapOf(
                "SCLBDM" to "04", "BYNJDM" to "-", "PCDM" to "-", "XH" to XH,
                "*order" to "+SFTG", "PYFADM" to PYFADM, "pageSize" to "1000", "pageNumber" to "1",
            ),
        )
        val fawRes = jwPost(
            "/jwapp/sys/xywccx/modules/xywccx/cxfawxfms.do",
            mapOf("XH" to XH, "PYFADM" to PYFADM, "BYNJDM" to "-", "PCDM" to "-"),
        )

        val plan = JSONObject().apply {
            put("PYFAMC", fa.opt("PYFAMC"))
            put("WCXF", num(fa.opt("WCXF")))
            put("YQXF", num(fa.opt("YQXF")))
            put("XKXF", num(fa.opt("XKXF")))
            put("CZSJ", fa.opt("CZSJ"))
        }
        return JSONObject().apply {
            put("hasPlan", true)
            put("plan", plan)
            put("groups", JSONArray(rows(kzRes, "cxscfakz")))
            put("courses", JSONArray(rows(kcRes, "cxscfakzkchxkqkx")))
            val missing = rows(fawRes, "cxfawxfms").firstOrNull()
            if (missing != null) put("missing", missing) else put("missing", JSONObject.NULL)
        }
    }

    /** GPA 排名 - 学生信息 + 可选成绩范围 + 申请记录 */
    fun getJdfpmInfo(): JSONObject {
        if (xh == null) getStudentInfo()
        val fwRes = jwPost("/jwapp/sys/jdfpm/modules/jdjs/cxxskxcjfw.do")
        val sqRes = jwPost("/jwapp/sys/jdfpm/api/jdjs/getJdjssq.do")
        var records = toList((sqRes as? JSONObject)?.optJSONObject("datas")?.opt("getJdjssq"))
        if (records.isEmpty() && sqRes is JSONArray) records = toList(sqRes)
        return JSONObject().apply {
            put("user", user ?: JSONObject.NULL)
            put("ranges", JSONArray(rows(fwRes, "cxxskxcjfw")))
            put("records", JSONArray(records))
        }
    }

    /** 新增绩点计算申请 */
    fun applyJdfpm(cjfwWid: String) {
        val fwRes = jwPost("/jwapp/sys/jdfpm/modules/jdjs/cxxskxcjfw.do")
        val range = rows(fwRes, "cxxskxcjfw").firstOrNull { it.optString("WID") == cjfwWid }
            ?: throw RuntimeException("成绩范围不存在")
        jwPost(
            "/jwapp/sys/jdfpm/api/jdjs/addJdjssq.do",
            mapOf("CJFWWID" to cjfwWid, "CJFWWID_DISPLAY" to range.optString("XSMC")),
        )
    }

    /** 某次申请的参与计算成绩明细 */
    fun getJdfpmDetail(cjfwWid: String?): List<JSONObject> {
        if (xh == null) getStudentInfo()
        val querySetting = JSONArray()
            .put(JSONObject().put("name", "CJFWWID").put("linkOpt", "AND").put("builder", "equal").put("value", cjfwWid ?: ""))
            .put(JSONObject().put("name", "SFZX").put("linkOpt", "AND").put("builder", "equal").put("value", "1"))
            .toString()
        val res = jwPost(
            "/jwapp/sys/jdfpm/modules/jdjs/cxzdxscjxq.do",
            mapOf(
                "SFZX" to "1", "CJFWWID" to (cjfwWid ?: ""), "querySetting" to querySetting,
                "*order" to "-XNXQDM,+KCH", "pageSize" to "1000", "pageNumber" to "1",
            ),
        )
        return rows(res, "cxzdxscjxq")
    }

    /** 打印绩点证明: 取教务系统的证明 HTML(保持会话) */
    fun printJdfpm(wid: String?, lx: String, dynr: String, sfxypm: String): String {
        val url = buildString {
            append(base).append("/jwapp/sys/jdfpm/api/jdjs/printZm.do?&wid=").append(enc(wid ?: ""))
            append("&type=").append(enc(lx))
            append("&DYNR=").append(enc(dynr))
            append("&SFXYPM=").append(enc(sfxypm))
        }
        return withWarmupRetry {
            synchronized(lock) { request(url, "GET", raw = true, referer = "$base/jwapp/sys/jdfpm/") as String }
        }
    }

    // ---------- 证明文件申请(app: zmsqxmu) ----------
    // 列表类接口(EMAP datatable 模型)在 modules/ 下; 动作类接口(save/down/mail)在应用根下
    private val ZMSQ_LIST = "/jwapp/sys/zmsqxmu/modules/xszmsq/"
    private val ZMSQ_ACTION = "/jwapp/sys/zmsqxmu/xszmsq/"

    // EMAP 框架对部分端点(码表/保存类)校验 Referer 必须为应用页
    private val ZMSQ_REF = "$base/jwapp/sys/zmsqxmu/*default/index.do"

    /** 我的证明申请列表 */
    fun getZmApplications(): List<JSONObject> {
        val querySetting = JSONArray()
            .put(JSONObject().put("name", "*order").put("value", "-SQSJ").put("linkOpt", "AND").put("builder", "equal"))
            .toString()
        val res = jwPost(
            ZMSQ_LIST + "xszmsqcx.do",
            mapOf("*order" to "-SQSJ", "querySetting" to querySetting, "pageSize" to "200", "pageNumber" to "1"),
            referer = ZMSQ_REF,
        )
        return rows(res, "xszmsqcx")
    }

    /** 可申请的证明列表 */
    fun getZmAvailable(): List<JSONObject> {
        val querySetting = JSONArray()
            .put(JSONObject().put("name", "*order").put("value", "+WID").put("linkOpt", "AND").put("builder", "equal"))
            .toString()
        val res = jwPost(
            ZMSQ_LIST + "dsqzmcx.do",
            mapOf("*order" to "+WID", "querySetting" to querySetting, "pageSize" to "200", "pageNumber" to "1"),
            referer = ZMSQ_REF,
        )
        return rows(res, "dsqzmcx")
    }

    /**
     * 提交证明申请
     * @param form SFDZZJDY 是否自助机打印, SQDYS 份数, SFSYSJM 是否随机码
     */
    fun applyZm(zmids: List<String>, form: Map<String, Any?>): Any? {
        val paramStr = JSONObject().apply {
            put("SFDZZJDY", (form["SFDZZJDY"] ?: "0").toString())
            put("SQDYS", (form["SQDYS"] ?: "1").toString())
            put("SFSYSJM", (form["SFSYSJM"] ?: "0").toString())
        }.toString()
        return jwPost(
            ZMSQ_ACTION + "saveZmsq.do",
            mapOf("paramStr" to paramStr, "zmid" to zmids.joinToString(",")),
            referer = ZMSQ_REF,
        )
    }

    /** 查询可打印方式(提交打印申请前调用) */
    fun queryZmPrintMethods(zmids: List<String>): Any? =
        jwPost(ZMSQ_ACTION + "queryKdyfs.do", mapOf("zmids" to zmids.joinToString(",")), referer = ZMSQ_REF)

    class Download(val contentType: String, val disposition: String, val data: ByteArray)

    /** 下载证明文件(有效期内返回文件, 否则返回 JSON 错误) */
    fun downloadZm(wid: String): Download = withWarmupRetry {
        synchronized(lock) {
            val r = Http.request(
                base + ZMSQ_ACTION + "downZm.do",
                "POST",
                mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Cookie" to jar.header("jw.xmu.edu.cn"),
                    "User-Agent" to UA,
                    "Referer" to ZMSQ_REF,
                ),
                "wid=${enc(wid)}",
            )
            r.headers("Set-Cookie").forEach { jar.addSetCookie(it) }
            if (r.status == 403) throw NeedWarmup()
            Download(
                r.header("content-type") ?: "application/octet-stream",
                r.header("content-disposition") ?: "",
                r.body,
            )
        }
    }

    /** 邮件发送证明 */
    fun emailZm(wids: List<String>, mail: String): Any? =
        jwPost(ZMSQ_ACTION + "sendMail.do", mapOf("wids" to wids.joinToString(","), "mail" to mail), referer = ZMSQ_REF)

    /**
     * 用 CAS 票据(ticket)交换教务系统会话。
     * 用于"浏览器辅助登录": 用户浏览器已登录统一身份认证时无需再输密码。
     */
    fun loginByTicket(ticket: String): JSONObject {
        // 先用一个临时罐换会话, 验证通过后才接管; 失败不影响已有登录态
        val newJar = CookieJar()
        val oldJar = jar
        jar = newJar
        var url = "$base/jwapp/sys/emapangular/index.do?ticket=" + URLEncoder.encode(ticket, "UTF-8")
        var hops = 0
        while (hops < 8 && url.isNotEmpty()) {
            val r = Http.request(
                url,
                "GET",
                mapOf("User-Agent" to UA, "Cookie" to jar.header("jw.xmu.edu.cn"), "Referer" to PORTAL_REFERER),
            )
            r.headers("Set-Cookie").forEach { jar.addSetCookie(it) }
            val next = r.header("location")
            if (r.status in 300..399 && next != null) {
                url = java.net.URL(java.net.URL(url), next).toString()
                hops++
                continue
            }
            break
        }
        val ok = jar.all().any { it.name == "GS_SESSIONID" || it.name == "_WEU" }
        if (!ok) {
            jar = oldJar // 还原, 不清掉用户原来的登录态
            throw BadTicket("票据无效或已过期")
        }
        initSession()
        return getStudentInfo()
    }

    // ---------- 工具 ----------

    private fun url(path: String): String = if (path.startsWith("http")) path else base + path

    private fun rows(res: Any?, dataName: String): List<JSONObject> {
        if (res !is JSONObject) return emptyList()
        val datas = res.optJSONObject("datas") ?: return emptyList()
        return toList(datas.opt(dataName))
    }

    private fun toList(v: Any?): List<JSONObject> = when (v) {
        is JSONArray -> (0 until v.length()).map { v.getJSONObject(it) }
        is JSONObject -> v.optJSONArray("rows")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            ?: emptyList()
        else -> emptyList()
    }

    /** 从角色下拉菜单里挑一个: 优先满足 prefer 的, 否则第一个 */
    private fun pickRole(menu: JSONArray?, prefer: (JSONObject) -> Boolean = { it.optBoolean("active") }): JSONObject? {
        if (menu == null) return null
        val list = (0 until menu.length()).map { menu.getJSONObject(it) }
        return list.firstOrNull(prefer) ?: list.firstOrNull()
    }

    private fun num(v: Any?): Number = when (v) {
        is Number -> if (v.toDouble().isFinite()) v else 0
        is String -> v.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let {
            if (it == Math.floor(it) && !it.isInfinite()) it.toLong() else it
        } ?: 0
        else -> 0
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val JW = "https://jw.xmu.edu.cn"
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val PORTAL_REFERER = "$JW/new/index.html"
        private const val TAG = "jw"

        /** 当前学年学期(教务系统惯例: 9 月后为第一学期) */
        fun currentXnxq(): String {
            val now = java.util.Calendar.getInstance()
            val y = now.get(java.util.Calendar.YEAR)
            val m = now.get(java.util.Calendar.MONTH) + 1
            return if (m >= 9) "$y-${y + 1}-1" else "${y - 1}-$y-2"
        }
    }
}
