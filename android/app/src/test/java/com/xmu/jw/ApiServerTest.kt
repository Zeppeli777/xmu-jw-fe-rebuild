package com.xmu.jw

import com.xmu.jw.core.SessionStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * /api 路由层测试: 验证 WebView 收到的响应与桌面版 server/index.js 一致
 * (状态码、JSON 形状、错误映射)。同样跑在本地桩服务上, 不联网。
 */
class ApiServerTest {

    private lateinit var stub: EmapStub

    private class MemStore : SessionStore {
        var data: SessionStore.Data? = null
        override fun load() = data
        override fun save(jar: String, xh: String?, user: String?) {
            data = SessionStore.Data(jar, xh, user)
        }

        override fun clear() {
            data = null
        }
    }

    @Before
    fun setUp() {
        stub = EmapStub()
        ApiServer.init(MemStore(), stub.base)
    }

    @After
    fun tearDown() {
        stub.stop()
    }

    private fun get(path: String, query: Map<String, String> = emptyMap()) =
        ApiServer.handle("GET", path, query, "")

    private fun post(path: String, body: String) =
        ApiServer.handle("POST", path, emptyMap(), body)

    private fun ApiServer.ApiResponse.json(): JSONObject = JSONObject(String(body, Charsets.UTF_8))

    private fun login() {
        post("/api/login/by-ticket", """{"ticket":"ST-abc"}""")
    }

    // ---------- 会话 ----------

    @Test
    fun `未登录时会话接口`() {
        val r = get("/api/session")
        assertEquals(200, r.status)
        assertTrue(r.contentType.contains("application/json"))
        assertFalse(r.json().optBoolean("loggedIn"))
    }

    @Test
    fun `票据登录后会话接口`() {
        login()
        val r = get("/api/session").json()
        assertTrue(r.optBoolean("loggedIn"))
        assertEquals("潘腾凯", r.getJSONObject("user").optString("XM"))
    }

    // ---------- 票据识别 ----------

    @Test
    fun `票据识别接受网址和裸票据`() {
        // 完整网址
        assertEquals(
            "ST-123",
            ApiServer.extractTicket("https://jw.xmu.edu.cn/new/index.html?ticket=ST-123&foo=bar"),
        )
        // 裸票据
        assertEquals("ST-123", ApiServer.extractTicket("ST-123"))
        // URL 编码
        assertEquals("ST-123", ApiServer.extractTicket("https://jw.xmu.edu.cn/new/index.html?ticket=ST%2D123"))
        // 认不出来
        assertEquals("", ApiServer.extractTicket("https://jw.xmu.edu.cn/new/index.html"))
        assertEquals("", ApiServer.extractTicket("hello world"))
        assertEquals("", ApiServer.extractTicket(""))
    }

    @Test
    fun `票据登录参数校验`() {
        // 认不出票据 → 400
        val bad = post("/api/login/by-ticket", """{"ticket":"随手打的"}""")
        assertEquals(400, bad.status)
        assertTrue(bad.json().optString("error").contains("未能识别票据"))
        // 空 ticket 也走同一条校验
        assertEquals(400, post("/api/login/by-ticket", """{"ticket":""}""").status)
    }

    // ---------- Cookie 导入 ----------

    @Test
    fun `Cookie 导入登录`() {
        login()
        val cookieHeader = ApiServer.clientInstance().exportJarJson()
            .let { com.xmu.jw.core.CookieJar.fromJson(it).header() }
        post("/api/logout", "{}")
        assertFalse(get("/api/session").json().optBoolean("loggedIn"))

        val r = post("/api/login/by-cookie", """{"cookies":${JSONObject.quote(cookieHeader)}}""").json()
        assertTrue("Cookie 导入应成功: ${r.optString("message")}", r.optBoolean("ok"))
        assertEquals("潘腾凯", r.getJSONObject("user").optString("XM"))
    }

    @Test
    fun `Cookie 导入支持三种粘贴格式`() {
        // 1. 请求头字符串
        val a = ApiServer.parseCookieInput("GS_SESSIONID=S1; _WEU=abc")
        assertEquals(2, a.all().size)
        // 2. DevTools 表格复制(TSV: Name/Value/Domain/Path)
        val tsv = "GS_SESSIONID\tS1\tjw.xmu.edu.cn\t/\n_WEU\tabc\tjw.xmu.edu.cn\t/"
        val b = ApiServer.parseCookieInput(tsv)
        assertEquals(2, b.all().size)
        assertTrue(b.header("jw.xmu.edu.cn").contains("_WEU=abc"))
        // 3. JSON 数组
        val c = ApiServer.parseCookieInput("""[{"name":"GS_SESSIONID","value":"S1","domain":"jw.xmu.edu.cn","path":"/"}]""")
        assertEquals(1, c.all().size)
        // 空/认不出
        assertEquals(0, ApiServer.parseCookieInput("").all().size)
        assertEquals(0, ApiServer.parseCookieInput("这不是 cookie").all().size)
        // 表头行要被忽略
        assertEquals(1, ApiServer.parseCookieInput("Name\tValue\nGS_SESSIONID\tS1").all().size)
    }

