package com.xmu.jw

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.xmu.jw.core.L
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.Base64

/**
 * 应用壳: 一个全屏 WebView 加载本地前端(assets/web),
 * 拦截 /api/ 请求交给内置的 ApiServer(等价于桌面版的 node server/index.js),
 * 其余路径当作静态资源。所以手机上不需要 node, 也不需要连电脑。
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.enabled = BuildConfig.DEBUG

        ApiServer.init(SharedPrefsSessionStore(this), context = this)
        DemoData.init(this)
        seedDebugSessionIfNeeded()
        requestStoragePermissionIfNeeded()

        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 不开 wideViewPort: 否则 WebView 会伪造 980px 视口, 响应式布局就一直走桌面分支
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            // 固定缩放: 数据表格按设计稿排版, 不跟随系统字体缩放
            settings.textZoom = 100
            // 与桌面版一致的身份, 学校个别页面按 UA 分流
            settings.userAgentString = com.xmu.jw.core.JwClient.UA
            setBackgroundColor(android.graphics.Color.parseColor("#12151C"))
            isVerticalScrollBarEnabled = false
            webViewClient = webClient
            webChromeClient = chromeClient
            addJavascriptInterface(JsBridge(this@MainActivity, this), "JWAndroid")
            setDownloadListener { url, _, _, _, _ -> onDownloadStart(url) }
        }
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        setContentView(web)
        web.loadUrl(Assets.ORIGIN + "/index.html")
    }

    // ---------- 请求拦截 ----------

    /**
     * 用在应用内 WebView 里完成官方登录后带回的票据换成本地会话。
     * 成功/失败都回到应用壳(前端再按 /api/session 决定显示登录层还是主界面)。
     */
    private fun exchangeTicket(ticket: String) {
        Thread {
            val ok = try {
                val user = ApiServer.clientInstance().loginByTicket(ticket)
                runOnUiThread { toast("登录成功: ${user.optString("XM")}") }
                true
            } catch (e: Throwable) {
                L.e("login", "票据交换失败", e)
                runOnUiThread { toast(e.message ?: "票据无效或已过期, 请重新登录") }
                false
            }
            L.i("login", "票据交换${if (ok) "成功" else "失败"}")
            runOnUiThread { web.loadUrl(Assets.ORIGIN + "/index.html") }
        }.start()
    }

    /** 前端 console/JS 错误打到 logcat(debug 构建), 排错全靠它 */
    private val chromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) {
                L.d("console", "${m.messageLevel()}: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
            }
            return true
        }
    }

    private val webClient = object : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            return try {
                // 只拦截本地源; 学校打印页等外部地址放行给 WebView 正常加载(会话已同步进 CookieManager)
                if (request.url.host != Assets.ORIGIN_HOST) return null
                val path = request.url.path ?: return null
                if (path.startsWith("/api/")) {
                    val body = readBody(request)
                    val query = parseQuery(request.url.encodedQuery)
                    val resp = ApiServer.handle(request.method, path, query, body)
                    toWebResourceResponse(resp)
                } else {
                    Assets.serve(this@MainActivity, path)
                }
            } catch (e: Throwable) {
                L.e("webview", "拦截失败: ${request.url}", e)
                jsonResponse(500, """{"error":"${escapeJson(e.message ?: "内部错误")}"}""")
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            // 官方登录回调: 登录成功后学校静态门户会带 ?ticket=ST-... 跳回来,
            // 必须在教务系统消费掉票据之前拿走它, 并阻止这次跳转
            if (url.host == "jw.xmu.edu.cn") {
                val ticket = ApiServer.extractTicket(url.toString())
                if (ticket.isNotEmpty()) {
                    exchangeTicket(ticket)
                    return true
                }
            }
            return false
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (url.startsWith(Assets.ORIGIN)) {
                // 安卓端没有本机 Chrome 可读, 隐藏「Chrome 导入」登录方式
                view.evaluateJavascript(HIDE_CHROME_TAB, null)
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            if (request.isForMainFrame) {
                Toast.makeText(this@MainActivity, "页面加载失败, 请检查网络", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * POST 请求体: WebView 不在 shouldInterceptRequest 里暴露 postData,
     * 所以由注入到 index.html 的 fetch 包装把请求体 base64 后塞进 X-JW-Body 头。
     */
    private fun readBody(request: WebResourceRequest): String {
        val headers = request.requestHeaders ?: return ""
        val raw = headers.entries
            .firstOrNull { it.key.equals("X-JW-Body", ignoreCase = true) }
            ?.value
            ?: return ""
        return try {
            String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
        } catch (e: Throwable) {
            L.w("webview", "解码 X-JW-Body 失败: ${e.message}")
            ""
        }
    }

    private fun parseQuery(encodedQuery: String?): Map<String, String> {
        if (encodedQuery.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in encodedQuery.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val k = if (idx < 0) pair else pair.substring(0, idx)
            val v = if (idx < 0) "" else pair.substring(idx + 1)
            out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return out
    }

    private fun toWebResourceResponse(r: ApiServer.ApiResponse): WebResourceResponse {
        val headers = mutableMapOf("Cache-Control" to "no-store")
        r.disposition?.let { headers["Content-Disposition"] = it }
        return WebResourceResponse(
            mimeOnly(r.contentType),
            "utf-8",
            r.status,
            reasonFor(r.status),
            headers,
            ByteArrayInputStream(r.body),
        )
    }

    private fun jsonResponse(status: Int, json: String): WebResourceResponse =
        WebResourceResponse(
            "application/json",
            "utf-8",
            status,
            reasonFor(status),
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
        )

    // ---------- 原生下载(blob 下载在 WebView 里不可用的兜底) ----------

    private fun onDownloadStart(url: String) {
        Thread {
            try {
                val parsed = android.net.Uri.parse(url)
                val wid = parsed.getQueryParameter("wid")
                if (wid.isNullOrEmpty()) {
                    toast("无法处理的下载地址")
                    return@Thread
                }
                val f = ApiServer.download(wid)
                if (f.isJson) {
                    val j = org.json.JSONObject(String(f.data, Charsets.UTF_8))
                    toast(j.optString("msg").ifEmpty { j.optString("message") }.ifEmpty { "下载失败(证明可能已过期)" })
                    return@Thread
                }
                val r = Downloads.save(this, f.name.ifEmpty { "证明文件.pdf" }, f.data)
                toast("已保存到 ${r.displayPath}")
            } catch (e: Throwable) {
                L.e("download", "下载失败", e)
                toast(e.message ?: "下载失败")
            }
        }.start()
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    // ---------- 其他 ----------

    /** 调试用: 把电脑上桌面版的登录态(server/.session.json)拷进来即可免登录调试 */
    private fun seedDebugSessionIfNeeded() {
        if (!BuildConfig.DEBUG) return
        if (ApiServer.clientInstance().hasSession()) return
        try {
            val f = java.io.File(getExternalFilesDir(null) ?: return, "jw-session.json")
            if (!f.exists()) return
            val raw = org.json.JSONObject(f.readText())
            val jarJson = raw.optString("jar")
            if (jarJson.isEmpty()) return
            ApiServer.clientInstance().restoreSession(
                jarJson = jarJson,
                xh = raw.optString("xh").ifEmpty { null },
                userJson = raw.optString("user").ifEmpty { null },
            )
            L.i("session", "已从 ${f.name} 导入调试登录态")
        } catch (e: Throwable) {
            L.w("session", "导入调试登录态失败: ${e.message}")
        }
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val granted = checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    @Deprecated("用 OnBackPressedDispatcher 需要额外依赖, 个人自用保持简单")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val HIDE_CHROME_TAB =
            "(function(){var b=document.querySelector('#login-tabs button[data-tab=\"chrome\"]');" +
                "if(b)b.remove();})()"
    }
}

/** WebResourceResponse 的 mimeType 不能带 charset 参数 */
private fun mimeOnly(contentType: String): String =
    contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }

private fun reasonFor(status: Int): String = when (status) {
    200 -> "OK"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    500 -> "Internal Server Error"
    else -> "OK"
}

private fun escapeJson(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
