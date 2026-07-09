package com.visionlink.android.controller

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.visionlink.android.ai.AIInferenceManager
import com.visionlink.android.audio.TTSManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AIController(
    private val context: Context,
    private val aiManager: AIInferenceManager,
    private val ttsManager: TTSManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AIController"
        const val ENGINE_STEPFUN = 0
        const val ENGINE_LM_STUDIO = 1
        const val ENGINE_LITERT = 2
        const val ENGINE_AICORE = 3
    }

    private var currentEngine = ENGINE_STEPFUN
    private var isInitialized = false

    fun getCurrentEngine(): Int = currentEngine
    fun isInitialized(): Boolean = isInitialized

    fun setEngine(engine: Int) {
        currentEngine = engine
        Log.i(TAG, "Engine switched to: $engine")
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            aiManager.initialize()
            val success = aiManager.isInitialized()
            isInitialized = success
            success
        } catch (e: Exception) {
            Log.e(TAG, "AI init error: ${e.message}", e)
            isInitialized = false
            false
        }
    }

    suspend fun analyze(bitmap: Bitmap, mode: Int): String = withContext(Dispatchers.IO) {
        if (!isInitialized) return@withContext "AI engine is not initialized"
        try {
            aiManager.analyzeImage(bitmap, mode)
        } catch (e: Exception) {
            Log.e(TAG, "Analyze error: ${e.message}", e)
            "Analysis failed: ${e.message}"
        }
    }

    fun release() {
        try {
            aiManager.release()
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Release error: ${e.message}", e)
        }
    }
}