    @Test
    fun `Cookie 导入空内容时报错`() {
        val r = post("/api/login/by-cookie", """{"cookies":""}""").json()
        assertFalse(r.optBoolean("ok"))
        assertTrue(r.optString("message").contains("未能解析"))
    }

    @Test
    fun `Chrome 导入在安卓端不可用`() {
        val r = post("/api/login/by-chrome", "{}").json()
        assertFalse(r.optBoolean("ok"))
        assertTrue(r.optString("message").contains("官方登录"))
    }

    @Test
    fun `退出登录`() {
        login()
        assertTrue(get("/api/session").json().optBoolean("loggedIn"))
        assertTrue(post("/api/logout", "{}").json().optBoolean("ok"))
        assertFalse(get("/api/session").json().optBoolean("loggedIn"))
    }

    // ---------- 业务接口 ----------

    @Test
    fun `学业完成查询接口`() {
        login()
        val r = get("/api/xywccx/overview")
        assertEquals(200, r.status)
        val d = r.json()
        assertTrue(d.optBoolean("hasPlan"))
        assertEquals(2, d.getJSONArray("groups").length())
        assertEquals(2, d.getJSONArray("courses").length())
        assertEquals("软件工程培养方案", d.getJSONObject("plan").optString("PYFAMC"))
        assertEquals(96.5, d.getJSONObject("plan").optDouble("WCXF"), 0.001)
    }

    @Test
    fun `GPA 排名接口`() {
        login()
        val info = get("/api/jdfpm/info").json()
        assertEquals(1, info.getJSONArray("ranges").length())
        assertEquals(1, info.getJSONArray("records").length())
        assertNotNull(info.getJSONObject("user"))

        // 申请后返回最新记录
        val applied = post("/api/jdfpm/apply", """{"cjfwWid":"W-1"}""").json()
        assertTrue(applied.has("records"))

        val detail = get("/api/jdfpm/detail", mapOf("cjfwWid" to "W-1"))
        assertEquals(200, detail.status)
        assertEquals(1, JSONArray(String(detail.body, Charsets.UTF_8)).length())

        val print = get(
            "/api/jdfpm/print",
            mapOf("wid" to "S-1", "lx" to "1", "dynr" to "1", "sfxypm" to "0"),
        )
        assertTrue(print.contentType.contains("text/html"))
        assertTrue(String(print.body, Charsets.UTF_8).contains("绩点证明"))
    }

    @Test
    fun `证明文件申请接口`() {
        login()
        val apps = JSONArray(String(get("/api/zmsq/applications").body, Charsets.UTF_8))
        val avail = JSONArray(String(get("/api/zmsq/available").body, Charsets.UTF_8))
        assertEquals(1, apps.length())
        assertEquals(1, avail.length())

        // 在线申请
        val apply = post(
            "/api/zmsq/apply",
            """{"zmids":["Z-9"],"form":{"SFDZZJDY":"0","SQDYS":1,"SFSYSJM":"0"}}""",
        ).json()
        assertTrue(apply.optBoolean("ok"))
        assertEquals("申请成功", apply.optString("message"))
        // 没选证明 → 400
        assertEquals(400, post("/api/zmsq/apply", """{"zmids":[],"form":{}}""").status)

        // 下载: PDF + Content-Disposition
        val dl = get("/api/zmsq/download", mapOf("wid" to "Z-1"))
        assertEquals(200, dl.status)
        assertTrue(dl.contentType.contains("application/pdf"))
        assertNotNull(dl.disposition)
        assertTrue(dl.disposition!!.contains("filename"))
        assertEquals(400, get("/api/zmsq/download", emptyMap()).status)

        // 邮件
        val mail = post("/api/zmsq/email", """{"wids":["Z-1"],"mail":"a@stu.xmu.edu.cn"}""").json()
        assertTrue(mail.optBoolean("ok"))
        assertEquals("已发送", mail.optString("message"))
        assertEquals(400, post("/api/zmsq/email", """{"wids":[],"mail":""}""").status)
    }

    // ---------- 错误映射 ----------

    @Test
    fun `会话失效返回 401`() {
        // 全新服务(无登录态): 业务接口应映射成 401 SESSION_EXPIRED, 前端据此弹登录层
        val r = get("/api/xywccx/overview")
        assertEquals(401, r.status)
        val d = r.json()
        assertEquals("SESSION_EXPIRED", d.optString("error"))
        assertTrue(d.optString("message").isNotEmpty())
    }

    @Test
    fun `未知路径 404`() {
        assertEquals(404, get("/api/nope").status)
    }

    @Test
    fun `打印地址解析`() {
        val upstream = ApiServer.resolveUpstream("/api/jdfpm/print", "wid=S-1&lx=1&dynr=1&sfxypm=0")
        assertEquals(
            "https://jw.xmu.edu.cn/jwapp/sys/jdfpm/api/jdjs/printZm.do?wid=S-1&lx=1&dynr=1&sfxypm=0",
            upstream,
        )
        assertEquals(null, ApiServer.resolveUpstream("/api/other", "x=1"))
    }

    @Test
    fun `下载结果解析`() {
        login()
        val f = ApiServer.download("Z-1")
        assertFalse(f.isJson)
        assertEquals("zhengming.pdf", f.name)
        assertTrue(String(f.data).startsWith("%PDF"))
    }
}
