package com.xmu.jw.core

/** 会话持久化抽象: 安卓用 SharedPreferences, JVM 单测用内存实现 */
interface SessionStore {
    class Data(val jar: String, val xh: String?, val user: String?)

    fun load(): Data?
    fun save(jar: String, xh: String?, user: String?)
    fun clear()
}
