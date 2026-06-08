package com.visionlink.android

import android.app.Application
import com.rokid.cxr.link.CXRLink
import android.util.Log

class VisionLinkApplication : Application() {
    var sharedLink: CXRLink? = null

    fun resetSession() {
        sharedLink = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "VisionLinkApplication created")
    }

    companion object {
        private const val TAG = "VisionLinkApp"
        @Volatile
        private lateinit var instance: VisionLinkApplication

        fun getInstance(): VisionLinkApplication = instance
    }
}