package com.visionlink.android.utils

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * App 更新检查器
 *
 * 同时检测 GitHub 和 Gitee 仓库的 Release，哪个先返回就用哪个。
 * 如果两个都返回，优先使用版本号更高的；版本号相同则优先 GitHub（通常下载更快）。
 *
 * GitHub API: GET https://api.github.com/repos/cpufreestyle/visionlink-android/releases/latest
 * Gitee API:  GET https://gitee.com/api/v5/repos/cpufreestyle/visionlink-android/releases/latest
 */
class AppUpdateChecker(
    private val context: Context,
    private val repo: String = "cpufreestyle/visionlink-android"
) {
    companion object {
        private const val TAG = "AppUpdateChecker"
        private const val GITHUB_API = "https://api.github.com"
        private const val GITEE_API = "https://gitee.com/api/v5"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionName: String,       // 如 "5.8.0"
        val versionCode: Int,          // 如 58
        val releaseNotes: String,      // release body (markdown)
        val apkUrl: String,            // 直接下载 URL
        val apkSize: Long,             // APK 文件大小 (bytes)
        val htmlUrl: String,           // release 页面 URL
        val publishedAt: String,       // 发布时间
        val source: String             // "GitHub" 或 "Gitee"
    )

    /**
     * 检查更新 — 同时查询 GitHub 和 Gitee，竞速返回
     *
     * @return UpdateInfo 如果有新版本; null 如果已是最新或检查失败
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val currentCode = getCurrentVersionCode()
            val currentName = getCurrentVersionName()
            Log.i(TAG, "Current version: $currentName ($currentCode)")

            coroutineScope {
                // 同时发起 GitHub 和 Gitee 请求
                val githubDeferred = async { fetchGitHubRelease() }
                val giteeDeferred = async { fetchGiteeRelease() }

                val githubResult = githubDeferred.await()
                val giteeResult = giteeDeferred.await()

                Log.i(TAG, "GitHub release: ${githubResult?.versionName} (${githubResult?.versionCode})")
                Log.i(TAG, "Gitee release: ${giteeResult?.versionName} (${giteeResult?.versionCode})")

                // 选择最优结果
                val bestResult = selectBestUpdate(githubResult, giteeResult)

                if (bestResult != null && bestResult.versionCode > currentCode) {
                    Log.i(TAG, "Update available: ${bestResult.versionName} from ${bestResult.source}")
                    bestResult
                } else {
                    Log.i(TAG, "Already up to date or no update available")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}", e)
            null
        }
    }

    /**
     * 从两个来源中选择最优更新：
     * - 都有结果：取版本号更高的；版本号相同优先 GitHub
     * - 只有一个有结果：用那个
     * - 都没有：返回 null
     */
    private fun selectBestUpdate(
        github: UpdateInfo?,
        gitee: UpdateInfo?
    ): UpdateInfo? {
        return when {
            github != null && gitee != null -> {
                if (gitee.versionCode > github.versionCode) gitee else github
            }
            github != null -> github
            gitee != null -> gitee
            else -> null
        }
    }

    /**
     * 查询 GitHub Releases API
     */
    private fun fetchGitHubRelease(): UpdateInfo? {
        return try {
            val url = "$GITHUB_API/repos/$repo/releases/latest"
            Log.d(TAG, "Fetching GitHub: $url")

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
                return null
            }

            parseGitHubRelease(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "GitHub fetch failed: ${e.message}")
            null
        }
    }

    /**
     * 解析 GitHub Release JSON
     */
    private fun parseGitHubRelease(json: JSONObject): UpdateInfo? {
        val tagName = json.optString("tag_name", "")
        val versionName = tagName.removePrefix("v").trim()
        val versionCode = parseVersionCode(versionName)

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

        val htmlUrl = json.optString("html_url", "")
        if (apkUrl == null) {
            Log.w(TAG, "GitHub: No APK asset found")
            apkUrl = htmlUrl
        }

        return UpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            releaseNotes = json.optString("body", "").ifBlank { "无更新说明" },
            apkUrl = apkUrl,
            apkSize = apkSize,
            htmlUrl = htmlUrl,
            publishedAt = json.optString("published_at", ""),
            source = "GitHub"
        )
    }

    /**
     * 查询 Gitee Releases API
     *
     * Gitee API 格式与 GitHub 类似，但字段名略有不同。
     * GET https://gitee.com/api/v5/repos/{owner}/{repo}/releases/latest
     */
    private fun fetchGiteeRelease(): UpdateInfo? {
        return try {
            val url = "$GITEE_API/repos/$repo/releases/latest"
            Log.d(TAG, "Fetching Gitee: $url")

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "VisionLink-Android")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.e(TAG, "Gitee API error: ${response.code} ${response.message}")
                return null
            }

            parseGiteeRelease(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "Gitee fetch failed: ${e.message}")
            null
        }
    }

    /**
     * 解析 Gitee Release JSON
     *
     * Gitee API 字段:
     * - tag_name: 版本标签
     * - body: release notes
     * - assets[].name, assets[].browser_download_url, assets[].size
     * - created_at: 创建时间
     */
    private fun parseGiteeRelease(json: JSONObject): UpdateInfo? {
        val tagName = json.optString("tag_name", "")
        val versionName = tagName.removePrefix("v").trim()
        val versionCode = parseVersionCode(versionName)

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

        val htmlUrl = json.optString("html_url", "")
        if (apkUrl == null) {
            Log.w(TAG, "Gitee: No APK asset found")
            apkUrl = htmlUrl
        }

        return UpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            releaseNotes = json.optString("body", "").ifBlank { "无更新说明" },
            apkUrl = apkUrl,
            apkSize = apkSize,
            htmlUrl = htmlUrl,
            publishedAt = json.optString("created_at", json.optString("published_at", "")),
            source = "Gitee"
        )
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
     * 将版本名 (如 "5.9.3") 转换为 versionCode (如 593)
     * 规则: major * 100 + minor * 10 + patch
     * "5.0.0" → 500, "5.9.2" → 592, "5.9.3" → 593, "6.0.0" → 600
     *
     * 注意: 旧版使用 major*10+minor（无patch），新版包含patch以支持同minor版本的增量更新。
     * versionCode 在 build.gradle.kts 中也使用相同公式，确保一致。
     */
    private fun parseVersionCode(versionName: String): Int {
        val parts = versionName.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 100 + minor * 10 + patch
    }
}
