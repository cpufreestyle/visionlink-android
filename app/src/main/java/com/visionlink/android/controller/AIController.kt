package com.visionlink.android.controller

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.visionlink.android.ai.AIInferenceManager
import com.visionlink.android.audio.TTSManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AI 控制器 — 管理 AI 引擎初始化、切换、分析调用
 *
 * 从 MainActivity 抽取的职责：
 * - AI 引擎初始化
 * - 引擎切换 (StepFun / LM Studio / LiteRT-LM / AICore)
 * - 单次/连续分析调度
 */
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
        val name = when (engine) {
            ENGINE_STEPFUN -> "StepFun"
            ENGINE_LM_STUDIO -> "LM Studio"
            ENGINE_LITERT -> "LiteRT-LM"
            ENGINE_AICORE -> "AICore"
            else -> "Unknown"
        }
        Log.i(TAG, "Engine switched to: $name")
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val success = aiManager.initialize()
            isInitialized = success
            if (success) {
                Log.i(TAG, "AI engine initialized successfully")
            } else {
                Log.e(TAG, "AI engine initialization failed")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "AI init error: ${e.message}", e)
            false
        }
    }

    suspend fun analyze(bitmap: Bitmap, mode: Int): String = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            return@withContext "AI引擎未初始化"
        }
        try {
            aiManager.analyzeImage(bitmap, mode)
        } catch (e: Exception) {
            Log.e(TAG, "Analyze error: ${e.message}", e)
            "分析失败：${e.message}"
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
