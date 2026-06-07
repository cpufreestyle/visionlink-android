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

/**
 * 主界面 Activity
 * 
 * 功能映射 (对应 PC 版 main.py):
 * - 模式切换 (1/2/3) → 按钮
 * - 拍照识别 (空格) → btnCapture
 * - 语音播报 (speak) → TTSManager
 * - HUD 显示 → activity_main.xml
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CAMERA_PERMISSION = 1001
    }
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var aiManager: AIInferenceManager
    private lateinit var cameraManager: CameraManager
    private lateinit var ttsManager: TTSManager
    private lateinit var glassesManager: CXRGlassesManager
    
    private var currentMode = 1  // 1=避障, 2=文字, 3=场景
    private var isAiInitialized = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置布局
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        Log.d(TAG, "🚀 VisionLink Android 启动")
        
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
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                Log.d(TAG, "✅ TTS 初始化成功")
                runOnUiThread {
                    binding.tvStatus.text = "✅ TTS 已就绪"
                }
            } else {
                Log.e(TAG, "❌ TTS 初始化失败")
                runOnUiThread {
                    binding.tvStatus.text = "❌ TTS 初始化失败"
                }
            }
        }
        
        // 4. 眼镜管理器
        glassesManager = CXRGlassesManager(this)
        
        Log.d(TAG, "✅ 所有管理器初始化完成")
    }
    
    /**
     * 设置 UI 事件
     */
    private fun setupUI() {
        // 模式 1: 避障
        binding.btnMode1.setOnClickListener {
            currentMode = 1
            updateModeUI()
            speak("已切换到避障模式")
        }
        
        // 模式 2: 文字
        binding.btnMode2.setOnClickListener {
            currentMode = 2
            updateModeUI()
            speak("已切换到文字阅读模式")
        }
        
        // 模式 3: 场景
        binding.btnMode3.setOnClickListener {
            currentMode = 3
            updateModeUI()
            speak("已切换到场景描述模式")
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
        updateAIStatus("⚠️ AI 模型未初始化")
    }
    
    /**
     * 初始化 AI 模型
     */
    private fun initAIModel() {
        Log.d(TAG, "🚀 开始初始化 AI 模型...")
        updateAIStatus("⏳ 正在初始化 AI 模型...")
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                aiManager.initialize()
                isAiInitialized = aiManager.isInitialized()
                
                if (isAiInitialized) {
                    Log.d(TAG, "✅ AI 模型初始化成功")
                    updateAIStatus("✅ AI 模型已就绪")
                    speak("AI 模型初始化成功")
                } else {
                    Log.e(TAG, "❌ AI 模型初始化失败")
                    updateAIStatus("❌ AI 模型初始化失败，请检查模型文件")
                    speak("AI 模型初始化失败")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ AI 初始化异常: ${e.message}")
                updateAIStatus("❌ AI 初始化异常: ${e.message}")
                speak("AI 初始化异常")
            }
        }
    }
    
    /**
     * 更新模式 UI
     */
    private fun updateModeUI() {
        val modeText = when (currentMode) {
            1 -> "🟢 避障模式 (Obstacle)"
            2 -> "🟡 文字阅读 (OCR)"
            3 -> "🔵 场景描述 (Scene)"
            else -> "未知模式"
        }
        
        binding.tvMode.text = modeText
        Log.d(TAG, "📺 模式切换: $modeText")
    }
    
    /**
     * 更新 AI 状态显示
     */
    private fun updateAIStatus(status: String) {
        runOnUiThread {
            binding.tvAiStatus.text = status
        }
    }
    
    /**
     * 拍照并分析
     */
    private fun captureAndAnalyze() {
        if (!isAiInitialized) {
            Log.w(TAG, "⚠️ AI 模型未初始化，无法分析")
            speak("请先初始化 AI 模型")
            return
        }
        
        Log.d(TAG, "📷 开始拍照识别...")
        updateAIStatus("⏳ 正在拍照...")
        
        cameraManager.capture { bitmap ->
            if (bitmap == null) {
                Log.e(TAG, "❌ 拍照失败")
                speak("拍照失败，请重试")
                updateAIStatus("❌ 拍照失败")
                return@capture
            }
            
            Log.d(TAG, "📷 拍照成功，开始 AI 分析...")
            updateAIStatus("🧠 AI 正在分析...")
            
            // AI 推理
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val result = aiManager.analyzeImage(bitmap, currentMode)
                    
                    Log.d(TAG, "✅ AI 分析完成: $result")
                    
                    // 显示结果
                    binding.tvResult.text = result
                    
                    // 语音播报
                    speak(result)
                    
                    // 更新状态
                    updateAIStatus("✅ 分析完成")
                    
                    // 发送到眼镜 (如果已连接)
                    if (glassesManager.isConnected()) {
                        glassesManager.showResult(result)
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ AI 分析失败: ${e.message}")
                    speak("分析失败: ${e.message}")
                    updateAIStatus("❌ 分析失败: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 语音播报
     */
    private fun speak(text: String) {
        ttsManager.speak(text)
    }
    
    /**
     * 检查权限
     */
    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        // 摄像头权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        
        // 麦克风权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        
        // 存储权限 (Android 13 以下)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        if (permissions.isNotEmpty()) {
            Log.d(TAG, "🔐 请求权限: $permissions")
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_CAMERA_PERMISSION)
        } else {
            Log.d(TAG, "✅ 所有权限已授予")
            // 权限已授予，启动摄像头
            startCamera()
        }
    }
    
    /**
     * 权限请求结果
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d(TAG, "✅ 权限授予成功")
                startCamera()
            } else {
                Log.e(TAG, "❌ 权限被拒绝")
                Toast.makeText(this, "需要摄像头和麦克风权限", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * 启动摄像头
     */
    private fun startCamera() {
        try {
            cameraManager.startCamera()
            Log.d(TAG, "✅ 摄像头启动成功")
            updateAIStatus("✅ 摄像头已就绪")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 摄像头启动失败: ${e.message}")
            updateAIStatus("❌ 摄像头启动失败: ${e.message}")
        }
    }
    
    /**
     * 连接眼镜
     */
    private fun connectGlasses() {
        Log.d(TAG, "🔗 开始连接眼镜...")
        updateAIStatus("⏳ 正在连接眼镜...")
        
        glassesManager.connect { success ->
            if (success) {
                Log.d(TAG, "✅ 眼镜连接成功")
                runOnUiThread {
                    binding.tvGlassesStatus.text = "✅ 眼镜已连接"
                    binding.tvGlassesStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
                }
                speak("眼镜连接成功")
            } else {
                Log.e(TAG, "❌ 眼镜连接失败")
                runOnUiThread {
                    binding.tvGlassesStatus.text = "❌ 眼镜未连接"
                    binding.tvGlassesStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
                }
                speak("眼镜连接失败")
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // 释放资源
        aiManager.release()
        cameraManager.release()
        ttsManager.release()
        glassesManager.release()
        
        Log.d(TAG, "✅ 所有资源已释放")
    }
}
