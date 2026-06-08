package com.visionlink.android.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * AI Inference Manager - v4.2 (Compilable)
 *
 * Supported engines:
 *   1. AICore (Gemini Nano) - Samsung S24/S25, Pixel 8+ (Android 14+)
 *   2. LiteRT-LM (Gemma 4 E2B) - Android 13+ (requires model file)
 *   3. Cloud API - Fallback
 *
 * Note: LiteRT-LM and AICore SDKs are not yet publicly available.
 *       The manager uses mock responses until SDKs are integrated.
 */
class AIInferenceManager(private val context: Context) {

    companion object {
        private const val TAG = "AIInferenceManager"
        const val MODEL_TYPE_GEMMA = "gemma4_e2b"
        const val MODEL_TYPE_GEMINI = "gemini_nano"
        private const val TEMPERATURE = 0.1f
        private const val MAX_TOKENS = 256
        private const val IMAGE_SIZE = 448
        private const val MIN_RAM_MB = 4096L
        private const val MIN_SDK = 33
    }

    enum class InferenceEngine { NONE, AICORE, LITERT_LM, CLOUD }
    enum class InferenceMode { SINGLE_SHOT, CONTINUOUS }

    data class ManagerState(
        val engine: InferenceEngine = InferenceEngine.NONE,
        val mode: InferenceMode = InferenceMode.SINGLE_SHOT,
        val isInitialized: Boolean = false,
        val modelDownloaded: Boolean = false,
        val downloadProgress: Int = 0,
        val initError: String? = null,
        val modelSizeMb: Long = 0,
        val currentFps: Int = 0,
        val isThermalThrottling: Boolean = false
    )

    private val _state = MutableStateFlow(ManagerState())
    val state: StateFlow<ManagerState> = _state

    private var currentEngine: InferenceEngine = InferenceEngine.NONE
    private var currentMode: InferenceMode = InferenceMode.SINGLE_SHOT
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var continuousJob: Job? = null
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    // Model file reference (for future LiteRT-LM integration)
    private var modelFile: File? = null

    fun getModelDir(): File = File(context.filesDir, "models")
    fun getGemmaModelPath(): File = File(getModelDir(), "gemma-4-e2b-it.litertlm")

    // ========== Public API ==========

    suspend fun initialize() = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== AI Initialization v4.2 ===")
        Log.d(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Log.d(TAG, "SDK: ${Build.VERSION.SDK_INT}, RAM: ${getDeviceRamMb()}MB")

        if (isS25Ultra()) {
            Log.d(TAG, "S25 Ultra detected - applying optimizations")
        }

        updateState { copy(initError = null, isInitialized = false) }

        val engine = detectBestEngine()
        Log.d(TAG, "Selected engine: $engine")

        val modelReady = ensureModelReady(engine)
        if (!modelReady && engine == InferenceEngine.LITERT_LM) {
            val msg = "Gemma model not found. Place model file at: ${getGemmaModelPath().absolutePath}"
            Log.e(TAG, msg)
            updateState { copy(initError = msg, engine = engine) }
            return@withContext
        }

        currentEngine = engine
        updateState { copy(engine = engine, isInitialized = true, modelDownloaded = modelReady) }
        Log.d(TAG, "AI initialized with $engine (model ready: $modelReady)")
    }

    suspend fun analyzeImage(bitmap: Bitmap, mode: Int): String =
        withContext(Dispatchers.IO) {
            if (!isInitialized()) {
                return@withContext initError() ?: "AI not initialized"
            }

            val prompt = buildPrompt(mode)
            val result = when (currentEngine) {
                InferenceEngine.AICORE -> runAICoreInference(prompt, bitmap)
                InferenceEngine.LITERT_LM -> runGemmaInference(prompt, bitmap)
                InferenceEngine.CLOUD -> runCloudFallback(prompt, bitmap)
                InferenceEngine.NONE -> "AI engine not available"
            }

            updateFps()
            Log.d(TAG, "[$currentEngine] Result: ${result.take(80)}")
            result.trim()
        }

    // ========== Inference Engines ==========

    private fun runAICoreInference(prompt: String, bitmap: Bitmap): String {
        // TODO: Replace with real AICore API when SDK is publicly available
        Log.d(TAG, "AICore inference (mock) - prompt: ${prompt.take(50)}")
        return getMockResponse(prompt)
    }

    private fun runGemmaInference(prompt: String, bitmap: Bitmap): String {
        val model = modelFile
        if (model == null || !model.exists()) {
            return "Gemma model not loaded. Place at: ${getGemmaModelPath().absolutePath}"
        }
        // TODO: Replace with real LiteRT-LM API when available
        Log.d(TAG, "Gemma inference (mock) - model: ${model.absolutePath}")
        return getMockResponse(prompt)
    }

    private fun runCloudFallback(prompt: String, bitmap: Bitmap): String {
        return "Cloud inference not configured. Set API key in settings."
    }

