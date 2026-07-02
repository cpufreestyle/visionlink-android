package com.visionlink.android.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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
    private var imageCapture: ImageCapture? = null
    private var currentMode = MODE_SINGLE_SHOT
    private var isAnalyzing = false

    // 实时帧回调（指向引导等实时模式使用）
    @Volatile private var frameListener: ((Bitmap) -> Unit)? = null
    @Volatile private var frameIntervalMs: Long = 250L
    @Volatile private var lastFrameTs: Long = 0L

    // Continuous mode state
    private val _analysisResults = MutableSharedFlow<AnalysisResult>(replay = 0)
    val analysisResults: SharedFlow<AnalysisResult> = _analysisResults.asSharedFlow()

    data class AnalysisResult(
        val bitmap: Bitmap?,
        val result: String,
        val timestamp: Long
    )

    /**
     * 注册实时帧回调：按 intervalMs 节流，bitmap 已按 rotationDegrees 转正。
     * 回调在相机分析线程执行（单线程，回调内可做耗时推理，期间新帧自动丢弃）。
     * 传 null 取消回调。
     */
    fun setFrameListener(intervalMs: Long = 250L, listener: ((Bitmap) -> Unit)?) {
        frameIntervalMs = intervalMs
        frameListener = listener
        Log.d(TAG, if (listener != null) "帧回调已注册 (间隔 ${intervalMs}ms)" else "帧回调已取消")
    }

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
            .setTargetResolution(android.util.Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { setupFrameAnalyzer(it) }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            imageCapture = ImageCapture.Builder()
                .setTargetResolution(android.util.Size(1920, 1080))
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(
                context as LifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer,
                imageCapture
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
     * 统一的帧分析器：有 frameListener 时按节流间隔转正 bitmap 并回调，
     * 否则立即释放帧（零开销）。
     */
    private fun setupFrameAnalyzer(analysis: ImageAnalysis) {
        analysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
            val listener = frameListener
            val now = System.currentTimeMillis()
            if (listener == null || now - lastFrameTs < frameIntervalMs) {
                imageProxy.close()
                return@setAnalyzer
            }
            lastFrameTs = now
            val bitmap: Bitmap? = try {
                val raw = imageProxy.toBitmap()
                val rotation = imageProxy.imageInfo.rotationDegrees
                if (rotation != 0) {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                } else raw
            } catch (e: Exception) {
                Log.e(TAG, "帧转换失败: ${e.message}", e)
                null
            } finally {
                imageProxy.close()
            }
            if (bitmap != null) {
                try {
                    listener(bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "帧回调异常: ${e.message}", e)
                }
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
     * Capture single frame using ImageCapture API
     */
    suspend fun capture(): Bitmap? = suspendCoroutine { cont ->
        Log.d(TAG, "Capturing single frame with ImageCapture")

        val imageCapture = this.imageCapture
        if (imageCapture == null) {
            Log.e(TAG, "ImageCapture not initialized")
            cont.resume(null)
            return@suspendCoroutine
        }

        imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bitmap = image.toBitmap()
                    Log.d(TAG, "Capture successful: ${bitmap.width}x${bitmap.height}")
                    cont.resume(bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Bitmap conversion failed: ${e.message}", e)
                    cont.resume(null)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed: ${exception.message}", exception)
                Log.e(TAG, "Error cause: ${exception.cause?.message ?: "unknown"}")
                cont.resume(null)
            }
        })
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
        frameListener = null
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
        imageCapture = null
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
