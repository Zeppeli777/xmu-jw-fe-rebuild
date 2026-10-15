package com.xmu.jw.core

/** 轻量日志: 安卓与 JVM 单测通用(不依赖 android.util) */
object L {
    var enabled = true
    fun d(tag: String, msg: String) {
        if (enabled) println("[D/$tag] $msg")
    }
    fun i(tag: String, msg: String) {
        if (enabled) println("[I/$tag] $msg")
    }
    fun w(tag: String, msg: String, e: Throwable? = null) {
        if (enabled) {
            println("[W/$tag] $msg")
            e?.printStackTrace()
        }
    }
    fun e(tag: String, msg: String, e: Throwable? = null) {
        println("[E/$tag] $msg")
        e?.printStackTrace()
    }
}
