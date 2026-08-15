package com.visionlink.android.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.visionlink.android.work.ModelDownloadWorker
import com.visionlink.android.ai.AIInferenceManager
import com.visionlink.android.ai.ModelApiConfig
import com.visionlink.android.ai.ModelApiConfigManager
import com.visionlink.android.ai.ModelApiConfigDialog
import com.visionlink.android.ai.HandGuideManager
import com.visionlink.android.camera.CameraManager
import com.visionlink.android.databinding.ActivityMainBinding
import com.visionlink.android.R
import com.visionlink.android.glasses.CXRGlassesManager
import com.visionlink.android.glasses.GlassesInteractionCallback
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
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val ACTION_DEBUG_COMMAND = "com.visionlink.android.DEBUG_COMMAND"
    }

    // ========== ActivityResult launchers ==========

    /** 必须权限（相机+录音）请求结果 */
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (cameraGranted && audioGranted) {
            Log.d(TAG, "Required permissions granted, starting camera")
            startCameraWithRetry()
            initVoiceAndRing()
        } else {
            Log.w(TAG, "Required permissions denied: camera=$cameraGranted, audio=$audioGranted")
            Toast.makeText(this, getString(com.visionlink.android.R.string.perm_camera_rationale), Toast.LENGTH_LONG).show()
        }
    }

    /** 可选权限（通知/蓝牙）请求结果，拒绝不影响核心功能 */
    private val requestOptionalPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> Log.d(TAG, "Optional permission result: $grants") }

    /** 模型文件选择（.litertlm） */
    private val pickModelFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            Log.i(TAG, "Model file selected: $uri")
            copyModelToInternal(uri)
        } else {
            Log.w(TAG, "Model file selection cancelled")
        }
    }

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

    /** 连续检测：上次播报的文本指纹，用于检测场景变化 */
    private var lastContinuousFingerprint: String? = null
    /** 连续检测：上次播报时间戳，用于控制重复播报间隔 */
    private var lastContinuousAnnounceTs = 0L

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

        Log.d(TAG, "VisionLink Android v5.9.5 started")
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
        observeModelDownload()
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

        // 设置眼镜交互回调 — 接收眼镜端点击命令和语音助手事件
        setupGlassesInteraction()

        Log.d(TAG, "All managers initialized (voicePrint: ${voicePrintManager.isReady()})")
    }

    /**
     * 设置眼镜交互回调
     *
     * 处理来自眼镜端的三类事件：
     * 1. 命令点击 — 用户在眼镜上点击按钮/图标，cmd 为按钮 id 或图标 name
     * 2. AI 助手启动 — 用户按了眼镜 AI 键，触发手机端语音识别
     * 3. AI 助手停止 — 语音助手结束
     */
    private fun setupGlassesInteraction() {
        glassesManager.setInteractionCallback(object : GlassesInteractionCallback {
            override fun onCommand(cmd: String, data: ByteArray?) {
                Log.i(TAG, "Glasses command: $cmd")
                runOnUiThread { handleGlassesCommand(cmd) }
            }

            override fun onAiAssistStart() {
                Log.i(TAG, "Glasses AI assist start")
                runOnUiThread {
                    // 眼镜 AI 键按下，启动手机端语音识别
                    if (!isVoiceEnabled) {
                        isVoiceEnabled = true
                        val prefs = getSharedPreferences("visionlink", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("isVoiceEnabled", true).apply()
                        voiceManager.startListening()
                        binding.tvStatus.text = getString(com.visionlink.android.R.string.voice_listening)
                    }
                    speakSafely(if (isEnglish) "Voice command ready" else "语音助手已启动，请说话")
                }
            }

            override fun onAiAssistStop() {
                Log.i(TAG, "Glasses AI assist stop")
            }
        })
    }

    /**
     * 处理眼镜端命令
     *
     * 支持的命令：
     * - btn_capture / capture      → 拍照分析
     * - btn_mode1 / mode_obstacle  → 障碍物检测模式
     * - btn_mode2 / mode_text      → 文字识别模式
     * - btn_mode3 / mode_scene     → 场景描述模式
     * - btn_guide / mode_guide     → 指向引导模式
     * - btn_continuous / continuous→ 连续检测模式
     */
    private fun handleGlassesCommand(cmd: String) {
        when (cmd) {
            "btn_capture", "capture" -> {
                speakSafely("正在拍照分析")
                captureAndAnalyze()
            }
            "btn_mode1", "mode_obstacle" -> {
                setMode(1)
                speakSafely("障碍物检测模式")
            }
            "btn_mode2", "mode_text" -> {
                setMode(2)
                speakSafely("文字识别模式")
            }
            "btn_mode3", "mode_scene" -> {
                setMode(3)
                speakSafely("场景描述模式")
            }
            "btn_guide", "mode_guide" -> {
                if (!isGuideMode) startGuideMode()
            }
            "btn_continuous", "continuous" -> {
                if (!isContinuousMode) toggleContinuousMode()
            }
            else -> {
                Log.w(TAG, "Unknown glasses command: $cmd")
            }
        }
    }

    private fun setupUI() {
        binding.btnMode1.setOnClickListener { setMode(1) }
        binding.btnMode2.setOnClickListener { setMode(2) }
        binding.btnMode3.setOnClickListener { setMode(3) }
        binding.btnMode4.setOnClickListener { toggleGuideMode() }

        binding.btnContinuous.setOnClickListener { toggleContinuousMode() }
        binding.btnCapture.setOnClickListener { captureAndAnalyze() }
        binding.btnSwitchEngine.setOnClickListener { switchEngine() }
        binding.btnMore.setOnClickListener { openMoreMenu() }

        updateModeUI()
        updateEngineButton()
        binding.tvAiStatus.text = getString(com.visionlink.android.R.string.tap_init_ai_to_start)
        binding.tvAiStatus.visibility = android.view.View.VISIBLE
        binding.tvFps.text = "FPS: 0"
        binding.tvFps.visibility = android.view.View.VISIBLE
    }

    /**
     * “更多”菜单：声纹、眼镜、初始化AI、下载模型、设置、测试等次要功能
         */
    private fun openMoreMenu() {
        val items = arrayOf(
            "声纹管理",
            "连接眼镜",
            "初始化 AI",
            "选择模型文件",
            "下载模型",
            "设置",
            "测试 API",
            "测试 LM Studio",
            "测试 EDGE",
            "检查 AICore",
            "退出"
        )
        AlertDialog.Builder(this)
            .setTitle("更多功能")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openVoicePrintDialog()
                    1 -> connectGlasses()
                    2 -> { if (!aiManager.isInitialized()) initAI() else Toast.makeText(this, "AI 已就绪", Toast.LENGTH_SHORT).show() }
                    3 -> pickModelFile()
                    4 -> downloadModel()
                    5 -> openSettings()
                    6 -> testApi()
                    7 -> testLmStudio()
                    8 -> testEdge()
                    9 -> runAICoreDiagnostic()
                    10 -> finish()
                }
            }
            .show()
    }

    /**
     * 切换 AI 引擎：YOLO → Gemma4 → API → YOLO 循环
     *
     * - YOLO: 快速物体检测（~30ms/帧），离线，COCO 80类
     * - Gemma4: 端侧 LLM（~2s/帧），离线，自然语言描述
     * - API (StepFun): 云端视觉模型，需要网络
     */
    private fun switchEngine() {
        val currentEngine = aiManager.getEngine()
        val nextEngine = when (currentEngine) {
            AIInferenceManager.InferenceEngine.YOLO -> AIInferenceManager.InferenceEngine.EDGE
            AIInferenceManager.InferenceEngine.EDGE -> AIInferenceManager.InferenceEngine.STEPFUN
            AIInferenceManager.InferenceEngine.STEPFUN -> AIInferenceManager.InferenceEngine.YOLO
            else -> AIInferenceManager.InferenceEngine.YOLO
        }

        aiManager.setEngine(nextEngine)
        updateEngineButton()

        val engineName = when (nextEngine) {
            AIInferenceManager.InferenceEngine.YOLO -> "YOLO 物体检测（快速离线）"
            AIInferenceManager.InferenceEngine.EDGE -> "Gemma 4 端侧大模型（离线）"
            AIInferenceManager.InferenceEngine.STEPFUN -> "StepFun API（云端）"
            else -> nextEngine.name
        }
        speakSafely("已切换到 $engineName")
        Toast.makeText(this, engineName, Toast.LENGTH_SHORT).show()

        // 切换引擎后需要重新初始化
        if (nextEngine == AIInferenceManager.InferenceEngine.YOLO) {
            // YOLO 可直接初始化（模型内置在 assets）
            scope.launch {
                aiManager.initialize()
                runOnUiThread { binding.tvAiStatus.text = "YOLO Ready" }
            }
        } else if (nextEngine == AIInferenceManager.InferenceEngine.EDGE) {
            // Gemma 4 需要模型文件
            if (modelDownloadRunning) {
                // 正在后台下载：提示等待，进度由 observeModelDownload 显示
                binding.tvAiStatus.text = if (isEnglish) "Model downloading..." else "模型下载中，请稍候…"
                speakSafely(if (isEnglish) "Model is downloading" else "模型正在后台下载")
            } else {
                scope.launch {
                    val testResult = aiManager.testEdgeConnection()
                    if (testResult.startsWith("✅")) {
                        aiManager.initialize()
                        runOnUiThread { binding.tvAiStatus.text = "Gemma 4 Ready" }
                    } else {
                        // 模型未下载 → 弹提示，引导后台下载
                        runOnUiThread { promptDownloadModel() }
                    }
                }
            }
        } else if (nextEngine == AIInferenceManager.InferenceEngine.STEPFUN) {
            // API 需要网络
            scope.launch {
                aiManager.initialize()
                runOnUiThread { binding.tvAiStatus.text = "API Ready" }
            }
        }
    }

    /**
     * 更新引擎切换按钮文本
     */
    private fun updateEngineButton() {
        val engine = aiManager.getEngine()
        val text = when (engine) {
            AIInferenceManager.InferenceEngine.YOLO -> "引擎: YOLO"
            AIInferenceManager.InferenceEngine.EDGE -> "引擎: Gemma4"
            AIInferenceManager.InferenceEngine.STEPFUN -> "引擎: API"
            AIInferenceManager.InferenceEngine.LM_STUDIO -> "引擎: LM Studio"
            AIInferenceManager.InferenceEngine.CUSTOM -> "引擎: ${aiManager.getCustomConfig()?.name ?: "Custom"}"
            else -> "引擎: ${engine.name}"
        }
        binding.btnSwitchEngine.text = text
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
                    AIInferenceManager.InferenceEngine.YOLO      -> "YOLO (Object Detection)"
                    AIInferenceManager.InferenceEngine.NONE      -> "Not selected"
                }
                binding.tvAiStatus.text = "Engine: $engineText"
                updateEngineButton()

                if (state.currentFps > 0) binding.tvFps.text = "FPS: ${state.currentFps}"
                // \u6a21\u578b\u540e\u53f0\u4e0b\u8f7d\u8fdb\u5ea6\uff1a\u4e3b\u754c\u9762\u72b6\u6001\u680f\u5b9e\u65f6\u663e\u793a\uff08\u5e26\u6a21\u578b\u5927\u5c0f\u63d0\u793a\uff09
                if (state.downloadProgress in 1..99) {
                    val sizeHint = if (state.modelSizeMb > 0) " / ${state.modelSizeMb}MB" else ""
                    binding.tvAiStatus.visibility = android.view.View.VISIBLE
                    binding.tvAiStatus.text = if (isEnglish)
                        "Gemma model downloading ${state.downloadProgress}%$sizeHint"
                    else
                        "Gemma \u6a21\u578b\u4e0b\u8f7d\u4e2d ${state.downloadProgress}%$sizeHint"
                }

                if (state.isInitialized) {
                    binding.tvAiStatus.text = if (isEnglish) "$engineText ready" else "$engineText \u5c31\u7eea"
                    binding.tvResult.text = getString(com.visionlink.android.R.string.ai_model_ready)
                    if (state.engine != AIInferenceManager.InferenceEngine.YOLO) {
                        speakSafely("AI initialized with $engineText")
                    }
                }

                if (state.initError != null) binding.tvAiStatus.text = "Error: ${state.initError}"
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
        lastContinuousFingerprint = null // 重置指纹，确保首次播报
        // 真实的连续检测循环：取帧 → 当前模式分析 → 场景变化时播报 → 等待
        continuousJob?.cancel()
        // 优先用分析流缓存的最新帧（720p，低延迟低功耗），无新鲜帧时回退全幅拍照
        cameraManager.startFrameCollection(500L)
        continuousJob = scope.launch {
            while (isActive && isContinuousMode && !isDestroyed) {
                try {
                    val bitmap = cameraManager.takeLatestFrame() ?: cameraManager.capture()
                    if (bitmap == null) {
                        delay(1000)
                        continue
                    }
                    val result = aiManager.analyzeImage(bitmap, currentMode)
                    if (isDestroyed || !isContinuousMode) break

                    // 更新屏幕显示（每帧都更新）
                    binding.tvResult.text = result

                    // 场景变化检测：结果不同时播报，或超过重复间隔时重播
                    val now = System.currentTimeMillis()
                    val fingerprint = result.take(30) // 取前30字符作为指纹，更灵敏地检测变化
                    val sceneChanged = fingerprint != lastContinuousFingerprint
                    val shouldRepeat = now - lastContinuousAnnounceTs > 8_000 // 8秒重播一次
                    val minInterval = now - lastContinuousAnnounceTs > 1500 // 最少间隔1.5秒，防止刷屏

                    if ((sceneChanged || shouldRepeat) && minInterval) {
                        speakSafely(result)
                        lastContinuousFingerprint = fingerprint
                        lastContinuousAnnounceTs = now
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Continuous detection error: ${e.message}", e)
                    CrashReporter.reportError("ContinuousDetection", e.message ?: "unknown", e)
                    ttsManager.speak("检测出错，正在重试")
                }
                delay(2000) // 检测间隔，YOLO 快速模式可短些
            }
        }
    }

    private fun stopContinuousDetection() {
        binding.tvStatus.text = getString(com.visionlink.android.R.string.status_continuous_stopped)
        continuousJob?.cancel()
        continuousJob = null
        cameraManager.stopFrameCollection()
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
        binding.tvAiStatus.text = getString(com.visionlink.android.R.string.status_init_ai)
        binding.tvAiStatus.visibility = android.view.View.VISIBLE

        scope.launch {
            try {
                aiManager.initialize()
                if (aiManager.isInitialized()) {
                    binding.tvAiStatus.text = "${aiManager.getEngine().name} ready"
                    speakSafely("AI initialized successfully")
                } else {
                    speakSafely("AI initialization failed")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Init exception: ${e.message}", e)
            CrashReporter.reportError("AIInit", "AI initialization exception: ${e.message}", e)
                ttsManager.speak("初始化失败")
                binding.tvAiStatus.text = getString(com.visionlink.android.R.string.error_prefix) + e.message
            }
        }
    }

    private fun testApi() {
        binding.tvAiStatus.text = getString(com.visionlink.android.R.string.api_test_waiting)
        binding.tvResult.text = "Testing API..."

        scope.launch {
            try {
                // 测试纯文本请求
                val result = aiManager.testApiConnection()
                
                runOnUiThread {
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
                    binding.tvResult.text = "API Test FAILED:\n${e.message}"
                    Toast.makeText(this@MainActivity, "API Test FAILED: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun testLmStudio() {
        binding.tvAiStatus.text = "Testing LM Studio..."
        binding.tvResult.text = "Testing local AI proxy at 127.0.0.1:1234..."

        scope.launch {
            try {
                // 切换到 LM Studio 引擎
                aiManager.setEngine(AIInferenceManager.InferenceEngine.LM_STUDIO)
                
                // 测试连接
                val result = aiManager.testLmStudioConnection()
                
                runOnUiThread {
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
                    binding.tvResult.text = "LM Studio Test FAILED:\n${e.message}"
                    Toast.makeText(this@MainActivity, "LM Studio Test FAILED: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun testEdge() {
        binding.tvAiStatus.text = "Testing EDGE (LiteRT-LM)..."
        binding.tvResult.text = "Checking local LiteRT-LM model..."

        scope.launch {
            try {
                aiManager.setEngine(AIInferenceManager.InferenceEngine.EDGE)

                // Check model availability via LiteRT-LM
                val checkResult = aiManager.testEdgeConnection()

                runOnUiThread {
                    binding.tvResult.text = "EDGE Test Result:\n$checkResult"
                    binding.tvAiStatus.text = "EDGE: ${if (checkResult.startsWith("✅")) "Ready" else "Model Needed"}"
                    Toast.makeText(this@MainActivity, "EDGE test completed", Toast.LENGTH_SHORT).show()
                    speakSafely("Edge engine test completed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "EDGE test error: ${e.message}", e)
                runOnUiThread {
                    binding.tvResult.text = "EDGE Test FAILED:\n${e.message}"
                    binding.tvAiStatus.text = "EDGE: Error"
                    Toast.makeText(this@MainActivity, "EDGE FAILED: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 打开文件选择器，让用户手动选择 .litertlm 模型文件
     * 选中后复制到应用内部存储，供 Gemma 4 引擎使用
     */
    private fun pickModelFile() {
        try {
            pickModelFileLauncher.launch(arrayOf("*/*"))
            speakSafely("请选择 litertlm 模型文件")
        } catch (e: Exception) {
            Log.e(TAG, "文件选择器启动失败: ${e.message}", e)
            Toast.makeText(this, "无法打开文件选择器: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 将选中的模型文件复制到应用内部存储
     */
    private fun copyModelToInternal(uri: Uri) {
        binding.tvAiStatus.text = "正在导入模型文件..."
        speakSafely("正在导入模型")
        scope.launch(Dispatchers.IO) {
            var input: java.io.InputStream? = null
            var output: FileOutputStream? = null
            try {
                val fileName = getFileName(uri) ?: "model_${System.currentTimeMillis()}.litertlm"
                val targetDir = File(filesDir, "litert_models")
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, fileName)

                input = contentResolver.openInputStream(uri)
                if (input == null) {
                    withContext(Dispatchers.Main) {
                        binding.tvAiStatus.text = "无法读取选中的文件"
                        speakSafely("无法读取文件")
                    }
                    return@launch
                }
                output = FileOutputStream(targetFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytes = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                }
                output.flush()

                val sizeMb = totalBytes / (1024 * 1024)
                Log.w(TAG, "模型已复制: ${targetFile.absolutePath} (${sizeMb}MB)")

                aiManager.setManualModelPath(targetFile.absolutePath)

                withContext(Dispatchers.Main) {
                    binding.tvAiStatus.text = "模型已导入: $fileName (${sizeMb}MB)"
                    speakSafely("模型导入成功，${sizeMb}兆字节")
                    Toast.makeText(this@MainActivity, "模型导入成功: $fileName (${sizeMb}MB)", Toast.LENGTH_LONG).show()

                    scope.launch {
                        aiManager.setEngine(AIInferenceManager.InferenceEngine.EDGE)
                        val testResult = aiManager.testEdgeConnection()
                        runOnUiThread {
                            binding.tvResult.text = testResult
                            if (testResult.startsWith("✅")) {
                                binding.tvAiStatus.text = "Gemma 4 Ready"
                                updateEngineButton()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "模型复制失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.tvAiStatus.text = "模型导入失败: ${e.message}"
                    speakSafely("模型导入失败")
                }
            } finally {
                try { input?.close() } catch (_: Exception) {}
                try { output?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 从 Uri 获取文件名
     */
    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        result = it.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.lastPathSegment
        }
        return result
    }

    /**
     * 下载模型入口（菜单/引擎切换共用）：先判断状态，再弹提示。
     */
    private fun downloadModel() {
        if (aiManager.isModelDownloaded()) {
            Toast.makeText(this, if (isEnglish) "Model already downloaded" else "模型已下载", Toast.LENGTH_SHORT).show()
            return
        }
        promptDownloadModel()
    }

    /**
     * 首次使用 Gemma / 模型缺失时的下载提示对话框。
     * 确认后走后台下载，主界面状态栏实时显示进度（observeAIState 处理）。
     */
    private fun promptDownloadModel() {
        if (modelDownloadRunning) {
            Toast.makeText(this, if (isEnglish) "Model is downloading..." else "模型正在下载中…", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (isEnglish) "Download Gemma 4 model?" else "下载 Gemma 4 模型？")
            .setMessage(
                if (isEnglish)
                    "On-device Gemma 4 needs a one-time model file (~2.6GB). Wi-Fi is strongly recommended.\n\nIt downloads in the background — you can keep using YOLO or the cloud engine meanwhile. Progress shows in the top status bar."
                else
                    "端侧 Gemma 4 需要下载模型文件（约 2.6GB，仅需一次）。强烈建议在 Wi-Fi 下进行。\n\n将在后台下载，期间可继续使用 YOLO 或云端引擎。进度显示在顶部状态栏。"
            )
            .setPositiveButton(if (isEnglish) "Download in background" else "后台下载") { _, _ -> startModelDownload() }
            .setNegativeButton(if (isEnglish) "Cancel" else "取消") { _, _ ->
                // 取消则回退到 YOLO，避免停在无法工作的 Gemma 引擎
                if (aiManager.getEngine() == AIInferenceManager.InferenceEngine.EDGE) {
                    aiManager.setEngine(AIInferenceManager.InferenceEngine.YOLO)
                    updateEngineButton()
                    scope.launch { aiManager.initialize() }
                }
            }
            .setCancelable(false)
            .show()
    }

    /** 模型是否正在（WorkManager 前台服务）下载中——由 observeModelDownload 维护 */
    private var modelDownloadRunning = false

    /**
     * 启动后台下载：入队 WorkManager 前台服务任务。
     * App 被杀也不中断（前台服务承载），断点续传。进度由 observeModelDownload 显示到状态栏。
     */
    private fun startModelDownload() {
        if (modelDownloadRunning) return
        binding.tvAiStatus.visibility = android.view.View.VISIBLE
        binding.tvAiStatus.text = if (isEnglish) "Model downloading 0%" else "模型下载中 0%"
        speakSafely(if (isEnglish) "Downloading Gemma model in background" else "正在后台下载 Gemma 模型，请保持网络")

        // 只要求联网（不强制 Wi-Fi，交由用户自行判断流量）；模型完成前保持任务
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .build()
        // KEEP：已有下载任务则不重复入队（幂等）
        WorkManager.getInstance(this).enqueueUniqueWork(
            ModelDownloadWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * 观察模型下载任务：把进度回写到 aiManager 状态（状态栏统一显示），
     * 完成时标记就绪并在当前是 Gemma 时自动初始化。
     */
    private fun observeModelDownload() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.UNIQUE_NAME)
            .observe(this) { infos ->
                val info = infos?.firstOrNull() ?: return@observe
                modelDownloadRunning =
                    info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val pct = info.progress.getInt(ModelDownloadWorker.KEY_PERCENT, 0)
                        val totalMb = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_MB, 0L)
                        aiManager.reportDownloadProgress(pct, totalMb)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        aiManager.onModelDownloaded()
                        binding.tvAiStatus.text = if (isEnglish) "Gemma 4 model ready" else "Gemma 4 模型已就绪"
                        speakSafely(if (isEnglish) "Gemma model ready" else "Gemma 模型已就绪")
                        // 若当前仍选 Gemma，自动初始化
                        if (aiManager.getEngine() == AIInferenceManager.InferenceEngine.EDGE) {
                            scope.launch { aiManager.initialize() }
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        binding.tvAiStatus.text = if (isEnglish) "Model download failed" else "模型下载失败，请重试"
                        speakSafely(if (isEnglish) "Model download failed" else "模型下载失败")
                    }
                    else -> { /* ENQUEUED / BLOCKED / CANCELLED：无需额外处理 */ }
                }
            }
    }

    private fun captureAndAnalyze() {
        // AI 未初始化时自动初始化 YOLO（内置模型，无需下载）
        if (!aiManager.isInitialized()) {
            speakSafely(if (isEnglish) "Initializing AI..." else "正在初始化AI")
            scope.launch {
                aiManager.setEngine(AIInferenceManager.InferenceEngine.YOLO)
                val ok = aiManager.initialize()
                if (ok) {
                    runOnUiThread { 
                        binding.tvAiStatus.text = "YOLO Ready"
                        updateEngineButton()
                    }
                    doCapture()
                } else {
                    runOnUiThread {
                        speakSafely(if (isEnglish) "AI init failed" else "AI初始化失败")
                    }
                }
            }
            return
        }
        // 检查相机状态
        if (!cameraManager.isCameraStarted()) {
            binding.tvAiStatus.text = if (isEnglish) "Camera not ready" else "相机未启动，正在重启"
            speakSafely(if (isEnglish) "Camera not ready, restarting..." else "相机未启动，正在重启")
            scope.launch {
                try {
                    cameraManager.startCamera()
                    delay(1500)
                    if (!cameraManager.isCameraStarted()) {
                        binding.tvAiStatus.text = if (isEnglish) "Camera restart failed" else "相机重启失败"
                        speakSafely(if (isEnglish) "Camera restart failed" else "相机重启失败，请稍后重试")
                        return@launch
                    }
                    doCapture()
                } catch (e: Exception) {
                    binding.tvAiStatus.text = if (isEnglish) "Camera error" else "相机错误"
                    Log.e(TAG, "Camera restart error: ${e.message}", e)
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
                "mode4", "guide" -> toggleGuideMode()
                "capture" -> captureAndAnalyze()
                "continuous" -> toggleContinuousMode()
                "test_api", "api" -> testApi()
                "test_lm", "lm" -> testLmStudio()
                "test_edge", "edge" -> testEdge()
                "switch_engine", "engine" -> switchEngine()
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
        updateEngineButton()
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
            requestOptionalPermissionsLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ))
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
        // 重置指纹，确保切换后立即播报新结果
        lastContinuousFingerprint = null
        val modeName = when (mode) {
            1 -> if (isEnglish) "Obstacle Avoidance" else "障碍物检测"
            2 -> if (isEnglish) "Text Reading" else "文字识别"
            3 -> if (isEnglish) "Scene Description" else "场景描述"
            4 -> if (isEnglish) "Pointing Guide" else "指向引导"
            else -> if (isEnglish) "Unknown" else "未知"
        }
        speakSafely("已切换到$modeName")
        // 高亮当前模式按钮
        updateModeButtonColors(mode)
        // 如果已在连续检测中，立即触发一次检测
        if (isContinuousMode && aiManager.isInitialized()) {
            // 连续循环会自动用新 mode，这里只是确保快速响应
            Log.d(TAG, "Mode switched during continuous, will use new mode: $mode")
        }
    }

    /** 高亮当前选中的模式按钮 */
    private fun updateModeButtonColors(mode: Int) {
        val activeColor = 0xFFFF0000.toInt()
        val normalColors = mapOf(
            1 to 0xFFFF0000.toInt(),
            2 to 0xFFFFAA00.toInt(),
            3 to 0xFF0000FF.toInt(),
            4 to 0xFF00BCD4.toInt()
        )
        binding.btnMode1.setBackgroundColor(normalColors[1]!!)
        binding.btnMode2.setBackgroundColor(normalColors[2]!!)
        binding.btnMode3.setBackgroundColor(normalColors[3]!!)
        binding.btnMode4.setBackgroundColor(normalColors[4]!!)
        when (mode) {
            1 -> binding.btnMode1.setBackgroundColor(activeColor)
            2 -> binding.btnMode2.setBackgroundColor(activeColor)
            3 -> binding.btnMode3.setBackgroundColor(activeColor)
            4 -> binding.btnMode4.setBackgroundColor(activeColor)
        }
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
        // 必须权限：相机 + 录音
        val requiredPerms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        // 可选权限：通知（拒绝不影响核心功能）
        val optionalPerms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            optionalPerms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingRequired = requiredPerms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        val missingOptional = optionalPerms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (missingRequired.isEmpty()) {
            Log.d(TAG, "Required permissions granted")
            // 可选权限单独请求，不影响核心流程
            if (missingOptional.isNotEmpty()) {
                requestOptionalPermissionsLauncher.launch(missingOptional.toTypedArray())
            }
            startCameraWithRetry()
            initVoiceAndRing()
        } else {
            requestPermissionsLauncher.launch(missingRequired.toTypedArray())
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
                    // 相机就绪后自动初始化 YOLO 引擎（内置模型，无需网络/下载）
                    autoInitYolo()
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

    /**
     * 自动初始化 YOLO 引擎（内置模型，无需网络/下载）
     * 相机就绪后调用，让用户可以直接拍照/连续检测
     */
    private fun autoInitYolo() {
        if (aiManager.isInitialized()) return
        scope.launch(Dispatchers.IO) {
            try {
                aiManager.setEngine(AIInferenceManager.InferenceEngine.YOLO)
                val ok = aiManager.initialize()
                if (ok && !isDestroyed) {
                    runOnUiThread {
                        binding.tvAiStatus.text = "YOLO Ready"
                        updateEngineButton()
                        Log.d(TAG, "YOLO auto-initialized on startup")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Auto-init YOLO failed: ${e.message}")
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
                    // 设置功能图标，启用眼镜端交互
                    glassesManager.setupFunctionIcons()
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
        binding.tvGlassesStatus.setTextColor(0xFFFFFF00.toInt())
        speakSafely(if (isEnglish) "Connecting glasses" else "正在连接眼镜")

        val started = glassesManager.requestAuthAndConnect(this) { connected ->
            // 即时授权回调（在 IO 线程）
            runOnUiThread {
                if (connected) {
                    binding.tvGlassesStatus.text = if (isEnglish) {
                        "Glasses: ${glassesManager.getDeviceName()}"
                    } else {
                        "眼镜: ${glassesManager.getDeviceName()}"
                    }
                    binding.tvGlassesStatus.setTextColor(0xFF00FF00.toInt())
                    speakSafely(if (isEnglish) "Glasses connected" else "眼镜已连接")
                    glassesManager.setupFunctionIcons()
                } else {
                    val err = glassesManager.errorMessage
                    binding.tvGlassesStatus.text = if (err.isNotEmpty()) err else getString(com.visionlink.android.R.string.glasses_disconnected)
                    binding.tvGlassesStatus.setTextColor(0xFFFF0000.toInt())
                    speakSafely(if (isEnglish) "Glasses connection failed" else "眼镜连接失败")
                }
            }
        }

        if (!started) {
            binding.tvGlassesStatus.text = if (isEnglish) "Rokid AI App not installed" else "请先安装 Rokid AI App"
            binding.tvGlassesStatus.setTextColor(0xFFFF0000.toInt())
            speakSafely(if (isEnglish) "Please install Rokid AI App first" else "请先安装 Rokid AI App")
        } else {
            // 授权超时保护：缩短到 30 秒
            glassesAuthTimeoutJob?.cancel()
            glassesAuthTimeoutJob = scope.launch {
                delay(30_000)
                if (!isDestroyed && !isFinishing &&
                    glassesManager.connectionState == com.visionlink.android.glasses.CXRGlassesManager.ConnectionState.AUTHENTICATING) {
                    Log.w(TAG, "Authorization timed out after 30s")
                    glassesManager.resetAuthState()
                    runOnUiThread {
                        binding.tvGlassesStatus.text = if (isEnglish) "Auth timeout, retry" else "授权超时，请重试\n请确保:\n1. 眼镜已开机\n2. 已通过 Rokid AI App 配对\n3. Rokid AI App 版本 >= 1.7.14"
                        binding.tvGlassesStatus.setTextColor(0xFFFF0000.toInt())
                        speakSafely(if (isEnglish) "Authorization timed out" else "授权超时，请确保眼镜已开机并配对")
                    }
                    CrashReporter.reportError("GlassesAuth", "Glasses authorization timed out after 30s")
                }
            }
        }
    }

    /**
     * 仅处理 Rokid 眼镜授权结果。
     * SDK 的 AuthorizationHelper.requestAuthorization 内部使用固定 requestCode
     * 调用 startActivityForResult，无法迁移到 ActivityResultContracts，
     * 其余结果（权限/文件选择）已迁移到各 launcher。
     */
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
                        // 设置功能图标，启用眼镜端交互
                        glassesManager.setupFunctionIcons()
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
