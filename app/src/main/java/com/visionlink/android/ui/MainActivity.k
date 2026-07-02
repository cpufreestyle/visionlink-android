package com.visionlink.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.visionlink.android.R
import com.visionlink.android.ai.AIInferenceManager
import com.visionlink.android.audio.TTSManager
import com.visionlink.android.camera.CameraManager
import com.visionlink.android.databinding.ActivityMainBinding
import com.visionlink.android.glasses.CXRGlassesManager
import java.util.*
import kotlinx.coroutines.*

/**
 * VisionLink Android - 主 Activity
 * 
 * 功能映射 (对应 PC 版 main.py):
 * - 模式1: 避障模式 (Obstacle) → btnMode1
 * - 模式2: 文字阅读 (OCR Text) → btnMode2  
 * - 模式3: 场景描述 (Scene) → btnMode3
 * - 空格: 触发识别 → btnCapture
 * 
 * 技术栈:
 * - LLM: Gemma 4 E2B-it (LiteRT-LM)
 * - 视觉: .tflite 模型 (LiteRT)
 * - 眼镜: CXR-M SDK (OKID)
 * - 输入: CameraX
 * - 输出: Android TTS + 眼镜音频
 * - 显示: HUD 文本 + 状态UI
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var aiManager: AIInferenceManager
    private lateinit var cameraManager: CameraManager
    private lateinit var ttsManager: TTSManager
    private lateinit var glassesManager: CXRGlassesManager
    
    private var currentMode = 1  // 1=避障, 2=文字, 3=场景
    private var isAiRunning = false

    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            initializeApp()
        } else {
            Toast.makeText(this, "需要所有权限才能运行", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 请求权限
        requestPermissions()
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
        )
        
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (notGranted.isEmpty()) {
            initializeApp()
        } else {
            requestPermissionLauncher.launch(notGranted)
        }
    }

    private fun initializeApp() {
        // 初始化各个模块
        aiManager = AIInferenceManager(this)
        cameraManager = CameraManager(this, binding.previewView)
        ttsManager = TTSManager(this, this)
        glassesManager = CXRGlassesManager(this)

        setupUI()
        setupCamera()
        connectGlasses()
    }

    private fun setupUI() {
        // 模式切换按钮 (对应 PC 版数字键 1/2/3)
        binding.btnMode1.setOnClickListener {
            currentMode = 1
            updateModeUI()
            speak("已切换避障模式")
        }
        
        binding.btnMode2.setOnClickListener {
            currentMode = 2
            updateModeUI()
            speak("已切换文字阅读模式")
        }
        
        binding.btnMode3.setOnClickListener {
            currentMode = 3
            updateModeUI()
            speak("已切换场景描述模式")
        }

        // 拍照识别按钮 (对应 PC 版空格键)
        binding.btnCapture.setOnClickListener {
            if (!isAiRunning) {
                captureAndAnalyze()
            }
        }

        // 退出按钮 (对应 PC 版 ESC)
        binding.btnExit.setOnClickListener {
            finish()
        }

        updateModeUI()
    }

    private fun updateModeUI() {
        val modeText = when (currentMode) {
            1 -> "当前模式：避障模式 (Obstacle)"
            2 -> "当前模式：文字阅读 (OCR Text)"
            3 -> "当前模式：场景描述 (Scene)"
            else -> "未知模式"
        }
        binding.tvMode.text = modeText
        
        // 更新 HUD 状态显示
        binding.tvStatus.text = if (isAiRunning) "AI 正在深度思考..." else "就绪"
    }

    private fun setupCamera() {
        cameraManager.startCamera()
    }

    private fun connectGlasses() {
        glassesManager.connect { connected ->
            runOnUiThread {
                binding.tvGlassesStatus.text = if (connected) "眼镜已连接" else "眼镜未连接"
            }
        }
    }

    private fun captureAndAnalyze() {
        isAiRunning = true
        updateModeUI()
        playShutterSound()

        // 拍照
        cameraManager.capture { bitmap ->
            if (bitmap != null) {
                // AI 推理 (异步)
                CoroutineScope(Dispatchers.IO).launch {
                    val result = aiManager.analyzeImage(bitmap, currentMode)
                    
                    withContext(Dispatchers.Main) {
                        // 语音播报
                        speak(result)
                        
                        // 更新 HUD 显示
                        binding.tvResult.text = result
                        
                        // 发送到眼镜显示
                        glassesManager.sendText(result)
                        
                        isAiRunning = false
                        updateModeUI()
                    }
                }
            } else {
                runOnUiThread {
                    speak("拍照失败")
                    isAiRunning = false
                    updateModeUI()
                }
            }
        }
    }

    private fun playShutterSound() {
        // 播放快门音效 (对应 PC 版 PlaySoundW)
        // 可以调用系统音效或自定义
    }

    private fun speak(text: String) {
        // 语音播报 (对应 PC 版 speak() 函数)
        ttsManager.speak(text)
        
        // 同时发送到眼镜音频
        glassesManager.playAudio(text)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsManager.setLanguage(Locale.CHINESE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        aiManager.release()
        cameraManager.release()
        ttsManager.release()
        glassesManager.disconnect()
    }
}
