package com.visionlink.android.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Camera Manager - v2.0 (Continuous Detection Support)
 *
 * - Single-shot capture via ImageCapture
 * - Throttled real-time frame callback for guide/continuous modes
 */
class CameraManager(
    private val context: Context,
    private val previewView: androidx.camera.view.PreviewView
) {

    companion object {
        private const val TAG = "CameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var _cameraStarted = false
    private var cameraExecutor: ExecutorService? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null

    // 实时帧回调（指向引导等实时模式使用）
    @Volatile private var frameListener: ((Bitmap) -> Unit)? = null
    @Volatile private var frameIntervalMs: Long = 250L
    @Volatile private var lastFrameTs: Long = 0L

    // 连续检测：缓存分析流最新帧（720p，替代每轮全幅 takePicture 拍照）
    @Volatile private var latestFrame: Bitmap? = null
    @Volatile private var latestFrameTs: Long = 0L

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

    /** 开始缓存分析流最新帧（连续检测用） */
    fun startFrameCollection(intervalMs: Long = 500L) {
        latestFrame = null
        latestFrameTs = 0L
        setFrameListener(intervalMs) { bmp ->
            latestFrame = bmp
            latestFrameTs = System.currentTimeMillis()
        }
    }

    /** 停止缓存分析流帧 */
    fun stopFrameCollection() {
        setFrameListener(listener = null)
        latestFrame = null
        latestFrameTs = 0L
    }

    /** 取缓存的新鲜帧；超过 maxAgeMs 的旧帧返回 null */
    fun takeLatestFrame(maxAgeMs: Long = 3000L): Bitmap? {
        val ts = latestFrameTs
        if (ts == 0L || System.currentTimeMillis() - ts > maxAgeMs) return null
        return latestFrame
    }

    /**
     * Start camera with continuous detection support
     */
    suspend fun startCamera() = withContext(Dispatchers.Main) {
        // 已绑定则跳过重绑：bindToLifecycle 是 lifecycle-aware 的，
        // onResume 时 CameraX 会自动恢复采集，重复 unbindAll/rebind 反而会黑屏闪烁
        val existingProvider = cameraProvider
        val existingCapture = imageCapture
        if (existingProvider != null && existingCapture != null && existingProvider.isBound(existingCapture)) {
            Log.d(TAG, "Camera already bound, skip rebind")
            return@withContext
        }

        Log.d(TAG, "Starting camera")

        if (cameraExecutor == null) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        val provider = getCameraProvider()
        if (provider == null) {
            Log.e(TAG, "Failed to get camera provider")
            return@withContext
        }

        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageAnalyzer = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { setupFrameAnalyzer(it) }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            imageCapture = ImageCapture.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
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
            Log.d(TAG, "Camera started successfully")
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
     * Release resources
     */
    fun release() {
        Log.d(TAG, "Releasing camera resources")
        frameListener = null
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
