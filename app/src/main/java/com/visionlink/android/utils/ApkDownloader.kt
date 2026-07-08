package com.visionlink.android.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * APK 下载与安装管理器
 *
 * 下载 APK 文件到应用缓存目录，然后通过 FileProvider + Intent 触发系统安装。
 */
class ApkDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ApkDownloader"
        private const val APK_FILENAME = "visionlink-update.apk"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)  // APK 文件较大，给足超时
        .build()

    /**
     * 下载进度回调
     */
    interface DownloadCallback {
        fun onProgress(downloaded: Long, total: Long, percent: Int)
        fun onComplete(file: File)
        fun onError(message: String)
    }

    /**
     * 下载 APK 文件
     *
     * @param url APK 下载 URL
     * @param callback 进度回调
     */
    suspend fun download(url: String, callback: DownloadCallback? = null) = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(context.cacheDir, APK_FILENAME)

            // 如果已存在同名文件，先删除
            if (outputFile.exists()) {
                outputFile.delete()
            }

            Log.i(TAG, "Downloading APK from: $url")
            Log.i(TAG, "Output: ${outputFile.absolutePath}")

            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                callback?.onError("下载失败: HTTP ${response.code}")
                return@withContext
            }

            val body = response.body ?: run {
                callback?.onError("下载失败: 响应体为空")
                return@withContext
            }

            val totalBytes = body.contentLength()
            Log.i(TAG, "APK size: ${totalBytes / 1024 / 1024} MB")

            var downloadedBytes = 0L
            var lastPercent = -1

            body.byteStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                            if (percent != lastPercent && percent % 5 == 0) {
                                lastPercent = percent
                                callback?.onProgress(downloadedBytes, totalBytes, percent)
                            }
                        }
                    }
                    output.flush()
                }
            }

            Log.i(TAG, "Download complete: ${outputFile.length() / 1024 / 1024} MB")
            callback?.onComplete(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            callback?.onError("下载失败: ${e.message}")
        }
    }

    /**
     * 触发 APK 安装
     *
     * 通过 FileProvider 提供文件 URI，启动 ACTION_VIEW Intent。
     */
    fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            Log.i(TAG, "Launching installer for: ${file.absolutePath}")
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed: ${e.message}", e)
        }
    }

    /**
     * 清理已下载的 APK 文件
     */
    fun cleanup() {
        val file = File(context.cacheDir, APK_FILENAME)
        if (file.exists()) {
            file.delete()
            Log.d(TAG, "Cleaned up APK file")
        }
    }
}
