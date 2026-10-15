package com.xmu.jw

import android.content.Context
import com.xmu.jw.core.L
import org.json.JSONArray
import org.json.JSONObject

/**
 * 演示模式(仅 debug 构建): 用 analysis/ 里抓取的真实样本数据渲染三个页面, 不访问教务系统。
 * 用于在没有登录态的模拟器/演示机上检查界面。
 * 开启方式: 在应用外部目录 Android/data/com.xmu.jw/files/ 下放一个空文件 jw-demo.on, 重启应用。
 */
object DemoData {

    var enabled = false
        private set

    private var xywccx: JSONObject? = null
    private var jdfpm: JSONObject? = null

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        // 标记文件放在内部 files 目录(adb shell run-as com.xmu.jw touch files/jw-demo.on)
        // 或外部目录 Android/data/com.xmu.jw/files/jw-demo.on, 二者其一存在即开启
        val internal = java.io.File(context.filesDir, "jw-demo.on")
        val external = java.io.File(context.getExternalFilesDir(null), "jw-demo.on")
        if (!internal.exists() && !external.exists()) return
        xywccx = readAsset(context, "mock/xywccx-data.json")
        jdfpm = readAsset(context, "mock/jdfpm-data.json")
        enabled = true
        L.i("demo", "演示模式已开启(使用 analysis 样本数据, 不访问教务系统)")
    }

    private var appContext: Context? = null

    private fun readAsset(context: Context, name: String): JSONObject? = try {
        JSONObject(context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) })
    } catch (e: Exception) {
        L.w("demo", "读取 $name 失败: ${e.message}")
        null
    }

    private val demoUser = JSONObject().apply {
        put("XH", "2023123456")
        put("XM", "潘腾凯")
        put("XZNJ", "2023级")
        put("YXDM", "信息学院")
        put("ZYDM", "软件工程")
        put("BJDM", "2023软件工程(主修)")
        put("XZ", "4")
        put("XJZT", "正常")
    }

    /** 演示模式不处理的路径返回 null, 交回正常逻辑 */
    fun handle(method: String, path: String, context: Context? = null): ApiServer.ApiResponse? {
        if (!enabled) return null
        if (context != null) appContext = context
        return when (path) {
            "/api/session" -> json(200, JSONObject().put("loggedIn", true).put("user", demoUser))

            "/api/xywccx/overview" ->
                xywccxOverview()?.let { json(200, it) }
                    ?: json(200, JSONObject().put("hasPlan", false))

            "/api/jdfpm/info" -> jdfpmInfo()?.let { json(200, it) }
                ?: json(200, JSONObject().put("user", demoUser).put("ranges", JSONArray()).put("records", JSONArray()))

            "/api/jdfpm/detail" -> json(200, JSONArray(jdfpmDetail()))

            "/api/jdfpm/print" -> ApiServer.ApiResponse(
                200,
                "text/html; charset=utf-8",
                "<html><body><h3>演示模式</h3><p>绩点证明打印页(示例)</p></body></html>"
                    .toByteArray(Charsets.UTF_8),
            )

            // 证明文件申请没有抓包样本, 给空列表(页面会显示空状态)
            "/api/zmsq/applications" -> json(200, JSONArray())
            "/api/zmsq/available" -> json(200, JSONArray())

            "/api/zmsq/apply" -> json(
                200,
                JSONObject().put("ok", true).put("message", "演示模式: 未实际提交申请"),
            )

            "/api/zmsq/email" -> json(
                200,
                JSONObject().put("ok", true).put("message", "演示模式: 未实际发送"),
            )

            // 演示模式下返回一份演示 PDF, 用来验证「保存到下载目录」的原生链路
            "/api/zmsq/download" -> ApiServer.ApiResponse(
                200,
                "application/pdf",
                readAssetBytes(context, "mock/demo-proof.pdf")
                    ?: "演示 PDF 缺失".toByteArray(Charsets.UTF_8),
                disposition = "attachment; filename=\"demo-proof.pdf\"",
            )

            else -> null
        }
    }

    // ---------- 与 server/index.js 的 MOCK 分支保持同一套形状 ----------

    private fun xywccxOverview(): JSONObject? {
        val d = xywccx ?: return null
        val fa = rows(d, "scfa", "cxscfa").firstOrNull() ?: return null
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
            put("groups", JSONArray(rows(d, "scfakz", "cxscfakz")))
            put("courses", JSONArray(rows(d, "kc", "cxscfakzkchxkqkx")))
            val missing = rows(d, "fawxfms", "cxfawxfms").firstOrNull()
            if (missing != null) put("missing", missing) else put("missing", JSONObject.NULL)
        }
    }

    private fun jdfpmInfo(): JSONObject? {
        val d = jdfpm ?: return null
        return JSONObject().apply {
            put("user", demoUser)
            put("ranges", JSONArray(rows(d, "cjfw", "cxxskxcjfw")))
            put("records", JSONArray(rows(d, "jdjssq", "getJdjssq")))
        }
    }

    private fun jdfpmDetail(): List<JSONObject> {
        val d = jdfpm ?: return emptyList()
        return rows(d, "cjmx", "cxzdxscjxq")
    }

    private fun rows(root: JSONObject, key: String, name: String): List<JSONObject> {
        val datas = root.optJSONObject(key)?.optJSONObject("datas") ?: return emptyList()
        return when (val v = datas.opt(name)) {
            is JSONArray -> (0 until v.length()).map { v.getJSONObject(it) }
            is JSONObject -> v.optJSONArray("rows")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }
            } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun num(v: Any?): Number = when (v) {
        is Number -> if (v.toDouble().isFinite()) v else 0
        is String -> v.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let {
            if (it == Math.floor(it) && !it.isInfinite()) it.toLong() else it
        } ?: 0
        else -> 0
    }

    private fun readAssetBytes(context: Context?, name: String): ByteArray? = try {
        (context ?: appContext)?.assets?.open(name)?.use { it.readBytes() }
    } catch (e: Exception) {
        L.w("demo", "读取 $name 失败: ${e.message}")
        null
    }

    private fun json(status: Int, obj: Any): ApiServer.ApiResponse =
        ApiServer.ApiResponse(status, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))
}
