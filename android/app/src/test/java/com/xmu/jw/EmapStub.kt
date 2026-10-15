package com.xmu.jw

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections

private const val CR: Byte = 13
private const val LF: Byte = 10

/** 桩服务记录到的一条请求 */
data class StubReq(
    val method: String,
    val path: String,
    val params: Map<String, String>,
    val cookie: String,
    val referer: String,
)

/** 桩服务要返回的响应(用列表而不是 Map, 因为 Set-Cookie 可以有多条) */
class StubResp(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: List<Pair<String, String>> = emptyList(),
)

/**
 * 模拟金智 EMAP + CAS 的行为, 只监听 127.0.0.1 随机端口, 不联网。
 * 用最朴素的 ServerSocket 手写 HTTP/1.1(不依赖 jdk.httpserver, 任何 JDK 都能跑)。
 *
 * 复刻了实测到的关键机制:
 *  - 登录成功后 302 带 ticket, 入口页种下 GS_SESSIONID 与"未激活"的 _WEU
 *  - 预热端点(/jwapp/sys/jwai/...)把 _WEU 升级为完整态, 且每次响应都轮换
 *  - 业务接口在 _WEU 未激活时返回 403, 没有会话 cookie 时返回 401
 *  - 应用角色菜单 / changeAppRole / setJwCommonAppRole
 */
class EmapStub {

    val base: String
    private val server: ServerSocket
    private val log: MutableList<StubReq> = Collections.synchronizedList(mutableListOf<StubReq>())
    private var weuCounter = 0

    /** 全部请求记录 */
    val requests: List<StubReq> get() = synchronized(log) { log.toList() }

    init {
        server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        base = "http://127.0.0.1:${server.localPort}"
        Thread({ acceptLoop() }, "emap-stub").apply { isDaemon = true }.start()
    }

    fun stop() {
        try {
            server.close()
        } catch (_: Exception) {
        }
    }

    fun clear() = synchronized(log) { log.clear() }

    fun req(path: String, method: String = "POST") = requests.last { it.path == path && it.method == method }

    fun reqs(path: String) = requests.filter { it.path == path }

    fun count(path: String) = requests.count { it.path == path }

    // ---------- HTTP 服务 ----------

    private fun acceptLoop() {
        while (!server.isClosed) {
            try {
                val sock = server.accept()
                Thread({ handle(sock) }, "emap-stub-conn").start()
            } catch (_: Exception) {
                return
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                val input = s.getInputStream()
                val parsed = readRequest(input) ?: return
                val path = parsed.target.substringBefore('?')
                val query = parsed.target.substringAfter('?', "")

                log.add(
                    StubReq(
                        method = parsed.method,
                        path = path,
                        params = parseParams(query, parsed.body),
                        cookie = parsed.headers["cookie"] ?: "",
                        referer = parsed.headers["referer"] ?: "",
                    ),
                )
                write(s.getOutputStream(), route(path, parsed.method, parsed.headers["cookie"] ?: ""))
            }
        } catch (_: Exception) {
            // 客户端提前断开等情况直接忽略
        }
    }

