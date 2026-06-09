package com.visionlink.android.ui

import android.Manifest
import android.content.Context
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
import com.visionlink.android.camera.CameraManager
import com.visionlink.android.databinding.ActivityMainBinding
import com.visionlink.android.R
import com.visionlink.android.glasses.CXRGlassesManager
import com.visionlink.android.audio.TTSManager
import com.visionlink.android.audio.VoiceCommandManager
import com.visionlink.android.bluetooth.BleRingManager
import com.visionlink.android.utils.AICoreChecker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var aiManager: AIInferenceManager
    private lateinit var cameraManager: CameraManager
    private lateinit var ttsManager: TTSManager
    private lateinit var voiceManager: VoiceCommandManager
    private lateinit var ringManager: BleRingManager
    private lateinit var glassesManager: CXRGlassesManager
    private var isVoiceEnabled = false

    private var currentMode = 1
    private var isEnglish = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isDestroyed = false
    private var isContinuousMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "VisionLink Android v4.2 started")
        Log.d(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")

        // Enable edge-to-edge, handle system bar insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topStatusBar.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        initManagers()
        setupUI()
        observeAIState()
        checkPermissions()
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
        ringManager = BleRingManager(this) { event -> handleRingEvent(event) }
        Log.d(TAG, "All managers initialized")
    }

    private fun setupUI() {
        binding.btnMode1.setOnClickListener { setMode(1) }
        binding.btnMode2.setOnClickListener { setMode(2) }
        binding.btnMode3.setOnClickListener { setMode(3) }

        binding.btnInitAI.setOnClickListener {
            if (!aiManager.isInitialized()) initAI()
            else Toast.makeText(this, "AI already initialized", Toast.LENGTH_SHORT).show()
        }

        binding.btnDownloadModel.setOnClickListener { downloadModel() }
        binding.btnContinuous.setOnClickListener { toggleContinuousMode() }
        binding.btnCapture.setOnClickListener { captureAndAnalyze() }
        binding.btnCheckAICore?.setOnClickListener { runAICoreDiagnostic() }
        binding.btnSettings?.setOnClickListener { openSettings() }
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
                    AIInferenceManager.InferenceEngine.NONE      -> "Not selected"
                }
                binding.tvAiStatus.text = "Engine: $engineText"

                if (state.currentFps > 0) binding.tvFps.text = "FPS: ${state.currentFps}"
                if (state.downloadProgress in 1..99) binding.tvAiStatus.text = "Downloading: ${state.downloadProgress}%"

                if (state.isInitialized) {
                    binding.tvAiStatus.text = "$engineText ready"
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
            Toast.makeText(this, "Please initialize AI first", Toast.LENGTH_SHORT).show()
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
        scope.launch {
            try {
                aiManager.startContinuousInference(
                    onFrame = { },
                    onResult = { result ->
                        if (!isDestroyed) {
                            runOnUiThreadSafe { binding.tvResult.text = result }
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
        binding.tvStatus.text = getString(com.visionlink.android.R.string.status_continuous_stopped)
        aiManager.stopContinuousInference()
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
                binding.btnInitAI.isEnabled = true
                binding.btnInitAI.text = "Retry Init"
                binding.tvAiStatus.text = getString(com.visionlink.android.R.string.error_prefix) + e.message
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
                    if (success) speakSafely("Model downloaded. Tap Init AI.")
                    else speakSafely("Model download failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                if (!isDestroyed) {
                    binding.btnDownloadModel.isEnabled = true
                    binding.btnDownloadModel.text = "Download Failed"
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
        binding.tvAiStatus.text = getString(com.visionlink.android.R.string.status_capturing)
        binding.tvAiStatus.visibility = android.view.View.VISIBLE

        scope.launch {
            try {
                val bitmap = cameraManager.capture()
                if (isDestroyed) return@launch

                if (bitmap == null) {
                    binding.tvAiStatus.text = getString(com.visionlink.android.R.string.status_capture_failed)
                    speakSafely("Capture failed")
                    return@launch
                }

                binding.tvAiStatus.text = getString(com.visionlink.android.R.string.status_analyzing)
                val result = aiManager.analyzeImage(bitmap, currentMode)
                if (isDestroyed) return@launch

                binding.tvResult.text = result
                binding.tvAiStatus.text = "${aiManager.getEngine().name} ready"
                binding.tvFps.text = "FPS: ${aiManager.getCurrentFps()}"
                speakSafely(result)

                if (glassesManager.isConnected) {
                    glassesManager.showResult(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed: ${e.message}", e)
                if (!isDestroyed) binding.tvAiStatus.text = getString(com.visionlink.android.R.string.status_analysis_failed)
                speakSafely("Analysis failed")
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
            getString(com.visionlink.android.R.string.settings_close)
        )

        AlertDialog.Builder(this)
            .setTitle(com.visionlink.android.R.string.settings_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showLanguageDialog(prefs)
                    1 -> toggleVoiceCommand(prefs)
                    2 -> toggleRingConnection()
                }
            }
            .show()
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
                speakSafely("Bluetooth permission required")
                requestBluetoothPermissions()
            }
        }
    }

    private fun handleVoiceCommand(command: VoiceCommandManager.VoiceCommand) {
        Log.d(TAG, "Voice command: $command")
        speakSafely("收到命令")

        when (command) {
            VoiceCommandManager.VoiceCommand.CAPTURE_ANALYZE -> captureAndAnalyze()
            VoiceCommandManager.VoiceCommand.MODE_OBSTACLE -> { setMode(1); speakSafely("障碍物模式") }
            VoiceCommandManager.VoiceCommand.MODE_READ_TEXT -> { setMode(2); speakSafely("读文本模式") }
            VoiceCommandManager.VoiceCommand.MODE_SCENE -> { setMode(3); speakSafely("场景描述模式") }
            VoiceCommandManager.VoiceCommand.START_CONTINUOUS -> {
                if (!isContinuousMode) { toggleContinuousMode() }
            }
            VoiceCommandManager.VoiceCommand.STOP_CONTINUOUS -> {
                if (isContinuousMode) { toggleContinuousMode() }
            }
            VoiceCommandManager.VoiceCommand.INIT_AI -> { if (!aiManager.isInitialized()) initAI() }
            VoiceCommandManager.VoiceCommand.HELP -> speakSafely(voiceManager.getHelpText())
            VoiceCommandManager.VoiceCommand.UNKNOWN -> speakSafely("未知命令，请说帮助")
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
            BleRingManager.RingEvent.TAP_DETECTED -> captureAndAnalyze()
            BleRingManager.RingEvent.DOUBLE_TAP -> {
                if (!aiManager.isInitialized()) initAI()
                else speakSafely("AI already ready")
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
        currentMode = mode
        updateModeUI()
        val modeName = when (mode) {
            1 -> if (isEnglish) "Obstacle Avoidance" else "障碍物检测"
            2 -> if (isEnglish) "Text Reading" else "文字识别"
            3 -> if (isEnglish) "Scene Description" else "场景描述"
            else -> if (isEnglish) "Unknown" else "未知"
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
        if (code == REQUEST_PERMISSIONS) {
            if (results.all { it == PackageManager.PERMISSION_GRANTED }) startCameraWithRetry()
            else Toast.makeText(this, getString(com.visionlink.android.R.string.perm_camera_rationale), Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::cameraManager.isInitialized && !isDestroyed) {
            Log.d(TAG, "onResume: restarting camera")
            startCameraWithRetry()
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        try { aiManager.release() }        catch (_: Exception) {}
        try { cameraManager.release() }     catch (_: Exception) {}
        try { ttsManager.release() }        catch (_: Exception) {}
        try { voiceManager.release() }      catch (_: Exception) {}
        try { ringManager.release() }        catch (_: Exception) {}
        try { glassesManager.release() }    catch (_: Exception) {}
        try { scope.cancel() }              catch (_: Exception) {}
        super.onDestroy()
    }
}
