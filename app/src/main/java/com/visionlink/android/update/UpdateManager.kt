package com.visionlink.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub Release 自动更新管理器
 *
 * 流程：查询 GitHub Releases API → 比较版本号 → 下载 APK 到应用私有目录
 * → 通过 FileProvider 拉起系统安装器。全程无需存储权限（私有目录下载）。
 *
 * 版本来源：release 的 tag_name（形如 v4.10.1），与本机 versionName 逐段数字比较。
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val REPO = "cpufreestyle/visionlink-android"
        private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
        private const val UPDATE_DIR = "updates"
    }

    data class ReleaseInfo(
        val tagName: String,      // 如 v4.10.1
        val versionName: String,  // 如 4.10.1
        val apkUrl: String,
        val apkName: String,
        val notes: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 本机当前 versionName（读 PackageManager，避免依赖 BuildConfig 开关） */
    fun currentVersion(): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (e: Exception) {
            Log.w(TAG, "读取本机版本失败: ${e.message}")
            "0"
        }

    /**
     * 查询最新 release。有比本机更新的版本且带 APK 资产时返回 ReleaseInfo，否则返回 null。
     * 网络失败/无 release/被限流一律静默返回 null（启动检查不该打扰用户）。
     */
    suspend fun checkForUpdate(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_LATEST)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "检查更新失败: HTTP ${resp.code}")
                    return@withContext null
                }
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                val tag = json.optString("tag_name")
                if (tag.isBlank()) return@withContext null
                val remote = tag.removePrefix("v").removePrefix("V")
                val local = currentVersion()
                if (compareVersions(remote, local) <= 0) {
                    Log.i(TAG, "已是最新版本 (本机 $local, 远端 $remote)")
                    return@withContext null
                }
                val assets = json.optJSONArray("assets") ?: return@withContext null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        Log.i(TAG, "发现新版本 $tag (本机 $local): $name")
                        return@withContext ReleaseInfo(
                            tagName = tag,
                            versionName = remote,
                            apkUrl = asset.optString("browser_download_url"),
                            apkName = name,
                            notes = json.optString("body")
                        )
                    }
                }
                Log.w(TAG, "release $tag 无 APK 资产")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查更新异常: ${e.message}")
            null
        }
    }

    /**
     * 下载 APK 到应用私有目录（无需存储权限）。已存在同名完整文件时直接复用。
     * 返回下载好的文件，失败返回 null。onProgress 回调 0-100。
     */
    suspend fun downloadApk(info: ReleaseInfo, onProgress: (Int) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, UPDATE_DIR)
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, info.apkName)
                // 清理旧版本残留，只保留本次目标
                dir.listFiles()?.forEach { if (it.name != info.apkName) it.delete() }

                val request = Request.Builder().url(info.apkUrl).build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "下载失败: HTTP ${resp.code}")
                        return@withContext null
                    }
                    val body = resp.body ?: return@withContext null
                    val total = body.contentLength()
                    // 已有完整文件直接复用（上次下载完但未安装的场景）
                    if (target.exists() && total > 0 && target.length() == total) {
                        Log.i(TAG, "复用已下载文件: ${target.absolutePath}")
                        onProgress(100)
                        return@withContext target
                    }
                    val tmp = File(dir, "${info.apkName}.part")
                    tmp.outputStream().use { out ->
                        val input = body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                    if (total > 0 && tmp.length() != total) {
                        Log.w(TAG, "下载不完整: ${tmp.length()}/$total")
                        tmp.delete()
                        return@withContext null
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    Log.i(TAG, "下载完成: ${target.absolutePath} (${target.length() / 1024 / 1024}MB)")
                    target
                }
            } catch (e: Exception) {
                Log.w(TAG, "下载异常: ${e.message}")
                null
            }
        }

    /** 是否已授予「安装未知来源应用」权限 */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 跳转到本应用的「安装未知应用」授权页 */
    fun openInstallPermissionSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "打开安装授权页失败: ${e.message}")
        }
    }

    /** 拉起系统安装器安装 APK。返回是否成功发起。 */
    fun installApk(file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "拉起安装器失败: ${e.message}")
            false
        }
    }

    /** 逐段数字比较版本号：a > b 返回正数。非数字段按 0 处理（容忍 4.10.1-beta 之类后缀）。 */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".", "-").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".", "-").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
