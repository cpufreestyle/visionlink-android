package com.visionlink.android.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.visionlink.android.utils.ModelDownloader

/**
 * Gemma 4 E2B 模型的前台服务下载 Worker。
 *
 * 用 WorkManager + 前台服务承载 2.6GB 大文件下载：**App 被划掉/杀掉也不中断**，
 * 断点续传由 [ModelDownloader] 负责。进度经 setProgress 广播给界面，同时更新前台通知。
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val UNIQUE_NAME = "gemma_model_download"
        const val KEY_PERCENT = "percent"
        const val KEY_DOWNLOADED_MB = "downloaded_mb"
        const val KEY_TOTAL_MB = "total_mb"

        private const val CHANNEL_ID = "model_download"
        private const val NOTIF_ID = 4201
    }

    override suspend fun doWork(): Result {
        // 进入前台服务态（Android 12+ 长任务要求）
        setForeground(makeForegroundInfo(0, 0, 0))

        val ok = ModelDownloader.download(applicationContext) { pct, dBytes, tBytes ->
            val dMb = dBytes / 1048576
            val tMb = tBytes / 1048576
            // 广播进度给界面观察者
            setProgressAsync(
                workDataOf(
                    KEY_PERCENT to pct,
                    KEY_DOWNLOADED_MB to dMb,
                    KEY_TOTAL_MB to tMb
                )
            )
            // 更新前台通知进度
            runCatching { setForegroundAsync(makeForegroundInfo(pct, dMb, tMb)) }
        }

        // 失败则交给 WorkManager 按退避策略重试（网络波动友好）
        return if (ok) Result.success() else Result.retry()
    }

    private fun makeForegroundInfo(percent: Int, downloadedMb: Long, totalMb: Long): ForegroundInfo {
        ensureChannel()
        val text = if (totalMb > 0)
            "Gemma 模型下载中 $percent%  ($downloadedMb / $totalMb MB)"
        else
            "Gemma 模型下载中…"

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("VisionLink 模型下载")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, percent, totalMb == 0L)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "模型下载",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Gemma 端侧模型下载进度" }
                )
            }
        }
    }
}
