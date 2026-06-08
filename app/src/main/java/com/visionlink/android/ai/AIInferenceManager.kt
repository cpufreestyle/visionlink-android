package com.visionlink.android.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.LiteRTLM
import com.google.ai.edge.litertlm.LiteRTLMOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * AI 推理管理器 - v2.0
 *
 * 优化:
 * - 移除 MOCK_MODE，默认使用真实 LiteRT-LM 推理
 * - 添加 isRealInference() 方法检查推理能力
 * - 改进异常处理链
 * - 更好的模型加载反馈
 *
 * LiteRT-LM API (Google on-device LLM):
 *   val options = LiteRTLMOptions.builder().setTemperature(T).setMaxTokens(N).build()
 *   val model = LiteRTLM.createFromFile(path, options)
 *   val result = model.generate(prompt, imageBitamp)  // 多模态
 *   val text = result.text
 */
class AIInferenceManager(private val context: Context) {

    companion object {
        private const val TAG = "AIInferenceManager"
        private const val GEMMA_MODEL_PATH = "models/gemma-4-e2b-it.litertlm"
        private const val TEMPERATURE = 0.1f
        private const val MAX_TOKENS = 256
        private const val IMAGE_SIZE = 448
    }

    private var gemmaModel: LiteRTLM? = null
    private var isInitialized = false
    private var initError: String? = null

    // ========== 初始化 ==========

    /**
     * 初始化 AI 模型（异步，IO 线程）
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting AI model initialization...")

            val modelFile = copyAssetToCache(GEMMA_MODEL_PATH)
            if (!modelFile.exists()) {
                val msg = "Model file not found: ${modelFile.absolutePath}. Please run download_models.ps1"
                Log.e(TAG, msg)
                initError = msg
                return@withContext
            }

            val sizeMb = modelFile.length() / 1024 / 1024
            Log.d(TAG, "Loading Gemma 4 E2B: ${modelFile.name} (~${sizeMb}MB)")

            val options = LiteRTLMOptions.builder()
                .setTemperature(TEMPERATURE)
                .setMaxTokens(MAX_TOKENS)
                .setTopK(40)
                .setTopP(0.95f)
                .build()

            gemmaModel = LiteRTLM.createFromFile(modelFile.absolutePath, options)
            isInitialized = true
            initError = null

            Log.d(TAG, "AI initialized successfully")

        } catch (e: Exception) {
            val msg = "Model loading failed: ${e.message}"
            Log.e(TAG, msg, e)
            initError = msg
            isInitialized = false
        }
    }

    // ========== 推理 ==========

    /**
     * 分析图像（真实 LiteRT-LM 推理）
     *
     * @param bitmap 摄像头图像
     * @param mode 模式: 1=避障 2=文字 3=场景
     * @return 分析结果文本
     */
    suspend fun analyzeImage(bitmap: Bitmap, mode: Int): String = withContext(Dispatchers.IO) {
        // 检查初始化状态
        if (!isInitialized) {
            val msg = initError ?: "AI model not initialized, please tap Init AI."
            Log.w(TAG, msg)
            return@withContext msg
        }

        try {
            Log.d(TAG, "Analyzing image (mode=$mode)")

            // 1. 预处理图像 → 448x448（Gemma 4 E2B 输入尺寸）
            val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)

            // 2. 构建 Prompt
            val prompt = buildPrompt(mode)

            // 3. 执行真实 LiteRT-LM 推理（多模态：文本 + 图像）
            val result = runGemmaInference(prompt, resized)

            Log.d(TAG, "Inference result: $result")
            return@withContext result.trim()

        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            return@withContext "Recognition failed: ${e.message}"
        }
    }

    /**
     * 运行 Gemma 4 E2B 多模态推理（真实 LiteRT-LM API）
     */
    private suspend fun runGemmaInference(prompt: String, image: Bitmap): String = withContext(Dispatchers.IO) {
        val model = gemmaModel ?: return@withContext "Model not loaded"

        try {
            Log.d(TAG, "Running LiteRT-LM generate(prompt, image)...")

            // LiteRT-LM 多模态 API:
            //   model.generate(prompt: String, image: Bitmap): LiteRTLMResult
            //   result.text: String
            val result = model.generate(prompt, image)

            val output = result.text
            if (output.isNullOrBlank()) {
                Log.w(TAG, "Model returned empty result")
                return@withContext "Recognition returned no result"
            }

            Log.d(TAG, "Generated ${output.length} chars")
            return@withContext output

        } catch (e: Exception) {
            Log.e(TAG, "LiteRT-LM generate() failed: ${e.message}", e)
            return@withContext "Inference error: ${e.message}"
        }
    }

    // ========== Prompt 构建 ==========

    /**
     * 构建 Prompt
     */
    private fun buildPrompt(mode: Int): String {
        return when (mode) {
            1 -> "You are an obstacle avoidance assistant for visually impaired users. " +
                 "Observe the center of the image carefully. Identify the nearest obstacle " +
                 "and estimate the distance using common sense. " +
                 "Answer in simple Chinese, within 25 characters. " +
                 "Format: [obstacle type], [direction], [distance]"

            2 -> "You are an OCR text reading assistant. " +
                 "Extract and read aloud all Chinese and English text from this image precisely. " +
                 "Answer in the language of the detected text."

            3 -> "You are a scene description assistant for visually impaired users. " +
                 "Describe the scene in front of the user in warm, natural Chinese, within 50 characters."

            else -> "Please describe this image in Chinese, within 50 characters."
        }
    }

    // ========== 工具方法 ==========

    /**
     * 复制 assets 文件到缓存目录
     */
    private fun copyAssetToCache(assetPath: String): File {
        val file = File(context.cacheDir, assetPath.replace("/", "_"))
        if (file.exists()) return file

        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "Copied to cache: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Asset not in APK: $assetPath")
        }

        return file
    }

    /**
     * 将 Bitmap 转为 Base64（用于多模态输入）
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val bytes = stream.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    // ========== 生命周期 ==========

    /**
     * 释放模型资源
     */
    fun release() {
        try {
            gemmaModel?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Model close error: ${e.message}")
        }
        gemmaModel = null
        isInitialized = false
        initError = null
        Log.d(TAG, "AI model released")
    }

    /**
     * 是否已初始化
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * 是否使用真实推理（非 Mock）
     */
    fun isRealInference(): Boolean = isInitialized && gemmaModel != null

    /**
     * 获取初始化错误信息
     */
    fun getInitError(): String? = initError
}
