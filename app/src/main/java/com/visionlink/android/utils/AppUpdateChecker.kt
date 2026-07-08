package com.visionlink.android.utils

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * App 更新检查器
 *
 * 通过 GitHub Releases API 检查是否有新版本 APK。
 *
 * 流程:
 * 1. 获取当前已安装的 versionCode
 * 2. 查询 GitHub Releases API 获取最新 release
 * 3. 从 release tag_name 解析版本号
 * 4. 比较版本号，如果有新版本则返回更新信息
 *
 * GitHub API: GET https://api.github.com/repos/cpufreestyle/visionlink-android/releases/latest
 */
class AppUpdateChecker(
    private val context: Context,
    private val repo: String = "cpufreestyle/visionlink-android"
) {
    companion object {
        private const val TAG = "AppUpdateChecker"
        private const val GITHUB_API = "https://api.github.com"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionName: String,       // 如 "5.0.0"
        val versionCode: Int,          // 如 50
        val releaseNotes: String,      // release body (markdown)
        val apkUrl: String,            // 直接下载 URL
        val apkSize: Long,             // APK 文件大小 (bytes)
        val htmlUrl: String,           // release 页面 URL
        val publishedAt: String        // 发布时间
    )

    /**
     * 检查更新
     *
     * @return UpdateInfo 如果有新版本; null 如果已是最新或检查失败
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val currentCode = getCurrentVersionCode()
            val currentName = getCurrentVersionName()
            Log.i(TAG, "Current version: $currentName ($currentCode)")

            val url = "$GITHUB_API/repos/$repo/releases/latest"
            Log.d(TAG, "Fetching: $url")

            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "VisionLink-Android")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.e(TAG, "GitHub API error: ${response.code} ${response.message}")
                return@withContext null
            }

            val json = JSONObject(body)

            // 解析版本号 — tag_name 通常为 "v5.0.0" 或 "5.0.0"
            val tagName = json.optString("tag_name", "")
            val remoteVersionName = tagName.removePrefix("v").trim()
            val remoteVersionCode = parseVersionCode(remoteVersionName)

            Log.i(TAG, "Latest release: $remoteVersionName ($remoteVersionCode)")

            // 查找 APK 资产
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var apkSize = 0L

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            // 如果没找到 APK 资产，使用 release 页面 URL
            val htmlUrl = json.optString("html_url", "")
            if (apkUrl == null) {
                Log.w(TAG, "No APK asset found in latest release")
                apkUrl = htmlUrl
            }

            val releaseNotes = json.optString("body", "").ifBlank { "无更新说明" }
            val publishedAt = json.optString("published_at", "")

            // 比较版本
            if (remoteVersionCode > currentCode) {
                Log.i(TAG, "Update available: $remoteVersionName > $currentName")
                UpdateInfo(
                    versionName = remoteVersionName,
                    versionCode = remoteVersionCode,
                    releaseNotes = releaseNotes,
                    apkUrl = apkUrl,
                    apkSize = apkSize,
                    htmlUrl = htmlUrl,
                    publishedAt = publishedAt
                )
            } else {
                Log.i(TAG, "Already up to date: $currentName >= $remoteVersionName")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}", e)
            null
        }
    }

    /**
     * 获取当前已安装的 versionCode
     */
    private fun getCurrentVersionCode(): Int {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Cannot get version code: ${e.message}")
            0
        }
    }

    /**
     * 获取当前已安装的 versionName
     */
    private fun getCurrentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }

    /**
     * 将版本名 (如 "5.0.0") 转换为 versionCode (如 50)
     * 规则: major * 10 + minor, 忽略 patch
     * "5.0.0" → 50, "5.1.0" → 51, "6.0.0" → 60
     */
    private fun parseVersionCode(versionName: String): Int {
        val parts = versionName.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return major * 10 + minor
    }
}
