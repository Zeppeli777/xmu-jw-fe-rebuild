package com.xmu.jw

import android.content.Context
import android.webkit.WebResourceResponse
import com.xmu.jw.core.L
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * 静态资源服务: 把 WebView 的请求映射到 assets/web/。
 * 前端是原生 HTML/CSS/JS(无构建), 由 Gradle 的 syncWeb 任务从 ../web 同步而来。
 */
object Assets {

    /** 域名占位: 必须是 https 源, 否则 ES module / fetch / localStorage 都会被浏览器策略拦掉 */
    const val ORIGIN = "https://appassets.androidplatform.net"

    /** ORIGIN 的 host 部分(用于判断请求是否该由本地拦截器处理) */
    val ORIGIN_HOST: String get() = ORIGIN.removePrefix("https://")

    private val MIME = mapOf(
        ".html" to "text/html",
        ".css" to "text/css",
        ".js" to "text/javascript",
        ".mjs" to "text/javascript",
        ".json" to "application/json",
        ".svg" to "image/svg+xml",
        ".png" to "image/png",
        ".jpg" to "image/jpeg",
        ".jpeg" to "image/jpeg",
        ".ico" to "image/x-icon",
        ".woff" to "font/woff",
        ".woff2" to "font/woff2",
        ".ttf" to "font/ttf",
        ".map" to "application/json",
        ".txt" to "text/plain",
    )

    fun serve(context: Context, path: String): WebResourceResponse? {
        var rel = path.trimStart('/')
        if (rel.isEmpty()) rel = "index.html"
        // 防目录穿越
        if (rel.contains("..") || rel.startsWith("/")) rel = "index.html"

        var assetPath = "web/$rel"
        if (!assetExists(context, assetPath)) {
            // 与桌面版一致: 未命中时回退到应用壳(前端是 hash 路由)
            assetPath = "web/index.html"
        }

        return try {
            val raw = context.assets.open(assetPath).use { it.readBytes() }
            val ext = File(assetPath).extension.lowercase().let { if (it.isEmpty()) "" else ".$it" }
            val mime = MIME[ext] ?: "application/octet-stream"
            val data = if (assetPath == "web/index.html") injectBridge(raw) else raw
            WebResourceResponse(
                mime,
                "utf-8",
                200,
                "OK",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(data),
            )
        } catch (e: IOException) {
            L.w("assets", "读取失败: $assetPath", e)
            null
        }
    }

    private fun assetExists(context: Context, assetPath: String): Boolean = try {
        context.assets.open(assetPath).use { true }
    } catch (_: IOException) {
        false
    }

    /**
     * 给 index.html 注入一小段 fetch 包装:
     * POST /api/ 请求时把请求体额外放进 X-JW-Body 头。
     * 极少数 WebView 版本在 shouldInterceptRequest 里拿不到 postData,
     * 有了这个头就能兜底, 不依赖具体系统版本。
     */
    private fun injectBridge(html: ByteArray): ByteArray {
        val text = String(html, Charsets.UTF_8)
        if (text.contains("__jwNativeBridge")) return html
        val script = """
            |<script>
            |(function(){if(window.__jwNativeBridge)return;window.__jwNativeBridge=1;
            |var f=window.fetch;if(!f)return;
            |window.fetch=function(input,init){try{
            |var u=typeof input==='string'?input:(input&&input.url)||'';
            |var b=init&&init.body;
            |if(b!=null&&typeof b==='string'&&u.indexOf('/api/')===0){
            |var h=new Headers((init&&init.headers)||{});
            |if(!h.has('X-JW-Body'))h.set('X-JW-Body',btoa(unescape(encodeURIComponent(b))));
            |init=Object.assign({},init,{headers:h});}
            |}catch(e){}return f.apply(this,arguments);};})();
            |</script>
        """.trimMargin()
        val idx = text.lastIndexOf("</body>")
        val out = if (idx >= 0) {
            text.substring(0, idx) + script + "\n" + text.substring(idx)
        } else {
            text + script
        }
        return out.toByteArray(Charsets.UTF_8)
    }
}