    private fun getMockResponse(prompt: String): String {
        return when {
            prompt.contains("obstacle") -> "[table], [left], [2m]"
            prompt.contains("OCR") -> "Sample text detected"
            prompt.contains("scene") -> "Indoor office scene"
            else -> "Image analyzed"
        }
    }

    // ========== Model Management ==========

    private fun ensureModelReady(engine: InferenceEngine): Boolean {
        return when (engine) {
            InferenceEngine.AICORE -> true
            InferenceEngine.LITERT_LM -> {
                val file = getGemmaModelPath()
                if (file.exists() && file.length() > 1024) {
                    modelFile = file
                    updateState { copy(modelSizeMb = file.length() / 1024 / 1024) }
                    Log.d(TAG, "Gemma model found: ${file.length() / 1024 / 1024}MB")
                    true
                } else {
                    tryCopyAssetModel()
                }
            }
            InferenceEngine.CLOUD -> true
            InferenceEngine.NONE -> false
        }
    }

    private fun tryCopyAssetModel(): Boolean {
        return try {
            val dest = getGemmaModelPath()
            if (dest.exists()) {
                modelFile = dest
                return true
            }
            dest.parentFile?.mkdirs()
            context.assets.open("models/gemma-4-e2b-it.litertlm").use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            modelFile = dest
            Log.d(TAG, "Model copied from assets: ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Asset model not found, manual placement required")
            false
        }
    }

    suspend fun downloadModel(onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            // TODO: Implement model download from Kaggle
            Log.w(TAG, "Model download not yet implemented. Use manual transfer.")
            false
        }

    fun getModelSizeMb(): Long = _state.value.modelSizeMb

    // ========== Continuous Mode ==========

    suspend fun startContinuousInference(
        onFrame: suspend (Bitmap) -> Unit,
        onResult: suspend (String) -> Unit
    ) {
        Log.d(TAG, "Continuous inference started")
        currentMode = InferenceMode.CONTINUOUS
        updateState { copy(mode = InferenceMode.CONTINUOUS) }
    }

    fun stopContinuousInference() {
        continuousJob?.cancel()
        continuousJob = null
        currentMode = InferenceMode.SINGLE_SHOT
        updateState { copy(mode = InferenceMode.SINGLE_SHOT) }
    }

    // ========== Device Detection ==========

    private fun detectBestEngine(): InferenceEngine {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (isSamsungDevice()) {
                Log.d(TAG, "AICore candidate (Android 14+ Samsung)")
                return InferenceEngine.AICORE
            }
        }
        if (Build.VERSION.SDK_INT >= MIN_SDK) {
            Log.d(TAG, "LiteRT-LM candidate")
            return InferenceEngine.LITERT_LM
        }
        return InferenceEngine.CLOUD
    }

    private fun isSamsungDevice(): Boolean {
        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val model = Build.MODEL
        val isS24 = model.startsWith("SM-S92")
        val isS25 = model.startsWith("SM-S93")
        return isSamsung || isS24 || isS25
    }

    private fun getDeviceRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.totalMem / 1024 / 1024
    }

    private fun isS25Ultra(): Boolean {
        val model = Build.MODEL
        return model.startsWith("SM-S93")
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            val fps = frameCount
            frameCount = 0
            lastFpsTime = now
            updateState { copy(currentFps = fps) }
        }
    }

    // ========== Prompt Building ==========

    private fun buildPrompt(mode: Int): String {
        return when (mode) {
            1 -> "You are an obstacle avoidance assistant for visually impaired users. " +
                 "Observe the center of the image. Identify the nearest obstacle and estimate " +
                 "the distance. Answer in Chinese, within 25 characters. " +
                 "Format: [obstacle], [direction], [distance]"
            2 -> "You are an OCR assistant. Extract all Chinese and English text from this " +
                 "image precisely. Answer in the detected language."
            3 -> "You are a scene description assistant for visually impaired users. " +
                 "Describe the scene in warm natural Chinese, within 50 characters."
            else -> "Please describe this image in Chinese, within 50 characters."
        }
    }

    // ========== Lifecycle ==========

    fun release() {
        stopContinuousInference()
        modelFile = null
        currentEngine = InferenceEngine.NONE
        currentMode = InferenceMode.SINGLE_SHOT
        scope.cancel()
        updateState { copy(isInitialized = false, engine = InferenceEngine.NONE, mode = InferenceMode.SINGLE_SHOT) }
        Log.d(TAG, "AI manager released")
    }

    fun isInitialized(): Boolean = _state.value.isInitialized
    fun initError(): String? = _state.value.initError
    fun getEngine(): InferenceEngine = currentEngine
    fun isModelDownloaded(): Boolean = _state.value.modelDownloaded
    fun getCurrentFps(): Int = _state.value.currentFps

    private fun updateState(update: ManagerState.() -> ManagerState) {
        _state.value = _state.value.update()
    }
}
