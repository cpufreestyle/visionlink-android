package com.visionlink.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.visionlink.android.R
import com.visionlink.android.ai.AIInferenceManager
import com.visionlink.android.audio.TTSManager
import com.visionlink.android.camera.CameraManager
import com.visionlink.android.databinding.ActivityMainBinding
import com.visionlink.android.glasses.CXRGlassesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * 主界面 Activity - v4.0 (Continuous Detection + S25 Ultra Optimized)
 *
 * New in v4.0:
 * - Continuous detection mode toggle
 * - Real-time FPS display
 * - S25 Ultra thermal monitoring
 * - Voice command support (placeholder)
 * - Settings button
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var aiManager: AIInferenceManager
    private lateinit var cameraManager: CameraManager
    private lateinit var ttsManager: TTSManager
    private lateinit var glassesManager: CXRGlassesManager

    private var currentMode = 1
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isDestroyed = false
    private var isContinuousMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "VisionLink Android v4.0 started")
        Log.d(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")

        initManagers()
        setupUI()
        observeAIState()
        checkPermissions()
    }

    // ========== 初始化 ==========

    private fun initManagers() {
        aiManager    = AIInferenceManager(this)
        cameraManager = CameraManager(this, binding.previewView)
        ttsManager    = TTSManager(this) { status ->
            if (isDestroyed) return@TTSManager
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                binding.tvStatus.text = "TTS ready"
            }
        }
        glassesManager = CXRGlassesManager(this)
        Log.d(TAG, "All managers initialized")
    }

    // ========== UI 设置 ==========

    private fun setupUI() {
        // 模式按钮
        binding.btnMode1.setOnClickListener { setMode(1) }
        binding.btnMode2.setOnClickListener { setMode(2) }
        binding.btnMode3.setOnClickListener { setMode(3) }

        // 初始化 AI 按钮
        binding.btnInitAI.setOnClickListener {
            if (!aiManager.isInitialized()) {
                initAI()
            } else {
                Toast.makeText(this, "AI already initialized", Toast.LENGTH_SHORT).show()
            }
        }

        // 下载模型按钮
        binding.btnDownloadModel.setOnClickListener {
            downloadModel()
        }

        // 连续检测切换按钮
        binding.btnContinuous.setOnClickListener {
            toggleContinuousMode()
        }

        // 拍照识别（单次）
        binding.btnCapture.setOnClickListener { captureAndAnalyze() }

        // 设置按钮
        binding.btnSettings.setOnClickListener {
            openSettings()
        }

        // 退出
        binding.btnExit.setOnClickListener { finish() }

        // 初始状态
        updateModeUI()
        binding.tvAiStatus.text = "Tap Init AI to start"
        binding.tvAiStatus.visibility = android.view.View.VISIBLE
        binding.tvFps.text = "FPS: 0"
        binding.tvFps.visibility = android.view.View.VISIBLE
    }

    /**
     * 监听 AI 状态变化
     */
    private fun observeAIState() {
        lifecycleScope.launch {
            aiManager.state.collectLatest { state ->
                if (isDestroyed) return@collectLatest

                val engineText = when (state.engine) {
                    AIInferenceManager.InferenceEngine.AICORE    -> "AICore (Gemini Nano)"
                    AIInferenceManager.InferenceEngine.LITERT_LM  -> "LiteRT-LM (Gemma 4 E2B)"
                    AIInferenceManager.InferenceEngine.CLOUD     -> "Cloud (API)"
                    AIInferenceManager.InferenceEngine.NONE      -> "Not selected"
                }
                binding.tvAiStatus.text = "Engine: $engineText"

                // FPS 显示
                if (state.currentFps > 0) {
                    binding.tvFps.text = "FPS: ${state.currentFps}"
                }

                if (state.downloadProgress in 1..99) {
                    binding.tvAiStatus.text = "Downloading: ${state.downloadProgress}%"
                }

                if (state.isInitialized) {
                    binding.tvAiStatus.text = "$engineText ready"
                    binding.btnInitAI.text = "AI Ready"
                    binding.btnInitAI.isEnabled = false
                    binding.tvResult.text = "AI model ready.\nTap Analyze to start."
                    speakSafely("AI initialized with $engineText")
                }

                if (state.initError != null) {
                    binding.tvAiStatus.text = "Error: ${state.initError}"
                }

                if (state.modelDownloaded && !state.isInitialized) {
                    binding.btnInitAI.text = "Init AI (${state.modelSizeMb}MB)"
                }
            }
        }
    }

    // ========== 连续检测模式 ==========

    private fun toggleContinuousMode() {
        if (!aiManager.isInitialized()) {
            Toast.makeText(this, "Please initialize AI first", Toast.LENGTH_SHORT).show()
            return
        }

        isContinuousMode = !isContinuousMode

        if (isContinuousMode) {
            Log.d(TAG, "Enabling continuous detection mode")
            binding.btnContinuous.text = "Stop\nContinuous"
            binding.btnContinuous.setBackgroundColor(0xFFFF0000.toInt()) // Red
            startContinuousDetection()
        } else {
            Log.d(TAG, "Disabling continuous detection mode")
            binding.btnContinuous.text = "Start\nContinuous"
            binding.btnContinuous.setBackgroundColor(0xFF00AA00.toInt()) // Green
            stopContinuousDetection()
        }
    }

    private fun startContinuousDetection() {
        Log.d(TAG, "Starting continuous detection...")
        binding.tvStatus.text = "Continuous mode active"

        scope.launch {
            try {
                aiManager.startContinuousInference(
                    onFrame = { bitmap ->
                        // Frame captured
                    },
                    onResult = { result ->
                        if (!isDestroyed) {
                            runOnUiThreadSafe {
                                binding.tvResult.text = result
                            }
                            speakSafely(result)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Continuous detection error: ${e.message}", e)
            }
        }
    }

    private fun stopContinuousDetection() {
        Log.d(TAG, "Stopping continuous detection...")
        binding.tvStatus.text = "Continuous mode stopped"
        aiManager.stopContinuousInference()
    }

    // ========== AI 操作 ==========

    private fun initAI() {
        Log.d(TAG, "Initializing AI...")
        binding.btnInitAI.isEnabled = false
        binding.btnInitAI.text = "Initializing..."
        binding.tvAiStatus.text = "Initializing AI..."
        binding.tvAiStatus.visibility = android.view.View.VISIBLE

        scope.launch {
            try {
                aiManager.initialize()

                if (aiManager.isInitialized()) {
                    val engine = aiManager.getEngine().name
                    Log.d(TAG, "AI initialized: $engine")
                    binding.tvAiStatus.text = "$engine ready"
                    binding.btnInitAI.text = "AI Ready"
                    speakSafely("AI initialized successfully")
                } else {
                    val error = aiManager.initError() ?: "Unknown error"
                    Log.e(TAG, "AI init failed: $error")
                    binding.btnInitAI.isEnabled = true
                    binding.btnInitAI.text = "Retry Init"
                    speakSafely("AI initialization failed: $error")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Init exception: ${e.message}", e)
                binding.btnInitAI.isEnabled = true
                binding.btnInitAI.text = "Retry Init"
                binding.tvAiStatus.text = "Error: ${e.message}"
            }
        }
    }

    private fun downloadModel() {
        if (aiManager.isModelDownloaded()) {
            Toast.makeText(this, "Model already downloaded", Toast.LENGTH_SHORT).show()
            return
        }

        val engine = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "AICore"
            else -> "Gemma"
        }

        val modelType = if (engine == "AICore") {
            AIInferenceManager.MODEL_TYPE_GEMINI
        } else {
            AIInferenceManager.MODEL_TYPE_GEMMA
        }

        val url = if (engine == "AICore") {
            AIInferenceManager.MODEL_URL_GEMINI
        } else {
            AIInferenceManager.MODEL_URL_GEMMA
        }

        Log.d(TAG, "Downloading model: $modelType from $url")

        binding.btnDownloadModel.isEnabled = false
        binding.btnDownloadModel.text = "Downloading..."
        binding.tvAiStatus.text = "Downloading model..."
        binding.tvAiStatus.visibility = android.view.View.VISIBLE

        scope.launch {
            try {
                aiManager.downloadModel(modelType, url) { progress ->
                    if (!isDestroyed) {
                        runOnUiThread {
                            binding.tvAiStatus.text = "Downloading: $progress%"
                            if (progress == 100) {
                                binding.btnDownloadModel.text = "Downloaded"
                                binding.btnInitAI.text = "Init AI (${aiManager.getModelSizeMb()}MB)"
                            }
                        }
                    }
                }

                if (!isDestroyed) {
                    binding.btnDownloadModel.isEnabled = true
                    if (aiManager.isModelDownloaded()) {
                        binding.btnDownloadModel.text = "Model Ready"
                        speakSafely("Model downloaded successfully. Tap Init AI.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                if (!isDestroyed) {
                    binding.btnDownloadModel.isEnabled = true
                    binding.btnDownloadModel.text = "Download Failed"
                    binding.tvAiStatus.text = "Download failed: ${e.message}"
                    speakSafely("Model download failed")
                }
            }
        }
    }

    private fun captureAndAnalyze() {
        if (!aiManager.isInitialized()) {
            Toast.makeText(this, "Please initialize AI first", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Capture and analyze...")
        binding.tvAiStatus.text = "Capturing..."
        binding.tvAiStatus.visibility = android.view.View.VISIBLE

        cameraManager.capture { bitmap ->
            if (isDestroyed) return@capture

            if (bitmap == null) {
                Log.e(TAG, "Capture failed")
                runOnUiThreadSafe { binding.tvAiStatus.text = "Capture failed" }
                speakSafely("Capture failed")
                return@capture
            }

            runOnUiThreadSafe { binding.tvAiStatus.text = "AI analyzing..." }

            scope.launch {
                try {
                    val result = aiManager.analyzeImage(bitmap, currentMode)
                    if (isDestroyed) return@launch

                    runOnUiThreadSafe {
                        binding.tvResult.text = result
                        binding.tvAiStatus.text = "${aiManager.getEngine().name} ready"
                        binding.tvFps.text = "FPS: ${aiManager.getCurrentFps()}"
                    }

                    speakSafely(result)

                    if (glassesManager.isConnected()) {
                        glassesManager.showResult(result)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Analysis failed: ${e.message}", e)
                    runOnUiThreadSafe { binding.tvAiStatus.text = "Analysis failed" }
                    speakSafely("Analysis failed")
                }
            }
        }
    }

    private fun openSettings() {
        Toast.makeText(this, "Settings - Coming soon", Toast.LENGTH_SHORT).show()
        // TODO: Open SettingsActivity
    }

    // ========== UI 辅助 ==========

    private fun setMode(mode: Int) {
        currentMode = mode
        updateModeUI()
        val modeName = when (mode) {
            1 -> "Obstacle Avoidance"
            2 -> "Text Reading"
            3 -> "Scene Description"
            else -> "Unknown"
        }
        speakSafely("Mode: $modeName")
    }

    private fun updateModeUI() {
        binding.tvMode.text = when (currentMode) {
            1 -> "Obstacle Avoidance"
            2 -> "Text Reading"
            3 -> "Scene Description"
            else -> "Unknown"
        }
    }

    private fun speakSafely(text: String) {
        try {
            ttsManager.speak(text)
        } catch (e: Exception) {
            Log.w(TAG, "TTS error: ${e.message}")
        }
    }

    private fun runOnUiThreadSafe(action: () -> Unit) {
        if (isDestroyed || isFinishing) return
        runOnUiThread {
            if (!isDestroyed && !isFinishing) action()
        }
    }

    // ========== 权限 ==========

    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            Log.d(TAG, "Permissions granted")
            startCameraWithRetry()
        } else {
            Log.w(TAG, "Requesting: $missing")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun startCameraWithRetry(retry: Int = 0) {
        try {
            cameraManager.startCamera()
            binding.tvStatus.text = "Camera ready"
        } catch (e: Exception) {
            Log.e(TAG, "Camera failed: ${e.message}")
            if (retry < 3) {
                scope.launch {
                    delay(1000)
                    startCameraWithRetry(retry + 1)
                }
            } else {
                binding.tvStatus.text = "Camera failed"
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQUEST_PERMISSIONS) {
            if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
                startCameraWithRetry()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        try { aiManager.release() }     catch (e: Exception) { Log.w(TAG, e.message) }
        try { cameraManager.release() }   catch (e: Exception) { Log.w(TAG, e.message) }
        try { ttsManager.release() }      catch (e: Exception) { Log.w(TAG, e.message) }
        try { glassesManager.release() }  catch (e: Exception) { Log.w(TAG, e.message) }
        try { scope.cancel() }           catch (e: Exception) { Log.w(TAG, e.message) }
        super.onDestroy()
        Log.d(TAG, "All resources released")
    }
}
