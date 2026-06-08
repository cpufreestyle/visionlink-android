package com.visionlink.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.visionlink.android.R
import com.visionlink.android.ai.AIInferenceManager
import com.visionlink.android.audio.TTSManager
import com.visionlink.android.camera.CameraManager
import com.visionlink.android.databinding.ActivityMainBinding
import com.visionlink.android.glasses.CXRGlassesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面 Activity
 * 
 * 优化点 (v1.1):
 * - 添加完整的权限请求处理
 * - 改进生命周期管理 (避免 Activity 销毁后更新 UI)
 * - 改进错误处理
 * - 优化协程作用域
 * - 添加摄像头启动重试
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CAMERA_PERMISSION = 1001
        private const val CAMERA_RETRY_DELAY_MS = 1000L
    }
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var aiManager: AIInferenceManager
    private lateinit var cameraManager: CameraManager
    private lateinit var ttsManager: TTSManager
    private lateinit var glassesManager: CXRGlassesManager
    
    private var currentMode = 1
    private var isAiInitialized = false
    private val scope = CoroutineScope(Dispatchers.Main + kotlinx.coroutines.Job())
    
    private var isDestroyed = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        Log.d(TAG, "VisionLink Android started")
        
        // 初始化管理器
        initManagers()
        
        // 设置 UI 事件
        setupUI()
        
        // 检查权限
        checkPermissions()
    }
    
    /**
     * 初始化所有管理器
     */
    private fun initManagers() {
        // 1. AI 推理管理器
        aiManager = AIInferenceManager(this)
        
        // 2. 摄像头管理器
        cameraManager = CameraManager(this, binding.previewView)
        
        // 3. TTS 管理器
        ttsManager = TTSManager(this) { status ->
            if (isDestroyed) return@TTSManager
            
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                Log.d(TAG, "TTS initialized successfully")
                runOnUiThreadSafe {
                    binding.tvStatus.text = "TTS ready"
                }
            } else {
                Log.e(TAG, "TTS initialization failed")
                runOnUiThreadSafe {
                    binding.tvStatus.text = "TTS failed"
                }
            }
        }
        
        // 4. 眼镜管理器
        glassesManager = CXRGlassesManager(this)
        
        Log.d(TAG, "All managers initialized")
    }
    
    /**
     * 设置 UI 事件
     */
    private fun setupUI() {
        // 模式 1: 避障
        binding.btnMode1.setOnClickListener {
            currentMode = 1
            updateModeUI()
            speakSafely("Switched to obstacle avoidance mode")
        }
        
        // 模式 2: 文字
        binding.btnMode2.setOnClickListener {
            currentMode = 2
            updateModeUI()
            speakSafely("Switched to text reading mode")
        }
        
        // 模式 3: 场景
        binding.btnMode3.setOnClickListener {
            currentMode = 3
            updateModeUI()
            speakSafely("Switched to scene description mode")
        }
        
        // 拍照识别
        binding.btnCapture.setOnClickListener {
            captureAndAnalyze()
        }
        
        // 初始化 AI 按钮
        binding.btnInitAI.setOnClickListener {
            initAIModel()
        }
        
        // 退出按钮
        binding.btnExit.setOnClickListener {
            finish()
        }
        
        // 初始 UI
        updateModeUI()
        updateAIStatus("AI model not initialized")
    }
    
    /**
     * 初始化 AI 模型
     */
    private fun initAIModel() {
        if (isAiInitialized) {
            Log.w(TAG, "AI already initialized")
            return
        }
        
        Log.d(TAG, "Initializing AI model...")
        updateAIStatus("Initializing AI model...")
        
        scope.launch {
            try {
                aiManager.initialize()
                isAiInitialized = aiManager.isInitialized()
                
                if (isAiInitialized) {
                    Log.d(TAG, "AI model initialized successfully")
                    updateAIStatus("AI model ready")
                    speakSafely("AI model initialized successfully")
                } else {
                    Log.e(TAG, "AI model initialization failed")
                    updateAIStatus("AI model initialization failed")
                    speakSafely("AI model initialization failed")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "AI initialization exception: ${e.message}")
                updateAIStatus("AI initialization error: ${e.message}")
                speakSafely("AI initialization error")
            }
        }
    }
    
    /**
     * 更新模式 UI
     */
    private fun updateModeUI() {
        val modeText = when (currentMode) {
            1 -> "Obstacle Avoidance"
            2 -> "Text Reading"
            3 -> "Scene Description"
            else -> "Unknown"
        }
        
        binding.tvMode.text = modeText
        Log.d(TAG, "Mode switched to: $modeText")
    }
    
    /**
     * 更新 AI 状态显示
     */
    private fun updateAIStatus(status: String) {
        runOnUiThreadSafe {
            binding.tvAiStatus.text = status
        }
    }
    
    /**
     * 拍照并分析
     */
    private fun captureAndAnalyze() {
        if (!isAiInitialized) {
            Log.w(TAG, "AI model not initialized")
            speakSafely("Please initialize AI model first")
            return
        }
        
        Log.d(TAG, "Starting capture and analyze...")
        updateAIStatus("Capturing...")
        
        cameraManager.capture { bitmap ->
            if (isDestroyed) return@capture
            
            if (bitmap == null) {
                Log.e(TAG, "Capture failed - null bitmap")
                updateAIStatus("Capture failed")
                speakSafely("Capture failed")
                return@capture
            }
            
            Log.d(TAG, "Capture successful, starting AI inference...")
            updateAIStatus("AI thinking...")
            
            // 执行 AI 推理
            scope.launch {
                try {
                    val result = aiManager.analyzeImage(bitmap, currentMode)
                    
                    if (isDestroyed) return@launch
                    
                    Log.d(TAG, "AI inference result: $result")
                    updateAIStatus("Analysis complete")
                    
                    // 显示结果
                    runOnUiThreadSafe {
                        binding.tvResult.text = result
                    }
                    
                    // 语音播报
                    speakSafely(result)
                    
                    // 发送到眼镜
                    if (glassesManager.isConnected()) {
                        glassesManager.showResult(result)
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "AI inference failed: ${e.message}")
                    updateAIStatus("Analysis failed: ${e.message}")
                    speakSafely("Analysis failed")
                }
            }
        }
    }
    
    /**
     * 安全语音播报 (检查 TTS 状态)
     */
    private fun speakSafely(text: String) {
        try {
            ttsManager.speak(text)
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak failed: ${e.message}")
        }
    }
    
    /**
     * 在 UI 线程执行 (安全检查 Activity 是否销毁)
     */
    private fun runOnUiThreadSafe(action: () -> Unit) {
        if (isDestroyed || isFinishing) {
            return
        }
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                action()
            }
        }
    }
    
    /**
     * 检查权限
     */
    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            Log.d(TAG, "All permissions granted")
            startCameraWithRetry()
        } else {
            Log.w(TAG, "Requesting permissions: $missingPermissions")
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_CAMERA_PERMISSION)
        }
    }
    
    /**
     * 启动摄像头 (带重试)
     */
    private fun startCameraWithRetry(retryCount: Int = 0) {
        try {
            cameraManager.startCamera()
            Log.d(TAG, "Camera started successfully")
            updateAIStatus("Camera ready")
        } catch (e: Exception) {
            Log.e(TAG, "Camera start failed: ${e.message}")
            if (retryCount < 3) {
                Log.w(TAG, "Retrying camera start in ${CAMERA_RETRY_DELAY_MS}ms...")
                scope.launch {
                    kotlinx.coroutines.delay(CAMERA_RETRY_DELAY_MS)
                    startCameraWithRetry(retryCount + 1)
                }
            } else {
                Log.e(TAG, "Camera start failed after 3 retries")
                updateAIStatus("Camera failed to start")
            }
        }
    }
    
    /**
     * 权限请求结果
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d(TAG, "Permissions granted")
                startCameraWithRetry()
            } else {
                Log.e(TAG, "Permissions denied")
                Toast.makeText(this, "Camera and microphone permissions required", Toast.LENGTH_LONG).show()
                updateAIStatus("Permissions denied")
            }
        }
    }
    
    /**
     * 连接眼镜
     */
    private fun connectGlasses() {
        Log.d(TAG, "Connecting glasses...")
        updateAIStatus("Connecting glasses...")
        
        glassesManager.connect { success ->
            if (isDestroyed) return@connect
            
            if (success) {
                Log.d(TAG, "Glasses connected successfully")
                runOnUiThreadSafe {
                    binding.tvGlassesStatus.text = "Glasses connected"
                    binding.tvGlassesStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
                }
                speakSafely("Glasses connected")
            } else {
                Log.e(TAG, "Glasses connection failed")
                runOnUiThreadSafe {
                    binding.tvGlassesStatus.text = "Glasses not connected"
                    binding.tvGlassesStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
                }
                speakSafely("Glasses connection failed")
            }
        }
    }
    
    override fun onDestroy() {
        isDestroyed = true
        
        // 释放资源
        try {
            aiManager.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AI manager: ${e.message}")
        }
        
        try {
            cameraManager.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing camera manager: ${e.message}")
        }
        
        try {
            ttsManager.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing TTS manager: ${e.message}")
        }
        
        try {
            glassesManager.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing glasses manager: ${e.message}")
        }
        
        // 取消所有协程
        try {
            scope.cancel("Activity destroyed")
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling coroutines: ${e.message}")
        }
        
        super.onDestroy()
        
        Log.d(TAG, "All resources released")
    }
}
