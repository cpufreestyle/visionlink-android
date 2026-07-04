package com.visionlink.android.controller

import android.util.Log
import android.graphics.Bitmap
import com.visionlink.android.ai.AIInferenceManager
import com.visionlink.android.audio.TTSManager
import kotlinx.coroutines.CoroutineScope
import import kotlinx.coroutines.Dispatchers
import import kotlinx.coroutines.Job
import import kotlinx.coroutines.SupervisorJob
import import kotlinx.coroutines.launch
import import kotlinx.coroutines.withContext
import import kotlinx.coroutines.delay
import import kotlinx.coroutines.cancel
import import kotlinx.coroutines.isActive

/**
 * 连续检测控制器 — 管理连续拍照分析循环
 *
 * 从 MainActivity 抽取的职责：
 * - 连续检测启动/停止
 * - 帧率控制
 * - 热节流处理
 * - 检测结果播报
 */
class ContinuousDetectionController(
    private val aiManager: AIInferenceManager,
    private val ttsManager: TTSManager,
    private val scope: CoroutineScope,
    private val captureFrame: suspend () -> Bitmap?,
    private val onModeChange: (Int) -> Unit
) {
    companion object {
        private const val TAG = "ContinuousDetection"
        private const val MIN_INTERVAL_MS = 2000L
        private const val MAX_INTERVAL_MS = 6000L
        private const val THERMAL_THRESHOLD = 45
    }

    private var continuousJob: Job? = null
    private var isRunning = false
    private var currentMode = 1

    fun isRunning(): Boolean = isRunning

    fun start(mode: Int) {
        if (isRunning) {
            Log.d(TAG, "Already running")
            return
        }
        currentMode = mode
        isRunning = true
        continuousJob = scope.launch {
            Log.i(TAG, "Continuous detection started (mode=$mode)")
            while (isActive && isRunning) {
                try {
                    val bitmap = captureFrame()
                    if (bitmap != null) {
                        val result = aiManager.analyzeImage(bitmap, currentMode)
                        if (result.isNotBlank()) {
                            ttsManager.speak(result)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Detection cycle error: ${e.message}", e)
                }

                // 动态间隔：根据热节流调整
                val interval = if (aiManager.state.value.isThermalThrottling) {
                    MAX_INTERVAL_MS
                } else {
                    MIN_INTERVAL_MS
                }
                delay(interval)
            }
        }
    }

    fun stop() {
        isRunning = false
        continuousJob?.cancel()
        continuousJob = null
        Log.i(TAG, "Continuous detection stopped")
    }

    fun setMode(mode: Int) {
        currentMode = mode
        onModeChange(mode)
    }

    fun toggle(mode: Int): Boolean {
        return if (isRunning) {
            stop()
            false
        } else {
            start(mode)
            true
        }
    }
}