    /**
     * 手工解析 HTTP 请求: 按字节读到 \r\n\r\n 为止, 余下的正好是请求体。
     * (不能用 BufferedReader: 它会预读把 body 吃进自己的缓冲区, 之后按 Content-Length 再读就会永远阻塞)
     */
    private fun readRequest(input: InputStream): StubReq4? {
        val head = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            val n = head.size()
            if (n >= 4) {
                val arr = head.toByteArray()
                if (arr[n - 4] == CR && arr[n - 3] == LF && arr[n - 2] == CR && arr[n - 1] == LF) break
            }
        }
        val lines = String(head.toByteArray(), Charsets.UTF_8).split("\r\n")
        val requestLine = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(bodyBytes, read, len - read)
            if (n < 0) break
            read += n
        }
        return StubReq4(parts[0], parts[1], headers, String(bodyBytes, 0, read, Charsets.UTF_8))
    }

    private data class StubReq4(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun write(out: java.io.OutputStream, resp: StubResp) {
        val head = StringBuilder("HTTP/1.1 ${resp.status} ${reason(resp.status)}\r\n")
        head.append("Content-Type: ${resp.contentType}\r\n")
        resp.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("Content-Length: ${resp.body.size}\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(resp.body)
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        302 -> "Found"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun parseParams(query: String, body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun add(pair: String) {
            if (pair.isEmpty()) return
            val i = pair.indexOf('=')
            val k = if (i < 0) pair else pair.substring(0, i)
            val v = if (i < 0) "" else pair.substring(i + 1)
            out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        query.split("&").forEach(::add)
        body.split("&").forEach(::add)
        return out
    }

    // ---------- 路由 ----------

    private fun route(path: String, method: String, cookie: String): StubResp {
        // 每响应轮换一次 _WEU: 短 token = 未激活态, 长 token = 已激活态
        fun rotatedWeu(activated: Boolean): String {
            weuCounter++
            return "_WEU=w" + weuCounter.toString().padStart(if (activated) 120 else 110, 'x') + "; Path=/"
        }

        fun resp(
            body: String,
            status: Int = 200,
            contentType: String = "application/json;charset=UTF-8",
            cookies: List<String> = emptyList(),
            location: String? = null,
        ): StubResp {
            val headers = mutableListOf<Pair<String, String>>()
            cookies.forEach { headers.add("Set-Cookie" to it) }
            location?.let { headers.add("Location" to it) }
            return StubResp(status, contentType, body.toByteArray(Charsets.UTF_8), headers)
        }

        val weuLen = Regex("_WEU=([^;]*)").find(cookie)?.groupValues?.get(1)?.length ?: 0

        return when {
            // ---------- 教务入口: 带 ticket 才种下会话 cookie(此时 _WEU 还是未激活态) ----------
            path == "/jwapp/sys/emapangular/index.do" -> {
                val ticket = requests.last().params["ticket"]
                if (ticket.isNullOrEmpty()) {
                    // 票据无效: 不种任何 cookie, 客户端应判定换票失败
                    resp("<html>bad ticket</html>", contentType = "text/html;charset=UTF-8")
                } else {
                    resp(
                        "<html>portal</html>",
                        contentType = "text/html;charset=UTF-8",
                        cookies = listOf("GS_SESSIONID=S1; Path=/; HttpOnly", rotatedWeu(activated = false)),
                    )
                }
            }

            // ---------- 预热(把 _WEU 升级为完整态) ----------
            path == "/jwapp/sys/jwai/*default/index.do" -> resp(
                "<html>jwai</html>",
                contentType = "text/html;charset=UTF-8",
                cookies = listOf(rotatedWeu(activated = true)),
            )

            path == "/jwapp/sys/jwai/index.do" -> resp(
                "<html>jwai</html>",
                contentType = "text/html;charset=UTF-8",
                cookies = listOf(rotatedWeu(activated = true)),
            )

            path == "/jwapp/sys/jwai/api/user/getCurrentUser.do" ->
                resp("""{"datas":{"getCurrentUser":{"rows":[{"XH":"$STUDENT_XH"}]}}}""")

            // ---------- 应用角色 ----------
            path.matches(Regex("/jwapp/sys/(xywccx|jdfpm|zmsqxmu)/\\*default/index\\.do")) ->
                resp("""<html><script>var cfg={"appId":"1001"};</script></html>""", contentType = "text/html;charset=UTF-8")

            path.matches(Regex("/jwapp/sys/funauthapp/api/getAppConfig/.+\\.do")) ->
                resp("""{"HEADER":{"dropMenu":[{"id":"R1","text":"本科生","active":true},{"id":"R2","text":"研究生","active":false}]}}""")

            path.matches(Regex("/jwapp/sys/funauthapp/api/changeAppRole/.+\\.do")) -> resp("""{"success":true}""")

            path == "/jwapp/sys/jwpubapp/pub/setJwCommonAppRole.do" -> resp("""{"success":true}""")

            // ---------- 没有会话 cookie: 401(等价于被重定向到 CAS) ----------
            !cookie.contains("GS_SESSIONID") -> resp("", status = 401)

            // ---------- _WEU 未激活: 业务接口一律 403, 客户端必须预热后重试 ----------
            path.startsWith("/jwapp/sys/") && weuLen < 120 -> resp("", status = 403)

            // ---------- 学业完成查询 ----------
            path == "/jwapp/sys/jdfpm/modules/jdjs/cxxsjbxx.do" ->
                resp("""{"datas":{"cxxsjbxx":{"rows":[$STUDENT_ROW]}}}""")

            path == "/jwapp/sys/xywccx/modules/xywccx/cxxsscfa.do" ->
                resp("""{"datas":{"cxxsscfa":{"rows":[{"PYFADM":"PLAN-1","PYFAMC":"软件工程培养方案","WCXF":"96.5","YQXF":"160","XKXF":"12","CZSJ":"2023-09-01"}]}}}""")

            path == "/jwapp/sys/xywccx/modules/xywccx/cxscfakz.do" ->
                resp("""{"datas":{"cxscfakz":{"rows":[{"KZMC":"通识必修","XQXF":"40","WCXF":"38"},{"KZMC":"专业必修","XQXF":"80","WCXF":"58.5"}]}}}""")

            path == "/jwapp/sys/xywccx/modules/xywccx/cxscfakzkchxkqkx.do" ->
                resp("""{"datas":{"cxscfakzkchxkqkx":{"rows":[{"KCH":"001","KCMC":"高等数学","XF":"5","ZCJ":"92","SFTG":"1"},{"KCH":"002","KCMC":"线性代数","XF":"3","ZCJ":"88","SFTG":"1"}]}}}""")

            path == "/jwapp/sys/xywccx/modules/xywccx/cxfawxfms.do" ->
                resp("""{"datas":{"cxfawxfms":{"rows":[{"WXF":"63.5"}]}}}""")

            // ---------- GPA 排名 ----------
            path == "/jwapp/sys/jdfpm/modules/jdjs/cxxskxcjfw.do" ->
                resp("""{"datas":{"cxxskxcjfw":{"rows":[{"WID":"W-1","XSMC":"全部成绩"}]}}}""")

            path == "/jwapp/sys/jdfpm/api/jdjs/getJdjssq.do" ->
                resp("""{"datas":{"getJdjssq":[{"WID":"S-1","CJFWWID":"W-1"}]}}""")

            path == "/jwapp/sys/jdfpm/api/jdjs/addJdjssq.do" -> resp("""{"success":true}""")

            path == "/jwapp/sys/jdfpm/modules/jdjs/cxzdxscjxq.do" ->
                resp("""{"datas":{"cxzdxscjxq":{"rows":[{"KCH":"001","KCMC":"高等数学","XF":"5","ZCJ":"92","JD":"4.5"}]}}}""")

            path == "/jwapp/sys/jdfpm/api/jdjs/printZm.do" ->
                resp("<html><body>绩点证明</body></html>", contentType = "text/html;charset=UTF-8")

            // ---------- 证明文件申请 ----------
            path == "/jwapp/sys/zmsqxmu/modules/xszmsq/xszmsqcx.do" ->
                resp("""{"datas":{"xszmsqcx":{"rows":[{"WID":"Z-1","ZMWJMC":"在学证明","WJYXZT":"1"}]}}}""")

            path == "/jwapp/sys/zmsqxmu/modules/xszmsq/dsqzmcx.do" ->
                resp("""{"datas":{"dsqzmcx":{"rows":[{"WID":"Z-9","ZMWJMC":"成绩单","WJYXTS":"7"}]}}}""")

            path == "/jwapp/sys/zmsqxmu/xszmsq/saveZmsq.do" ->
                resp("""{"success":true,"msg":"申请成功"}""")

            path == "/jwapp/sys/zmsqxmu/xszmsq/downZm.do" ->
                StubResp(
                    200,
                    "application/pdf",
                    "%PDF-1.4 stub".toByteArray(Charsets.UTF_8),
                    listOf("Content-Disposition" to "attachment; filename=\"zhengming.pdf\""),
                )

            path == "/jwapp/sys/zmsqxmu/xszmsq/sendMail.do" -> resp("""{"success":true,"msg":"已发送"}""")

            else -> resp("""{"error":"no route: $path"}""", status = 404)
        }
    }

    companion object {
        const val STUDENT_XH = "2023123456"
        const val STUDENT_ROW =
            """{"XH":"2023123456","XM":"潘腾凯","XZNJ_DISPLAY":"2023级","YXDM_DISPLAY":"信息学院","ZYDM_DISPLAY":"软件工程","BJDM_DISPLAY":"2023软件工程(主修)","XZ":"4","XJZTDM_DISPLAY":"正常"}"""
    }
}
