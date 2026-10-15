package com.xmu.jw

import android.app.Activity
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.xmu.jw.core.L
import com.xmu.jw.core.SessionExpired
import org.json.JSONObject

/**
 * 前端 ↔ 原生桥(window.JWAndroid)。
 * 只做两件 WebView 里做不了或做不好的事:
 *  1. saveFile  - 证明 PDF 保存到系统下载目录(WebView 不支持 blob 下载)
 *  2. openUrl   - 打开学校打印页(需要把会话 cookie 同步进 WebView 再直接加载)
 */
class JsBridge(private val activity: Activity, private val web: WebView) {

    /** 前端调用: window.JWAndroid.saveFile("/api/zmsq/download?wid=...", "在学证明.pdf") */
    @JavascriptInterface
    fun saveFile(url: String, filename: String): String {
        return try {
            val uri = Uri.parse(Assets.ORIGIN + url)
            val wid = uri.getQueryParameter("wid")
            if (wid.isNullOrEmpty()) {
                return err("缺少 wid")
            }
            val f = ApiServer.download(wid)
            if (f.isJson) {
                val j = JSONObject(String(f.data, Charsets.UTF_8))
                val msg = j.optString("msg").ifEmpty { j.optString("message") }
                    .ifEmpty { "下载失败(证明可能已过期)" }
                err(msg)
            } else {
                val r = Downloads.save(activity, filename, f.data)
                JSONObject()
                    .put("ok", true)
                    .put("path", r.displayPath)
                    .put("name", f.name.ifEmpty { filename })
                    .toString()
            }
        } catch (e: SessionExpired) {
            JSONObject()
                .put("ok", false)
                .put("message", "会话已失效, 请重新登录")
                .put("reload", true)
                .toString()
        } catch (e: Throwable) {
            L.e("bridge", "saveFile 失败", e)
            err(e.message ?: "下载失败")
        }
    }

    /** 前端调用: window.JWAndroid.openCasLogin(CAS 登录页地址) —— 官方登录走应用内 WebView */
    @JavascriptInterface
    fun openCasLogin(url: String) {
        activity.runOnUiThread {
            try {
                web.loadUrl(url)
            } catch (e: Throwable) {
                L.e("bridge", "打开登录页失败", e)
            }
        }
    }

    /** 前端调用: window.JWAndroid.openUrl("/api/jdfpm/print?wid=...") */
    @JavascriptInterface
    fun openUrl(path: String) {
        activity.runOnUiThread {
            try {
                val uri = Uri.parse(Assets.ORIGIN + path)
                val upstream = ApiServer.resolveUpstream(uri.path ?: "", uri.query ?: "")
                if (upstream == null) {
                    L.w("bridge", "无法解析的地址: $path")
                    return@runOnUiThread
                }
                syncCookies()
                web.loadUrl(upstream)
            } catch (e: Throwable) {
                L.e("bridge", "openUrl 失败", e)
            }
        }
    }

    /** 把内置客户端的会话 cookie 复制给 WebView, 让学校打印页认得出登录态 */
    private fun syncCookies() {
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            for (c in ApiServer.cookiesForHost("jw.xmu.edu.cn")) {
                cm.setCookie("https://jw.xmu.edu.cn", "${c.name}=${c.value}; Path=${c.path.ifEmpty { "/" }}")
            }
            cm.flush()
        } catch (e: Throwable) {
            L.w("bridge", "同步 cookie 失败: ${e.message}")
        }
    }

    private fun err(message: String): String =
        JSONObject().put("ok", false).put("message", message).toString()
}
