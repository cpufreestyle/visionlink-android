package com.visionlink.android.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.visionlink.android.ai.AIInferenceManager
import com.visionlink.android.ai.ModelApiConfig
import com.visionlink.android.ai.ModelApiConfigManager
import com.visionlink.android.ai.ModelApiConfigDialog
import com.visionlink.android.ai.HandGuideManager
import com.visionlink.android.camera.CameraManager
import com.visionlink.android.databinding.ActivityMainBinding
import com.visionlink.android.R
import com.visionlink.android.glasses.CXRGlassesManager
import com.visionlink.android.audio.TTSManager
import com.visionlink.android.audio.VoiceCommandManager
import com.visionlink.android.bluetooth.BleRingManager
import com.visionlink.android.utils.AICoreChecker
import com.visionlink.android.utils.AppUpdateChecker
import com.visionlink.android.utils.CrashReporter
import com.visionlink.android.utils.UpdateDialog
import com.visionlink.android.voiceprint.VoicePrintManager
import com.visionlink.android.voiceprint.VoicePrintDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_CAMERA_FOR_CAPTURE = 1003
        private const val ACTION_DEBUG_COMMAND = "com.visionlink.android.DEBUG_COMMAND"
    }

    /** 标记：用户点击拍照时相机权限未授予，授权后自动重试 */
    private var pendingCaptureAfterPermission = false
    /** 眼镜授权超时任务，收到 onActivityResult 时取消 */
    private var glassesAuthTimeoutJob: Job? = null

    private lateinit var binding: ActivityMainBinding
    private lateinit var aiManager: AIInferenceManager
    private lateinit var cameraManager: CameraManager
    private lateinit var ttsManager: TTSManager
    private lateinit var voiceManager: VoiceCommandManager
    private lateinit var ringManager: BleRingManager
    private lateinit var glassesManager: CXRGlassesManager
    private var isVoiceEnabled = false
    private lateinit var voicePrintManager: VoicePrintManager
    private lateinit var modelApiConfigManager: ModelApiConfigManager
    private var currentUserId: String? = null  // 当前识别到的用户

    private var currentMode = 1
    private var isEnglish = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isDestroyed = false
    private var isContinuousMode = false
    private var continuousJob: Job? = null

    // 模式4: 指向引导（端侧实时手部+物体检测）
    private var guideManager: HandGuideManager? = null
    private var isGuideMode = false
    private var isGuideStarting = false

    private val debugCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val command = intent?.getStringExtra("command") ?: return
            handleDebugCommand(command)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "VisionLink Android v5.3.0 started")
        Log.d(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")

        // Enable edge-to-edge, handle system bar insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topStatusBar.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        initManagers()
        setupUI()
        setupDebugCommands()
        observeAIState()
        checkPermissions()

        // 启动后检查更新
        checkForAppUpdate()
    }

    private fun initManagers() {
        aiManager     = AIInferenceManager(this)
        cameraManager = CameraManager(this, binding.previewView)
        ttsManager    = TTSManager(this) { status ->
            if (isDestroyed) return@TTSManager
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                binding.tvStatus.text = getString(com.visionlink.android.R.string.status_ready)
            }
        }
        glassesManager = CXRGlassesManager(this)
        voiceManager = VoiceCommandManager(this) { command -> handleVoiceCommand(command) }
        voiceManager.setEnglish(isEnglish)
        ttsManager.switchLanguage(isEnglish)
        ringManager = BleRingManager(this) { event -> handleRingEvent(event) }
        voicePrintManager = VoicePrintManager(this)
        voicePrintManager.initialize()
        modelApiConfigManager = ModelApiConfigManager(this)

        // 恢复上次选中的自定义 API 配置
        modelApiConfigManager.getActive()?.let { config ->
            aiManager.setCustomConfig(config)
            Log.i(TAG, "Restored custom API: ${config.name}")
        }

        // 声纹门控：受保护命令需先验证身份
        voiceManager.voicePrintGate = { command, execute ->
            if (voicePrintManager.getEnrolledCount() > 0 && voicePrintManager.isReady()) {
                // 使用当前已识别用户，而非第一个注册用户
                val targetUser = currentUserId ?: voicePrintManager.getEnrolledUsers().firstOrNull()?.userId
                if (targetUser.isNullOrEmpty()) {
                    // 无已注册用户，直接执行
                    execute()
                } else {
                    speakSafely("请先验证身份")
                    voicePrintManager.startVerification(targetUser) { result ->
                        if (result.isMatch) {
                            execute()
                        } else {
                            speakSafely("身份验证失败")
                        }
                    }
                }
            } else {
                // 未注册声纹，直接执行
                execute()
            }
        }

        Log.d(TAG, "All managers initialized (voicePrint: ${voicePrintManager.isReady()})")
    }

    private fun setupUI() {
        binding.btnMode1.setOnClickListener { setMode(1) }
        binding.btnMode2.setOnClickListener { setMode(2) }
        binding.btnMode3.setOnClickListener { setMode(3) }
        binding.btnMode4.setOnClickListener { toggleGuideMode() }

        binding.btnInitAI.setOnClickListener {
            if (!aiManager.isInitialized()) initAI()
            else Toast.makeText(this, if (isEnglish) "AI already initialized" else "AI\u5df2\u521d\u59cb\u5316", Toast.LENGTH_SHORT).show()
        }

        binding.btnDownloadModel.setOnClickListener { downloadModel() }
        binding.btnContinuous.setOnClickListener { toggleContinuousMode() }
        binding.btnCapture.setOnClickListener { captureAndAnalyze() }
        binding.btnCheckAICore?.setOnClickListener { runAICoreDiagnostic() }
        binding.btnSettings?.setOnClickListener { openSettings() }
        binding.btnGlasses?.setOnClickListener { connectGlasses() }
        binding.btnTestApi.setOnClickListener { testApi() }
        binding.btnTestLm.setOnClickListener { testLmStudio() }
        binding.btnTestEdge.setOnClickListener { testEdge() }
        binding.btnVoicePrint.setOnClickListener { openVoicePrintDialog() }
        binding.btnExit.setOnClickListener { finish() }

        updateModeUI()
        binding.tvAiStatus.text = getString(com.visionlink.android.R.string.tap_init_ai_to_start)
        binding.tvAiStatus.visibility = android.view.View.VISIBLE
        binding.tvFps.text = "FPS: 0"
        binding.tvFps.visibility = android.view.View.VISIBLE
    }

    private fun observeAIState() {
        lifecycleScope.launch {
            aiManager.state.collectLatest { state ->
                if (isDestroyed) return@collectLatest

                val engineText = when (state.engine) {
                    AIInferenceManager.InferenceEngine.AICORE    -> "AICore (Gemini Nano)"
                    AIInferenceManager.InferenceEngine.EDGE     -> "Edge (Gemma 4)"
                    AIInferenceManager.InferenceEngine.LITERT_LM  -> "LiteRT-LM (Gemma 4 E2B)"
                    AIInferenceManager.InferenceEngine.CLOUD     -> "Cloud (API)"
                    AIInferenceManager.InferenceEngine.STEPFUN  -> "StepFun API (阶跃星辰)"
                    AIInferenceManager.InferenceEngine.LM_STUDIO -> "LM Studio (Local)"
                    AIInferenceManager.InferenceEngine.CUSTOM   -> aiManager.getCustomConfig()?.let { "${it.name} (Custom)" } ?: "Custom API"
                    AIInferenceManager.InferenceEngine.NONE      -> "Not selected"
                }
                binding.tvAiStatus.text = "Engine: $engineText"

                if (state.currentFps > 0) binding.tvFps.text = "FPS: ${state.currentFps}"
                if (state.downloadProgress in 1..99) binding.tvAiStatus.text = if (isEnglish) "Downloading: ${state.downloadProgress}%" else "\u4e0b\u8f7d\u4e2d: ${state.downloadProgress}%"

                if (state.isInitialized) {
                    binding.tvAiStatus.text = if (isEnglish) "$engineText ready" else "$engineText \u5c31\u7eea"
                    binding.btnInitAI.text = "AI Ready"
                    binding.btnInitAI.isEnabled = false
                    binding.tvResult.text = getString(com.visionlink.android.R.string.ai_model_ready)
                    speakSafely("AI initialized with $engineText")
                }

                if (state.initError != null) binding.tvAiStatus.text = "Error: ${state.initError}"
                if (state.modelDownloaded && !state.isInitialized) {
                    binding.btnInitAI.text = "Init AI (${state.modelSizeMb}MB)"
                }
            }
        }
    }

    private fun runAICoreDiagnostic() {
        Log.d(TAG, "Running AICore diagnostic...")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            AlertDialog.Builder(this)
                .setTitle("AICore Diagnostic")
                .setMessage("AICore requires Android 14+ (API 34+)\n\nCurrent: Android ${Build.VERSION.SDK_INT}")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("AICore Diagnostic")
            .setMessage("Running diagnostic...\nPlease wait...")
            .setCancelable(false)
            .show()

        scope.launch(Dispatchers.IO) {
            try {
                val result = AICoreChecker.runFullDiagnostic(this@MainActivity)
                val summary = result.getSummary()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("AICore Diagnostic Result")
                        .setMessage(summary)
                        .setPositiveButton("OK", null)
                        .show()
                    Log.d("AICore-Diagnostic", summary)
                    if (result.isAvailable) speakSafely("AICore is available")
                    else speakSafely("AICore not available, will use Gemma 4 E2B")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Diagnostic Error")
                        .setMessage("Error: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                    Log.e(TAG, "Diagnostic failed: ${e.message}", e)
                }
            }
        }
    }

    private fun toggleContinuousMode() {
        if (!aiManager.isInitialized()) {
            Toast.makeText(this, if (isEnglish) "Please initialize AI first" else "\u8bf7\u5148\u521d\u59cb\u5316AI", Toast.LENGTH_SHORT).show()
            return
        }
        isContinuousMode = !isContinuousMode
        if (isContinuousMode) {
            binding.btnContinuous.text = "Stop\nContinuous"
            binding.btnContinuous.setBackgroundColor(0xFFFF0000.toInt())
            startContinuousDetection()
        } else {
            binding.btnContinuous.text = "Start\nContinuous"
            binding.btnContinuous.setBackgroundColor(0xFF00AA00.toInt())
            stopContinuousDetection()
        }
    }

    private fun startContinuousDetection() {
        binding.tvStatus.text = getString(com.visionlink.android.R.string.status_continuous_active)
        // 真实的连续检测循环：拍照 → 当前模式分析 → 播报 → 等待，串行执行防止请求堆积
        continuousJob?.cancel()
        continuousJob = scope.launch {
            while (isActive && isContinuousMode && !isDestroyed) {
                try {
                    val bitmap = cameraManager.capture()
                    if (bitmap == null) {
                        delay(1000)
                        continue
                    }
                    val result = aiManager.analyzeImage(bitmap, currentMode)
                    if (isDestroyed || !isContinuousMode) break
                    binding.tvResult.text = result
                    speakSafely(result)
            } catch (e: Exception) {
                Log.e(TAG, "Continuous detection error: ${e.message}", e)
                CrashReporter.reportError("ContinuousDetection", e.message ?: "unknown", e)
                ttsManager.speak("检测出错，正在重试")
                }
                delay(3000) // 每轮间隔，兼顾播报时长与 API 频率
            }
        }
    }

    private fun stopContinuousDetection() {
        binding.tvStatus.text = getString(com.visionlink.android.R.string.status_continuous_stopped)
        continuousJob?.cancel()
        continuousJob = null
    }

    // ========== 模式4: 指向引导 ==========

    private fun toggleGuideMode() {
        if (isGuideStarting) return
        if (isGuideMode) stopGuideMode() else startGuideMode()
    }

    private fun startGuideMode() {
        if (isContinuousMode) toggleContinuousMode() // 与连续模式互斥
        isGuideStarting = true
        binding.tvAiStatus.text = if (isEnglish) "Loading guide models..." else "加载引导模型中..."
        speakSafely(if (isEnglish) "Starting pointing guide" else "正在启动指向引导")

        scope.launch {
            try {
                val ready = withContext(Dispatchers.IO) {
                    if (guideManager == null) {
                        guideManager = HandGuideManager(
                            this@MainActivity,
                            onAnnounce = { text ->
                                if (!isDestroyed) {
                                    runOnUiThreadSafe { binding.tvResult.text = text }
                                    speakSafely(text)
                                    try { glassesManager.sendText(text) } catch (_: Exception) {}
                                }
                            },
                            onStatus = { status ->
                                runOnUiThreadSafe { binding.tvStatus.text = status }
                            }
                        )
                    }
                    guideManager!!.initialize()
                }

                if (!ready) {
                    binding.tvAiStatus.text = getString(R.string.guide_init_failed)
                    speakSafely(if (isEnglish) "Guide model load failed" else "引导模型加载失败")
                    return@launch
                }

                if (!cameraManager.isCameraStarted()) {
                    cameraManager.startCamera()
                    delay(1000)
                }
                if (!cameraManager.isCameraStarted()) {
                    speakSafely(if (isEnglish) "Camera not ready" else "相机未就绪，无法启动引导")
                    return@launch
                }

                guideManager!!.start()
                cameraManager.setFrameListener(250L) { bitmap ->
                    guideManager?.processFrame(bitmap)
                }

                isGuideMode = true
                currentMode = 4
                updateModeUI()
                binding.btnMode4.setBackgroundColor(0xFFFF0000.toInt())
                binding.tvAiStatus.text = getString(R.string.guide_active)
                speakSafely(
                    if (isEnglish) "Pointing guide on. Point with your index finger. Say lock to lock a target."
                    else "指向引导已开启。请将手抬到胸前，用食指指向想去的方向。说锁定，可锁定目标开始引导"
                )
        } catch (e: Exception) {
            Log.e(TAG, "Guide mode start failed: ${e.message}", e)
            CrashReporter.reportError("GuideMode", "Guide mode start failed: ${e.message}", e)
                binding.tvAiStatus.text = "Error: ${e.message}"
            } finally {
                isGuideStarting = false
            }
        }
    }

    private fun stopGuideMode() {
        cameraManager.setFrameListener(listener = null)
        guideManager?.stop()
        isGuideMode = false
        currentMode = 1
        updateModeUI()
        binding.btnMode4.setBackgroundColor(0xFF00BCD4.toInt())
        binding.tvStatus.text = getString(com.visionlink.android.R.string.status_ready)
        binding.tvAiStatus.text = getString(R.string.guide_stopped)
        speakSafely(if (isEnglish) "Pointing guide off" else "指向引导已关闭")
    }

    private fun initAI() {
        binding.btnInitAI.isEnabled = false
        binding.btnInitAI.text = "Initializing..."
        binding.tvAiStatus.text = getString(com.visionlink.android.R.string.status_init_ai)
        binding.tvAiStatus.visibility = android.view.View.VISIBLE

        scope.launch {
            try {
                aiManager.initialize()
                if (aiManager.isInitialized()) {
                    binding.tvAiStatus.text = "${aiManager.getEngine().name} ready"
                    binding.btnInitAI.text = "AI Ready"
                    speakSafely("AI initialized successfully")
                } else {
                    binding.btnInitAI.isEnabled = true
                    binding.btnInitAI.text = "Retry Init"
                    speakSafely("AI initialization failed")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Init exception: ${e.message}", e)
            CrashReporter.reportError("AIInit", "AI initialization exception: ${e.message}", e)
                ttsManager.speak("初始化失败")
                binding.btnInitAI.isEnabled = true
                binding.btnInitAI.text = "Retry Init"
                binding.tvAiStatus.text = getString(com.visionlink.android.R.string.error_prefix) + e.message
            }
        }
    }

    private fun testApi() {
        binding.tvAiStatus.text = getString(com.visionlink.android.R.string.api_test_waiting)
        binding.tvResult.text = "Testing API..."
        binding.btnTestApi.isEnabled = false

        scope.launch {
            try {
                // 测试纯文本请求
                val result = aiManager.testApiConnection()
                
                runOnUiThread {
                    binding.btnTestApi.isEnabled = true
                    binding.tvResult.text = "API Test Result:\n$result"
                    
                    if (result.startsWith("Error") || result.startsWith("失败") || result.startsWith("错误")) {
                        Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "API Test SUCCESS", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "API test error: ${e.message}", e)
                runOnUiThread {
                    binding.btnTestApi.isEnabled = true
                    binding.tvResult.text = "API Test FAILED:\n${e.message}"
                    Toast.makeText(this@MainActivity, "API Test FAILED: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun testLmStudio() {
        binding.tvAiStatus.text = "Testing LM Studio..."
        binding.tvResult.text = "Testing local AI proxy at 127.0.0.1:1234..."
        binding.btnTestLm.isEnabled = false

        scope.launch {
            try {
                // 切换到 LM Studio 引擎
                aiManager.setEngine(AIInferenceManager.InferenceEngine.LM_STUDIO)
                
                // 测试连接
                val result = aiManager.testLmStudioConnection()
                
                runOnUiThread {
                    binding.btnTestLm.isEnabled = true
                    binding.tvResult.text = "LM Studio Test Result:\n$result"
                    
                    if (result.startsWith("SUCCESS") || result.startsWith("OK")) {
                        Toast.makeText(this@MainActivity, "LM Studio Connected!", Toast.LENGTH_SHORT).show()
                        speakSafely("LM Studio connected successfully")
                    } else {
                        Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LM Studio test error: ${e.message}", e)
                runOnUiThread {
                    binding.btnTestLm.isEnabled = true
                    binding.tvResult.text = "LM Studio Test FAILED:\n${e.message}"
                    Toast.makeText(this@MainActivity, "LM Studio Test FAILED: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun testEdge() {
        binding.tvAiStatus.text = "Testing EDGE (LiteRT-LM)..."
        binding.tvResult.text = "Checking local LiteRT-LM model..."
        binding.btnTestEdge.isEnabled = false

        scope.launch {
            try {
                aiManager.setEngine(AIInferenceManager.InferenceEngine.EDGE)

                // Check model availability via LiteRT-LM
                val checkResult = aiManager.testEdgeConnection()

                runOnUiThread {
                    binding.btnTestEdge.isEnabled = true
                    binding.tvResult.text = "EDGE Test Result:\n$checkResult"
                    binding.tvAiStatus.text = "EDGE: ${if (checkResult.startsWith("✅")) "Ready" else "Model Needed"}"
                    Toast.makeText(this@MainActivity, "EDGE test completed", Toast.LENGTH_SHORT).show()
                    speakSafely("Edge engine test completed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "EDGE test error: ${e.message}", e)
                runOnUiThread {
                    binding.btnTestEdge.isEnabled = true
                    binding.tvResult.text = "EDGE Test FAILED:\n${e.message}"
                    binding.tvAiStatus.text = "EDGE: Error"
                    Toast.makeText(this@MainActivity, "EDGE FAILED: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadModel() {
        if (aiManager.isModelDownloaded()) {
            Toast.makeText(this, "Model already downloaded", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnDownloadModel.isEnabled = false
        binding.btnDownloadModel.text = "Downloading..."
        binding.tvAiStatus.text = getString(com.visionlink.android.R.string.status_downloading)
        binding.tvAiStatus.visibility = android.view.View.VISIBLE

        scope.launch {
            try {
                val success = aiManager.downloadModel { progress ->
                    if (!isDestroyed) {
                        runOnUiThread { binding.tvAiStatus.text = "Downloading: $progress%" }
                    }
                }
                if (!isDestroyed) {
                    binding.btnDownloadModel.isEnabled = true
                    binding.btnDownloadModel.text = if (success) "Model Ready" else "Download Failed"
                    if (success) speakSafely(if (isEnglish) "Model downloaded. Tap Init AI." else "\u6a21\u578b\u5df2\u4e0b\u8f7d\uff0c\u8bf7\u70b9\u51fb\u521d\u59cb\u5316AI")
                    else speakSafely(if (isEnglish) "Model download failed" else "\u6a21\u578b\u4e0b\u8f7d\u5931\u6548")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                if (!isDestroyed) {
                    ttsManager.speak("下载失败")
                    binding.btnDownloadModel.isEnabled = true
                    binding.btnDownloadModel.text = if (isEnglish) "Download Failed" else "\u4e0b\u8f7d\u5931\u6548"
                    speakSafely(if (isEnglish) "Model download failed" else "\u6a21\u578b\u4e0b\u8f7d\u5931\u6548")
                }
            }
        }
    }

    private fun captureAndAnalyze() {
        if (!aiManager.isInitialized()) {
            Toast.makeText(this, if (isEnglish) "Please initialize AI first" else "请先初始化AI", Toast.LENGTH_SHORT).show()
            speakSafely(if (isEnglish) "Please initialize AI first" else "请先初始化AI")
            return
        }
        // 检查相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted, requesting...")
            speakSafely(if (isEnglish) "Camera permission needed" else "需要相机权限")
            pendingCaptureAfterPermission = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_FOR_CAPTURE)
            return
        }
        if (!cameraManager.isCameraStarted()) {
            binding.tvAiStatus.text = if (isEnglish) "Camera not ready" else "相机未启动"
            speakSafely(if (isEnglish) "Camera not ready, restarting..." else "相机未启动，正在重启")
            scope.launch {
                try {
                    cameraManager.startCamera()
                    delay(1500)
                    if (!cameraManager.isCameraStarted()) {
                        binding.tvAiStatus.text = if (isEnglish) "Camera restart failed" else "相机重启失败"
                        return@launch
                    }
                    doCapture()
                } catch (e: Exception) {
                    binding.tvAiStatus.text = if (isEnglish) "Camera error" else "相机错误"
                }
            }
            return
        }
        doCapture()
    }

    private fun doCapture() {
        binding.tvAiStatus.text = getString(com.visionlink.android.R.string.status_capturing)
        binding.tvAiStatus.visibility = android.view.View.VISIBLE

        scope.launch {
            try {
                val bitmap = cameraManager.capture()
                if (isDestroyed) return@launch

                if (bitmap == null) {
                    Log.e("VisionLink", "Capture returned null bitmap!")
                    binding.tvAiStatus.text = getString(com.visionlink.android.R.string.status_capture_failed)
                    speakSafely(if (isEnglish) "Capture failed" else "拍摄失效")
                    return@launch
                }

                binding.tvAiStatus.text = getString(com.visionlink.android.R.string.status_analyzing)
                Log.d("VisionLink", "Analyzing bitmap: ${bitmap.width}x${bitmap.height}")
                val result = aiManager.analyzeImage(bitmap, currentMode)
                Log.d("VisionLink", "Analysis result: $result")
                if (isDestroyed) return@launch

                binding.tvResult.text = result
                binding.tvAiStatus.text = "${aiManager.getEngine().name} ready"
                binding.tvFps.text = "FPS: ${aiManager.getCurrentFps()}"
                speakSafely(result)
            } catch (e: Exception) {
                Log.e("VisionLink", "Capture/analysis error: ${e.message}", e)
                CrashReporter.reportError("CaptureAnalyze", "Capture/analysis error: ${e.message}", e)
                ttsManager.speak("分析失败")
                binding.tvAiStatus.text = "Error: ${e.message}"
            }
        }
    }

    // ========== 声纹识别 ==========

    private fun openVoicePrintDialog() {
        val dialog = VoicePrintDialog(this, voicePrintManager)
        dialog.onUserSelectedListener = object : VoicePrintDialog.OnUserSelectedListener {
            override fun onUserSelected(user: VoicePrintManager.VoicePrintUser) {
                currentUserId = user.userId
                applyUserPreferences(user)
            }
        }
        dialog.show()
    }

    private fun setupDebugCommands() {
        val filter = IntentFilter(ACTION_DEBUG_COMMAND)
        ContextCompat.registerReceiver(
            this,
            debugCommandReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.d(TAG, "Debug command receiver registered: $ACTION_DEBUG_COMMAND")
    }

    private fun handleDebugCommand(command: String) {
        Log.d(TAG, "Debug command received: $command")
        runOnUiThread {
            when (command.lowercase()) {
                "mode1", "obstacle" -> setMode(1)
                "mode2", "text" -> setMode(2)
                "mode3", "scene" -> setMode(3)
                "mode4", "guide" -> setMode(4)
                "capture" -> captureAndAnalyze()
                "continuous" -> toggleContinuousMode()
                "test_lm", "lm" -> testLmStudio()
                "test_edge", "edge" -> testEdge()
                "settings" -> openSettings()
                "voiceprint" -> openVoicePrintDialog()
                "exit" -> finish()
                else -> {
                    binding.tvResult.text = "Unknown debug command: $command"
                    Toast.makeText(this, "Unknown debug command: $command", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 应用用户个性化设置
     */
    private fun applyUserPreferences(user: VoicePrintManager.VoicePrintUser) {
        // 切换模式
        if (user.preferredMode != currentMode) {
            setMode(user.preferredMode)
        }

        // 切换语言
        if (user.isEnglish != isEnglish) {
            isEnglish = user.isEnglish
            voiceManager.setEnglish(isEnglish)
            ttsManager.switchLanguage(isEnglish)
            val prefs = getSharedPreferences("visionlink", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("isEnglish", isEnglish).apply()
        }

        // TTS 语速/音调 (v5.0: 真正生效)
        ttsManager.setSpeechRate(user.ttsRate)
        ttsManager.setPitch(user.ttsPitch)

        speakSafely("欢迎回来，${user.name}")
        Log.i(TAG, "Applied preferences for ${user.name}: mode=${user.preferredMode}, en=${user.isEnglish}")
    }

    /**
     * 自动声纹识别（启动时调用）
     */
    private fun autoIdentifyUser() {
        if (voicePrintManager.getEnrolledCount() == 0) return
        if (!voicePrintManager.isReady()) return

        speakSafely("正在识别身份...")
        voicePrintManager.startIdentification { result ->
            if (result.isMatch && result.userId != null) {
                val user = voicePrintManager.getUser(result.userId!!)
                user?.let {
                    currentUserId = it.userId
                    applyUserPreferences(it)
                }
            } else {
                Log.d(TAG, "Auto-identify: no match (score=${result.score})")
            }
        }
    }

    private fun openSettings() {
        val prefs = getSharedPreferences("visionlink", Context.MODE_PRIVATE)
        isEnglish = prefs.getBoolean("isEnglish", false)
        isVoiceEnabled = prefs.getBoolean("isVoiceEnabled", false)

        val items = arrayOf(
            getString(com.visionlink.android.R.string.settings_lang_chinese) + " / " + getString(com.visionlink.android.R.string.settings_lang_english),
            getString(com.visionlink.android.R.string.settings_voice_enable) + if (isVoiceEnabled) " ✓" else "",
            if (ringManager.isConnected()) getString(com.visionlink.android.R.string.settings_ring_disconnect)
               else getString(com.visionlink.android.R.string.settings_ring_scan),
            if (isEnglish) "Connect Glasses" else "连接眼镜",
            if (isEnglish) "Model API Settings" else "模型 API 设置",
            getString(com.visionlink.android.R.string.settings_close)
        )

        AlertDialog.Builder(this)
            .setTitle(com.visionlink.android.R.string.settings_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showLanguageDialog(prefs)
                    1 -> toggleVoiceCommand(prefs)
                    2 -> toggleRingConnection()
                    3 -> connectGlasses()
                    4 -> openModelApiSettings()
                }
            }
            .show()
    }

    /**
     * 打开模型 API 配置对话框
     */
    private fun openModelApiSettings() {
        val dialog = ModelApiConfigDialog(this, modelApiConfigManager) { config ->
            aiManager.setCustomConfig(config)
            binding.tvAiStatus.text = "Engine: ${config.name} (Custom API)"
            binding.tvAiStatus.visibility = android.view.View.VISIBLE
            speakSafely("已切换到模型 ${config.name}")
            Log.i(TAG, "Switched to custom API: ${config.name}")
        }
        dialog.show()
    }

    private fun showLanguageDialog(prefs: android.content.SharedPreferences) {
        val langOptions = arrayOf(
            getString(com.visionlink.android.R.string.settings_lang_chinese),
            getString(com.visionlink.android.R.string.settings_lang_english)
        )
        val checked = if (isEnglish) 1 else 0

        AlertDialog.Builder(this)
            .setTitle(com.visionlink.android.R.string.settings_language)
            .setSingleChoiceItems(langOptions, checked) { dialog, which ->
                val newIsEnglish = (which == 1)
                if (newIsEnglish != isEnglish) {
                    isEnglish = newIsEnglish
                    prefs.edit().putBoolean("isEnglish", isEnglish).apply()
                    voiceManager.setEnglish(isEnglish)
                    ttsManager.switchLanguage(isEnglish)
                    applyLanguage()
                }
                dialog.dismiss()
            }
            .setNegativeButton(com.visionlink.android.R.string.settings_close, null)
            .show()
    }

    private fun toggleVoiceCommand(prefs: android.content.SharedPreferences) {
        isVoiceEnabled = !isVoiceEnabled
        prefs.edit().putBoolean("isVoiceEnabled", isVoiceEnabled).apply()

        if (isVoiceEnabled) {
            voiceManager.startListening()
            binding.tvStatus.text = getString(com.visionlink.android.R.string.voice_listening)
            speakSafely(getString(com.visionlink.android.R.string.voice_command_help))
        } else {
            voiceManager.stopListening()
            binding.tvStatus.text = getString(com.visionlink.android.R.string.status_ready)
        }
        speakSafely(if (isVoiceEnabled) "Voice command enabled" else "Voice command disabled")
    }

    private fun toggleRingConnection() {
        if (ringManager.isConnected()) {
            ringManager.disconnect()
            speakSafely(getString(com.visionlink.android.R.string.ring_disconnected))
        } else {
            if (ringManager.hasPermissions()) {
                ringManager.startScan()
                binding.tvStatus.text = getString(com.visionlink.android.R.string.ring_scanning)
                speakSafely(getString(com.visionlink.android.R.string.ring_scanning))
            } else {
                speakSafely(if (isEnglish) "Bluetooth permission required" else "\u9700\u8981\u84dd\u7259\u6743\u9650")
                requestBluetoothPermissions()
            }
        }
    }

    private fun handleVoiceCommand(command: VoiceCommandManager.VoiceCommand) {
        Log.d(TAG, "Voice command: $command")

        when (command) {
            // === 原有命令 ===
            VoiceCommandManager.VoiceCommand.CAPTURE_ANALYZE -> {
                speakSafely("正在拍照分析")
                captureAndAnalyze()
            }
            VoiceCommandManager.VoiceCommand.MODE_OBSTACLE -> { setMode(1); speakSafely("障碍物模式") }
            VoiceCommandManager.VoiceCommand.MODE_READ_TEXT -> { setMode(2); speakSafely("读文本模式") }
            VoiceCommandManager.VoiceCommand.MODE_SCENE -> { setMode(3); speakSafely("场景描述模式") }
            VoiceCommandManager.VoiceCommand.MODE_GUIDE -> { if (!isGuideMode) startGuideMode() }
            VoiceCommandManager.VoiceCommand.LOCK_TARGET -> {
                if (isGuideMode) guideManager?.lockTarget()
                else speakSafely("请先说指向引导，进入引导模式")
            }
            VoiceCommandManager.VoiceCommand.UNLOCK_TARGET -> {
                if (isGuideMode) guideManager?.unlockTarget()
            }
            VoiceCommandManager.VoiceCommand.START_CONTINUOUS -> {
                if (!isContinuousMode) { toggleContinuousMode(); speakSafely("已开启连续检测") }
            }
            VoiceCommandManager.VoiceCommand.STOP_CONTINUOUS -> {
                if (isContinuousMode) { toggleContinuousMode(); speakSafely("已停止连续检测") }
            }
            VoiceCommandManager.VoiceCommand.INIT_AI -> {
                speakSafely("正在初始化AI")
                if (!aiManager.isInitialized()) initAI()
            }
            VoiceCommandManager.VoiceCommand.HELP -> speakSafely(voiceManager.getHelpText())

            // === v5.0 新增命令 ===
            VoiceCommandManager.VoiceCommand.VOLUME_UP -> {
                ttsManager.volumeUp()
                speakSafely("音量已调大")
            }
            VoiceCommandManager.VoiceCommand.VOLUME_DOWN -> {
                ttsManager.volumeDown()
                speakSafely("音量已调小")
            }
            VoiceCommandManager.VoiceCommand.SPEED_UP -> {
                val newRate = (ttsManager.getSpeechRate() + 0.2f).coerceIn(0.5f, 2.0f)
                ttsManager.setSpeechRate(newRate)
                speakSafely("语速已调快")
            }
            VoiceCommandManager.VoiceCommand.SPEED_DOWN -> {
                val newRate = (ttsManager.getSpeechRate() - 0.2f).coerceIn(0.5f, 2.0f)
                ttsManager.setSpeechRate(newRate)
                speakSafely("语速已调慢")
            }
            VoiceCommandManager.VoiceCommand.REPEAT -> {
                val last = lastSpokenText
                if (last != null) {
                    speakSafely("重复：$last")
                } else {
                    speakSafely("没有需要重复的内容")
                }
            }
            VoiceCommandManager.VoiceCommand.PAUSE -> {
                voiceManager.stopListening()
                ttsManager.stop()
                speakSafely("已暂停")
            }
            VoiceCommandManager.VoiceCommand.RESUME -> {
                speakSafely("已恢复")
                voiceManager.startListening()
            }
            VoiceCommandManager.VoiceCommand.SWITCH_USER -> {
                speakSafely("正在识别身份")
                autoIdentifyUser()
            }
            VoiceCommandManager.VoiceCommand.ENROLL_VOICEPRINT -> {
                openVoicePrintDialog()
            }
            VoiceCommandManager.VoiceCommand.CLOSE -> {
                speakSafely("再见")
                finish()
            }
            VoiceCommandManager.VoiceCommand.UNKNOWN -> {
                if (voicePrintManager.getEnrolledCount() > 0) {
                    speakSafely("正在识别身份")
                    autoIdentifyUser()
                } else {
                    speakSafely("未识别，请说帮助")
                }
            }
        }
    }

    private fun handleRingEvent(event: BleRingManager.RingEvent) {
        Log.d(TAG, "Ring event: $event")

        when (event) {
            BleRingManager.RingEvent.DEVICE_FOUND -> {
                val name = ringManager.getDeviceName() ?: "ring"
                binding.tvStatus.text = getString(com.visionlink.android.R.string.ring_found, name)
                speakSafely(getString(com.visionlink.android.R.string.ring_found, name))
            }
            BleRingManager.RingEvent.DEVICE_CONNECTED -> {
                speakSafely(getString(com.visionlink.android.R.string.ring_connected))
            }
            BleRingManager.RingEvent.DEVICE_DISCONNECTED -> {
                speakSafely(getString(com.visionlink.android.R.string.ring_disconnected))
            }
            BleRingManager.RingEvent.TAP_DETECTED -> {
                if (isGuideMode) guideManager?.lockTarget() // 引导模式下单击=锁定目标
                else captureAndAnalyze()
            }
            BleRingManager.RingEvent.DOUBLE_TAP -> {
                if (!aiManager.isInitialized()) initAI()
                else speakSafely(if (isEnglish) "AI already ready" else "AI\u5df2\u5c31\u7eea")
            }
            BleRingManager.RingEvent.LONG_PRESS -> toggleContinuousMode()
            BleRingManager.RingEvent.SWIPE_UP -> setMode(minOf(currentMode + 1, 3))
            BleRingManager.RingEvent.SWIPE_DOWN -> setMode(maxOf(currentMode - 1, 1))
            else -> {}
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ), 1002)
        }
    }

    private fun applyLanguage() {
        val locale = if (isEnglish) java.util.Locale.ENGLISH else java.util.Locale("zh", "CN")
        java.util.Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        recreate()
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("visionlink", Context.MODE_PRIVATE)
        val isEnglish = prefs.getBoolean("isEnglish", false)
        val locale = if (isEnglish) java.util.Locale.ENGLISH else java.util.Locale("zh", "CN")
        java.util.Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }



    private fun setMode(mode: Int) {
        if (isGuideMode && mode != 4) stopGuideMode() // 切回模式1-3时先退出指向引导
        currentMode = mode
        updateModeUI()
        val modeName = when (mode) {
            1 -> if (isEnglish) "Obstacle Avoidance" else "障碍物检测"
            2 -> if (isEnglish) "Text Reading" else "文字识别"
            3 -> if (isEnglish) "Scene Description" else "场景描述"
            4 -> if (isEnglish) "Pointing Guide" else "指向引导"
            else -> if (isEnglish) "Unknown" else "未知"
        }
        speakSafely("Mode: $modeName")
    }

    private fun updateModeUI() {
        binding.tvMode.text = when (currentMode) {
            1 -> if (isEnglish) "Obstacle Avoidance" else "障碍物检测"
            2 -> if (isEnglish) "Text Reading" else "文字识别"
            3 -> if (isEnglish) "Scene Description" else "场景描述"
            4 -> if (isEnglish) "Pointing Guide" else "指向引导"
            else -> if (isEnglish) "Unknown" else "未知"
        }
    }

    private var lastSpokenText: String? = null

    private fun speakSafely(text: String) {
        lastSpokenText = text
        voiceManager.setLastSpokenText(text)
        try { ttsManager.speak(text) } catch (e: Exception) { Log.w(TAG, "TTS error: ${e.message}") }
    }

    private fun runOnUiThreadSafe(action: () -> Unit) {
        if (isDestroyed || isFinishing) return
        runOnUiThread { if (!isDestroyed && !isFinishing) action() }
    }

    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            Log.d(TAG, "Permissions granted")
            startCameraWithRetry()
            initVoiceAndRing()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun initVoiceAndRing() {
        val prefs = getSharedPreferences("visionlink", Context.MODE_PRIVATE)
        isVoiceEnabled = prefs.getBoolean("isVoiceEnabled", false)
        if (isVoiceEnabled) {
            voiceManager.startListening()
            binding.tvStatus.text = getString(com.visionlink.android.R.string.voice_listening)
        }
    }

    private fun startCameraWithRetry(retry: Int = 0) {
        if (isDestroyed || isFinishing) return
        scope.launch {
            try {
                cameraManager.startCamera()
                if (!isDestroyed) {
                    binding.tvStatus.text = getString(com.visionlink.android.R.string.status_ready)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera failed: ${e.message}", e)
                CrashReporter.reportError("Camera", "Camera failed: ${e.message}", e)
                if (retry < 3 && !isDestroyed) {
                    delay(1000)
                    startCameraWithRetry(retry + 1)
                } else if (!isDestroyed) {
                    binding.tvStatus.text = "Camera failed: ${e.message}"
                }
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        when (code) {
            REQUEST_PERMISSIONS -> {
                if (results.all { it == PackageManager.PERMISSION_GRANTED }) startCameraWithRetry()
                else Toast.makeText(this, getString(com.visionlink.android.R.string.perm_camera_rationale), Toast.LENGTH_LONG).show()
            }
            REQUEST_CAMERA_FOR_CAPTURE -> {
                if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Camera permission granted for capture")
                    if (pendingCaptureAfterPermission) {
                        pendingCaptureAfterPermission = false
                        // 权限刚授予，先启动相机再拍照
                        scope.launch {
                            try {
                                cameraManager.startCamera()
                                delay(1500)
                            } catch (e: Exception) {
                                Log.e(TAG, "Camera start after permission: ${e.message}")
                            }
                            if (!isDestroyed && !isFinishing) captureAndAnalyze()
                        }
                    }
                } else {
                    pendingCaptureAfterPermission = false
                    Toast.makeText(this, if (isEnglish) "Camera permission denied" else "相机权限被拒绝", Toast.LENGTH_LONG).show()
                    speakSafely(if (isEnglish) "Camera permission denied" else "相机权限被拒绝")
                }
            }
        }
    }

    /**
     * 检查应用更新
     * 通过 GitHub Releases API 检查是否有新版本
     */
    private fun checkForAppUpdate() {
        scope.launch {
            delay(2000) // 延迟 2 秒，避免与启动流程冲突
            if (isDestroyed || isFinishing) return@launch

            val checker = AppUpdateChecker(this@MainActivity)
            val updateInfo = checker.checkForUpdate()

            if (updateInfo != null) {
                Log.i(TAG, "Update available: v${updateInfo.versionName}")
                UpdateDialog(updateInfo).show(supportFragmentManager, UpdateDialog.TAG_FRAGMENT)
            } else {
                Log.d(TAG, "App is up to date")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ttsManager.switchLanguage(isEnglish)
        voiceManager.setEnglish(isEnglish)

        // 如果眼镜正在授权中，跳过相机重启和眼镜连接检查，避免干扰授权流程
        val isGlassesAuthenticating = glassesManager.connectionState ==
            com.visionlink.android.glasses.CXRGlassesManager.ConnectionState.AUTHENTICATING

        if (!isGlassesAuthenticating) {
            if (::cameraManager.isInitialized && !isDestroyed) {
                Log.d(TAG, "onResume: restarting camera")
                startCameraWithRetry()
            }
            checkGlassesConnection()
        } else {
            Log.d(TAG, "onResume: glasses authenticating, skipping camera/glasses check")
        }
        
        // 自动声纹识别（如果已注册用户）
        if (voicePrintManager.getEnrolledCount() > 0 && voicePrintManager.isReady()) {
            // 延迟 2 秒，避免与相机初始化冲突
            scope.launch {
                delay(2000)
                if (!isDestroyed && !isFinishing) {
                    autoIdentifyUser()
                }
            }
        }
    }
    
    /**
     * Check and update glasses connection status
     */
    private fun checkGlassesConnection() {
        glassesManager.connect { connected ->
            runOnUiThread {
                if (connected) {
                    binding.tvGlassesStatus.text = if (isEnglish) {
                        "Glasses: ${glassesManager.getDeviceName()}"
                    } else {
                        "眼镜: ${glassesManager.getDeviceName()}"
                    }
                    binding.tvGlassesStatus.setTextColor(0xFF00FF00.toInt())
                    speakSafely(if (isEnglish) "Glasses connected" else "眼镜已连接")
                } else {
                    binding.tvGlassesStatus.text = getString(com.visionlink.android.R.string.glasses_disconnected)
                    binding.tvGlassesStatus.setTextColor(0xFFFF0000.toInt())
                }
            }
        }
    }

    /**
     * 连接 Rokid 眼镜（通过 Rokid AI App 授权）
     */
    private fun connectGlasses() {
        if (glassesManager.isConnected) {
            Toast.makeText(this, if (isEnglish) "Glasses already connected" else "眼镜已连接", Toast.LENGTH_SHORT).show()
            return
        }
        binding.tvGlassesStatus.text = if (isEnglish) "Authenticating..." else "正在授权..."
        speakSafely(if (isEnglish) "Connecting glasses" else "正在连接眼镜")
        val started = glassesManager.requestAuthAndConnect(this)
        if (!started) {
            binding.tvGlassesStatus.text = if (isEnglish) "Rokid AI App not installed" else "请先安装 Rokid AI App"
            speakSafely(if (isEnglish) "Please install Rokid AI App first" else "请先安装 Rokid AI App")
        } else {
            // 授权超时保护：如果 120 秒内未收到 onActivityResult，重置状态
            glassesAuthTimeoutJob?.cancel()
            glassesAuthTimeoutJob = scope.launch {
                delay(120_000)
                if (!isDestroyed && !isFinishing &&
                    glassesManager.connectionState == com.visionlink.android.glasses.CXRGlassesManager.ConnectionState.AUTHENTICATING) {
                    Log.w(TAG, "Authorization timed out after 120s")
                    glassesManager.resetAuthState()
                    runOnUiThread {
                        binding.tvGlassesStatus.text = if (isEnglish) "Auth timeout, retry" else "授权超时，请重试"
                        binding.tvGlassesStatus.setTextColor(0xFFFF0000.toInt())
                        speakSafely(if (isEnglish) "Authorization timed out" else "授权超时")
                    }
                    CrashReporter.reportError("GlassesAuth", "Glasses authorization timed out after 120s")
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == com.visionlink.android.glasses.RokidCxrHelper.REQUEST_AUTH) {
            Log.i(TAG, "onActivityResult: REQUEST_AUTH, resultCode=$resultCode")
            // 收到授权结果，取消超时保护
            glassesAuthTimeoutJob?.cancel()
            glassesAuthTimeoutJob = null
            glassesManager.handleAuthResult(resultCode, data) { connected ->
                runOnUiThread {
                    if (connected) {
                        binding.tvGlassesStatus.text = if (isEnglish) {
                            "Glasses: ${glassesManager.getDeviceName()}"
                        } else {
                            "眼镜: ${glassesManager.getDeviceName()}"
                        }
                        binding.tvGlassesStatus.setTextColor(0xFF00FF00.toInt())
                        speakSafely(if (isEnglish) "Glasses connected" else "眼镜已连接")
                    } else {
                        val err = glassesManager.errorMessage
                        binding.tvGlassesStatus.text = if (err.isNotEmpty()) err else getString(com.visionlink.android.R.string.glasses_disconnected)
                        binding.tvGlassesStatus.setTextColor(0xFFFF0000.toInt())
                        speakSafely(if (isEnglish) "Glasses connection failed" else "眼镜连接失败")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        try { unregisterReceiver(debugCommandReceiver) } catch (_: Exception) {}
        try { guideManager?.release() }     catch (_: Exception) {}
        try { aiManager.release() }        catch (_: Exception) {}
        try { cameraManager.release() }     catch (_: Exception) {}
        try { ttsManager.release() }        catch (_: Exception) {}
        try { voiceManager.release() }      catch (_: Exception) {}
        try { ringManager.release() }        catch (_: Exception) {}
        try { voicePrintManager.release() }   catch (_: Exception) {}
        try { glassesManager.release() }    catch (_: Exception) {}
        try { scope.cancel() }              catch (_: Exception) {}
        super.onDestroy()
    }
}
