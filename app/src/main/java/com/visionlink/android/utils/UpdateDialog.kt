package com.visionlink.android.utils

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.visionlink.android.R
import kotlinx.coroutines.launch

/**
 * 应用更新对话框
 *
 * 显示新版本信息，提供「立即更新」和「稍后再说」按钮。
 * 点击「立即更新」后下载 APK 并触发安装。
 */
class UpdateDialog(
    private val updateInfo: AppUpdateChecker.UpdateInfo
) : DialogFragment() {

    companion object {
        private const val TAG = "UpdateDialog"
        const val TAG_FRAGMENT = "app_update_dialog"
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val inflater = LayoutInflater.from(activity)

        val sizeText = if (updateInfo.apkSize > 0) {
            String.format("%.1f MB", updateInfo.apkSize / 1024.0 / 1024.0)
        } else {
            "未知"
        }

        // 截取 release notes 前 500 字符
        val notes = updateInfo.releaseNotes.take(500).let {
            if (updateInfo.releaseNotes.length > 500) "$it..." else it
        }

        val message = buildString {
            appendLine("发现新版本: v${updateInfo.versionName} (${updateInfo.source})")
            appendLine("大小: $sizeText")
            appendLine("发布时间: ${updateInfo.publishedAt.take(10)}")
            appendLine()
            appendLine("更新内容:")
            append(notes)
        }

        return AlertDialog.Builder(activity)
            .setTitle("有新版本可用")
            .setMessage(message)
            .setPositiveButton("立即更新") { _, _ ->
                startDownload(activity)
            }
            .setNegativeButton("稍后再说") { _, _ ->
                dismiss()
            }
            .setCancelable(false)
            .create()
    }

    /**
     * 开始下载 APK 并显示进度
     */
    private fun startDownload(activity: FragmentActivity) {
        val downloader = ApkDownloader(activity)

        // 创建进度对话框
        val progressView = LayoutInflater.from(activity).inflate(R.layout.dialog_download_progress, null)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.downloadProgressBar)
        val progressText = progressView.findViewById<TextView>(R.id.downloadProgressText)
        val statusText = progressView.findViewById<TextView>(R.id.downloadStatusText)

        val progressDialog = AlertDialog.Builder(activity)
            .setView(progressView)
            .setTitle("正在下载更新...")
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ ->
                // 取消下载 — 只是关闭对话框，下载会在后台继续但不触发安装
            }
            .create()

        progressDialog.show()

        activity.lifecycleScope.launch {
            downloader.download(updateInfo.apkUrl, object : ApkDownloader.DownloadCallback {
                override fun onProgress(downloaded: Long, total: Long, percent: Int) {
                    activity.runOnUiThread {
                        progressBar.progress = percent
                        progressText.text = "$percent%"
                        val downloadedMB = downloaded / 1024.0 / 1024.0
                        val totalMB = total / 1024.0 / 1024.0
                        statusText.text = String.format("%.1f / %.1f MB", downloadedMB, totalMB)
                    }
                }

                override fun onComplete(file: java.io.File) {
                    activity.runOnUiThread {
                        progressDialog.dismiss()
                        // 触发安装
                        downloader.installApk(file)
                        dismiss()
                    }
                }

                override fun onError(message: String) {
                    activity.runOnUiThread {
                        progressDialog.dismiss()
                        AlertDialog.Builder(activity)
                            .setTitle("下载失败")
                            .setMessage(message)
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            })
        }
    }
}
