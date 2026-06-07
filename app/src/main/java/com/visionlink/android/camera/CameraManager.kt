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
 * - cv2.VideoCapture(USB_CAMERA_ID, cv2.CAP_DSHOW) → CameraX
 * - cap.set(cv2.CAP_PROP_FRAME_WIDTH, PREVIEW_WIDTH) → PreviewView
 * - cap.set(cv2.CAP_PROP_FRAME_HEIGHT, PREVIEW_HEIGHT) → Layout XML
 * - cap.read() → ImageCapture.takePicture()
 * - 中心 ROI 框 → ROI Frame Overlay (XML)
 * 
 * 技术栈: CameraX (AndroidX Camera)
 */
class CameraManager(
    private val context: Context,
    private val previewView: androidx.camera.view.PreviewView
) {
    
    companion object {
        private const val TAG = "CameraManager"
        
        // 对应 PC 版配置
        const val PREVIEW_WIDTH = 1280   // PC 版 PREVIEW_WIDTH = 1280
        const val PREVIEW_HEIGHT = 720   // PC 版 PREVIEW_HEIGHT = 720
        const val AI_IMAGE_SIZE = 448      // PC 版 AI_IMAGE_SIZE = 448
        const val USB_CAMERA_ID = 1        // PC 版 USB_CAMERA_ID = 1
    }
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService? = null
    
    /**
     * 启动摄像头 (对应 PC 版 cap = cv2.VideoCapture())
     */
    fun startCamera() {
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
                
                // 拍照用例 (对应 PC 版 cap.read())
                imageCapture = ImageCapture.Builder()
                    .setTargetResolution(android.util.Size(AI_IMAGE_SIZE, AI_IMAGE_SIZE))
                    .build()
                
                // 选择后置摄像头
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()
                
                // 绑定生命周期
                cameraProvider?.bindToLifecycle(
                    context as LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                
                Log.d(TAG, "✅ 摄像头启动成功")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 摄像头启动失败: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
        
        cameraExecutor = Executors.newSingleThreadExecutor()
    }
    
    /**
     * 拍照并分析 (对应 PC 版 space 键触发)
     * 
     * @param callback 返回 Bitmap 的回调
     */
    fun capture(callback: (Bitmap?) -> Unit) {
        val imageCapture = imageCapture ?: run {
            Log.e(TAG, "❌ ImageCapture 未初始化")
            callback(null)
            return
        }
        
        // 创建临时文件
        val photoFile = File(
            context.externalMediaDirs.first(),
            "visionlink_${System.currentTimeMillis()}.jpg"
        )
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        imageCapture.takePicture(
            outputOptions,
            cameraExecutor!!,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // 读取照片为 Bitmap (对应 PC 版 frame.copy())
                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                    
                    // 裁剪中心 ROI (对应 PC 版 core_snap = snap[box_y1:box_y2, box_x1:box_x2])
                    val roiBitmap = cropCenterROI(bitmap)
                    
                    Log.d(TAG, "✅ 拍照成功，ROI 尺寸: ${roiBitmap.width}x${roiBitmap.height}")
                    callback(roiBitmap)
                }
                
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "❌ 拍照失败: ${exc.message}")
                    callback(null)
                }
            }
        )
    }
    
    /**
     * 裁剪中心 ROI (对应 PC 版 frame[box_y1:box_y2, box_x1:box_x2])
     * 
     * PC 版代码:
     *   box_x1, box_y1 = int(w * 0.25), int(h * 0.2)
     *   box_x2, box_y2 = int(w * 0.75), int(h * 0.8)
     *   core_snap = snap[box_y1:box_y2, box_x1:box_x2]
     */
    private fun cropCenterROI(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val boxX1 = (width * 0.25).toInt()
        val boxY1 = (height * 0.2).toInt()
        val boxX2 = (width * 0.75).toInt()
        val boxY2 = (height * 0.8).toInt()
        
        return Bitmap.createBitmap(
            bitmap,
            boxX1,
            boxY1,
            boxX2 - boxX1,
            boxY2 - boxY1
        )
    }
    
    /**
     * 释放资源
     */
    fun release() {
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        Log.d(TAG, "✅ 摄像头已释放")
    }
}
