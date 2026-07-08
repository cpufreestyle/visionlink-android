package com.visionlink.android.utils

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 崩溃日志自动收集 & 上传到 GitHub Issue
 *
 * 工作流程：
 * 1. 安装为全局 UncaughtExceptionHandler，捕获原生崩溃
 * 2. 崩溃时将 stack trace + 设备信息写入本地文件
 * 3. 下次 App 启动时检测到崩溃日志，自动上传到 GitHub Issue
 * 4. 提供 reportError() 手动上报非致命错误
 * 5. 提供 reportLog() 上传自定义日志
 *
 * GitHub Token 存储在 SharedPreferences 中（可通过设置页面配置）。
 * 如果没有配置 Token，日志只保留在本地，不上传。
 *
 * @param repo GitHub 仓库 (owner/repo)
 * @param githubToken GitHub Personal Access Token (需要 repo 权限)
 */
class CrashReporter private constructor(
    private val context: Context,
    private val repo: String,
    private val githubToken: String?
) {
    companion object {
        private const val TAG = "CrashReporter"
        private const val CRASH_DIR = "crash_logs"
        private const val PREFS_NAME = "visionlink"
        private const val KEY_GITHUB_TOKEN = "github_report_token"
        private const val KEY_LAST_CRASH_UPLOADED = "last_crash_uploaded"

        @Volatile
        private var instance: CrashReporter? = null

        /**
         * 初始化全局崩溃捕获
         */
        fun init(application: Application, repo: String = "cpufreestyle/visionlink-android") {
            if (instance != null) return

            val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val token = prefs.getString(KEY_GITHUB_TOKEN, null)
            val reporter = CrashReporter(application, repo, token)
            instance = reporter

            // 安装全局 UncaughtExceptionHandler
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                reporter.handleCrash(thread, throwable, previousHandler)
            }

            // 检查上次崩溃日志，如果有则上传
            reporter.checkAndUploadPendingCrashes()

            Log.i(TAG, "CrashReporter initialized (repo=$repo, token=${if (token != null) "configured" else "not set"})")
        }

        /**
         * 手动上报非致命错误
         */
        fun reportError(tag: String, message: String, throwable: Throwable? = null) {
            instance?.let { reporter ->
                reporter.scope.launch {
                    val body = reporter.buildErrorReport(tag, message, throwable)
                    // 无论是否有 token，都先保存到本地
                    reporter.saveErrorToLocal(tag, body)
                    // 如果有 token，上传到 GitHub
                    reporter.uploadToGitHub(
                        title = "[Error] $tag: ${message.take(80)}",
                        body = body,
                        labels = listOf("error", "auto-report")
                    )
                }
            }
        }

        /**
         * 手动上报自定义日志
         */
        fun reportLog(title: String, body: String, labels: List<String> = listOf("log")) {
            instance?.let { reporter ->
                reporter.scope.launch {
                    reporter.uploadToGitHub(title, body, labels)
                }
            }
        }

        /**
         * 获取实例
         */
        fun get(): CrashReporter? = instance
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // ========== 崩溃捕获 ==========

    /**
     * 处理未捕获异常
     */
    private fun handleCrash(thread: Thread, throwable: Throwable, previousHandler: Thread.UncaughtExceptionHandler?) {
        Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)

        try {
            // 写入崩溃日志到本地文件
            val crashReport = buildCrashReport(thread, throwable)
            saveCrashToFile(crashReport)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash report: ${e.message}", e)
        }

        // 交给系统默认处理器（终止进程）
        previousHandler?.uncaughtException(thread, throwable)
    }

    // ========== 本地崩溃日志管理 ==========

    /**
     * 检查并上传待处理的崩溃日志
     */
    private fun checkAndUploadPendingCrashes() {
        scope.launch {
            try {
                val crashDir = getCrashDir()
                val crashFiles = crashDir.listFiles { f -> f.name.endsWith(".txt") }
                    ?.sortedBy { it.lastModified() }
                    ?: emptyList()

                if (crashFiles.isEmpty()) return@launch

                Log.i(TAG, "Found ${crashFiles.size} pending crash log(s)")

                for (file in crashFiles) {
                    val content = file.readText()
                    // 从文件名提取时间戳作为标题的一部分
                    val title = "[Crash] ${file.nameWithoutExtension}"
                    val success = uploadToGitHub(title, content, listOf("crash", "auto-report"))
                    if (success) {
                        file.delete()
                        Log.i(TAG, "Crash log uploaded and deleted: ${file.name}")
                    } else {
                        Log.w(TAG, "Failed to upload crash log: ${file.name}, will retry next launch")
                        break // 网络问题，停止上传后续文件
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkAndUploadPendingCrashes error: ${e.message}", e)
            }
        }
    }

    /**
     * 保存崩溃报告到本地文件
     */
    private fun saveCrashToFile(report: String) {
        val crashDir = getCrashDir()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(crashDir, "$timestamp.txt")
        file.writeText(report)
        Log.i(TAG, "Crash report saved: ${file.absolutePath}")
    }

    /**
     * 保存错误报告到本地文件（无 token 时也能保留日志）
     */
    private fun saveErrorToLocal(tag: String, report: String) {
        try {
            val crashDir = getCrashDir()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(crashDir, "error_${tag}_$timestamp.txt")
            file.writeText(report)
            Log.i(TAG, "Error log saved locally: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save error log: ${e.message}")
        }
    }

    private fun getCrashDir(): File {
        val dir = File(context.filesDir, CRASH_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ========== 报告生成 ==========

    /**
     * 构建崩溃报告
     */
    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        sw.appendLine("## 崩溃报告")
        sw.appendLine()
        sw.appendLine("### 设备信息")
        sw.appendLine("- 设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        sw.appendLine("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sw.appendLine("- 应用版本: ${getAppVersion()}")
        sw.appendLine("- 崩溃时间: ${dateFormat.format(Date())}")
        sw.appendLine("- 崩溃线程: ${thread.name}")
        sw.appendLine()
        sw.appendLine("### 异常堆栈")
        sw.appendLine("```")
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        sw.appendLine("```")
        sw.appendLine()
        sw.appendLine("---")
        sw.appendLine("_此报告由 CrashReporter 自动生成_")
        return sw.toString()
    }

    /**
     * 构建错误报告
     */
    private fun buildErrorReport(tag: String, message: String, throwable: Throwable?): String {
        val sw = StringWriter()
        sw.appendLine("## 错误报告")
        sw.appendLine()
        sw.appendLine("### 设备信息")
        sw.appendLine("- 设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        sw.appendLine("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sw.appendLine("- 应用版本: ${getAppVersion()}")
        sw.appendLine("- 时间: ${dateFormat.format(Date())}")
        sw.appendLine()
        sw.appendLine("### 错误详情")
        sw.appendLine("- 标签: `$tag`")
        sw.appendLine("- 消息: $message")
        sw.appendLine()
        if (throwable != null) {
            sw.appendLine("### 异常堆栈")
            sw.appendLine("```")
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            pw.flush()
            sw.appendLine("```")
        }
        sw.appendLine()
        sw.appendLine("---")
        sw.appendLine("_此报告由 CrashReporter 自动生成_")
        return sw.toString()
    }

    // ========== GitHub 上传 ==========

    /**
     * 上传到 GitHub Issue
     *
     * @return true 如果上传成功
     */
    private suspend fun uploadToGitHub(title: String, body: String, labels: List<String>): Boolean {
        if (githubToken.isNullOrEmpty()) {
            Log.w(TAG, "GitHub token not configured, skip upload: $title")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("title", title)
                    put("body", body)
                    put("labels", JSONArray(labels))
                }

                val request = Request.Builder()
                    .url("https://api.github.com/repos/$repo/issues")
                    .addHeader("Authorization", "Bearer $githubToken")
                    .addHeader("Accept", "application/vnd.github+json")
                    .addHeader("User-Agent", "VisionLink-CrashReporter")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful) {
                    val issueUrl = JSONObject(responseBody ?: "{}").optString("html_url", "")
                    Log.i(TAG, "Issue created: $issueUrl")
                    true
                } else {
                    Log.e(TAG, "GitHub API error: ${response.code} ${response.message}")
                    Log.e(TAG, "Response: ${responseBody?.take(500)}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload to GitHub failed: ${e.message}", e)
                false
            }
        }
    }

    // ========== 工具方法 ==========

    private fun getAppVersion(): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) pi.longVersionCode.toInt() else pi.versionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
