package com.visionlink.android.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Gemma 4 E2B 端侧模型（.litertlm，约 2.6GB）下载核心。
 *
 * 被 [com.visionlink.android.work.ModelDownloadWorker]（前台服务后台下载）
 * 与 [com.visionlink.android.ai.AIInferenceManager]（就绪判断）共用，避免下载逻辑两份漂移。
 *
 * 特性：断点续传（.part + HTTP Range）、主源 hf-mirror 免 token、失败自动切官方直连。
 * 下载目录 getExternalFilesDir/litert_models 已被 AIInferenceManager.findModelFile 扫描，无需存储权限。
 */
object ModelDownloader {
    private const val TAG = "ModelDownloader"

    /** 目标文件名（findModelFile 按 .litertlm 后缀识别，名字仅为可读性） */
    const val FILENAME = "gemma-4-E2B-it.litertlm"
    const val MODEL_DIR = "litert_models"

    /** 完整文件约 2.59GB；低于此阈值视为未下完（用于就绪预检与完成校验） */
    const val MIN_COMPLETE_BYTES = 2_400_000_000L

    // 来源：litert-community/gemma-4-E2B-it-litert-lm（apache-2.0，免登录直连），通用 GPU 版不挑芯片
    private val URLS = listOf(
        "https://hf-mirror.com/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    )

    // 大文件长超时（readTimeout 是"单次读"超时，不是总时长）
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun modelDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, MODEL_DIR).apply { if (!exists()) mkdirs() }
    }

    fun targetFile(context: Context): File = File(modelDir(context), FILENAME)

    /** 是否已有达标的完整模型文件 */
    fun isComplete(context: Context): Boolean {
        val f = targetFile(context)
        return f.exists() && f.length() >= MIN_COMPLETE_BYTES
    }

    /**
     * 下载模型。已存在则直接成功。
     * @param onProgress (percent 0..100, downloadedBytes, totalBytes)
     * @return 成功与否
     */
    suspend fun download(
        context: Context,
        onProgress: (Int, Long, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val target = targetFile(context)
        if (target.exists() && target.length() >= MIN_COMPLETE_BYTES) {
            Log.i(TAG, "Model already present: ${target.length() / 1048576}MB")
            onProgress(100, target.length(), target.length())
            return@withContext true
        }
        for ((idx, url) in URLS.withIndex()) {
            try {
                Log.i(TAG, "Downloading from source ${idx + 1}/${URLS.size}: $url")
                if (downloadOne(url, target, onProgress)) {
                    Log.i(TAG, "Model download complete: ${target.length() / 1048576}MB")
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Source ${idx + 1} failed: ${e.message}", e)
            }
            if (idx < URLS.size - 1) Log.w(TAG, "切换到备用下载源…")
        }
        false
    }

    /** 单源断点续传：写入 target.part，达标后原子改名为 target */
    private fun downloadOne(url: String, target: File, onProgress: (Int, Long, Long) -> Unit): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        var existing = if (part.exists()) part.length() else 0L

        val reqB = Request.Builder().url(url)
        if (existing > 0) reqB.addHeader("Range", "bytes=$existing-")

        client.newCall(reqB.build()).execute().use { response ->
            // 要断点却返回 200（不支持续传）→ 从头下
            if (existing > 0 && response.code == 200) {
                existing = 0L
                part.delete()
            } else if (response.code != 200 && response.code != 206) {
                Log.e(TAG, "HTTP ${response.code} from $url")
                return false
            }

            val body = response.body ?: return false
            val remaining = body.contentLength()
            val total = if (existing > 0 && response.code == 206) existing + remaining else remaining
            if (total <= 0) {
                Log.e(TAG, "Unknown content length from $url")
                return false
            }

            var downloaded = existing
            var lastPercent = -1
            body.byteStream().use { input ->
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(existing)
                    val buffer = ByteArray(65536)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        raf.write(buffer, 0, read)
                        downloaded += read
                        val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 99)
                        if (pct != lastPercent) {
                            lastPercent = pct
                            onProgress(pct, downloaded, total)
                        }
                    }
                }
            }

            if (part.length() >= MIN_COMPLETE_BYTES) {
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                onProgress(100, target.length(), target.length())
                return true
            }
            Log.w(TAG, "Downloaded size too small: ${part.length() / 1048576}MB")
            return false
        }
    }
}
