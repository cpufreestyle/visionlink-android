package com.visionlink.android

import android.app.Application
import android.util.Log
import com.visionlink.android.utils.CrashReporter

class VisionLinkApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "VisionLinkApplication created")

        // 初始化崩溃日志自动收集和上传
        // 捕获 App 崩溃和关键错误，自动创建 GitHub Issue 上传日志
        CrashReporter.init(this)
    }

    companion object {
        private const val TAG = "VisionLinkApp"
        @Volatile
        private lateinit var instance: VisionLinkApplication

        fun getInstance(): VisionLinkApplication = instance
    }
}
