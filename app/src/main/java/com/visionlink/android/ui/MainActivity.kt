package com.visionlink.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.visionlink.android.ai.AIInferenceManager
import com.visionlink.android.camera.CameraManager
import com.visionlink.android.databinding.ActivityMainBinding
import com.visionlink.android.glasses.CXRGlassesManager
import com.visionlink.android.audio.TTSManager
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
    private lateinit var glassesManager: CXRGlassesManager

    private var currentMode = 1
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isDestroyed = false
    private var isContinuousMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "VisionLink Android v4.2 started")
        Log.d(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")

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
                binding.tvStatus.text = "TTS ready"
            }
        }
        glassesManager = CXRGlassesManager(this)
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
        binding.tvAiStatus.text = "Tap Init AI to start"
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
                    binding.tvResult.text = "AI model ready.\nTap Capture to analyze."
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
        binding.tvStatus.text = "Continuous mode active"
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
        binding.tvStatus.text = "Continuous mode stopped"
        aiManager.stopContinuousInference()
    }

    private fun initAI() {
        binding.btnInitAI.isEnabled = false
        binding.btnInitAI.text = "Initializing..."
        binding.tvAiStatus.text = "Initializing AI..."
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
                binding.tvAiStatus.text = "Error: ${e.message}"
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
        binding.tvAiStatus.text = "Downloading model..."
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
        binding.tvAiStatus.text = "Capturing..."
        binding.tvAiStatus.visibility = android.view.View.VISIBLE

        scope.launch {
            try {
                val bitmap = cameraManager.capture()
                if (isDestroyed) return@launch

                if (bitmap == null) {
                    binding.tvAiStatus.text = "Capture failed"
                    speakSafely("Capture failed")
                    return@launch
                }

                binding.tvAiStatus.text = "AI analyzing..."
                val result = aiManager.analyzeImage(bitmap, currentMode)
                if (isDestroyed) return@launch

                binding.tvResult.text = result
                binding.tvAiStatus.text = "${aiManager.getEngine().name} ready"
                binding.tvFps.text = "FPS: ${aiManager.getCurrentFps()}"
                speakSafely(result)

                if (glassesManager.isConnected()) {
                    glassesManager.showResult(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed: ${e.message}", e)
                if (!isDestroyed) binding.tvAiStatus.text = "Analysis failed"
                speakSafely("Analysis failed")
            }
        }
    }

    private fun openSettings() {
        Toast.makeText(this, "Settings - Coming soon", Toast.LENGTH_SHORT).show()
    }

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
        try { ttsManager.speak(text) } catch (e: Exception) { Log.w(TAG, "TTS error: ${e.message}") }
    }

    private fun runOnUiThreadSafe(action: () -> Unit) {
        if (isDestroyed || isFinishing) return
        runOnUiThread { if (!isDestroyed && !isFinishing) action() }
    }

    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            Log.d(TAG, "Permissions granted")
            startCameraWithRetry()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun startCameraWithRetry(retry: Int = 0) {
        scope.launch {
            try {
                cameraManager.startCamera()
                binding.tvStatus.text = "Camera ready"
            } catch (e: Exception) {
                Log.e(TAG, "Camera failed: ${e.message}")
                if (retry < 3) {
                    delay(1000)
                    startCameraWithRetry(retry + 1)
                } else {
                    binding.tvStatus.text = "Camera failed"
                }
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQUEST_PERMISSIONS) {
            if (results.all { it == PackageManager.PERMISSION_GRANTED }) startCameraWithRetry()
            else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        try { aiManager.release() }        catch (_: Exception) {}
        try { cameraManager.release() }     catch (_: Exception) {}
        try { ttsManager.release() }        catch (_: Exception) {}
        try { glassesManager.release() }    catch (_: Exception) {}
        try { scope.cancel() }             catch (_: Exception) {}
        super.onDestroy()
    }
}
