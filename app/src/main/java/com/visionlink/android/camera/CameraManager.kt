package com.visionlink.android.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.visionlink.android.R
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 摄像头管理器
 * 
 * 功能映射 (对应 PC 版 main.py):
 * - cv2.VideoCapture(USB_CAMERA_ID) → CameraX
 * - cap.read() → ImageCapture.takePicture()
 * 
 * 优化点 (v1.1):
 * - 修复 cameraExecutor 初始化顺序
 * - 添加 ROI 边界校验
 * - 添加相机可用性检查
 * - 优化错误处理
 */
class CameraManager(
    private val context: Context,
    private val previewView: androidx.camera.view.PreviewView
) {
    
    companion object {
        private const val TAG = "CameraManager"
        
        // 对应 PC 版配置
        const val PREVIEW_WIDTH = 1280
        const val PREVIEW_HEIGHT = 720
        const val AI_IMAGE_SIZE = 448
        const val USB_CAMERA_ID = 1
    }
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService? = null
    private var isCameraRunning = false
    
    /**
     * 启动摄像头
     */
    fun startCamera() {
        if (isCameraRunning) {
            Log.w(TAG, "Camera already running, skipping")
            return
        }
        
        // 先初始化 executor，确保在 listener 之前创建
        if (cameraExecutor == null) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // 预览用例
                val preview = Preview.Builder()
                    .setTargetResolution(android.util.Size(PREVIEW_WIDTH, PREVIEW_HEIGHT))
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                
                // 拍照用例
                imageCapture = ImageCapture.Builder()
                    .setTargetResolution(android.util.Size(AI_IMAGE_SIZE, AI_IMAGE_SIZE))
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                // 选择后置摄像头
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()
                
                // 解绑再绑定，避免重复绑定崩溃
                cameraProvider?.unbindAll()
                
                cameraProvider?.bindToLifecycle(
                    context as LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                
                isCameraRunning = true
                Log.d(TAG, "Camera started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed: ${e.message}")
                e.printStackTrace()
                isCameraRunning = false
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * 拍照并分析
     * 
     * @param callback 返回 Bitmap 的回调
     */
    fun capture(callback: (Bitmap?) -> Unit) {
        val imageCapture = imageCapture ?: run {
            Log.e(TAG, "ImageCapture not initialized")
            callback(null)
            return
        }
        
        val executor = cameraExecutor ?: run {
            Log.e(TAG, "Camera executor not initialized")
            callback(null)
            return
        }
        
        // 创建临时文件
        val photoFile = File(
            context.externalCacheDir ?: context.cacheDir,
            "visionlink_${System.currentTimeMillis()}.jpg"
        )
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        imageCapture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                        if (bitmap == null) {
                            Log.e(TAG, "Failed to decode captured photo")
                            callback(null)
                            return
                        }
                        
                        // 裁剪中心 ROI
                        val roiBitmap = cropCenterROI(bitmap)
                        
                        Log.d(TAG, "Photo captured, ROI size: ${roiBitmap.width}x${roiBitmap.height}")
                        callback(roiBitmap)
                        
                        // 删除临时文件
                        photoFile.delete()
                    } catch (e: Exception) {
                        Log.e(TAG, "Photo processing failed: ${e.message}")
                        callback(null)
                    }
                }
                
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}")
                    callback(null)
                }
            }
        )
    }
    
    /**
     * 裁剪中心 ROI
     * 
     * PC 版代码:
     *   box_x1, box_y1 = int(w * 0.25), int(h * 0.2)
     *   box_x2, box_y2 = int(w * 0.75), int(h * 0.8)
     */
    private fun cropCenterROI(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // 计算 ROI 区域，并校验边界
        val boxX1 = (width * 0.25).toInt().coerceIn(0, width - 1)
        val boxY1 = (height * 0.2).toInt().coerceIn(0, height - 1)
        val boxX2 = (width * 0.75).toInt().coerceIn(boxX1 + 1, width)
        val boxY2 = (height * 0.8).toInt().coerceIn(boxY1 + 1, height)
        
        val roiWidth = boxX2 - boxX1
        val roiHeight = boxY2 - boxY1
        
        if (roiWidth <= 0 || roiHeight <= 0) {
            Log.w(TAG, "Invalid ROI size, returning original bitmap")
            return bitmap
        }
        
        return Bitmap.createBitmap(bitmap, boxX1, boxY1, roiWidth, roiHeight)
    }
    
    /**
     * 释放资源
     */
    fun release() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding camera: ${e.message}")
        }
        cameraProvider = null
        imageCapture = null
        
        try {
            cameraExecutor?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down executor: ${e.message}")
        }
        cameraExecutor = null
        
        isCameraRunning = false
        Log.d(TAG, "Camera released")
    }
    
    /**
     * 检查相机是否运行中
     */
    fun isRunning(): Boolean = isCameraRunning
}
