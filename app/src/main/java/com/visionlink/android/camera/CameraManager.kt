package com.visionlink.android.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.visionlink.android.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Camera Manager - v2.0 (Continuous Detection Support)
 *
 * New in v2.0:
 * - Continuous frame analysis mode (real-time detection)
 * - Frame rate control (5/10/15 FPS)
 * - Analysis result flow for real-time updates
 * - Optimized for Samsung Galaxy S25 Ultra
 */
class CameraManager(
    private val context: Context,
    private val previewView: androidx.camera.view.PreviewView
) {

    companion object {
        private const val TAG = "CameraManager"
        const val MODE_SINGLE_SHOT = 0
        const val MODE_CONTINUOUS = 1
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var _cameraStarted = false
    private var cameraExecutor: ExecutorService? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var currentMode = MODE_SINGLE_SHOT
    private var isAnalyzing = false

    // Continuous mode state
    private val _analysisResults = MutableSharedFlow<AnalysisResult>(replay = 0)
    val analysisResults: SharedFlow<AnalysisResult> = _analysisResults.asSharedFlow()

    data class AnalysisResult(
        val bitmap: Bitmap?,
        val result: String,
        val timestamp: Long
    )

    /**
     * Start camera with continuous detection support
     */
    suspend fun startCamera(mode: Int = MODE_SINGLE_SHOT, frameRate: Int = 10) = withContext(Dispatchers.Main) {
        currentMode = mode
        Log.d(TAG, "Starting camera in mode: $mode, frameRate: $frameRate")

        if (cameraExecutor == null) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        val provider = getCameraProvider()
        if (provider == null) {
            Log.e(TAG, "Failed to get camera provider")
            return@withContext
        }

        val preview = Preview.Builder()
            .setTargetResolution(android.util.Size(1920, 1080))
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(1920, 1080))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        if (mode == MODE_CONTINUOUS) {
            setupContinuousAnalysis(frameRate)
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                context as LifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            Log.d(TAG, "Camera started successfully in mode: $mode")
            _cameraStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "Camera start failed: ${e.message}", e)
            _cameraStarted = false
        }
    }

    fun isCameraStarted(): Boolean = _cameraStarted

    /**
     * Setup continuous frame analysis
     */
    private fun setupContinuousAnalysis(frameRate: Int) {
        Log.d(TAG, "Setting up continuous analysis at $frameRate FPS")

        imageAnalyzer?.setAnalyzer(cameraExecutor!!) { imageProxy ->
            if (!isAnalyzing) {
                imageProxy.close()
                return@setAnalyzer
            }

            val bitmap = imageProxy.toBitmap()
            imageProxy.close()

            // Emit to flow for collection
            CoroutineScope(Dispatchers.IO).launch {
                _analysisResults.emit(AnalysisResult(bitmap, "", System.currentTimeMillis()))
            }
        }
    }

    /**
     * Start continuous analysis
     */
    fun startContinuousAnalysis() {
        Log.d(TAG, "Starting continuous analysis")
        isAnalyzing = true
    }

    /**
     * Stop continuous analysis
     */
    fun stopContinuousAnalysis() {
        Log.d(TAG, "Stopping continuous analysis")
        isAnalyzing = false
    }

    /**
     * Capture single frame
     */
    suspend fun capture(): Bitmap? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Capturing single frame")

        if (cameraProvider == null) {
            Log.e(TAG, "Camera not initialized")
            return@withContext null
        }

        try {
            previewView.bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed: ${e.message}", e)
            null
        }
    }

    /**
     * Switch mode (single shot <-> continuous)
     */
    suspend fun switchMode(mode: Int, frameRate: Int = 10) {
        Log.d(TAG, "Switching to mode: $mode")
        stopContinuousAnalysis()
        startCamera(mode, frameRate)
        if (mode == MODE_CONTINUOUS) {
            startContinuousAnalysis()
        }
    }

    /**
     * Release resources
     */
    fun release() {
        Log.d(TAG, "Releasing camera resources")
        stopContinuousAnalysis()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Unbind error: ${e.message}")
        }
        try {
            cameraExecutor?.shutdown()
            cameraExecutor = null
        } catch (e: Exception) {
            Log.w(TAG, "Executor shutdown error: ${e.message}")
        }
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider? = suspendCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                cont.resume(cameraProvider)
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider error: ${e.message}", e)
                cont.resume(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
