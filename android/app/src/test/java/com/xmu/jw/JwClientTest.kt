package com.xmu.jw

import com.xmu.jw.core.CookieJar
import com.xmu.jw.core.Http
import com.xmu.jw.core.JwClient
import com.xmu.jw.core.NeedWarmup
import com.xmu.jw.core.SessionExpired
import com.xmu.jw.core.SessionStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 验证 Kotlin 移植版与 server/jw.js + server/index.js 行为一致:
 * 请求参数、Referer、_WEU 令牌轮换、403 预热重试、会话失效识别、返回给前端的 JSON 形状。
 * 桩服务(EmapStub)只监听 127.0.0.1 随机端口, 不联网, 不使用任何真实账号。
 */
class JwClientTest {

    private lateinit var stub: EmapStub

    /** 内存版 SessionStore */
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
    }

    @After
    fun tearDown() {
        stub.stop()
    }

    private fun store() = MemStore()

    /** 已激活 _WEU 的客户端(跳过 403 预热, 直接测业务接口) */
    private fun activatedClient(): JwClient {
        val client = JwClient(store(), stub.base)
        client.setJarFromCookieHeader("GS_SESSIONID=S1; _WEU=" + "x".repeat(120))
        client.initSession()
        return client
    }

    // ---------- 票据登录(替代原来的学号密码) ----------

    /** 票据交换: 拿到会话 cookie + 预热 + 学生信息 + 持久化 */
    @Test
    fun `票据交换登录`() {
        val st = store()
        val client = JwClient(st, stub.base)
        val user = client.loginByTicket("ST-abc")

        assertEquals(EmapStub.STUDENT_XH, user.optString("XH"))
        assertEquals("潘腾凯", user.optString("XM"))
        assertTrue("登录态应已持久化", st.load()!!.jar.contains("GS_SESSIONID"))

        // 票据 URL 必须落在教务入口
        val entry = stub.reqs("/jwapp/sys/emapangular/index.do").last()
        assertTrue("ticket 走 query, 不在表单里", entry.method == "GET" && entry.params["ticket"] == "ST-abc")
        // 预热与角色都要跑
        listOf("xywccx", "jdfpm", "zmsqxmu").forEach { app ->
            assertNotNull("$app 应切换角色", stub.reqs("/jwapp/sys/funauthapp/api/changeAppRole/$app/R1.do").lastOrNull())
        }
        assertTrue("应设置公共角色", stub.count("/jwapp/sys/jwpubapp/pub/setJwCommonAppRole.do") > 0)
    }

    /** 票据无效时不能把用户原来的登录态清掉 */
    @Test
    fun `票据交换失败不清原有会话`() {
        val st = store()
        val client = JwClient(st, stub.base)
        // 先正常登录一次
        client.setJarFromCookieHeader("GS_SESSIONID=S1; _WEU=" + "x".repeat(120))
        client.initSession()
        client.getStudentInfo()
        assertTrue(client.hasSession())
        val jarBefore = st.load()!!.jar

        // 桩服务对没有 ticket 的入口请求不给会话 cookie → 换票失败
        try {
            client.loginByTicket("")
            throw AssertionError("空票据应当失败")
        } catch (e: RuntimeException) {
            // 期望路径
        }
        assertTrue("失败后仍应保持登录", client.hasSession())
        assertEquals(jarBefore, st.load()!!.jar)
        assertEquals("潘腾凯", client.currentUser()?.optString("XM"))
    }

    // ---------- cookie 同名去重(_WEU 按 Path 轮换的坑) ----------

    /** 同名 cookie 只发最新那条: 否则服务端可能取到旧 _WEU 导致 403 */
    @Test
    fun `同名 cookie 只发最新值`() {
        val jar = CookieJar()
        jar.addSetCookie("_WEU=old-token; Path=/; Domain=jw.xmu.edu.cn")
        jar.addSetCookie("GS_SESSIONID=S1; Path=/; Domain=jw.xmu.edu.cn")
        // 服务端轮换: 同一个名字按另一个 Path 再写一次
        jar.addSetCookie("_WEU=new-token; Path=/jwapp; Domain=jw.xmu.edu.cn")

        val header = jar.header("jw.xmu.edu.cn")
        assertTrue("应发新令牌: $header", header.contains("_WEU=new-token"))
        assertFalse("不应再发旧令牌: $header", header.contains("_WEU=old-token"))
        assertTrue(header.contains("GS_SESSIONID=S1"))
        // 每个名字只出现一次
        assertEquals(1, Regex("_WEU=").findAll(header).count())
        assertEquals(1, Regex("GS_SESSIONID=").findAll(header).count())
    }

    /** 反例: 若按 name|domain|path 存了多条同名 cookie, 旧值绝不能被带上 */
    @Test
    fun `导入 cookie 丢失 Path 时也不会重复发送`() {
        val jar = ApiServer.parseCookieInput("_WEU=aaa; GS_SESSIONID=S1")
        jar.addSetCookie("_WEU=bbb; Path=/; Domain=jw.xmu.edu.cn")
        val header = jar.header("jw.xmu.edu.cn")
        assertTrue("应发后设置的值: $header", header.contains("_WEU=bbb"))
        assertEquals("同名只发一条: $header", 1, Regex("_WEU=").findAll(header).count())
    }

    /** 序列化/恢复后序号仍按数组顺序, 去重行为不变 */
    @Test
    fun `cookie 罐序列化恢复后仍去重`() {
        val jar = CookieJar()
        jar.addSetCookie("_WEU=first; Path=/; Domain=jw.xmu.edu.cn")
        jar.addSetCookie("_WEU=second; Path=/jwapp; Domain=jw.xmu.edu.cn")
        val restored = CookieJar.fromJson(jar.toJson())
        val header = restored.header("jw.xmu.edu.cn")
        assertTrue(header, header.contains("_WEU=second"))
        assertEquals(header, 1, Regex("_WEU=").findAll(header).count())
    }

    /** 给 WebView 同步的 cookie 同样按名去重 */
    @Test
    fun `同步给 WebView 的 cookie 也去重`() {
        val client = JwClient(store(), stub.base)
        client.setJarFromCookieHeader("GS_SESSIONID=S1; _WEU=aaa")
        client.initSession() // 预热会轮换出新的 _WEU
        val list = client.cookiesForHost("jw.xmu.edu.cn")
        assertEquals(1, list.count { it.name == "_WEU" })
        assertEquals(1, list.count { it.name == "GS_SESSIONID" })
    }

    // ---------- 会话管理 ----------

    /** 登录后: 预热激活 _WEU + 三个应用的角色设置 + 学生信息 + 持久化 */
    @Test
    fun `登录后预热与角色设置`() {
        val st = store()
        val client = JwClient(st, stub.base)
        client.loginByTicket("ST-abc")

        // 中文界面 cookie 必须种下
        val warmup = stub.reqs("/jwapp/sys/jwai/index.do").last()
        assertTrue("应带中文界面 cookie", warmup.cookie.contains("EMAP_LANG=zh"))
        assertTrue("应带中文语言 cookie", warmup.cookie.contains("amp.locale=zh_CN"))

        val user = client.getStudentInfo()
        assertEquals(EmapStub.STUDENT_XH, user.optString("XH"))
        assertEquals("潘腾凯", user.optString("XM"))
        assertEquals("软件工程", user.optString("ZYDM"))
        assertTrue("登录态应已持久化", st.load()!!.jar.contains("GS_SESSIONID"))
    }

    /** _WEU 轮换: 每个请求都必须带上上一个响应下发的最新值 */
    @Test
    fun `WEU 令牌轮换`() {
        val st = store()
        val client = JwClient(st, stub.base)
        client.setJarFromCookieHeader("GS_SESSIONID=S1")
        client.initSession()
        val latest = CookieJar.fromJson(st.load()!!.jar).all().first { it.name == "_WEU" }.value

        client.getStudentInfo()
        assertTrue(
            "应使用轮换后的令牌",
            stub.req("/jwapp/sys/jdfpm/modules/jdjs/cxxsjbxx.do").cookie.contains("_WEU=$latest"),
        )
    }

    /** 403(_WEU 未激活)时先预热再重试, 最终成功 */
    @Test
    fun `403 预热重试`() {
        val client = JwClient(store(), stub.base)
        client.setJarFromCookieHeader("GS_SESSIONID=S1") // 只有会话 cookie, _WEU 未激活
        val warmupsBefore = stub.count("/jwapp/sys/jwai/*default/index.do")
        client.getStudentInfo()
        assertTrue("403 后应重新预热", stub.count("/jwapp/sys/jwai/*default/index.do") > warmupsBefore)
        assertEquals(EmapStub.STUDENT_XH, client.currentUser()?.optString("XH"))
    }

    /** 会话失效(401 / 302 到 CAS)要能被识别, 而不是当成普通错误 */
    @Test
    fun `会话失效识别`() {
        val client = JwClient(store(), stub.base)
        try {
            client.getStudentInfo()
            throw AssertionError("应当抛出会话失效")
        } catch (e: SessionExpired) {
            assertEquals("会话已失效, 请重新登录", e.message)
        }
    }

    /** 退出登录要清掉持久化 */
    @Test
    fun `退出登录`() {
        val st = store()
        val client = JwClient(st, stub.base)
        client.setJarFromCookieHeader("GS_SESSIONID=S1")
        client.initSession()
        client.getStudentInfo()
        assertTrue(client.hasSession())
        client.clearSession()
        assertFalse(client.hasSession())
        assertNull(st.load())
        assertNull(client.currentUser())
    }

    /** 会话恢复: 与桌面版 .session.json 同格式的 jar 字符串 */
    @Test
    fun `会话恢复`() {
        val st = store()
        val c1 = JwClient(st, stub.base)
        c1.setJarFromCookieHeader("GS_SESSIONID=S1; _WEU=" + "x".repeat(120))
        c1.initSession()
        c1.getStudentInfo()
        val jarJson = st.load()!!.jar

        val c2 = JwClient(MemStore(), stub.base)
        c2.restoreSession(jarJson, EmapStub.STUDENT_XH, st.load()!!.user)
        assertTrue(c2.hasSession())
        assertEquals(EmapStub.STUDENT_XH, c2.currentXh())
        assertEquals("潘腾凯", c2.currentUser()?.optString("XM"))
    }

    // ---------- 学业完成查询 ----------

    @Test
    fun `学业完成查询聚合`() {
        val ov = activatedClient().getXywccxOverview()

        assertTrue(ov.optBoolean("hasPlan"))
        val plan = ov.getJSONObject("plan")
        assertEquals("软件工程培养方案", plan.optString("PYFAMC"))
        assertEquals(96.5, plan.optDouble("WCXF"), 0.001)
        assertEquals(160.0, plan.optDouble("YQXF"), 0.001)
        assertEquals(12.0, plan.optDouble("XKXF"), 0.001)
        assertEquals(2, ov.getJSONArray("groups").length())
        assertEquals(2, ov.getJSONArray("courses").length())
        assertEquals(63.5, ov.getJSONObject("missing").optDouble("WXF"), 0.001)

        // 与 server/jw.js 完全一致的参数
        assertEquals(0, stub.req("/jwapp/sys/xywccx/modules/xywccx/cxxsscfa.do").params.size)
        val kz = stub.req("/jwapp/sys/xywccx/modules/xywccx/cxscfakz.do").params
        assertEquals(EmapStub.STUDENT_XH, kz["XH"])
        assertEquals("PLAN-1", kz["PYFADM"])
        assertEquals("-", kz["BYNJDM"])
        assertEquals("-", kz["PCDM"])
        assertEquals("04", kz["SCLBDM"])
        assertTrue(kz["XNXQDM"]!!.matches(Regex("\\d{4}-\\d{4}-[12]")))
        val kc = stub.req("/jwapp/sys/xywccx/modules/xywccx/cxscfakzkchxkqkx.do").params
        assertEquals("+SFTG", kc["*order"])
        assertEquals("1000", kc["pageSize"])
        assertEquals("1", kc["pageNumber"])
    }

    // ---------- GPA 排名 ----------

    @Test
    fun `GPA 排名接口`() {
        val client = activatedClient()

        val info = client.getJdfpmInfo()
        assertEquals(EmapStub.STUDENT_XH, info.getJSONObject("user").optString("XH"))
        assertEquals(1, info.getJSONArray("ranges").length())
        assertEquals(1, info.getJSONArray("records").length())

        val detail = client.getJdfpmDetail("W-1")
        assertEquals(1, detail.size)
        val q = stub.req("/jwapp/sys/jdfpm/modules/jdjs/cxzdxscjxq.do").params
        assertEquals("1", q["SFZX"])
        assertEquals("W-1", q["CJFWWID"])
        assertTrue(q["querySetting"]!!.contains("\"name\":\"CJFWWID\""))
        assertTrue(q["querySetting"]!!.contains("\"builder\":\"equal\""))
        assertEquals("-XNXQDM,+KCH", q["*order"])

        val html = client.printJdfpm("S-1", "2", "1", "1")
        assertTrue(html.contains("绩点证明"))
        val p = stub.req("/jwapp/sys/jdfpm/api/jdjs/printZm.do", "GET").params
        assertEquals("S-1", p["wid"])
        assertEquals("2", p["type"])
        assertEquals("1", p["DYNR"])
        assertEquals("1", p["SFXYPM"])
    }

    // ---------- 证明文件申请 ----------

    @Test
    fun `证明文件申请接口`() {
        val client = activatedClient()

        assertEquals(1, client.getZmApplications().size)
        val listReq = stub.req("/jwapp/sys/zmsqxmu/modules/xszmsq/xszmsqcx.do")
        assertEquals("200", listReq.params["pageSize"])
        assertEquals("-SQSJ", listReq.params["*order"])
        assertEquals(1, client.getZmAvailable().size)

        val save = client.applyZm(listOf("Z-9"), mapOf("SFDZZJDY" to "0", "SQDYS" to 2, "SFSYSJM" to "1")) as JSONObject
        assertTrue(save.optBoolean("success"))
        val saveReq = stub.req("/jwapp/sys/zmsqxmu/xszmsq/saveZmsq.do").params
        assertEquals("Z-9", saveReq["zmid"])
        assertTrue(saveReq["paramStr"]!!.contains("\"SQDYS\":\"2\""))

        val dl = client.downloadZm("Z-1")
        assertTrue(dl.contentType.contains("application/pdf"))
        assertTrue(String(dl.data).startsWith("%PDF"))
        assertEquals("zhengming.pdf", Regex("filename=\"([^\"]+)\"").find(dl.disposition)!!.groupValues[1])
        assertEquals("Z-1", stub.req("/jwapp/sys/zmsqxmu/xszmsq/downZm.do").params["wid"])

        val mail = client.emailZm(listOf("Z-1"), "a@stu.xmu.edu.cn") as JSONObject
        assertTrue(mail.optBoolean("success"))
        val mailReq = stub.req("/jwapp/sys/zmsqxmu/xszmsq/sendMail.do").params
        assertEquals("Z-1", mailReq["wids"])
        assertEquals("a@stu.xmu.edu.cn", mailReq["mail"])
    }

    /** Referer 校验: EMAP 对码表/保存类端点要求 Referer 为应用页 */
    @Test
    fun `证明接口的 Referer 必须是应用页`() {
        val client = activatedClient()
        client.getZmApplications()
        client.getZmAvailable()
        client.applyZm(listOf("Z-9"), mapOf("SQDYS" to 1))
        client.downloadZm("Z-1")
        client.emailZm(listOf("Z-1"), "a@stu.xmu.edu.cn")

        val appPage = "${stub.base}/jwapp/sys/zmsqxmu/*default/index.do"
        listOf(
            "/jwapp/sys/zmsqxmu/modules/xszmsq/xszmsqcx.do",
            "/jwapp/sys/zmsqxmu/modules/xszmsq/dsqzmcx.do",
            "/jwapp/sys/zmsqxmu/xszmsq/saveZmsq.do",
            "/jwapp/sys/zmsqxmu/xszmsq/downZm.do",
            "/jwapp/sys/zmsqxmu/xszmsq/sendMail.do",
        ).forEach { path ->
            assertEquals("$path 的 Referer 应为应用页", appPage, stub.req(path).referer)
        }
    }

    // ---------- 真实网络(不需要登录态) ----------

    /** 无会话时业务接口要被识别为失效, 而不是崩掉 */
    @Test
    fun `真实接口无会话时识别为失效`() {
        val client = JwClient(MemStore()) // 默认指向真实教务系统
        try {
            client.getStudentInfo()
            throw AssertionError("无会话时应当失败")
        } catch (e: SessionExpired) {
            // 期望路径
        } catch (e: NeedWarmup) {
            // 也可能先 403, 同属预期内的失效表现
        }
    }

    /** analysis/ 里的真实抓包样本要符合 datas.<name>.rows 结构 */
    @Test
    fun `真实样本的 JSON 结构可解析`() {
        val xywccx = File("../../analysis/xywccx-data.json").takeIf { it.exists() } ?: return
        val root = JSONObject(xywccx.readText(Charsets.UTF_8))
        listOf("scfa", "scfakz", "kc", "fawxfms").forEach { key ->
            assertTrue("$key 应有 datas", root.getJSONObject(key).getJSONObject("datas").length() > 0)
        }
        val jdfpm = File("../../analysis/jdfpm-data.json").takeIf { it.exists() } ?: return
        val root2 = JSONObject(jdfpm.readText(Charsets.UTF_8))
        assertTrue(root2.getJSONObject("cjfw").getJSONObject("datas").length() > 0)
        assertTrue(root2.getJSONObject("cjmx").getJSONObject("datas").length() > 0)
    }

    /** 真实网络的 UA 与桌面版一致 */
    @Test
    fun `UA 与桌面版一致`() {
        assertTrue(JwClient.UA.startsWith("Mozilla/5.0"))
        assertTrue(JwClient.UA.contains("Chrome/"))
    }

    /** HTTP 客户端能正常发请求并解析响应头 */
    @Test
    fun `HTTP 客户端基本能力`() {
        val r = Http.request("${stub.base}/jwapp/sys/jwai/index.do", "GET")
        assertEquals(200, r.status)
        assertTrue(r.text.contains("jwai"))
        assertTrue(r.headers("Set-Cookie").isNotEmpty())
    }
}
