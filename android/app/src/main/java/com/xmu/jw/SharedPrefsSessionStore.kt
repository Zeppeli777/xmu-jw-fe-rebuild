package com.xmu.jw

import android.content.Context
import android.content.SharedPreferences
import com.xmu.jw.core.SessionStore

/** 会话持久化: SharedPreferences(登录态只存本机, 不联网) */
class SharedPrefsSessionStore(context: Context) : SessionStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("jw_session", Context.MODE_PRIVATE)

    override fun load(): SessionStore.Data? {
        val jar = prefs.getString(KEY_JAR, null) ?: return null
        if (jar.isEmpty()) return null
        return SessionStore.Data(
            jar = jar,
            xh = prefs.getString(KEY_XH, null),
            user = prefs.getString(KEY_USER, null),
        )
    }

    override fun save(jar: String, xh: String?, user: String?) {
        prefs.edit()
            .putString(KEY_JAR, jar)
            .putString(KEY_XH, xh)
            .putString(KEY_USER, user)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_JAR = "jar"
        const val KEY_XH = "xh"
        const val KEY_USER = "user"
    }
}
