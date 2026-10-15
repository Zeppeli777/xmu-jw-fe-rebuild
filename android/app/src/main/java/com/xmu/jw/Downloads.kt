package com.xmu.jw

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/** 把证明 PDF 保存到系统「下载」目录(API 29+ 用 MediaStore, 更早用公共目录) */
object Downloads {

    class Result(val displayPath: String, val absolutePath: String)

    fun save(context: Context, filename: String, data: ByteArray): Result {
        val safeName = filename.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法在下载目录创建文件")
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                ?: throw IllegalStateException("无法写入下载目录")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            Result("下载/$safeName", uri.toString())
        } else {
            @Suppress("DEPRECATION")
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val hasPermission = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val dir = if (hasPermission && (publicDir.exists() || publicDir.mkdirs())) publicDir else null
            if (dir != null) {
                val f = File(dir, safeName)
                FileOutputStream(f).use { it.write(data) }
                MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("application/pdf"), null)
                Result("下载/$safeName", f.absolutePath)
            } else {
                // 没拿到存储权限时退回应用自己的外部目录(无需任何权限)
                val appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: throw IllegalStateException("无可写目录")
                appDir.mkdirs()
                val f = File(appDir, safeName)
                FileOutputStream(f).use { it.write(data) }
                Result("应用目录/Download/$safeName", f.absolutePath)
            }
        }
    }
}
